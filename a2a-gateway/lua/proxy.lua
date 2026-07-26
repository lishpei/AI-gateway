-- content_by_lua：A2A 代理（信封解析 + 能力校验 + 凭证代换 + 转发 + SSE 透传，设计文档 6.8 节）
local http = require("resty.http")
local util = require("a2a.util")
local redis_client = require("a2a.redis_client")
local auth_upstream = require("a2a.auth_upstream")

local STREAM_METHODS = { SendStreamingMessage = true, SubscribeToTask = true }
local CONNECT_TIMEOUT = 5000
local SEND_TIMEOUT = 10000
local READ_TIMEOUT = 60000
local SSE_READ_TIMEOUT = 600000

local agent_id = ngx.var.a2a_agent_id
local ctx = ngx.ctx

-- 0. 仅允许 POST
if ngx.req.get_method() ~= "POST" then
    return util.rpc_abort(405, util.ERR.INVALID_REQUEST, "Method not allowed")
end

-- 1. 信封解析
ngx.req.read_body()
local body = ngx.req.get_body_data()
local env = util.parse_envelope(body)
if env.err then
    return util.rpc_abort(400, env.err.code, env.err.message, env.err.id)
end
ngx.var.a2a_rpc_method = env.method

-- 2. 取 Agent 配置（调用方已过 ACL；未发布则配置不存在 → 403 防枚举）
local cfg, err = redis_client.get_agent_config(agent_id)
if not cfg then
    return util.rpc_abort(403, util.ERR.FORBIDDEN, "Forbidden", env.id)
end

-- 3. 能力校验：流式方法但上游未声明 streaming → -32004
if STREAM_METHODS[env.method] and not (cfg.capabilities and cfg.capabilities.streaming) then
    return util.rpc_abort(400, util.ERR.UNSUPPORTED_OP,
        "Streaming is not supported by this agent", env.id)
end

-- 4. 第二跳凭证代换
local auth_headers, extra_query, auth_err = auth_upstream.build_upstream_auth(agent_id, cfg)
if not auth_headers then
    return util.rpc_abort(502, util.ERR.UPSTREAM, "Upstream auth failed: " .. tostring(auth_err), env.id)
end

-- 5. 组装上游请求头：剥离客户端凭证（auth_caller 已擦除），透传协议头
local req_h = ngx.req.get_headers()
local up_headers = {
    ["Content-Type"] = "application/json",
    ["Accept"] = "application/json, text/event-stream",
    ["X-Request-Id"] = ngx.var.request_id,
}
for k, v in pairs(auth_headers) do
    up_headers[k] = v
end
if req_h["A2A-Version"] then up_headers["A2A-Version"] = req_h["A2A-Version"] end
if req_h["A2A-Extensions"] then up_headers["A2A-Extensions"] = req_h["A2A-Extensions"] end

-- 6. 发起上游请求（流式 API，先 connect 再 request）
local url = cfg.endpoint_url
if extra_query then
    url = url .. (url:find("?", 1, true) and "&" or "?") .. extra_query
end
local upstream = util.parse_url(url)
if not upstream then
    return util.rpc_abort(502, util.ERR.UPSTREAM, "Invalid upstream url", env.id)
end

local httpc = http.new()
local is_stream_req = STREAM_METHODS[env.method] or false
httpc:set_timeouts(CONNECT_TIMEOUT, SEND_TIMEOUT, is_stream_req and SSE_READ_TIMEOUT or READ_TIMEOUT)

local start_upstream = ngx.now() * 1000
local okc, cerr = httpc:connect({
    scheme = upstream.scheme,
    host = upstream.host,
    port = upstream.port,
    ssl_verify = false,
    ssl_server_name = upstream.host,
})
if not okc then
    ngx.log(ngx.ERR, "upstream connect failed: ", cerr)
    return util.rpc_abort(502, util.ERR.UPSTREAM, "Upstream unavailable", env.id)
end

local res, rerr = httpc:request({
    path = upstream.path,
    method = "POST",
    body = body,
    headers = up_headers,
})
ngx.var.a2a_upstream_rt = tostring(math.floor(ngx.now() * 1000 - start_upstream))

if not res then
    ngx.log(ngx.ERR, "upstream request failed: ", rerr)
    return util.rpc_abort(502, util.ERR.UPSTREAM, "Upstream unavailable", env.id)
end
ngx.var.a2a_upstream_status = tostring(res.status)

-- 7. 响应分流（lua-resty-http 响应头键名为小写）
local ct = res.headers and res.headers["content-type"] or ""
if type(ct) == "string" and ct:find("text/event-stream", 1, true) then
    -- SSE 流式透传
    ngx.status = res.status
    ngx.header["Content-Type"] = "text/event-stream"
    ngx.header["Cache-Control"] = "no-cache"
    ngx.header["X-Accel-Buffering"] = "no"
    ngx.flush(true)

    local reader = res.body_reader
    while true do
        local chunk, read_err = reader(65536)
        if read_err then
            ngx.log(ngx.ERR, "sse upstream read error: ", read_err)
            break
        end
        if not chunk then break end
        local ok, flush_err = pcall(function()
            ngx.print(chunk)
            ngx.flush(true)
        end)
        if not ok then
            ngx.log(ngx.NOTICE, "client disconnected during sse: ", flush_err)
            break
        end
    end
    return ngx.exit(ngx.OK)
else
    -- 普通 JSON 响应：有界缓冲后原样回传（状态码透传）
    local resp_body, read_err = res:read_body()
    if not resp_body then
        return util.rpc_abort(502, util.ERR.INVALID_UPSTREAM, "Invalid agent response", env.id)
    end
    ngx.status = res.status
    ngx.header["Content-Type"] = ct ~= "" and ct or "application/json"
    ngx.print(resp_body)
end
