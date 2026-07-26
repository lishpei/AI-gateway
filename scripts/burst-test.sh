#!/bin/bash
# 突发调用限流实测：先从 IdP 拿 token 链，再连续调 35 次
HOST_IP=$(ip route show default | awk '{print $3}')
IDP="http://${HOST_IP}:8080/idp-mock"
GW="http://localhost:9080"

# token 链: password → exchange ×2
T1=$(curl -s -X POST "$IDP/oauth/token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=alice&password=alice123&client_id=employee-assistant" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
B1=$(echo -n "employee-assistant:agent-secret-1" | base64)
T2=$(curl -s -X POST "$IDP/oauth/token" -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $B1" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=$T1" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
B2=$(echo -n "business-agent:agent-secret-2" | base64)
T3=$(curl -s -X POST "$IDP/oauth/token" -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $B2" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=$T2" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

echo "token ready, 开始突发 35 次调用..."
redis-cli DEL "mcp:rl:agent-tool:business-agent:attendance-mcp:attendance.query" > /dev/null

BODY='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"attendance.query","arguments":{"employee_id":"alice@corp.com"}}}'
declare -A codes
for i in $(seq 1 35); do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GW/attendance-mcp/mcp" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $T3" \
      -H "MCP-Protocol-Version: 2025-06-18" --data-binary "$BODY")
    codes[$code]=$((${codes[$code]:-0} + 1))
done
echo "状态码分布:"
for k in "${!codes[@]}"; do echo "  $k: ${codes[$k]} 次"; done
echo "限流键状态:"
redis-cli HGETALL "mcp:rl:agent-tool:business-agent:attendance-mcp:attendance.query"
