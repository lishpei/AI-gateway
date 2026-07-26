#!/bin/bash
# WSL → Windows 连通性诊断
echo "=== 默认路由 ==="
ip route show default
echo "=== 主机 IP ==="
HOST_IP=$(ip route show default | awk '{print $3}')
echo "HOST_IP=$HOST_IP"
echo "=== ping 主机 ==="
ping -c 2 -W 2 "$HOST_IP" || echo "ping 失败"
echo "=== curl 管理台 (8080) ==="
curl -s --connect-timeout 3 "http://${HOST_IP}:8080/actuator/health" && echo "" || echo "8080 不通"
echo "=== curl Mock Server (8090) ==="
curl -s --connect-timeout 3 "http://${HOST_IP}:8090/health" && echo "" || echo "8090 不通"
