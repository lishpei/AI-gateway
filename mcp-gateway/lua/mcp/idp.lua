-- mcp.idp：Token 验证（Introspection）与 Token Exchange
-- 一期采用 Introspection 路径（零外部依赖）；JWKS 本地验签为 Phase 2 增强（需 lua-resty-jwt）
local http = require("resty.http")
local config = require("mcp.config")
local util = require("mcp.util")
local mcp_redis = require("mcp.redis")

local _M = {}

local function basic_auth_header()
    return "Basic " .. ngx.encode_base64(config.gateway_client_id .. ":" .. config.gateway_client_secret)
end

-- act 链解析：嵌套 {"sub":"employee-assistant","act":{"sub":"business-agent"}}
-- 返回链列表（外层→内层）与最内层 sub
local function parse_act_chain(act)
    local chain = {}
    local cursor = act
    local depth = 0
    while type(cursor) == "table" and cursor.sub and depth < 5 do
        table.insert(chain, tostring(cursor.sub))
        cursor = cursor.act
        depth = depth + 1
    end
    return chain, chain[#chain]
end

-- 查询用户上下文（IdP /api/users/{id}，带 Redis 缓存）
local function load_user_ctx(user_sub)
    local cached = mcp_redis.get_user_ctx(user_sub)
    if cached then return cached end

    local httpc = http.new()
    httpc:set_timeouts(2000, 2000, 3000)
    local res, err = httpc:request_uri(config.idp_base_url .. "/api/users/" .. user_sub, {
        method = "GET",
        ssl_verify = false,
    })
    if not res or res.status ~= 200 then
        return nil
    end
    local ctx = util.json_decode(res.body)
    if ctx then
        mcp_redis.set_user_ctx(user_sub, ctx)
    end
    return ctx
end

-- 验证 Bearer Token：Introspection（30s 缓存）→ aud 校验 → act 链校验
-- 返回 idctx 或 nil, err
function _M.verify_token()
    local headers = ngx.req.get_headers()
    local auth = headers["Authorization"]
    if not auth or not auth:match("^Bearer .+") then
        return nil, "missing bearer token"
    end
    local token = auth:sub(8)
    local token_hash = util.sha256_hex(token)

    -- 1. Introspection（缓存优先）
    local result = mcp_redis.get_token_verify(token_hash)
    if not result then
        local httpc = http.new()
        httpc:set_timeouts(3000, 3000, 5000)
        local res, err = httpc:request_uri(config.idp_base_url .. "/oauth/introspect", {
            method = "POST",
            body = "token=" .. ngx.escape_uri(token),
            headers = {
                ["Content-Type"] = "application/x-www-form-urlencoded",
                ["Authorization"] = basic_auth_header(),
            },
            ssl_verify = false,
        })
        if not res or res.status ~= 200 then
            return nil, "introspection failed: " .. (err or (res and res.status) or "?")
        end
        result = util.json_decode(res.body)
        if result then
            mcp_redis.set_token_verify(token_hash, result)
        end
    end

    -- 2. active / exp
    if not result or not result.active then
        return nil, "invalid or expired token"
    end

    -- 3. aud 必须包含网关 audience
    local aud = result.aud
    if type(aud) ~= "table" then aud = { aud } end
    local aud_ok = false
    for _, a in ipairs(aud) do
        if a == config.gateway_aud then aud_ok = true break end
    end
    if not aud_ok then
        return nil, "token audience is not mcp-gateway"
    end

    -- 4. act 委托链：最内层必须等于持证者（client_id）
    local chain, innermost = parse_act_chain(result.act)
    local holder = result.client_id
    if innermost and holder and innermost ~= holder then
        return nil, "invalid delegation chain: holder mismatch"
    end

    -- 5. 用户上下文（sub ≠ 持证者 = 存在真实用户委托；服务级 token 无用户上下文）
    local has_user = result.sub ~= nil and result.sub ~= holder
    local idctx = {
        user_sub = has_user and result.sub or nil,
        agent_chain = chain,
        direct_caller = holder or innermost,
        scopes = result.scope,
        token_hash = token_hash,
        raw_token = token,
    }
    if has_user then
        local uctx = load_user_ctx(result.sub)
        if uctx then
            idctx.email = uctx.email
            idctx.org_id = uctx.org_id
            idctx.dept_id = uctx.dept_id
            idctx.roles = uctx.roles or {}
            idctx.groups = uctx.groups or {}
        end
    end
    return idctx
end

-- Token Exchange（RFC 8693）：Delegation Token → MCP Access Token（audience 绑定）
-- 返回 access_token 或 nil, err
function _M.exchange(idctx, resource_uri, scope)
    local cache_key = util.sha256_hex(idctx.token_hash .. ":" .. resource_uri .. ":" .. (scope or ""))
    local cached = mcp_redis.get_exchange(cache_key)
    if cached then return cached end

    local body = "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
        .. "&subject_token=" .. ngx.escape_uri(idctx.raw_token)
        .. "&subject_token_type=urn:ietf:params:oauth:token-type:access_token"
        .. "&resource=" .. ngx.escape_uri(resource_uri)
    if scope then
        body = body .. "&scope=" .. ngx.escape_uri(scope)
    end

    local httpc = http.new()
    httpc:set_timeouts(3000, 3000, 5000)
    local res, err = httpc:request_uri(config.idp_base_url .. "/oauth/token", {
        method = "POST",
        body = body,
        headers = {
            ["Content-Type"] = "application/x-www-form-urlencoded",
            ["Authorization"] = basic_auth_header(),
        },
        ssl_verify = false,
    })
    if not res or res.status ~= 200 then
        ngx.log(ngx.ERR, "token exchange failed: ", err or (res and res.status), " ", res and res.body)
        return nil, "token exchange failed"
    end
    local data = util.json_decode(res.body)
    if not data or not data.access_token then
        return nil, "token exchange: invalid response"
    end
    mcp_redis.set_exchange(cache_key, data.access_token, data.expires_in)
    return data.access_token
end

return _M
