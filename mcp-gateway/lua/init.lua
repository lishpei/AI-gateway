-- init_by_lua：全局配置加载（环境变量由启动脚本注入）
-- 对应设计文档 3.2 节：集中式 Redis + IdP/管理台地址

local config = {
    redis_host       = os.getenv("REDIS_HOST") or "127.0.0.1",
    redis_port       = tonumber(os.getenv("REDIS_PORT") or "6379"),
    idp_base_url     = os.getenv("IDP_BASE_URL") or "http://127.0.0.1:8080/idp-mock",
    admin_base_url   = os.getenv("ADMIN_BASE_URL") or "http://127.0.0.1:8080",
    internal_key     = os.getenv("INTERNAL_KEY") or "dev-internal-key-2026",
    gateway_client_id     = os.getenv("GATEWAY_CLIENT_ID") or "mcp-gateway",
    gateway_client_secret = os.getenv("GATEWAY_CLIENT_SECRET") or "dev-gateway-secret",
    -- 网关自身 audience：Delegation Token 的 aud 必须包含它
    gateway_aud      = os.getenv("GATEWAY_AUD") or "mcp-gateway",
}

-- 暴露为模块，全 worker 共享只读
package.loaded["mcp.config"] = config

ngx.log(ngx.NOTICE, "mcp gateway config loaded: redis=", config.redis_host, ":",
        config.redis_port, " idp=", config.idp_base_url, " admin=", config.admin_base_url)
