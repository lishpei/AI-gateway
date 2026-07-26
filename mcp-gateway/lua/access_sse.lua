-- access_by_lua（旧版 GET /sse）：仅做 Token 验证 + Server 状态检查，无工具级授权
local util = require("mcp.util")
local idp = require("mcp.idp")
local mcp_redis = require("mcp.redis")

local server_id = ngx.var.mcp_server_id
local ctx = ngx.ctx
ctx.start_time_ms = ngx.now() * 1000

util.check_origin()

ngx.var.mcp_rpc_method = "GET /sse"

local idctx, terr = idp.verify_token()
if not idctx then
    ctx.auth_failed = true
    return util.abort_auth(terr)
end
ctx.idctx = idctx
ngx.var.mcp_caller = idctx.direct_caller or ""
ngx.var.mcp_user = idctx.user_sub or ""

local cfg, serr = mcp_redis.get_server_cfg(server_id)
if not cfg or cfg.status ~= "2" then
    return util.abort(404, util.ERR.SERVER_NOT_FOUND, "MCP server not found")
end
cfg.server_id = server_id
ctx.server_cfg = cfg
ctx.envelope = { method = "GET /sse", is_notification = true }
