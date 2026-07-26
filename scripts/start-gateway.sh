#!/bin/bash
# 启动 MCP 网关（在 WSL 内执行）
# 解析 Windows 主机 IP → 注入环境变量 → 启动 Redis + OpenResty

set -e

# 1. 解析 Windows 主机 IP（WSL2 NAT 模式下的默认网关）
HOST_IP=$(ip route show default | awk '{print $3}')
echo "Windows 主机 IP: $HOST_IP"

# 2. 启动 Redis（若未运行）
if ! redis-cli ping > /dev/null 2>&1; then
    echo "启动 Redis..."
    sudo service redis-server start
    sleep 1
fi
redis-cli ping

# 3. 导出网关环境变量（nginx.conf env 指令声明）
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export IDP_BASE_URL="http://${HOST_IP}:8080/idp-mock"
export ADMIN_BASE_URL="http://${HOST_IP}:8080"
export INTERNAL_KEY="dev-internal-key-2026"
export GATEWAY_CLIENT_ID="mcp-gateway"
export GATEWAY_CLIENT_SECRET="dev-gateway-secret"
export GATEWAY_AUD="mcp-gateway"

echo "IDP_BASE_URL=$IDP_BASE_URL"
echo "ADMIN_BASE_URL=$ADMIN_BASE_URL"

# 4. 启动 OpenResty（指定自定义 conf；先停旧进程）
if [ -f /var/run/mcp-gateway-nginx.pid ]; then
    sudo openresty -c /mnt/d/code/ai/agent-gateway/mcp-gateway/nginx.conf -s stop 2>/dev/null || true
    sleep 1
fi

echo "测试配置..."
sudo openresty -c /mnt/d/code/ai/agent-gateway/mcp-gateway/nginx.conf -t

echo "启动 OpenResty..."
sudo -E openresty -c /mnt/d/code/ai/agent-gateway/mcp-gateway/nginx.conf

sleep 1
echo "=== 验证 ==="
curl -s http://localhost:9080/healthz && echo ""
curl -s http://localhost:9080/readyz && echo ""
echo "网关已启动: http://localhost:9080 (Windows 侧同地址可达)"
