-- access_by_lua：第一跳认证（X-API-Key + ACL，防枚举设计文档 7.2 节）
local util = require("a2a.util")
local redis_client = require("a2a.redis_client")

local agent_id = ngx.var.a2a_agent_id

-- 1. 提取 API Key
local api_key = ngx.req.get_headers()["X-API-Key"]
if not api_key or api_key == "" then
    return util.rpc_abort(401, util.ERR.UNAUTHENTICATED, "Missing API key")
end
local hash = util.sha256_hex(api_key)

-- 2. 调用方校验（存在/启用/未过期）
local info, err = redis_client.get_caller_with_acl(hash)
if not info or info.status ~= 1 then
    return util.rpc_abort(401, util.ERR.UNAUTHENTICATED, "Invalid or revoked API key")
end
if info.expires_at and info.expires_at ~= "" and info.expires_at ~= ngx.null
        and info.expires_at < os.date("!%Y-%m-%d %H:%M:%S") then
    return util.rpc_abort(401, util.ERR.UNAUTHENTICATED, "API key expired")
end

-- 3. ACL 校验（先于 Agent 配置查询：403 同时覆盖"未授权"与"Agent 不存在"，防枚举）
if not info.acl[agent_id] then
    return util.rpc_abort(403, util.ERR.FORBIDDEN, "Forbidden")
end

-- 4. 记录日志变量 + 擦除客户端凭证（绝不转发上游）
ngx.var.a2a_caller_id = info.caller_id
ngx.req.set_header("X-API-Key", nil)
