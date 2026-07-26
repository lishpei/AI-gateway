-- /admin/invalidate：管理台缓存失效通知（X-Internal-Key 校验）
local config = require("mcp.config")
local util = require("mcp.util")
local mcp_redis = require("mcp.redis")

local key = ngx.req.get_headers()["X-Internal-Key"]
if key ~= config.internal_key then
    return util.abort(401, -32010, "invalid internal key")
end

ngx.req.read_body()
local body = util.json_decode(ngx.req.get_body_data() or "")
local server_id = body and body.serverId

if server_id then
    mcp_redis.invalidate_server(server_id)
    ngx.log(ngx.NOTICE, "cache invalidated for server: ", server_id)
end

ngx.header["Content-Type"] = "application/json"
ngx.print('{"code":0,"message":"ok"}')
