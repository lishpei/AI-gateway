-- content_by_lua（旧版 GET /sse）：
-- Exchange → 打开上游 SSE 流 → endpoint 事件改写（内网地址→网关地址）+ 会话映射建立 → 字节级透传
local http = require("resty.http")
local util = require("mcp.util")
local idp = require("mcp.idp")
local router = require("mcp.router")
local mcp_redis = require("mcp.redis")

local ctx = ngx.ctx
local cfg = ctx.server_cfg
local idctx = ctx.idctx
local server_id = ngx.var.mcp_server_id

-- 1. Token Exchange（连接级，scope 为 server 通配）
local downstream_token = nil
if cfg.auth_mode == "user-delegation" and idctx.user_sub then
    local token, terr = idp.exchange(idctx, cfg.resource_uri, "mcp:" .. server_id .. ":*")
    if not token then
        return util.abort(502, util.ERR.UPSTREAM, "Token exchange failed: " .. (terr or ""))
    end
    downstream_token = token
    ctx.token_exchanged = 1
elseif cfg.auth_mode == "service" then
    local token = idp.exchange(idctx, cfg.resource_uri, "mcp:" .. server_id .. ":*")
    downstream_token = token
    ctx.token_exchanged = token and 1 or 0
end

-- 2. 路由（新会话无粘滞，轮询选择）
local upstream, rerr = router.pick_instance(cfg, nil)
if not upstream then
    return util.abort(503, util.ERR.UPSTREAM, "No healthy upstream: " .. (rerr or ""))
end

-- 3. 打开上游 SSE 流（旧版端点 {base}/sse）
local headers = util.build_upstream_headers(ctx, downstream_token)
headers["Accept"] = "text/event-stream"

local httpc = http.new()
httpc:set_timeouts(5000, 10000, 600000)
local start_upstream = ngx.now() * 1000
local okc, cerr = httpc:connect({
    scheme = upstream.scheme,
    host = upstream.host,
    port = upstream.port,
    ssl_verify = false,
    ssl_server_name = upstream.host,
})
if not okc then
    return util.abort(502, util.ERR.UPSTREAM, "Upstream unavailable: " .. (cerr or ""))
end
local res, rerr2 = httpc:request({
    path = (upstream.path == "/" and "/sse" or upstream.path .. "/sse"),
    method = "GET",
    headers = headers,
})
ctx.upstream_latency_ms = math.floor(ngx.now() * 1000 - start_upstream)
ngx.var.mcp_upstream_rt = tostring(ctx.upstream_latency_ms)

if not res then
    return util.abort(502, util.ERR.UPSTREAM, "Upstream unavailable: " .. (rerr2 or ""))
end

local ct = res.headers and res.headers["content-type"] or ""
if not ct:find("text/event-stream", 1, true) then
    return util.abort(502, util.ERR.UPSTREAM, "Upstream did not open SSE stream")
end

-- 4. 响应头
ngx.status = 200
ngx.header["Content-Type"] = "text/event-stream"
ngx.header["Cache-Control"] = "no-cache"
ngx.header["X-Accel-Buffering"] = "no"
ngx.flush(true)

-- 5. 行级状态机：仅改写 endpoint 事件的 data 行，其余字节透传
local reader = res.body_reader
local pending = ""
local last_event = nil

local function process_line(line)
    local ev = line:match("^event:%s*(.-)%s*$")
    if ev then
        last_event = ev
        return line
    end
    if last_event == "endpoint" and line:match("^data:") then
        last_event = nil
        local session_id = line:match("session_id=([%w%-_]+)")
        if session_id then
            -- 建立会话映射并改写为网关地址
            mcp_redis.set_session(session_id, upstream.base)
            ctx.legacy_session_id = session_id
            return "data: /" .. server_id .. "/messages?session_id=" .. session_id
        end
    end
    return line
end

while true do
    local chunk, read_err = reader(65536)
    if read_err then
        ngx.log(ngx.ERR, "legacy sse upstream read error: ", read_err)
        break
    end
    if not chunk then break end

    pending = pending .. chunk
    local pos = 1
    while true do
        local nl = pending:find("\n", pos, true)
        if not nl then break end
        local line = pending:sub(pos, nl - 1)
        pos = nl + 1
        -- 去除行尾 \r
        line = line:gsub("\r$", "")
        local out_line = process_line(line)
        local ok, ferr = pcall(function()
            ngx.print(out_line .. "\n")
            ngx.flush(true)
        end)
        if not ok then
            ngx.log(ngx.NOTICE, "client disconnected during legacy sse: ", ferr)
            return ngx.exit(ngx.OK)
        end
    end
    pending = pending:sub(pos)
end

return ngx.exit(ngx.OK)
