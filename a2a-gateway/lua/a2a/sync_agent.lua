-- a2a.sync_agent：节点配置拉取（拉取模型，设计文档 6.7/8.x 节）
-- worker0 定时器：5s 轮询（失败指数退避至 60s）→ 增量应用 → shared_dict 失效 → 心跳上报
local http = require("resty.http")
local redis = require("resty.redis")
local config = require("a2a.config")
local util = require("a2a.util")
local redis_client = require("a2a.redis_client")

local _M = {}

local function connect_redis()
    local red = redis:new()
    red:set_timeouts(1000, 1000, 1000)
    local ok, err = red:connect(config.redis_host, config.redis_port)
    if not ok then
        return nil, err
    end
    return red
end

-- ---------- 变更应用 ----------

local function apply_agent(red, ch)
    local agent_id = ch.entityId
    if ch.operation == "DELETE" then
        red:del("agent:card:" .. agent_id)
    else
        local p = ch.payload
        if not p then return end
        red:hmset("agent:card:" .. agent_id,
            "card_json", p.cardJson or "",
            "etag", p.etag or "",
            "endpoint_url", p.endpointUrl or "",
            "upstream_auth_type", p.upstreamAuthType or "NONE",
            "upstream_auth_config", p.upstreamAuthConfig or "{}",
            "capabilities", p.capabilities or "{}")
    end
    redis_client.invalidate_agent(agent_id)
end

local function apply_caller_cred(red, ch)
    local key_hash = ch.entityId
    if ch.operation == "DELETE" then
        red:del("gw:caller:" .. key_hash)
    else
        local p = ch.payload
        if not p then return end
        red:hmset("gw:caller:" .. key_hash,
            "caller_id", p.callerId or "",
            "caller_name", p.callerName or "",
            "caller_status", tostring(p.callerStatus or 0),
            "key_status", tostring(p.keyStatus or 0),
            "expires_at", p.expiresAt or "")
    end
    redis_client.invalidate_caller(key_hash)
end

local function apply_acl(red, ch)
    local caller_id = ch.entityId
    red:del("gw:acl:" .. caller_id)
    if ch.operation ~= "DELETE" and ch.payload and type(ch.payload.agentIds) == "table"
            and #ch.payload.agentIds > 0 then
        red:sadd("gw:acl:" .. caller_id, unpack(ch.payload.agentIds))
    end
    redis_client.flush_caller_cache()  -- ACL 嵌在 caller 缓存中，全清（量小）
end

local function apply_change(red, ch)
    local t = ch.entityType
    if t == "AGENT" or t == "UPSTREAM_CRED" then
        apply_agent(red, ch)
    elseif t == "CALLER_CRED" then
        apply_caller_cred(red, ch)
    elseif t == "ACL" then
        apply_acl(red, ch)
    elseif t == "CALLER" then
        redis_client.flush_caller_cache()  -- 兜底清缓存（状态由配套 CALLER_CRED 事件收敛）
    end
end

-- ---------- 全量重建 ----------

local function scan_del(red, pattern)
    local cursor = "0"
    repeat
        local res = red:scan(cursor, "MATCH", pattern, "COUNT", 100)
        cursor = res[1]
        for _, k in ipairs(res[2]) do
            red:del(k)
        end
    until cursor == "0"
end

