# 一键启动全部本地组件（MCP 网关 + A2A 网关全家桶）
New-Item -ItemType Directory -Path "D:\dev\logs" -Force | Out-Null

# 确保 WSL 网卡防火墙放行（WSL→Windows 连通性，幂等）
Set-NetFirewallProfile -Profile Public -DisabledInterfaceAliases "vEthernet (WSL)" -ErrorAction SilentlyContinue

Write-Host "=== 1/6 mcp-admin (:8080) ==="
& "$PSScriptRoot\start-admin.ps1"

Write-Host "=== 2/6 mcp-server-mock (:8090) ==="
& "$PSScriptRoot\start-mock-server.ps1"

Write-Host "=== 3/6 agent-admin (:8081) ==="
& "$PSScriptRoot\start-agent-admin.ps1"

Write-Host "=== 4/6 agent-server-mock (:8091) ==="
& "$PSScriptRoot\start-agent-server-mock.ps1"

Write-Host "=== 5/6 MCP 网关 (:9080, WSL) ==="
wsl -d Ubuntu -e bash /mnt/d/code/ai/agent-gateway/scripts/start-gateway.sh

Write-Host "=== 6/6 A2A 网关 (:9081, WSL) ==="
wsl -d Ubuntu -e bash /mnt/d/code/ai/agent-gateway/scripts/start-a2a-gateway.sh

Write-Host ""
Write-Host "全部启动完成:"
Write-Host "  管理台(MCP):   http://localhost:8080  前端: http://localhost:5173"
Write-Host "  管理面(A2A):   http://localhost:8081"
Write-Host "  Mock MCP:      http://localhost:8090   Mock A2A: http://localhost:8091"
Write-Host "  MCP 网关:      http://localhost:9080   A2A 网关: http://localhost:9081"
