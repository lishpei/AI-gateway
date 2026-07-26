# 启动 agent-server-mock（Mock A2A Server Agent, 端口 8091）
New-Item -ItemType Directory -Path "D:\dev\logs" -Force | Out-Null
Start-Process -FilePath "node" -ArgumentList "server.js" `
    -WorkingDirectory "$PSScriptRoot\..\agent-server-mock" `
    -RedirectStandardOutput "D:\dev\logs\agent-server-mock.log" `
    -RedirectStandardError "D:\dev\logs\agent-server-mock-err.log" -WindowStyle Hidden
Write-Host "agent-server-mock 启动中 (日志: D:\dev\logs\agent-server-mock.log)"
