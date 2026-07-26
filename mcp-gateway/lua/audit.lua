-- log_by_lua：审计事件入队（shared_dict 队列，O(1)，由 audit_flush 定时批量上报）
local util = require("mcp.util")

local ok, err = pcall(function()
    local event = util.build_audit_event(ngx.ctx)
    local queue = ngx.shared.mcp_audit
    local pushed, perr = queue:lpush("queue", util.json_encode(event))
    if not pushed then
        ngx.log(ngx.ERR, "audit queue push failed (queue full?): ", perr)
    end
end)
if not ok then
    ngx.log(ngx.ERR, "audit enqueue error: ", err)
end
