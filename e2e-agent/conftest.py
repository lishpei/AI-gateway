# -*- coding: utf-8 -*-
"""A2A Agent 网关 E2E 共享 fixtures。

前置组件：
  - agent-admin (8081)
  - agent-server-mock (8091, 上游要求 X-Api-Key: mock-upstream-key)
  - a2a-gateway (9081, WSL OpenResty) + Redis (6379)
"""
import time

import pytest
import requests

ADMIN = "http://localhost:8081"
GATEWAY = "http://localhost:9081"
MOCK_SERVER = "http://localhost:8091"
ADMIN_TOKEN = "dev-admin-token-2026"

AGENT_ID = "weather-reporter"
ECHO_AGENT_ID = "echo-agent"
CALLER_ID = "data-analyst-bot"
UPSTREAM_API_KEY = "mock-upstream-key"


def admin_headers():
    return {"Authorization": f"Bearer {ADMIN_TOKEN}", "Content-Type": "application/json"}


def _upsert_agent(agent_id, streaming):
    """幂等注册 Agent（存在则跳过创建），始终发布"""
    r = requests.get(f"{ADMIN}/api/v1/agents/{agent_id}", headers=admin_headers(), timeout=5)
    if r.json().get("code") != 0:
        body = {
            "id": agent_id,
            "name": agent_id.replace("-", " ").title(),
            "description": f"{agent_id} 测试 Agent",
            "version": "1.0.0",
            "endpointUrl": f"http://{_gateway_host()}:8091/a2a",
            "capabilities": {"streaming": streaming, "pushNotifications": False},
            "defaultInputModes": ["text/plain"],
            "defaultOutputModes": ["text/plain", "application/json"],
            "skills": [{"id": "echo", "name": "echo", "description": "echo", "tags": ["test"]}],
        }
        r = requests.post(f"{ADMIN}/api/v1/agents", json=body, headers=admin_headers(), timeout=5)
        assert r.json()["code"] == 0, f"create agent failed: {r.text}"
    # 发布（刷新变更日志驱动节点同步）
    r = requests.post(f"{ADMIN}/api/v1/agents/{agent_id}/publish", headers=admin_headers(), timeout=5)
    assert r.json()["code"] == 0, f"publish failed: {r.text}"


def _gateway_host():
    """WSL 可达的 Windows 主机 IP（endpoint_url 指向 mock server 用）"""
    import subprocess
    try:
        out = subprocess.run(["wsl", "-d", "Ubuntu", "-u", "root", "ip", "route", "show", "default"],
                             capture_output=True, text=True, timeout=10)
        return out.stdout.split()[2]
    except Exception:
        return "localhost"


@pytest.fixture(scope="session", autouse=True)
def ensure_test_data():
    # 两个 Agent：streaming 开/关各一
    _upsert_agent(AGENT_ID, streaming=True)
    _upsert_agent(ECHO_AGENT_ID, streaming=False)

    # 上游凭证（API_KEY 代换）
    cred = {"authType": "API_KEY",
            "config": {"location": "header", "name": "X-Api-Key", "value": UPSTREAM_API_KEY}}
    requests.put(f"{ADMIN}/api/v1/agents/{AGENT_ID}/upstream-credential",
                 json=cred, headers=admin_headers(), timeout=5)

    # 调用方（存在则跳过）
    r = requests.get(f"{ADMIN}/api/v1/callers?page=1&size=100", headers=admin_headers(), timeout=5)
    callers = [c["id"] for c in r.json()["data"]["list"]]
    if CALLER_ID not in callers:
        requests.post(f"{ADMIN}/api/v1/callers",
                      json={"id": CALLER_ID, "name": "数据分析Bot"},
                      headers=admin_headers(), timeout=5)
    # ACL 覆盖两个 Agent
    requests.put(f"{ADMIN}/api/v1/callers/{CALLER_ID}/acl",
                 json={"agentIds": [AGENT_ID, ECHO_AGENT_ID]},
                 headers=admin_headers(), timeout=5)
    # 等待节点同步生效（5s 轮询 + 余量）
    time.sleep(7)
    yield


@pytest.fixture(scope="session")
def caller_api_key():
    """每会话生成一个新 Key（明文仅此可见）"""
    r = requests.post(f"{ADMIN}/api/v1/callers/{CALLER_ID}/credentials",
                      json={"keyName": "e2e"}, headers=admin_headers(), timeout=5)
    key = r.json()["data"]["apiKey"]
    time.sleep(7)  # 等待 Key 同步到节点
    return key


def gateway_headers(api_key):
    return {"X-API-Key": api_key, "Content-Type": "application/json"}


def send_message(agent_id, api_key, method="SendMessage", params=None, rpc_id=1):
    payload = {"jsonrpc": "2.0", "id": rpc_id, "method": method,
               "params": params or {"message": {"role": "ROLE_USER", "parts": [{"text": "北京天气"}]}}}
    return requests.post(f"{GATEWAY}/{agent_id}/a2a", json=payload,
                         headers=gateway_headers(api_key), timeout=15, stream=True)
