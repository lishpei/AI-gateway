-- init_by_lua：全局配置（环境变量由启动脚本注入）
local config = {
    redis_host     = os.getenv("REDIS_HOST") or "127.0.0.1",
    redis_port     = tonumber(os.getenv("REDIS_PORT") or "6379"),
    admin_base_url = os.getenv("ADMIN_BASE_URL") or "http://127.0.0.1:8081",
    node_token     = os.getenv("NODE_TOKEN") or "dev-node-token-1",
    node_id        = os.getenv("NODE_ID") or "node-local-1",
    sync_interval  = 5,    -- 正常轮询间隔(秒)
    retry_max      = 60,   -- 失败退避上限(秒)
}

package.loaded["a2a.config"] = config

ngx.log(ngx.NOTICE, "a2a gateway config loaded: redis=", config.redis_host,
        " admin=", config.admin_base_url, " node=", config.node_id)
