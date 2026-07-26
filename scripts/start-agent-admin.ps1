# 启动 agent-admin（A2A 网关管理面, 端口 8081）
$env:JAVA_HOME = "D:\dev\jdk-17"
$env:Path = "D:\dev\jdk-17\bin;D:\dev\maven\bin;" + $env:Path
New-Item -ItemType Directory -Path "D:\dev\logs" -Force | Out-Null
Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" `
    -WorkingDirectory "$PSScriptRoot\..\agent-admin" `
    -RedirectStandardOutput "D:\dev\logs\agent-admin.log" `
    -RedirectStandardError "D:\dev\logs\agent-admin-err.log" -WindowStyle Hidden
Write-Host "agent-admin 启动中 (日志: D:\dev\logs\agent-admin.log)"