local function apply_full(red, snapshot)
    -- 清空三类 key 后重建
    scan_del(red, "agent:card:*")
    scan_del(red, "gw:caller:*")
    scan_del(red, "gw:acl:*")

    for _, agent in ipairs(snapshot.agents or {}) do
        red:hmset("agent:card:" .. agent.id,
            "card_json", agent.cardJson or "",
            "etag", agent.etag or "",
            "endpoint_url", agent.endpointUrl or "",
            "upstream_auth_type", agent.upstreamAuthType or "NONE",
            "upstream_auth_config", agent.upstreamAuthConfig or "{}",
            "capabilities", agent.capabilities or "{}")
    end
    for _, cred in ipairs(snapshot.callerCreds or {}) do
        red:hmset("gw:caller:" .. cred.keyHash,
            "caller_id", cred.callerId or "",
            "caller_name", cred.callerName or "",
            "caller_status", tostring(cred.callerStatus or 0),
            "key_status", tostring(cred.keyStatus or 0),
            "expires_at", cred.expiresAt or "")
    end
    for _, acl in ipairs(snapshot.acls or {}) do
        if type(acl.agentIds) == "table" and #acl.agentIds > 0 then
            red:del("gw:acl:" .. acl.callerId)
            red:sadd("gw:acl:" .. acl.callerId, unpack(acl.agentIds))
        end
    end

    redis_client.flush_agent_cache()
    redis_client.flush_caller_cache()
end

-- ---------- 心跳 ----------

local function send_heartbeat(seq)
    local httpc = http.new()
    httpc:set_timeouts(2000, 2000, 3000)
    httpc:request_uri(config.admin_base_url .. "/internal/v1/sync/heartbeat", {
        method = "POST",
        body = util.json_encode({ nodeId = config.node_id, seq = seq, redisOk = true }),
        headers = {
            ["Content-Type"] = "application/json",
            ["X-Node-Token"] = config.node_token,
        },
        ssl_verify = false,
    })
end

-- ---------- 同步主流程 ----------

local function sync_once()
    local red, rerr = connect_redis()
    if not red then
        error("redis connect failed: " .. tostring(rerr))
    end
    local since = red:get("gw:sync:seq")
    if not since or since == ngx.null then since = "0" end

    local httpc = http.new()
    httpc:set_timeouts(3000, 3000, 10000)
    local res, err = httpc:request_uri(
        config.admin_base_url .. "/internal/v1/sync/config?since=" .. since, {
        method = "GET",
        headers = { ["X-Node-Token"] = config.node_token },
        ssl_verify = false,
    })
    if not res or res.status ~= 200 then
        error("sync api error: " .. tostring(err or (res and res.status)))
    end

    local body = util.json_decode(res.body)
    if not body or not body.data then
        error("sync api invalid response")
    end
    local data = body.data

    if data.fullSync then
        apply_full(red, data.snapshot or {})
    else
        for _, ch in ipairs(data.changes or {}) do
            local ok, cerr = pcall(apply_change, red, ch)
            if not ok then
                ngx.log(ngx.ERR, "apply change failed: ", cerr, " type=", ch.entityType, " id=", ch.entityId)
            end
        end
    end

    red:set("gw:sync:seq", tostring(data.seq))
    red:set("gw:sync:ts", ngx.time())
    red:set_keepalive(10000, 20)

    send_heartbeat(data.seq)
    ngx.log(ngx.NOTICE, "sync applied: since=", since, " → seq=", data.seq,
            " fullSync=", tostring(data.fullSync))
end

function _M.start()
    if ngx.worker.id() ~= 0 then return end
    local backoff = config.sync_interval
    local function loop(premature)
        if premature then return end
        local ok, err = pcall(sync_once)
        if ok then
            backoff = config.sync_interval
        else
            ngx.log(ngx.ERR, "sync failed: ", err, " (backoff ", backoff, "s)")
            backoff = math.min(backoff * 2, config.retry_max)
        end
        ngx.timer.at(backoff, loop)
    end
    -- 启动即拉一次（预热）
    ngx.timer.at(0, loop)
end

-- /statusz 端点
function _M.statusz()
    local red = connect_redis()
    local seq, ts = nil, nil
    if red then
        seq = red:get("gw:sync:seq")
        ts = red:get("gw:sync:ts")
        red:set_keepalive(10000, 20)
    end
    ngx.header["Content-Type"] = "application/json"
    ngx.print(util.json_encode({
        nodeId = config.node_id,
        redisOk = red ~= nil,
        syncSeq = tonumber(seq) or 0,
        syncTs = tonumber(ts) or nil,
        time = ngx.utctime(),
    }))
end

return _M
