# -*- coding: utf-8 -*-
"""A2A Agent 网关 E2E：Card 托管 / 第一跳认证 / ACL 防枚举 / 代理 / 凭证代换 / SSE / 同步传播"""
import time

import pytest
import requests

from conftest import (ADMIN, AGENT_ID, CALLER_ID, ECHO_AGENT_ID, GATEWAY,
                      MOCK_SERVER, admin_headers, send_message)


class TestCardDiscovery:
    def test_card_served_and_rewritten(self):
        r = requests.get(f"{GATEWAY}/{AGENT_ID}/.well-known/agent-card.json", timeout=5)
        assert r.status_code == 200
        card = r.json()
        # URL 重写为网关地址
        iface = card["supportedInterfaces"][0]
        assert iface["url"] == f"http://localhost/{AGENT_ID}/a2a" or \
               iface["url"].endswith(f"/{AGENT_ID}/a2a")
        assert iface["protocolBinding"] == "JSONRPC"
        # 上游 endpoint 不泄露
        assert "endpointUrl" not in card
        assert "endpoint_url" not in card
        # 对外安全声明为网关 API Key 方案
        assert "gateway-key" in card["securitySchemes"]
        # 缓存头（规范 8.6）
        assert "max-age" in r.headers.get("Cache-Control", "")
        assert "ETag" in r.headers
        assert r.headers.get("X-Gateway-Card-Rewritten") == "true"

    def test_card_etag_304(self):
        r = requests.get(f"{GATEWAY}/{AGENT_ID}/.well-known/agent-card.json", timeout=5)
        etag = r.headers["ETag"]
        r2 = requests.get(f"{GATEWAY}/{AGENT_ID}/.well-known/agent-card.json",
                          headers={"If-None-Match": etag}, timeout=5)
        assert r2.status_code == 304

    def test_card_not_found(self):
        r = requests.get(f"{GATEWAY}/ghost-agent/.well-known/agent-card.json", timeout=5)
        assert r.status_code == 404


class TestCallerAuth:
    def test_missing_api_key(self):
        r = requests.post(f"{GATEWAY}/{AGENT_ID}/a2a",
                          json={"jsonrpc": "2.0", "id": 1, "method": "SendMessage",
                                "params": {"message": {"role": "ROLE_USER", "parts": []}}},
                          timeout=5)
        assert r.status_code == 401
        assert r.json()["error"]["code"] == -32010

    def test_invalid_api_key(self):
        r = send_message(AGENT_ID, "gwk_invalid_key_xxxxxxxxxxxxxxxxxxxxxxxx")
        assert r.status_code == 401
        assert r.json()["error"]["code"] == -32010

    def test_acl_forbidden(self, caller_api_key):
        """无 ACL 的 Agent（新建 third-agent 不授权）→ 403 且与不存在同码（防枚举）"""
        # third-agent 注册并发布但不加入 ACL
        body = {
            "id": "third-agent", "name": "Third", "version": "1.0.0",
            "endpointUrl": "http://192.168.96.1:8091/a2a",
            "capabilities": {"streaming": False},
            "defaultInputModes": ["text/plain"], "defaultOutputModes": ["text/plain"],
            "skills": [{"id": "t", "name": "t", "description": "t", "tags": ["t"]}],
        }
        requests.post(f"{ADMIN}/api/v1/agents", json=body, headers=admin_headers(), timeout=5)
        requests.post(f"{ADMIN}/api/v1/agents/third-agent/publish", headers=admin_headers(), timeout=5)
        time.sleep(7)
        r = send_message("third-agent", caller_api_key)
        assert r.status_code == 403
        assert r.json()["error"]["code"] == -32011
        # 与"Agent 不存在"同码同消息（防枚举）
        r2 = send_message("ghost-agent-xyz", caller_api_key)
        assert r2.status_code == 403
        assert r2.json()["error"]["code"] == -32011


