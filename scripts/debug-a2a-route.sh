#!/bin/bash
# /a2a 路由排查
echo "=== GET /weather-reporter/a2a (期望405) ==="
curl -s -w "\n[HTTP %{http_code}]\n" "http://localhost:9081/weather-reporter/a2a"
echo "=== GET /weather-reporter/a2a/ ==="
curl -s -w "\n[HTTP %{http_code}]\n" "http://localhost:9081/weather-reporter/a2a/"
echo "=== 错误日志尾部 ==="
tail -n 10 /var/log/nginx/a2a-error.log
