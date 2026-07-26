-- init_worker_by_lua：启动配置拉取循环（worker 0）
local sync_agent = require("a2a.sync_agent")
sync_agent.start()
