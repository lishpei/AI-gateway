#!/bin/bash
# 逐个测试 Lua 模块 require，定位加载错误
export PATH=/usr/local/openresty/bin:$PATH
for mod in mcp.util mcp.redis mcp.idp mcp.policy mcp.router; do
    echo "=== require $mod ==="
    resty -I /mnt/d/code/ai/agent-gateway/mcp-gateway/lua/ -e "package.loaded['mcp.config']={redis_host='127.0.0.1',redis_port=6379,idp_base_url='x',admin_base_url='x',internal_key='x',gateway_client_id='x',gateway_client_secret='x',gateway_aud='x'}; local ok,err = pcall(require, '$mod'); if ok then print('OK') else print('FAIL: '..tostring(err)) end" 2>&1
done
