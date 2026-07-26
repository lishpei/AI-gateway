#!/bin/bash
# A2A 网关 Lua 语法检查
export PATH=/usr/local/openresty/luajit/bin:$PATH
fail=0
for f in /mnt/d/code/ai/agent-gateway/a2a-gateway/lua/*.lua /mnt/d/code/ai/agent-gateway/a2a-gateway/lua/a2a/*.lua; do
    if luajit -bl "$f" /dev/null 2>/tmp/luaerr.txt; then
        echo "OK   $f"
    else
        echo "FAIL $f"
        cat /tmp/luaerr.txt
        fail=1
    fi
done
exit $fail
