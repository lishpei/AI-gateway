-- a2a.redis_client：shared_dict 旁路缓存 + 本地 Redis 读取（设计文档 6.3 节）
local redis = require("resty.redis")
local config = require("a2a.config")
local util = require("a2a.util")

local _M = {}

local CACHE_TTL = 60
local NEG_TTL = 10

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

-- get_agent_config：agent:card:{id} Hash → {card_json, etag, endpoint_url, upstream_auth_type, upstream_auth_config, capabilities}
function _M.get_agent_config(agent_id)
    local cache = ngx.shared.agent_cache
    local ckey = "agent:" .. agent_id
    local cached = cache:get(ckey)
    if cached then
        if cached == "null" then return nil, "not_found" end
        return util.json_decode(cached)
    end

    local red, err = connect()
    if not red then return nil, err end
    local res = red:array_to_hash(red:hgetall("agent:card:" .. agent_id))
    close(red)
    if not res.card_json or res.card_json == ngx.null then
        cache:set(ckey, "null", NEG_TTL)
        return nil, "not_found"
    end
    res.capabilities = util.json_decode(res.capabilities) or {}
    cache:set(ckey, util.json_encode(res), CACHE_TTL)
    return res
end

-- get_caller_with_acl：gw:caller:{hash} + gw:acl:{callerId} → {caller_id, status, expires_at, acl={aid=true}}
function _M.get_caller_with_acl(api_key_hash)
    local cache = ngx.shared.caller_cache
    local ckey = "caller:" .. api_key_hash
    local cached = cache:get(ckey)
    if cached then
        if cached == "null" then return nil, "not_found" end
        return util.json_decode(cached)
    end

    local red, err = connect()
    if not red then return nil, err end
    local cred = red:array_to_hash(red:hgetall("gw:caller:" .. api_key_hash))
    if not cred.caller_id or cred.caller_id == ngx.null then
        close(red)
        cache:set(ckey, "null", NEG_TTL)
        return nil, "not_found"
    end
    local members = red:smembers("gw:acl:" .. cred.caller_id)
    close(red)

    local acl = {}
    for _, aid in ipairs(members) do
        acl[aid] = true
    end
    local status = (tonumber(cred.caller_status) == 1 and tonumber(cred.key_status) == 1) and 1 or 0
    local info = {
        caller_id = cred.caller_id,
        caller_name = cred.caller_name,
        status = status,
        expires_at = (cred.expires_at ~= ngx.null) and cred.expires_at or "",
        acl = acl,
    }
    cache:set(ckey, util.json_encode(info), CACHE_TTL)
    return info
end

-- OAuth2 token 缓存（shared_dict agent_cache, key=oauth2:{agentId}）
function _M.get_oauth2_token(agent_id)
    local cache = ngx.shared.agent_cache
    local cached = cache:get("oauth2:" .. agent_id)
    if not cached then return nil end
    local tk = util.json_decode(cached)
    if tk and tk.expires_at and tk.expires_at - 30 > ngx.time() then
        return tk.access_token
    end
    return nil
end

function _M.set_oauth2_token(agent_id, access_token, expires_in)
    local ttl = tonumber(expires_in) or 300
    ngx.shared.agent_cache:set("oauth2:" .. agent_id, util.json_encode({
        access_token = access_token,
        expires_at = ngx.time() + ttl,
    }), ttl)
end

-- 缓存失效（sync_agent 调用）
function _M.invalidate_agent(agent_id)
    local cache = ngx.shared.agent_cache
    cache:delete("agent:" .. agent_id)
    cache:delete("oauth2:" .. agent_id)
end

function _M.invalidate_caller(api_key_hash)
    ngx.shared.caller_cache:delete("caller:" .. api_key_hash)
end

function _M.flush_caller_cache()
    ngx.shared.caller_cache:flush_all()
end

function _M.flush_agent_cache()
    ngx.shared.agent_cache:flush_all()
end

-- sync 水位
function _M.get_sync_seq()
    local red = connect()
    if not red then return "0" end
    local seq = red:get("gw:sync:seq")
    red:set("gw:sync:ts", ngx.time())
    close(red)
    if not seq or seq == ngx.null then return "0" end
    return seq
end

function _M.set_sync_seq(seq)
    local red = connect()
    if red then
        red:set("gw:sync:seq", seq)
        red:set("gw:sync:ts", ngx.time())
        close(red)
    end
end

return _M
