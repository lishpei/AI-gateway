# 停止全部本地组件
Write-Host "停止 A2A 网关 (WSL)..."
wsl -d Ubuntu -e sudo openresty -c /mnt/d/code/ai/agent-gateway/a2a-gateway/nginx.conf -s stop 2>$null

Write-Host "停止 MCP 网关 (WSL)..."
wsl -d Ubuntu -e sudo openresty -c /mnt/d/code/ai/agent-gateway/mcp-gateway/nginx.conf -s stop 2>$null

Write-Host "停止 java/node 进程（两个 admin + 两个 mock）..."
Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name node -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

Write-Host "已停止 (MySQL 服务 MySQL8 保持运行)"
