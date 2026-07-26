# 启动 mcp-admin（管理台后端, 内嵌 IdP Mock dev版）
$env:JAVA_HOME = "D:\dev\jdk-17"
$env:Path = "D:\dev\jdk-17\bin;D:\dev\maven\bin;" + $env:Path
Set-Location "$PSScriptRoot\..\mcp-admin"
Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" `
    -WorkingDirectory "$PSScriptRoot\..\mcp-admin" `
    -RedirectStandardOutput "D:\dev\logs\mcp-admin.log" `
    -RedirectStandardError "D:\dev\logs\mcp-admin-err.log" -WindowStyle Hidden
Write-Host "mcp-admin 启动中 (日志: D:\dev\logs\mcp-admin.log)"
