# -*- coding: utf-8 -*-
"""Streamable HTTP 主链路 E2E：initialize/会话/tools-call/SSE/策略/绑定/限流/审计"""
import json
import time

import pytest
import requests

from conftest import (GATEWAY, MOCK_SERVER, MOCK_RESOURCE_URI, SERVER_ID,
                      admin_headers, gateway_init, gateway_post, rpc)


class TestFullChain:
    """9.2 节全链路：Delegation Token → 网关 → Exchange → MCP Server"""

    def test_tools_call_success(self, alice_delegation_token, mock_debug):
        resp, sid = gateway_init(SERVER_ID, alice_delegation_token)
        assert resp.status_code == 200
        assert sid, "initialize 应返回 MCP-Session-Id"

        resp = gateway_post(SERVER_ID, alice_delegation_token,
                            rpc("tools/call", {"name": "attendance.query",
                                               "arguments": {"employee_id": "alice@corp.com"}}),
                            session_id=sid)
        assert resp.status_code == 200
        body = resp.json()
        assert "result" in body, body
        text = body["result"]["content"][0]["text"]
        assert "出勤22天" in text

    def test_token_exchanged_not_passthrough(self, alice_delegation_token, mock_debug):
        """核心安全断言：Mock Server 收到的 token 是 Exchange 后的（aud=Server），
        而非原始 Delegation Token（aud=mcp-gateway）"""
        gateway_post(SERVER_ID, alice_delegation_token,
                     rpc("tools/call", {"name": "attendance.query",
                                        "arguments": {"employee_id": "alice@corp.com"}}))
        time.sleep(0.3)
        requests_debug = mock_debug()
        assert requests_debug, "Mock Server 应记录到请求"
        for req in requests_debug:
            if req["rpc"] and req["rpc"].get("method") == "tools/call":
                token_info = req["token"]
                assert token_info is not None
                aud = token_info["aud"]
                aud_list = aud if isinstance(aud, list) else [aud]
                assert MOCK_RESOURCE_URI in aud_list, \
                    f"下游 token aud 应绑定 Server resourceUri, 实际: {aud_list}"
                assert "mcp-gateway" not in aud_list, \
                    "原始 Delegation Token 被透传（严重安全问题）"
                # 委托链保留
                assert token_info["act"] is not None
                # 身份头由网关注入
                assert req["headers"]["x-user-id"] == "alice"
                assert req["headers"]["x-user-email"] == "alice@corp.com"
                assert req["headers"]["x-data-scope"] == "self"
                return
        pytest.fail("未找到 tools/call 调试记录")

    def test_sse_streaming(self, alice_delegation_token):
        """SSE 流式透传：逐块收到进度事件与最终响应"""
        resp = gateway_post(SERVER_ID, alice_delegation_token,
                            rpc("tools/call", {"name": "attendance.stream",
                                               "arguments": {"month": "2026-07"}}))
        assert resp.status_code == 200
        assert "text/event-stream" in resp.headers["Content-Type"]
        chunks = []
        events = []
        for chunk in resp.iter_content(chunk_size=128):
            chunks.append(chunk)
            text = b"".join(chunks).decode("utf-8", errors="ignore")
            if text.count("\n\n") > len(events):
                events = [e for e in text.split("\n\n") if e.strip()]
        full = b"".join(chunks).decode()
        assert "data:" in full
        assert "考勤统计完成" in full
        assert len(events) >= 3, f"应逐块收到多个事件, 实际 {len(events)}"


class TestPolicyAndBinding:
    def test_deny_when_no_policy(self, bob_delegation_token):
        """bob 无任何策略 → 默认拒绝 403"""
        resp = gateway_post(SERVER_ID, bob_delegation_token,
                            rpc("tools/call", {"name": "attendance.query",
                                               "arguments": {"employee_id": "bob@corp.com"}}))
        assert resp.status_code == 403
        body = resp.json()
        assert body["error"]["code"] == -32011

    def test_param_binding_violation(self, alice_delegation_token):
        """alice 以 bob 的 employee_id 调用 → 参数绑定拒绝 403 -32016"""
        resp = gateway_post(SERVER_ID, alice_delegation_token,
                            rpc("tools/call", {"name": "attendance.query",
                                               "arguments": {"employee_id": "bob@corp.com"}}))
        assert resp.status_code == 403
        assert resp.json()["error"]["code"] == -32016

    def test_unknown_tool(self, alice_delegation_token):
        resp = gateway_post(SERVER_ID, alice_delegation_token,
                            rpc("tools/call", {"name": "ghost.tool", "arguments": {}}))
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == -32602

    def test_rate_limit(self, alice_delegation_token):
        """策略约束 30rpm（< 工具默认 60）→ 并发突发 40 次应耗尽令牌桶出现 429。
        必须并发（顺序调用速度慢于令牌回填速率则永不耗尽）。"""
        from concurrent.futures import ThreadPoolExecutor

        def call(i):
            return gateway_post(
                SERVER_ID, alice_delegation_token,
                rpc("tools/call", {"name": "attendance.query",
                                   "arguments": {"employee_id": "alice@corp.com"}},
                    rpc_id=100 + i)).status_code

        with ThreadPoolExecutor(max_workers=10) as pool:
            statuses = list(pool.map(call, range(40)))
        assert 429 in statuses, f"应触发限流, 状态分布: {sorted(set(statuses))}"
        ok_count = statuses.count(200)
        assert 25 <= ok_count <= 35, f"令牌桶容量30, 通过数应在25~35, 实际 {ok_count}"


class TestAuthFailures:
    def test_no_token(self):
        resp = requests.post(f"{GATEWAY}/{SERVER_ID}/mcp", json=rpc("ping"), timeout=5)
        assert resp.status_code == 401
        assert "WWW-Authenticate" in resp.headers
        assert resp.json()["error"]["code"] == -32010

    def test_invalid_token(self):
        resp = gateway_post(SERVER_ID, "invalid-token-xxx", rpc("ping"))
        assert resp.status_code == 401

    def test_server_not_found(self, alice_delegation_token):
        resp = gateway_post("ghost-server", alice_delegation_token, rpc("ping"))
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == -32012

    def test_aud_reuse_rejected_at_server(self, alice_delegation_token):
        """aud 绑定验证：Delegation Token(aud=mcp-gateway) 直接打 Mock Server 应被拒绝"""
        resp = requests.post(f"{MOCK_SERVER}/mcp", json=rpc("ping"),
                             headers={"Authorization": f"Bearer {alice_delegation_token}",
                                      "Content-Type": "application/json"}, timeout=5)
        assert resp.status_code == 403, "aud 非本 Server 的 token 应被资源侧拒绝"


class TestAudit:
    def test_audit_recorded(self, alice_delegation_token):
        marker = int(time.time())
        gateway_post(SERVER_ID, alice_delegation_token,
                     rpc("tools/call", {"name": "attendance.query",
                                        "arguments": {"employee_id": "alice@corp.com"}},
                         rpc_id=marker % 100000))
        time.sleep(4)  # 等待 audit flush（2s 周期）
        r = requests.get(f"http://localhost:8080/api/v1/audit/logs?serverId={SERVER_ID}&size=5",
                         headers=admin_headers(), timeout=5)
        data = r.json()["data"]
        assert data["total"] > 0, "审计应有记录"
        latest = data["list"][0]
        assert latest["toolName"] == "attendance.query"
        assert latest["delegatorUserId"] == "alice"
        assert latest["callerAgentId"] == "business-agent"
        assert latest["policyDecision"] == "allow"
        assert latest["tokenExchanged"] == 1
