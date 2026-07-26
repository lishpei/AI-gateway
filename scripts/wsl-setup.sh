#!/bin/bash
# WSL Ubuntu 环境初始化：安装 OpenResty + Redis（在 WSL 内执行: bash wsl-setup.sh）
set -e

echo "=== apt update ==="
sudo apt-get update -y

echo "=== 安装基础工具 ==="
sudo apt-get install -y curl gnupg ca-certificates lsb-release

echo "=== 添加 OpenResty 官方源 ==="
if [ ! -f /etc/apt/sources.list.d/openresty.list ]; then
    curl -fsSL https://openresty.org/package/pubkey.gpg | sudo gpg --dearmor -o /usr/share/keyrings/openresty.gpg --yes
    echo "deb [signed-by=/usr/share/keyrings/openresty.gpg] http://openresty.org/package/ubuntu $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/openresty.list
    sudo apt-get update -y
fi

echo "=== 安装 OpenResty + Redis ==="
sudo apt-get install -y openresty redis-server

echo "=== 安装 lua-resty-http（不随 OpenResty 捆绑，经 OPM 安装） ==="
sudo opm get ledgetech/lua-resty-http

echo "=== 版本确认 ==="
openresty -v
redis-server --version

echo "=== 创建日志目录 ==="
sudo mkdir -p /var/log/nginx
sudo touch /var/log/mcp-gateway-audit-fallback.log

echo "=== 完成。启动网关: bash /mnt/d/code/ai/agent-gateway/scripts/start-gateway.sh ==="
