#!/bin/bash
# 启动 A2A Agent 网关（WSL 内执行，端口 9081）
set -e

HOST_IP=$(ip route show default | awk '{print $3}')
echo "Windows 主机 IP: $HOST_IP"

# Redis（与 MCP 网关共用本地实例，key 命名空间隔离）
if ! redis-cli ping > /dev/null 2>&1; then
    echo "启动 Redis..."
    sudo service redis-server start
    sleep 1
fi
redis-cli ping

export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export ADMIN_BASE_URL="http://${HOST_IP}:8081"
export NODE_TOKEN="dev-node-token-1"
export NODE_ID="node-local-1"

echo "ADMIN_BASE_URL=$ADMIN_BASE_URL"

# 停旧进程（若存在）
if [ -f /var/run/a2a-gateway-nginx.pid ]; then
    sudo openresty -c /mnt/d/code/ai/agent-gateway/a2a-gateway/nginx.conf -s stop 2>/dev/null || true
    sleep 1
fi

echo "测试配置..."
sudo openresty -c /mnt/d/code/ai/agent-gateway/a2a-gateway/nginx.conf -t

echo "启动 A2A 网关..."
sudo -E openresty -c /mnt/d/code/ai/agent-gateway/a2a-gateway/nginx.conf

sleep 1
echo "=== 验证 ==="
curl -s http://localhost:9081/statusz && echo ""
echo "A2A 网关已启动: http://localhost:9081"
