-- /readyz：就绪检查（Redis 连通性）
local redis = require("resty.redis")
local config = require("mcp.config")

local red = redis:new()
red:set_timeouts(500, 500, 500)
local ok = red:connect(config.redis_host, config.redis_port)

ngx.header["Content-Type"] = "application/json"
if ok then
    ngx.print('{"status":"ready","redis":"ok"}')
else
    ngx.status = 503
    ngx.print('{"status":"not_ready","redis":"down"}')
end
