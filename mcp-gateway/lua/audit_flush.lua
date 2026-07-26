-- audit_flush：init_worker 定时器，每 2s 批量上报审计日志到管理台；失败写本地兜底文件
local http = require("resty.http")
local config = require("mcp.config")
local util = require("mcp.util")

local _M = {}

local FALLBACK_FILE = "/var/log/mcp-gateway-audit-fallback.log"
local BATCH_SIZE = 100

local function write_fallback(lines)
    local f = io.open(FALLBACK_FILE, "a")
    if not f then
        ngx.log(ngx.ERR, "audit fallback file open failed")
        return
    end
    for _, line in ipairs(lines) do
        f:write(line, "\n")
    end
    f:close()
end

local function flush()
    local queue = ngx.shared.mcp_audit
    local batch = {}
    for _ = 1, BATCH_SIZE do
        local item = queue:rpop("queue")
        if not item then break end
        table.insert(batch, item)
    end
    if #batch == 0 then return end

    -- 组装为管理台契约：{"logs":[...]}
    local logs = {}
    for _, item in ipairs(batch) do
        local log = util.json_decode(item)
        if log then table.insert(logs, log) end
    end

    local httpc = http.new()
    httpc:set_timeouts(2000, 3000, 5000)
    local res, err = httpc:request_uri(config.admin_base_url .. "/api/v1/audit/logs/batch", {
        method = "POST",
        body = util.json_encode({ logs = logs }),
        headers = {
            ["Content-Type"] = "application/json",
            ["X-Internal-Key"] = config.internal_key,
        },
        ssl_verify = false,
    })
    if not res or res.status ~= 200 then
        ngx.log(ngx.WARN, "audit batch report failed: ", err or (res and res.status), " → fallback file")
        write_fallback(batch)
    end
end

function _M.start()
    -- 仅 worker 0 运行 flush 循环
    if ngx.worker.id() ~= 0 then return end
    local function loop(premature)
        if premature then return end
        local ok, err = pcall(flush)
        if not ok then
            ngx.log(ngx.ERR, "audit flush error: ", err)
        end
        ngx.timer.at(2, loop)
    end
    ngx.timer.at(2, loop)
end

return _M
