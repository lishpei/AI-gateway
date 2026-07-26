# -*- coding: utf-8 -*-
"""旧版 HTTP+SSE 传输 E2E：endpoint 改写 / 会话映射 / 202 / 响应下行"""
import re
import threading
import time

import pytest
import requests

from conftest import GATEWAY, SERVER_ID, rpc


def open_sse(token):
    """打开旧版 SSE 流，返回 (response, events 收集列表, 线程)"""
    resp = requests.get(f"{GATEWAY}/{SERVER_ID}/sse",
                        headers={"Authorization": f"Bearer {token}",
                                 "Accept": "text/event-stream"},
                        stream=True, timeout=15)
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["Content-Type"]
    events = []

    def reader():
        buf = b""
        try:
            for chunk in resp.iter_content(chunk_size=256):
                buf += chunk
                while b"\n\n" in buf:
                    raw, buf = buf.split(b"\n\n", 1)
                    events.append(raw.decode("utf-8", errors="ignore"))
        except Exception:
            pass

    t = threading.Thread(target=reader, daemon=True)
    t.start()
    return resp, events, t


def wait_for_event(events, predicate, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for e in events:
            if predicate(e):
                return e
        time.sleep(0.1)
    return None


class TestLegacyTransport:
    def test_full_legacy_flow(self, alice_delegation_token):
        # 1. GET /sse → endpoint 事件（已改写为网关地址）
        resp, events, _ = open_sse(alice_delegation_token)
        endpoint_evt = wait_for_event(events, lambda e: "event: endpoint" in e)
        assert endpoint_evt, "应收到 endpoint 事件"
        m = re.search(r"data: (\S+)", endpoint_evt)
        assert m, "endpoint 事件应含 data 行"
        messages_uri = m.group(1)
        assert messages_uri.startswith(f"/{SERVER_ID}/messages?session_id="), \
            f"endpoint 应改写为网关地址, 实际: {messages_uri}"
        session_id = messages_uri.split("session_id=")[1]

        # 2. POST /messages → 202
        r = requests.post(f"{GATEWAY}{messages_uri}",
                          json=rpc("tools/call", {"name": "attendance.query",
                                                  "arguments": {"employee_id": "alice@corp.com"}},
                                   rpc_id=77),
                          headers={"Authorization": f"Bearer {alice_delegation_token}",
                                   "Content-Type": "application/json"}, timeout=10)
        assert r.status_code == 202, f"/messages 应返回 202, 实际 {r.status_code}"

        # 3. 响应经 SSE 流下行
        msg_evt = wait_for_event(events, lambda e: "event: message" in e and "出勤22天" in e)
        assert msg_evt, "SSE 流应下行 tools/call 响应"
        assert '"id":77' in msg_evt

        resp.close()

    def test_messages_unknown_session(self, alice_delegation_token):
        r = requests.post(f"{GATEWAY}/{SERVER_ID}/messages?session_id=ghost-session",
                          json=rpc("ping"),
                          headers={"Authorization": f"Bearer {alice_delegation_token}",
                                   "Content-Type": "application/json"}, timeout=5)
        assert r.status_code == 404

    def test_legacy_requires_auth(self):
        r = requests.get(f"{GATEWAY}/{SERVER_ID}/sse", timeout=5)
        assert r.status_code == 401

    def test_legacy_policy_enforced(self, bob_delegation_token):
        """旧版传输同样执行策略求值：bob 无策略 → 403"""
        resp, events, _ = open_sse(bob_delegation_token)
        endpoint_evt = wait_for_event(events, lambda e: "event: endpoint" in e)
        assert endpoint_evt
        messages_uri = re.search(r"data: (\S+)", endpoint_evt).group(1)
        r = requests.post(f"{GATEWAY}{messages_uri}",
                          json=rpc("tools/call", {"name": "attendance.query",
                                                  "arguments": {"employee_id": "bob@corp.com"}}),
                          headers={"Authorization": f"Bearer {bob_delegation_token}",
                                   "Content-Type": "application/json"}, timeout=5)
        assert r.status_code == 403
        resp.close()