class TestProxy:
    def test_send_message_full_chain(self, caller_api_key):
        r = send_message(AGENT_ID, caller_api_key)
        assert r.status_code == 200
        body = r.json()
        assert "result" in body
        # 天气问题返回完成的 Task
        assert body["result"]["task"]["status"]["state"] == "TASK_STATE_COMPLETED"

    def test_credential_injection_and_header_passthrough(self, caller_api_key):
        requests.post(f"{MOCK_SERVER}/__debug/clear", timeout=5)
        r = send_message(AGENT_ID, caller_api_key, rpc_id=42)
        assert r.status_code == 200
        time.sleep(0.3)
        records = requests.get(f"{MOCK_SERVER}/__debug/requests", timeout=5).json()["requests"]
        target = [x for x in records if x["rpc"] and x["rpc"].get("id") == 42]
        assert target, "上游应记录到请求"
        headers = target[0]["headers"]
        # 网关注入了上游凭证（X-Api-Key），且客户端的 X-API-Key 已被擦除不混入
        assert headers["x-api-key"] == "<present>"
        # 协议头透传
        assert headers["x-request-id"]

    def test_sse_streaming(self, caller_api_key):
        r = send_message(AGENT_ID, caller_api_key, method="SendStreamingMessage")
        assert r.status_code == 200
        assert "text/event-stream" in r.headers["Content-Type"]
        chunks = []
        for chunk in r.iter_content(chunk_size=128):
            chunks.append(chunk)
        full = b"".join(chunks).decode()
        # 首事件 Task + 后续 statusUpdate
        assert '"task"' in full
        assert '"statusUpdate"' in full
        assert "TASK_STATE_COMPLETED" in full
        assert full.count("data:") >= 4

    def test_streaming_not_supported(self, caller_api_key):
        """echo-agent streaming=false → 流式方法快速失败 -32004"""
        r = send_message(ECHO_AGENT_ID, caller_api_key, method="SendStreamingMessage")
        assert r.status_code == 400
        assert r.json()["error"]["code"] == -32004

    def test_get_method_rejected(self, caller_api_key):
        r = requests.get(f"{GATEWAY}/{AGENT_ID}/a2a",
                         headers={"X-API-Key": caller_api_key}, timeout=5)
        assert r.status_code == 405


class TestSyncPropagation:
    def test_new_agent_propagates_to_node(self):
        """新注册+发布的 Agent 在轮询周期内同步到节点并可访问 Card"""
        agent_id = f"sync-test-{int(time.time()) % 10000}"
        body = {
            "id": agent_id, "name": "Sync Test", "version": "1.0.0",
            "endpointUrl": "http://192.168.96.1:8091/a2a",
            "capabilities": {"streaming": False},
            "defaultInputModes": ["text/plain"], "defaultOutputModes": ["text/plain"],
            "skills": [{"id": "s", "name": "s", "description": "s", "tags": ["s"]}],
        }
        requests.post(f"{ADMIN}/api/v1/agents", json=body, headers=admin_headers(), timeout=5)
        requests.post(f"{ADMIN}/api/v1/agents/{agent_id}/publish", headers=admin_headers(), timeout=5)
        # 轮询等待节点生效（5s 轮询 + 余量）
        deadline = time.time() + 12
        ok = False
        while time.time() < deadline:
            r = requests.get(f"{GATEWAY}/{agent_id}/.well-known/agent-card.json", timeout=3)
            if r.status_code == 200:
                ok = True
                break
            time.sleep(1)
        assert ok, "新 Agent 未在 12s 内同步到节点"

    def test_node_heartbeat_recorded(self):
        r = requests.get(f"{ADMIN}/api/v1/dashboard/nodes", headers=admin_headers(), timeout=5)
        nodes = r.json()["data"]
        assert len(nodes) >= 1
        node = list(nodes)[0]
        assert node["nodeId"]
        assert node["seq"] >= 1
        assert node["redisOk"] is True or node["redisOk"] == "true"
