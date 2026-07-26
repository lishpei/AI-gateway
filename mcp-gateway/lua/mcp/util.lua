-- mcp.util：JSON-RPC 信封解析、错误构造、头部处理、哈希与日志变量
local cjson = require("cjson.safe")
local sha256 = require("resty.sha256")
local str = require("resty.string")

local _M = {}

_M.ERR = {
    PARSE_ERROR       = -32700,
    INVALID_REQUEST   = -32600,
    METHOD_NOT_FOUND  = -32601,
    INVALID_PARAMS    = -32602,
    INTERNAL          = -32603,
    UNAUTHENTICATED   = -32010,
    FORBIDDEN         = -32011,
    SERVER_NOT_FOUND  = -32012,
    UPSTREAM          = -32013,
    RATE_LIMITED      = -32014,
    PARAM_BINDING     = -32016,
}

cjson.encode_sparse_array(true)

function _M.json_encode(v)
    return cjson.encode(v)
end

-- 递归去除 cjson.null（Java 序列化的 JSON null 解码后为 userdata，会破坏拼接/比较）
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
    local v = cjson.decode(s)
    return denull(v)
end

-- JSON-RPC 信封解析（仅信封层，不触碰业务语义）
-- 返回 {id=, method=, params=, is_notification=, err=}
function _M.parse_envelope(body)
    if not body or body == "" then
        return { err = { code = _M.ERR.INVALID_REQUEST, message = "Empty body" } }
    end
    local msg = cjson.decode(body)
    if type(msg) ~= "table" then
        return { err = { code = _M.ERR.PARSE_ERROR, message = "Parse error: invalid JSON" } }
    end
    if msg.jsonrpc ~= "2.0" then
        return { err = { code = _M.ERR.INVALID_REQUEST, message = "Invalid Request: jsonrpc must be \"2.0\"", id = msg.id } }
    end
    return {
        id = msg.id,
        method = msg.method,
        params = type(msg.params) == "table" and msg.params or nil,
        is_notification = msg.id == nil or msg.id == cjson.null,
        raw = msg,
    }
end

-- JSON-RPC 错误响应并中断请求
function _M.abort(http_status, code, message, id)
    ngx.status = http_status
    ngx.header["Content-Type"] = "application/json"
    ngx.var.mcp_error = tostring(code)
    local body = {
        jsonrpc = "2.0",
        id = id or cjson.null,
        error = {
            code = code,
            message = message,
            data = { requestId = ngx.var.request_id },
        },
    }
    ngx.print(cjson.encode(body))
    return ngx.exit(http_status)
end

-- 401 认证失败（携带 WWW-Authenticate，RFC 9728 语义）
function _M.abort_auth(message)
    ngx.header["WWW-Authenticate"] = 'Bearer realm="mcp-gateway"'
    return _M.abort(401, _M.ERR.UNAUTHENTICATED, message, nil)
end

-- sha256 hex
function _M.sha256_hex(s)
    local h = sha256:new()
    h:update(s)
    return str.to_hex(h:final())
end

-- Origin 校验（规范 MUST；服务端 Agent 调用一般无 Origin 头，放行）
function _M.check_origin()
    local origin = ngx.req.get_headers()["Origin"]
    if not origin or origin == "" then
        return true
    end
    -- 本地开发：放行 localhost 域；生产配置企业域名白名单
    if origin:match("^https?://localhost") or origin:match("^https?://127%.0%.0%.1") then
        return true
    end
    ngx.status = 403
    ngx.header["Content-Type"] = "application/json"
    ngx.print('{"error":"forbidden origin"}')
    ngx.exit(403)
end

-- 解析 URL 为 {scheme, host, port, path}
function _M.parse_url(url)
    local scheme, host, port, path = url:match("^(https?)://([^:/]+):?(%d*)(/?.*)$")
    if not scheme then return nil end
    if port == "" then port = (scheme == "https") and 443 or 80 end
    if path == "" then path = "/" end
    return { scheme = scheme, host = host, port = tonumber(port), path = path }
end

-- 请求头重建：剥离入站身份头 → 透传协议头 → 注入网关身份头（设计文档 11.2）
local STRIP_HEADERS = {
    ["x-user-id"] = true, ["x-user-email"] = true, ["x-org-id"] = true,
    ["x-dept-id"] = true, ["x-agent-chain"] = true, ["x-data-scope"] = true,
    ["x-internal-key"] = true, ["cookie"] = true, ["authorization"] = true,
    ["x-request-id"] = true, ["x-trace-id"] = true, ["host"] = true,
    ["content-length"] = true, ["connection"] = true,
}

local PASS_HEADERS = {
    ["mcp-session-id"] = "MCP-Session-Id",
    ["mcp-protocol-version"] = "MCP-Protocol-Version",
    ["last-event-id"] = "Last-Event-ID",
    ["accept"] = "Accept",
    ["content-type"] = "Content-Type",
}

