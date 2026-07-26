-- access_by_lua（/mcp 与 /messages 共用）：
-- Origin 校验 → 信封解析 → 限流 → Token 验证(act 链) → Server 状态 → tools/call 策略求值
local util = require("mcp.util")
local idp = require("mcp.idp")
local mcp_redis = require("mcp.redis")
local policy = require("mcp.policy")

local server_id = ngx.var.mcp_server_id
local ctx = ngx.ctx
ctx.start_time_ms = ngx.now() * 1000

-- 0. Origin 校验（规范 MUST）
util.check_origin()

-- 1. 信封解析（GET/DELETE 无 body，跳过）
local method = ngx.req.get_method()
if method == "POST" then
    ngx.req.read_body()
    local body = ngx.req.get_body_data()
    local env = util.parse_envelope(body)
    if env.err then
        return util.abort(400, env.err.code, env.err.message, env.err.id)
    end
    ctx.envelope = env
    ngx.var.mcp_rpc_method = env.method or ""
    if env.method == "tools/call" and env.params then
        ctx.tool_name = env.params.name
        ngx.var.mcp_tool_name = ctx.tool_name or ""
        if env.params.arguments then
            ctx.args_hash = util.sha256_hex(util.json_encode(env.params.arguments) or "")
        end
    end
else
    ctx.envelope = { method = method, is_notification = false }
    ngx.var.mcp_rpc_method = method
end

-- 2. Token 验证（Introspection + act 链）
local idctx, terr = idp.verify_token()
if not idctx then
    ctx.auth_failed = true
    return util.abort_auth(terr)
end
ctx.idctx = idctx
ngx.var.mcp_caller = idctx.direct_caller or ""
ngx.var.mcp_user = idctx.user_sub or ""

-- 3. Server 存在性与状态
local cfg, serr = mcp_redis.get_server_cfg(server_id)
if not cfg or cfg.status ~= "2" then
    return util.abort(404, util.ERR.SERVER_NOT_FOUND, "MCP server not found",
                      ctx.envelope and ctx.envelope.id)
end
cfg.server_id = server_id
ctx.server_cfg = cfg

-- 4. 工具元数据 + 授权（仅 tools/call 做工具级策略求值）
if ctx.tool_name then
    local tool_meta = mcp_redis.get_tool_meta(server_id, ctx.tool_name)
    if not tool_meta or tool_meta.is_active == 0 or tool_meta.is_active == false then
        return util.abort(404, util.ERR.INVALID_PARAMS, "Unknown tool: " .. ctx.tool_name,
                          ctx.envelope.id)
    end
    ctx.tool_meta = tool_meta

    if ctx.envelope.method == "tools/call" then
        local decision = policy.evaluate(server_id, ctx.tool_name, idctx)
        ctx.policy = decision
        ngx.var.mcp_decision = decision.allowed and "allow" or "deny"
        if not decision.allowed then
            return util.abort(403, util.ERR.FORBIDDEN, "Forbidden: " .. (decision.reason or ""), ctx.envelope.id)
        end
    end
end

-- 5. 限流（阈值优先级：策略约束 > 工具默认 > 60）
if ctx.tool_name then
    local rpm = tonumber(ctx.tool_meta and ctx.tool_meta.rate_limit_rpm) or 60
    local pc = ctx.policy and ctx.policy.constraints
    if pc and tonumber(pc.max_calls_per_minute) then
        rpm = math.min(rpm, tonumber(pc.max_calls_per_minute))
    end
    local ok = mcp_redis.rate_limit("agent-tool", (idctx.direct_caller or "?") .. ":" .. server_id .. ":" .. ctx.tool_name, rpm)
    if not ok then
        ngx.header["X-RateLimit-Limit"] = rpm
        ngx.header["X-RateLimit-Remaining"] = 0
        return util.abort(429, util.ERR.RATE_LIMITED, "Rate limit exceeded", ctx.envelope.id)
    end
end
