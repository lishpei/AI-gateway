-- content_by_lua（旧版 POST /messages）：
-- 会话路由（同实例）→ Exchange → 转发 → 202 透传
-- 注意：access.lua 已完成 token 验证 + tools/call 策略求值 + 参数绑定前置检查在下方执行
local http = require("resty.http")
local util = require("mcp.util")
local idp = require("mcp.idp")
local mcp_redis = require("mcp.redis")

local ctx = ngx.ctx
local cfg = ctx.server_cfg
local env = ctx.envelope
local idctx = ctx.idctx
local server_id = ngx.var.mcp_server_id

-- 1. 会话路由
local session_id = ngx.var.arg_session_id
if not session_id then
    return util.abort(400, util.ERR.INVALID_REQUEST, "missing session_id", env.id)
end
local base = mcp_redis.get_session(session_id)
if not base then
    return util.abort(404, util.ERR.UPSTREAM,
                      "session expired, re-establish SSE connection", env.id)
end

-- 2. tools/call：参数绑定校验 + 基础输入校验（与主端点一致）
if env.method == "tools/call" and ctx.tool_meta then
    local arguments = env.params and env.params.arguments or {}
    if type(ctx.tool_meta.subject_bindings) == "table" then
        local ok, perr = util.check_subject_bindings(ctx.tool_meta.subject_bindings, arguments, idctx)
        if not ok then
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

-- 3. Token Exchange
local downstream_token = nil
if cfg.auth_mode == "user-delegation" and idctx.user_sub then
    local scope
    if ctx.tool_meta and ctx.tool_meta.required_scope and ctx.tool_meta.required_scope ~= "" then
        scope = ctx.tool_meta.required_scope
    elseif ctx.tool_name then
        scope = "mcp:" .. server_id .. ":" .. ctx.tool_name
    else
        scope = "mcp:" .. server_id .. ":*"
    end
    local token, terr = idp.exchange(idctx, cfg.resource_uri, scope)
    if not token then
        return util.abort(502, util.ERR.UPSTREAM, "Token exchange failed: " .. (terr or ""), env.id)
    end
    downstream_token = token
    ctx.token_exchanged = 1
elseif cfg.auth_mode == "service" then
    local token = idp.exchange(idctx, cfg.resource_uri, "mcp:" .. server_id .. ":*")
    downstream_token = token
    ctx.token_exchanged = token and 1 or 0
end

-- 4. 转发到同实例 /messages?session_id=...
local upstream = util.parse_url(base)
if not upstream then
    return util.abort(502, util.ERR.UPSTREAM, "invalid session instance url", env.id)
end
local headers = util.build_upstream_headers(ctx, downstream_token)

local httpc = http.new()
httpc:set_timeouts(5000, 10000, 30000)
local start_upstream = ngx.now() * 1000
local res, rerr = httpc:request_uri(base .. "/messages?session_id=" .. ngx.escape_uri(session_id), {
    method = "POST",
    body = ngx.req.get_body_data(),
    headers = headers,
    ssl_verify = false,
})
ctx.upstream_latency_ms = math.floor(ngx.now() * 1000 - start_upstream)
ngx.var.mcp_upstream_rt = tostring(ctx.upstream_latency_ms)

if not res then
    return util.abort(502, util.ERR.UPSTREAM, "Upstream unavailable: " .. (rerr or ""), env.id)
end

-- 5. 202 透传（lua-resty-http 响应头键名为小写）
ngx.status = res.status
ngx.header["Content-Type"] = res.headers and res.headers["content-type"] or "application/json"
if res.body and #res.body > 0 then
    ngx.print(res.body)
end
