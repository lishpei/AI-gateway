-- mcp.policy：策略快照求值（设计文档第 8 章）
-- 语义：DENY 优先 → ALLOW → 默认拒绝；约束取最严格（min rpm）；dataScope 取最大
local util = require("mcp.util")
local mcp_redis = require("mcp.redis")

local _M = {}

local SCOPE_LEVEL = { self = 1, team = 2, department = 3, organization = 4 }

-- time_range "HH:mm-HH:mm" 窗口检查（跨零点环绕）
local function in_time_range(range)
    local start_h, start_m, end_h, end_m = range:match("^(%d+):(%d+)%s*%-%s*(%d+):(%d+)$")
    if not start_h then return true end
    local now = os.date("*t")
    local now_min = now.hour * 60 + now.min
    local start_min = tonumber(start_h) * 60 + tonumber(start_m)
    local end_min = tonumber(end_h) * 60 + tonumber(end_m)
    if start_min < end_min then
        return now_min >= start_min and now_min < end_min
    end
    return now_min >= start_min or now_min < end_min
end

local function grantee_matches(p, idctx)
    local gt, gid = p.granteeType, p.granteeId
    if gt == "AGENT" then
        for _, a in ipairs(idctx.agent_chain or {}) do
            if a == gid then return true end
        end
        return false
    elseif gt == "USER" then
        return idctx.user_sub ~= nil and idctx.user_sub == gid
    elseif gt == "ROLE" then
        for _, r in ipairs(idctx.roles or {}) do
            if r == gid then return true end
        end
        return false
    elseif gt == "GROUP" then
        for _, g in ipairs(idctx.groups or {}) do
            if g == gid then return true end
        end
        return false
    end
    return false
end

local function evaluate_snapshot(snapshot, tool_name, idctx)
    local deny_reason = nil
    local has_allow = false
    local max_scope = nil
    local min_rpm = nil
    local time_range = nil

    for _, p in ipairs(snapshot.policies or {}) do
        -- 工具匹配（精确或 *）
        if p.toolName == "*" or p.toolName == tool_name then
            if grantee_matches(p, idctx) then
                if p.effect == "DENY" then
                    deny_reason = "explicitly denied by policy #" .. tostring(p.policyId)
                    return { allowed = false, reason = deny_reason, version = snapshot.version }
                end
                has_allow = true
                local constraints = type(p.constraints) == "table" and p.constraints or nil
                if constraints then
                    local rpm = tonumber(constraints.max_calls_per_minute)
                    if rpm then
                        min_rpm = min_rpm and math.min(min_rpm, rpm) or rpm
                    end
                    if type(constraints.time_range) == "string" and constraints.time_range ~= "" then
                        time_range = constraints.time_range
                    end
                end
                local scope = p.dataScope or "self"
                if not max_scope or (SCOPE_LEVEL[scope] or 1) > (SCOPE_LEVEL[max_scope] or 1) then
                    max_scope = scope
                end
            end
        end
    end

    if not has_allow then
        return { allowed = false, reason = "no matching policy", version = snapshot.version }
    end
    if time_range and not in_time_range(time_range) then
        return { allowed = false, reason = "outside allowed time range " .. time_range, version = snapshot.version }
    end
    return {
        allowed = true,
        data_scope = max_scope,
        constraints = { max_calls_per_minute = min_rpm, time_range = time_range },
        version = snapshot.version,
    }
end

-- 求值入口（带决策缓存；快照缺失 fail-closed）
function _M.evaluate(server_id, tool_name, idctx)
    local snapshot, err = mcp_redis.get_policy_snapshot(server_id)
    if not snapshot then
        -- fail-closed：策略快照不可用一律拒绝（设计文档 8.2）
        return { allowed = false, reason = "policy snapshot unavailable", fatal = true }
    end

    local cache_key = util.sha256_hex(table.concat({
        server_id, tool_name, idctx.user_sub or "-",
        table.concat(idctx.agent_chain or {}, ">"),
        tostring(snapshot.version),
    }, "|"))
    local cached = mcp_redis.get_decision(cache_key)
    if cached then return cached end

    local decision = evaluate_snapshot(snapshot, tool_name, idctx)
    mcp_redis.set_decision(cache_key, decision)
    return decision
end

return _M
