# 启动 Mock MCP Server (Node.js, 端口 8090)
New-Item -ItemType Directory -Path "D:\dev\logs" -Force | Out-Null
Start-Process -FilePath "node" -ArgumentList "server.js" `
    -WorkingDirectory "$PSScriptRoot\..\mcp-server-mock" `
    -RedirectStandardOutput "D:\dev\logs\mock-server.log" `
    -RedirectStandardError "D:\dev\logs\mock-server-err.log" -WindowStyle Hidden
Write-Host "mcp-server-mock 启动中 (日志: D:\dev\logs\mock-server.log)"
