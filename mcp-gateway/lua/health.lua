-- health：init_worker 定时器，主动健康探测 + 熔断（设计文档 7.9）
-- 每 10s 遍历 Redis 中的活跃 Server 实例，GET health_endpoint，连续 3 次失败熔断
local http = require("resty.http")
local config = require("mcp.config")
local redis = require("resty.redis")
local util = require("mcp.util")

local _M = {}

local function connect_redis()
    local red = redis:new()
    red:set_timeouts(1000, 1000, 1000)
    local ok = red:connect(config.redis_host, config.redis_port)
    if not ok then return nil end
    return red
end

-- SCAN 遍历 mcp:server:cfg:* 键
local function scan_server_keys(red)
    local keys = {}
    local cursor = "0"
    repeat
        local res = red:scan(cursor, "MATCH", "mcp:server:cfg:*", "COUNT", 100)
        cursor = res[1]
        for _, k in ipairs(res[2]) do
            table.insert(keys, k)
        end
    until cursor == "0"
    return keys
end

local function check_once()
    local red = connect_redis()
    if not red then return end

    local keys = scan_server_keys(red)
    for _, key in ipairs(keys) do
        local server_id = key:sub(#"mcp:server:cfg:" + 1)
        local cfg = red:array_to_hash(red:hgetall(key))
        if cfg and cfg.status == "2" then
            -- 实例列表
            local instances = util.json_decode(cfg.instances) or {}
            if #instances == 0 then
                instances = { { url = cfg.base_url } }
            end
            for _, inst in ipairs(instances) do
                local base = util.parse_url(inst.url)
                if base then
                    local health_path = cfg.health_endpoint or "/health"
                    local httpc = http.new()
                    httpc:set_timeouts(2000, 2000, 2000)
                    local okc = httpc:connect({
                        scheme = base.scheme, host = base.host, port = base.port,
                        ssl_verify = false,
                    })
                    local res
                    if okc then
                        res = httpc:request({ path = health_path, method = "GET" })
                    end
                    local field = inst.url
                    local state = red:hget("mcp:server:health:" .. server_id, field)
                    local fail_count = 0
                    if state and state ~= ngx.null then
                        local s = util.json_decode(state)
                        fail_count = s and tonumber(s.fail_count) or 0
                    end
                    local healthy
                    if res and res.status == 200 then
                        healthy, fail_count = true, 0
                    else
                        fail_count = fail_count + 1
                        healthy = fail_count < 3
                    end
                    red:hset("mcp:server:health:" .. server_id, field,
                             util.json_encode({
                                 healthy = healthy,
                                 fail_count = fail_count,
                                 checked_at = ngx.time(),
                             }))
                end
            end
        end
    end
    red:set_keepalive(10000, 20)
end

function _M.start()
    if ngx.worker.id() ~= 0 then return end
    local function loop(premature)
        if premature then return end
        local ok, err = pcall(check_once)
        if not ok then
            ngx.log(ngx.ERR, "health check error: ", err)
        end
        ngx.timer.at(10, loop)
    end
    -- 启动后延迟 5s 开始（等待管理台数据）
    ngx.timer.at(5, loop)
end

return _M
