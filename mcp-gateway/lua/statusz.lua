-- /statusz：节点状态（审计队列深度等）
local redis = require("resty.redis")
local config = require("mcp.config")
local util = require("mcp.util")

local red = redis:new()
red:set_timeouts(500, 500, 500)
local ok = red:connect(config.redis_host, config.redis_port)

local queue_len = 0
local audit_queue = ngx.shared.mcp_audit
if audit_queue then
    queue_len = audit_queue:llen("queue") or 0
end

ngx.header["Content-Type"] = "application/json"
ngx.print(util.json_encode({
    redis = ok and "ok" or "down",
    auditQueueDepth = queue_len,
    workerId = ngx.worker.id(),
    workerCount = ngx.worker.count(),
    time = ngx.utctime(),
}))
