-- a2a.util：JSON-RPC 错误构造、信封解析、sha256、日志变量
local cjson = require("cjson.safe")
local sha256 = require("resty.sha256")
local str = require("resty.string")

local _M = {}

cjson.encode_sparse_array(true)

local REASON = {
    [-32700] = "PARSE_ERROR",
    [-32600] = "INVALID_REQUEST",
    [-32601] = "METHOD_NOT_FOUND",
    [-32602] = "INVALID_PARAMS",
    [-32603] = "INTERNAL_ERROR",
    [-32004] = "UNSUPPORTED_OPERATION",
    [-32006] = "INVALID_AGENT_RESPONSE",
    [-32010] = "UNAUTHENTICATED",
    [-32011] = "FORBIDDEN",
    [-32012] = "AGENT_NOT_FOUND",
    [-32013] = "UPSTREAM_UNAVAILABLE",
}

_M.ERR = {
    PARSE_ERROR      = -32700,
    INVALID_REQUEST  = -32600,
    METHOD_NOT_FOUND = -32601,
    INVALID_PARAMS   = -32602,
    INTERNAL         = -32603,
    UNSUPPORTED_OP   = -32004,
    INVALID_UPSTREAM = -32006,
    UNAUTHENTICATED  = -32010,
    FORBIDDEN        = -32011,
    AGENT_NOT_FOUND  = -32012,
    UPSTREAM         = -32013,
}

-- JSON-RPC 错误响应并中断请求（id 未知时为 null）
function _M.rpc_abort(http_status, code, message, id)
    ngx.status = http_status
    ngx.header["Content-Type"] = "application/json"
    ngx.var.a2a_error_code = tostring(code)
    ngx.print(cjson.encode({
        jsonrpc = "2.0",
        id = id or cjson.null,
        error = {
            code = code,
            message = message,
            data = {{
                ["@type"] = "type.googleapis.com/google.rpc.ErrorInfo",
                reason = REASON[code] or "GATEWAY_ERROR",
                domain = "a2a-protocol.org",
                metadata = { requestId = ngx.var.request_id },
            }},
        },
    }))
    return ngx.exit(http_status)
end

-- Card 端点等非 RPC 资源的 JSON 错误
function _M.json_error(http_status, code, message)
    ngx.status = http_status
    ngx.header["Content-Type"] = "application/json"
    ngx.var.a2a_error_code = tostring(code)
    ngx.print(cjson.encode({ error = { code = code, message = message } }))
    return ngx.exit(http_status)
end

function _M.sha256_hex(s)
    local h = sha256:new()
    h:update(s)
    return str.to_hex(h:final())
end

function _M.json_encode(v)
    return cjson.encode(v)
end

-- 递归去 cjson.null（Java 序列化的 JSON null 会破坏拼接/比较）
local function denull(v)
    if v == cjson.null then return nil end
    if type(v) ~= "table" then return v end
    local out = {}
    for k, val in pairs(v) do
        local nv = denull(val)
        if nv ~= nil then out[k] = nv end
    end
    return out
end

function _M.json_decode(s)
    if not s or s == "" then return nil end
    return denull(cjson.decode(s))
end

-- JSON-RPC 信封解析（仅信封层）
function _M.parse_envelope(body)
    if not body or body == "" then
        return { err = { code = _M.ERR.INVALID_REQUEST, message = "Empty body" } }
    end
    local msg = cjson.decode(body)
    if type(msg) ~= "table" then
        return { err = { code = _M.ERR.PARSE_ERROR, message = "Parse error: invalid JSON" } }
    end
    if msg.jsonrpc ~= "2.0" or type(msg.method) ~= "string" then
        return { err = { code = _M.ERR.INVALID_REQUEST, message = "Invalid Request", id = msg.id } }
    end
    return { id = msg.id, method = msg.method, params = msg.params }
end

-- 解析 URL → {scheme, host, port, path}
function _M.parse_url(url)
    local scheme, host, port, path = url:match("^(https?)://([^:/]+):?(%d*)(/?.*)$")
    if not scheme then return nil end
    if port == "" then port = (scheme == "https") and 443 or 80 end
    if path == "" then path = "/" end
    return { scheme = scheme, host = host, port = tonumber(port), path = path }
end

return _M
