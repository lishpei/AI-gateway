-- content_by_lua（/mcp 主端点）：
-- Token Exchange → 参数绑定/输入校验 → 头部重建 → 转发 → JSON/SSE 分流 → 会话映射
-- 关键：SSE 必须用 lua-resty-http 的 request() + res.body_reader 流式读取
local http = require("resty.http")
local util = require("mcp.util")
local idp = require("mcp.idp")
local router = require("mcp.router")
local mcp_redis = require("mcp.redis")

local ctx = ngx.ctx
local cfg = ctx.server_cfg
local env = ctx.envelope
local idctx = ctx.idctx
local http_method = ngx.req.get_method()

-- 1. Token Exchange（user-delegation 模式且有用户上下文）
local downstream_token = nil
if cfg.auth_mode == "user-delegation" and idctx.user_sub then
    local scope
    if ctx.tool_meta and ctx.tool_meta.required_scope and ctx.tool_meta.required_scope ~= "" then
        scope = ctx.tool_meta.required_scope
    elseif ctx.tool_name then
        scope = "mcp:" .. cfg.server_id .. ":" .. ctx.tool_name
    else
        scope = "mcp:" .. cfg.server_id .. ":*"
    end
    local token, terr = idp.exchange(idctx, cfg.resource_uri, scope)
    if not token then
        return util.abort(502, util.ERR.UPSTREAM, "Token exchange failed: " .. (terr or ""), env.id)
    end
    downstream_token = token
    ctx.token_exchanged = 1
elseif cfg.auth_mode == "service" then
    -- 服务级模式：交换服务 token（audience 绑定）
    local token, terr = idp.exchange(idctx, cfg.resource_uri, "mcp:" .. cfg.server_id .. ":*")
    if token then
        downstream_token = token
        ctx.token_exchanged = 1
    end
end

-- 2. tools/call：参数绑定校验 + 基础输入校验
if env.method == "tools/call" and ctx.tool_meta then
    local arguments = env.params and env.params.arguments or {}
    if type(ctx.tool_meta.subject_bindings) == "table" then
        local ok, perr = util.check_subject_bindings(ctx.tool_meta.subject_bindings, arguments, idctx)
        if not ok then
            ctx.policy = ctx.policy or {}
            ctx.policy.allowed = false
            ctx.policy.reason = perr
            ngx.var.mcp_decision = "deny"
            return util.abort(403, util.ERR.PARAM_BINDING, perr, env.id)
        end
    end
    if ctx.tool_meta.validation_level == "basic" and type(ctx.tool_meta.input_schema) == "table" then
        local ok, verr = util.validate_arguments_basic(ctx.tool_meta.input_schema, arguments)
        if not ok then
            return util.abort(400, util.ERR.INVALID_PARAMS, verr, env.id)
        end
    end
end

-- 3. 路由：选择后端实例（含会话粘滞）
local session_id = ngx.req.get_headers()["MCP-Session-Id"]
local upstream, rerr = router.pick_instance(cfg, session_id)
if not upstream then
    return util.abort(503, util.ERR.UPSTREAM, "No healthy upstream: " .. (rerr or ""), env.id)
end

-- 4. 构建上游请求
local path = upstream.path
if path == "/" then
    path = "/mcp"
end
local headers = util.build_upstream_headers(ctx, downstream_token)

local httpc = http.new()
httpc:set_timeouts(5000, 10000, 600000)

local start_upstream = ngx.now() * 1000
-- 先建立连接（request() 不解析 scheme/host/port，必须显式 connect）
local okc, cerr = httpc:connect({
    scheme = upstream.scheme,
    host = upstream.host,
    port = upstream.port,
    ssl_verify = false,
    ssl_server_name = upstream.host,
})
if not okc then
    ngx.log(ngx.ERR, "upstream connect failed: ", cerr)
    return util.abort(502, util.ERR.UPSTREAM, "Upstream unavailable", env.id)
end
local res, rerr2 = httpc:request({
    path = path,
    method = http_method,
    body = http_method == "POST" and ngx.req.get_body_data() or nil,
    headers = headers,
    query = ngx.req.get_uri_args(),
})
ctx.upstream_latency_ms = math.floor(ngx.now() * 1000 - start_upstream)
ngx.var.mcp_upstream_rt = tostring(ctx.upstream_latency_ms)

if not res then
    ngx.log(ngx.ERR, "upstream request failed: ", rerr2)
    return util.abort(502, util.ERR.UPSTREAM, "Upstream unavailable", env.id)
end

-- DELETE 会话：成功后删除映射
if http_method == "DELETE" and session_id then
    mcp_redis.del_session(session_id)
end

-- 5. 响应分流（lua-resty-http 响应头键名为小写）
local ct = res.headers and res.headers["content-type"] or ""

-- initialize 响应：保存 MCP-Session-Id 映射
local resp_session_id = res.headers and res.headers["mcp-session-id"]
if resp_session_id then
    mcp_redis.set_session(resp_session_id, upstream.base)
end

if type(ct) == "string" and ct:find("text/event-stream", 1, true) then
    -- SSE 流式透传
    ngx.status = res.status
    ngx.header["Content-Type"] = "text/event-stream"
    ngx.header["Cache-Control"] = "no-cache"
    ngx.header["X-Accel-Buffering"] = "no"
    if resp_session_id then
        ngx.header["MCP-Session-Id"] = resp_session_id
    end
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
    -- JSON/202 缓冲转发（输出脱敏仅对配置工具启用）
    local body, read_err = res:read_body()
    if not body then
        return util.abort(502, util.ERR.UPSTREAM, "Invalid upstream response", env.id)
    end
    ngx.status = res.status
    ngx.header["Content-Type"] = ct ~= "" and ct or "application/json"
    if resp_session_id then
        ngx.header["MCP-Session-Id"] = resp_session_id
    end
    -- 透传上游其他协议相关头（小写查找）
    local pass_resp = { ["mcp-protocol-version"] = "MCP-Protocol-Version",
                        ["www-authenticate"] = "WWW-Authenticate",
                        ["retry-after"] = "Retry-After" }
    for lk, out_name in pairs(pass_resp) do
        if res.headers and res.headers[lk] then
            ngx.header[out_name] = res.headers[lk]
        end
    end
    -- 输出脱敏（仅 200 JSON 且配置了 output_masking 的工具）
    if ctx.tool_meta and type(ctx.tool_meta.output_masking) == "table" and res.status == 200 then
        for _, rule in ipairs(ctx.tool_meta.output_masking) do
            if rule.pattern and rule.replacement then
                body = body:gsub(rule.pattern, rule.replacement)
            end
        end
    end
    ngx.print(body)
end
