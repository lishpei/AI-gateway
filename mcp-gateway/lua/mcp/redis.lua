-- mcp.redis：Redis 连接池与配置读取（shared_dict 旁路缓存）
local redis = require("resty.redis")
local config = require("mcp.config")
local util = require("mcp.util")

local _M = {}

local CACHE_TTL = 60          -- 配置类缓存秒数
local NEG_TTL = 10            -- 负缓存秒数

local function connect()
    local red = redis:new()
    red:set_timeouts(500, 500, 500)
    local ok, err = red:connect(config.redis_host, config.redis_port)
    if not ok then
        return nil, "redis connect failed: " .. (err or "?")
    end
    return red
end

local function close(red)
    red:set_keepalive(10000, 100)
end

-- 通用：shared_dict 旁路读（值存 cjson 字符串）
local function cached_read(cache_key, loader)
    local cache = ngx.shared.mcp_cache
    local cached = cache:get(cache_key)
    if cached then
        if cached == "null" then return nil, "not_found" end
        return util.json_decode(cached)
    end
    local value, err = loader()
    if not value then
        if err == "not_found" then
            cache:set(cache_key, "null", NEG_TTL)
        end
        return nil, err
    end
    cache:set(cache_key, util.json_encode(value), CACHE_TTL)
    return value
end

-- ---------- Server 配置 ----------
function _M.get_server_cfg(server_id)
    return cached_read("cfg:" .. server_id, function()
        local red, err = connect()
        if not red then return nil, err end
        local res = red:array_to_hash(red:hgetall("mcp:server:cfg:" .. server_id))
        close(red)
        if not res.base_url or res.base_url == ngx.null or res.base_url == "" then
            return nil, "not_found"
        end
        res.instances = util.json_decode(res.instances) or {}
        res.server_id = server_id
        return res
    end)
end

-- ---------- 工具元数据 ----------
function _M.get_tool_meta(server_id, tool_name)
    return cached_read("tool:" .. server_id .. ":" .. tool_name, function()
        local red, err = connect()
        if not red then return nil, err end
        local res = red:hget("mcp:server:tools:" .. server_id, tool_name)
        close(red)
        if not res or res == ngx.null then
            return nil, "not_found"
        end
        return util.json_decode(res)
    end)
end

-- ---------- 策略快照 ----------
function _M.get_policy_snapshot(server_id)
    return cached_read("pol:" .. server_id, function()
        local red, err = connect()
        if not red then return nil, err end
        local res = red:get("mcp:policy:snapshot:" .. server_id)
        close(red)
        if not res or res == ngx.null then
            return nil, "not_found"
        end
        return util.json_decode(res)
    end)
end

-- ---------- 会话映射 ----------
function _M.get_session(session_id)
    local red, err = connect()
    if not red then return nil, err end
    local url = red:get("mcp:session:" .. session_id)
    if url and url ~= ngx.null then
        red:expire("mcp:session:" .. session_id, 1800)  -- 滚动续期
    end
    close(red)
    if not url or url == ngx.null then return nil end
    return url
end

function _M.set_session(session_id, instance_url)
    local red = connect()
    if red then
        red:setex("mcp:session:" .. session_id, 1800, instance_url)
        close(red)
    end
end

function _M.del_session(session_id)
    local red = connect()
    if red then
        red:del("mcp:session:" .. session_id)
        close(red)
    end
end

-- ---------- Token 验证缓存 ----------
function _M.get_token_verify(token_hash)
    local red = connect()
    if not red then return nil end
    local res = red:get("mcp:token:verify:" .. token_hash)
    close(red)
    if not res or res == ngx.null then return nil end
    return util.json_decode(res)
end

function _M.set_token_verify(token_hash, introspect_result)
    local red = connect()
    if red then
        red:setex("mcp:token:verify:" .. token_hash, 30, util.json_encode(introspect_result))
        close(red)
    end
end

-- ---------- Token Exchange 缓存 ----------
function _M.get_exchange(cache_key)
    local red = connect()
    if not red then return nil end
    local res = red:get("mcp:token:exchange:" .. cache_key)
    close(red)
    if not res or res == ngx.null then return nil end
    local data = util.json_decode(res)
    -- 提前 30s 过期
    if data and data.expires_at and data.expires_at - 30 > ngx.time() then
        return data.access_token
    end
    return nil
end

function _M.set_exchange(cache_key, access_token, expires_in)
    local red = connect()
    if red then
        local ttl = math.min(tonumber(expires_in) or 240, 240)
        red:setex("mcp:token:exchange:" .. cache_key, ttl,
                  util.json_encode({ access_token = access_token, expires_at = ngx.time() + ttl }))
        close(red)
    end
end

-- ---------- 策略决策缓存 ----------
function _M.get_decision(key)
    local red = connect()
    if not red then return nil end
    local res = red:get("mcp:policy:decision:" .. key)
    close(red)
    if not res or res == ngx.null then return nil end
    return util.json_decode(res)
end

function _M.set_decision(key, decision)
    local red = connect()
    if red then
        red:setex("mcp:policy:decision:" .. key, 30, util.json_encode(decision))
        close(red)
    end
end

-- ---------- 用户上下文缓存（IdP 用户信息） ----------
function _M.get_user_ctx(user_id)
    local red = connect()
    if not red then return nil end
    local res = red:get("mcp:idp:user:" .. user_id)
    close(red)
    if not res or res == ngx.null then return nil end
    return util.json_decode(res)
end

function _M.set_user_ctx(user_id, ctx)
    local red = connect()
    if red then
        red:setex("mcp:idp:user:" .. user_id, 300, util.json_encode(ctx))
        close(red)
    end
end

-- ---------- 限流（令牌桶, Lua 脚本原子执行） ----------
local RATE_LIMIT_SCRIPT = [[
local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local rate = tonumber(ARGV[1])
local now = tonumber(ARGV[2])
local tokens = tonumber(data[1]) or rate
local ts = tonumber(data[2]) or now
tokens = math.min(rate, tokens + (now - ts) * rate / 60000)
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
    redis.call('EXPIRE', KEYS[1], 120)
    return {1, math.floor(tokens)}
else
    redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
    return {0, math.floor(tokens)}
end
]]

-- dim 维度名, id 维度值, rpm 每分钟配额；返回 allowed, remaining
function _M.rate_limit(dim, id, rpm)
    local red, err = connect()
    if not red then
        -- Redis 故障时限流放行（可用性优先，由熔断与审计兜底）
        return true, -1
    end
    local key = "mcp:rl:" .. dim .. ":" .. id
    local res, rerr = red:eval(RATE_LIMIT_SCRIPT, 1, key, rpm, math.floor(ngx.now() * 1000))
    close(red)
    if not res then
        ngx.log(ngx.ERR, "rate limit eval failed: ", rerr)
        return true, -1
    end
    return res[1] == 1, res[2]
end

-- 失效 shared_dict 中某 server 的配置缓存
function _M.invalidate_server(server_id)
    local cache = ngx.shared.mcp_cache
    cache:delete("cfg:" .. server_id)
    cache:delete("pol:" .. server_id)
    -- 工具缓存逐出需要枚举，简单起见按前缀不可行 → 由 TTL 兜底;
    -- 这里记录失效时间戳, 读取侧忽略早于该时间戳的工具缓存(一期省略, TTL=60s可接受)
end

return _M