function _M.build_upstream_headers(ctx, downstream_token)
    local in_headers = ngx.req.get_headers()
    local out = {}
    for k, v in pairs(in_headers) do
        local lk = k:lower()
        if PASS_HEADERS[lk] then
            out[PASS_HEADERS[lk]] = v
        elseif not STRIP_HEADERS[lk] then
            out[k] = v
        end
    end
    -- 注入
    if downstream_token then
        out["Authorization"] = "Bearer " .. downstream_token
    end
    local idctx = ctx.idctx
    if idctx then
        if idctx.user_sub then
            out["X-User-Id"] = idctx.user_sub
            if idctx.email then out["X-User-Email"] = idctx.email end
        end
        if idctx.org_id then out["X-Org-Id"] = idctx.org_id end
        if idctx.dept_id then out["X-Dept-Id"] = idctx.dept_id end
        if idctx.agent_chain and #idctx.agent_chain > 0 then
            out["X-Agent-Chain"] = cjson.encode(idctx.agent_chain)
        end
    end
    if ctx.policy and ctx.policy.data_scope then
        out["X-Data-Scope"] = ctx.policy.data_scope
    end
    out["X-Request-Id"] = ngx.var.request_id
    out["X-Trace-Id"] = ngx.var.request_id
    out["Content-Type"] = "application/json"
    return out
end

-- 参数绑定校验（设计文档 11.3）：arguments[param] 必须等于委托身份声明值
function _M.check_subject_bindings(bindings, arguments, idctx)
    if type(bindings) ~= "table" then return true end
    arguments = arguments or {}
    local claim_values = {
        sub = idctx.user_sub,
        email = idctx.email,
        org_id = idctx.org_id,
        dept_id = idctx.dept_id,
    }
    for _, b in ipairs(bindings) do
        local expected = claim_values[b.claim]
        local actual = arguments[b.param]
        if actual == nil then
            if b.required then
                return false, "missing required bound argument: " .. b.param
            end
        elseif expected and tostring(actual) ~= tostring(expected) then
            return false, "argument " .. b.param .. " must match caller identity (" .. b.claim .. ")"
        end
    end
    return true
end

-- 基础输入校验（validation_level=basic）：required + 简单类型检查
function _M.validate_arguments_basic(input_schema, arguments)
    if type(input_schema) ~= "table" then return true end
    arguments = arguments or {}
    if type(input_schema.required) == "table" then
        for _, field in ipairs(input_schema.required) do
            if arguments[field] == nil then
                return false, "missing required argument: " .. field
            end
        end
    end
    if type(input_schema.properties) == "table" then
        local type_ok = { string = true, number = true, boolean = true, object = true, array = true }
        for field, spec in pairs(input_schema.properties) do
            local v = arguments[field]
            if v ~= nil and type(spec) == "table" and spec.type and type_ok[spec.type] then
                local lua_type = type(v)
                local match =
                    (spec.type == "string" and lua_type == "string") or
                    (spec.type == "number" and lua_type == "number") or
                    (spec.type == "boolean" and lua_type == "boolean") or
                    (spec.type == "object" and lua_type == "table") or
                    (spec.type == "array" and lua_type == "table")
                if not match then
                    return false, "argument " .. field .. " must be " .. spec.type
                end
                -- enum 检查
                if lua_type == "string" and type(spec.enum) == "table" then
                    local found = false
                    for _, e in ipairs(spec.enum) do
                        if e == v then found = true break end
                    end
                    if not found then
                        return false, "argument " .. field .. " not in enum"
                    end
                end
            end
        end
    end
    return true
end

-- 审计上下文组装（audit.lua 调用）
function _M.build_audit_event(ctx)
    local idctx = ctx.idctx or {}
    local env = ctx.envelope or {}
    return {
        requestId = ngx.var.request_id,
        traceId = ngx.var.request_id,
        timestamp = os.date("!%Y-%m-%d %H:%M:%S", ngx.time()) .. string.format(".%03d", (ngx.now() % 1) * 1000),
        callerAgentId = idctx.direct_caller,
        delegationChain = idctx.agent_chain and cjson.encode(idctx.agent_chain) or nil,
        delegatorUserId = idctx.user_sub,
        delegatorOrgId = idctx.org_id,
        jsonrpcMethod = env.method,
        toolName = ctx.tool_name,
        serverId = ngx.var.mcp_server_id,
        requestArgsHash = ctx.args_hash,
        authResult = ctx.auth_failed and "failed" or "success",
        policyDecision = ctx.policy and (ctx.policy.allowed and "allow" or "deny") or "n-a",
        denyReason = ctx.policy and ctx.policy.reason or nil,
        tokenExchanged = ctx.token_exchanged or 0,
        latencyMs = math.floor(ngx.now() * 1000 - (ctx.start_time_ms or 0)),
        upstreamLatencyMs = ctx.upstream_latency_ms,
        responseStatus = ngx.status,
        responseSize = tonumber(ngx.var.bytes_sent) or 0,
        clientIp = ngx.var.remote_addr,
    }
end

return _M
