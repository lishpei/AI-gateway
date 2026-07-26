-- mcp.router：后端实例选择（会话粘滞 > 健康过滤 > 加权轮询）
local util = require("mcp.util")
local mcp_redis = require("mcp.redis")

local _M = {}

-- 解析实例列表：优先 instances JSON，退化 base_url 单实例
local function parse_instances(cfg)
    local instances = {}
    if type(cfg.instances) == "table" and #cfg.instances > 0 then
        for _, inst in ipairs(cfg.instances) do
            table.insert(instances, { url = inst.url, weight = tonumber(inst.weight) or 1 })
        end
    else
        table.insert(instances, { url = cfg.base_url, weight = 1 })
    end
    return instances
end

-- 简单轮询计数器（shared_dict）
local function next_index(key, n)
    local cache = ngx.shared.mcp_cache
    local i = (cache:get(key) or 0) + 1
    if i > n then i = 1 end
    cache:set(key, i)
    return i
end

-- 返回 {url=, scheme=, host=, port=, path=, base=} 或 nil, err
function _M.pick_instance(cfg, session_id)
    local instances = parse_instances(cfg)

    -- 会话粘滞
    if session_id then
        local url = mcp_redis.get_session(session_id)
        if url then
            for _, inst in ipairs(instances) do
                if inst.url == url then
                    local parsed = util.parse_url(url)
                    if parsed then
                        parsed.url = url
                        parsed.base = url
                        return parsed
                    end
                end
            end
            -- 映射指向的实例已不在列表（下线）：继续走选择逻辑重建映射
        end
    end

    -- 一期：健康检查尚未接入时，按轮询选择；health 模块就绪后过滤
    local idx = next_index("rr:" .. cfg.server_id, #instances)
    local chosen = instances[idx]
    local parsed = util.parse_url(chosen.url)
    if not parsed then
        return nil, "invalid instance url: " .. tostring(chosen.url)
    end
    parsed.url = chosen.url
    parsed.base = chosen.url

    -- 建立/更新会话映射
    if session_id then
        mcp_redis.set_session(session_id, chosen.url)
    end
    return parsed
end

return _M
