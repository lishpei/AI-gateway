-- content_by_lua：Agent Card 托管端点（占位符替换 + ETag/304，设计文档 6.4 节）
local util = require("a2a.util")
local redis_client = require("a2a.redis_client")

local agent_id = ngx.var.a2a_agent_id

local cfg, err = redis_client.get_agent_config(agent_id)
if not cfg then
    -- Card 发现端点无鉴权语义差异：一律 404，不泄露 Agent 存在性
    return util.json_error(404, util.ERR.AGENT_NOT_FOUND, "Agent not found")
end

-- 条件请求：If-None-Match 命中 → 304
local inm = ngx.req.get_headers()["If-None-Match"]
if inm and cfg.etag and inm == '"' .. cfg.etag .. '"' then
    ngx.status = 304
    return ngx.exit(304)
end

-- 占位符替换：{{GW_BASE}} → scheme://host（无 JSON 解析，纯字符串替换）
local gw_base = ngx.var.scheme .. "://" .. ngx.var.host
local body = (cfg.card_json:gsub("{{GW_BASE}}", gw_base))

ngx.header["Content-Type"] = "application/json"
ngx.header["Cache-Control"] = "public, max-age=300"
if cfg.etag then
    ngx.header["ETag"] = '"' .. cfg.etag .. '"'
end
ngx.header["X-Gateway-Card-Rewritten"] = "true"
ngx.print(body)
