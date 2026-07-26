#!/bin/bash
# 检查 Redis 中的策略快照/工具/限流键状态
echo "=== 策略快照 ==="
redis-cli GET mcp:policy:snapshot:attendance-mcp | head -c 800
echo ""
echo "=== 工具列表 ==="
redis-cli HKEYS mcp:server:tools:attendance-mcp
echo "=== 限流键 ==="
redis-cli KEYS "mcp:rl:*"
echo "=== 限流键内容 ==="
for k in $(redis-cli KEYS "mcp:rl:*"); do echo "$k: $(redis-cli HGETALL $k | tr '\n' ' ')"; done
