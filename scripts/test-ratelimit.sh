#!/bin/bash
# 限流脚本行为测试
redis-cli DEL mcp:rl:test > /dev/null
SCRIPT='local data = redis.call("HMGET", KEYS[1], "tokens", "ts")
local rate = tonumber(ARGV[1])
local now = tonumber(ARGV[2])
local tokens = tonumber(data[1]) or rate
local ts = tonumber(data[2]) or now
tokens = math.min(rate, tokens + (now - ts) * rate / 60000)
if tokens >= 1 then
    tokens = tokens - 1
    redis.call("HMSET", KEYS[1], "tokens", tokens, "ts", now)
    redis.call("EXPIRE", KEYS[1], 120)
    return {1, math.floor(tokens)}
else
    redis.call("HMSET", KEYS[1], "tokens", tokens, "ts", now)
    return {0, math.floor(tokens)}
end'
echo "=== 连续 6 次调用 (rate=3) ==="
for i in 1 2 3 4 5 6; do
    redis-cli EVAL "$SCRIPT" 1 mcp:rl:test 3 1785074000000
done
