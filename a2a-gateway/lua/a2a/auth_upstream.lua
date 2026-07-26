-- a2a.auth_upstream：第二跳凭证代换（设计文档 7.3 节，5 种 auth_type）
local http = require("resty.http")
local util = require("a2a.util")
local redis_client = require("a2a.redis_client")

local _M = {}

-- OAuth2 Client Credentials 换 token（shared_dict 缓存，提前 30s 刷新）
local function get_oauth2_token(agent_id, conf)
    local cached = redis_client.get_oauth2_token(agent_id)
    if cached then return cached end

    local body = "grant_type=client_credentials"
        .. "&client_id=" .. ngx.escape_uri(tostring(conf.clientId))
        .. "&client_secret=" .. ngx.escape_uri(tostring(conf.clientSecret))
    if conf.scopes then
        local scopes = conf.scopes
        if type(scopes) == "table" then
            scopes = table.concat(scopes, " ")
        end
        body = body .. "&scope=" .. ngx.escape_uri(scopes)
    end

    local httpc = http.new()
    httpc:set_timeouts(3000, 3000, 5000)
    local res, err = httpc:request_uri(tostring(conf.tokenUrl), {
        method = "POST",
        body = body,
        headers = { ["Content-Type"] = "application/x-www-form-urlencoded" },
        ssl_verify = false,
    })
    if not res or res.status ~= 200 then
        ngx.log(ngx.ERR, "oauth2 token fetch failed: ", err or (res and res.status))
        return nil, "oauth2 token fetch failed"
    end
    local data = util.json_decode(res.body)
    if not data or not data.access_token then
        return nil, "oauth2 invalid token response"
    end
    redis_client.set_oauth2_token(agent_id, data.access_token, data.expires_in)
    return data.access_token
end

-- 返回 (headers 表, extra_query 或 nil, err)
function _M.build_upstream_auth(agent_id, cfg)
    local t = cfg.upstream_auth_type
    if not t or t == "" or t == "NONE" then
        return {}, nil
    end
    local conf = util.json_decode(cfg.upstream_auth_config or "{}") or {}

    if t == "API_KEY" then
        if conf.location == "query" then
            return {}, (conf.name or "api_key") .. "=" .. ngx.escape_uri(tostring(conf.value or ""))
        end
        return { [conf.name or "X-Api-Key"] = tostring(conf.value or "") }, nil

    elseif t == "HTTP_BEARER" then
        return { ["Authorization"] = "Bearer " .. tostring(conf.token or "") }, nil

    elseif t == "HTTP_BASIC" then
        return { ["Authorization"] = "Basic "
            .. ngx.encode_base64(tostring(conf.username or "") .. ":" .. tostring(conf.password or "")) }, nil

    elseif t == "OAUTH2_CLIENT_CREDENTIALS" then
        local token, terr = get_oauth2_token(agent_id, conf)
        if not token then
            return nil, nil, terr or "oauth2 failed"
        end
        return { ["Authorization"] = "Bearer " .. token }, nil

    elseif t == "MTLS" then
        -- TLS 层双向认证（节点证书配置），无 header 注入
        return {}, nil
    end
    return {}, nil
end

return _M
