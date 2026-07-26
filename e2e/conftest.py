# -*- coding: utf-8 -*-
"""E2E 测试共享 fixtures：IdP 令牌链、网关/管理台/.Mock Server 客户端。

前置条件（由 scripts/start-all.ps1 保证）：
  - mcp-admin (8080, 内嵌 IdP Mock)
  - mcp-server-mock (8090)
  - mcp-gateway (9080, WSL OpenResty) + Redis (6379)
"""
import json
import subprocess
import time

import pytest
import requests

ADMIN = "http://localhost:8080"
IDP = f"{ADMIN}/idp-mock"
GATEWAY = "http://localhost:9080"
MOCK_SERVER = "http://localhost:8090"
MOCK_RESOURCE_URI = f"{MOCK_SERVER}/mcp"
SERVER_ID = "attendance-mcp"
ADMIN_TOKEN = "dev-admin-token-2026"


def gateway_host_ip():
    """解析 WSL 视角的 Windows 主机 IP（网关访问 Mock Server 的地址）。
    解析失败时回退 localhost（仅适用于同机部署）。"""
    try:
        out = subprocess.run(
            ["wsl", "-d", "Ubuntu", "-u", "root", "ip", "route", "show", "default"],
            capture_output=True, text=True, timeout=10)
        # 形如 "default via 192.168.96.1 dev eth0"
        return out.stdout.split()[2]
    except Exception:
        return "localhost"


MOCK_SERVER_FOR_GATEWAY = f"http://{gateway_host_ip()}:8090"

CLIENTS = {
    "employee-assistant": "agent-secret-1",
    "business-agent": "agent-secret-2",
    "mcp-gateway": "dev-gateway-secret",
}


# ---------------- IdP 令牌链助手 ----------------

USER_PASSWORDS = {"alice": "alice123", "bob": "bob123"}


def password_grant(username="alice", password=None, client_id="employee-assistant"):
    password = password or USER_PASSWORDS[username]
    r = requests.post(f"{IDP}/oauth/token", data={
        "grant_type": "password", "username": username,
        "password": password, "client_id": client_id,
    }, timeout=5)
    r.raise_for_status()
    return r.json()["access_token"]


def token_exchange(subject_token, client_id, resource=None, scope=None):
    data = {
        "grant_type": "urn:ietf:params:oauth:grant-type:token-exchange",
        "subject_token": subject_token,
    }
    if resource:
        data["resource"] = resource
    if scope:
        data["scope"] = scope
    r = requests.post(f"{IDP}/oauth/token", data=data,
                      auth=(client_id, CLIENTS[client_id]), timeout=5)
    r.raise_for_status()
    return r.json()["access_token"]


def delegation_token(username="alice", chain=("employee-assistant", "business-agent")):
    """按委托链依次 exchange，返回最终 Delegation Token（aud=mcp-gateway）"""
    token = password_grant(username)
    for actor in chain:
        token = token_exchange(token, actor)
    return token


@pytest.fixture(scope="session")
def alice_delegation_token():
    return delegation_token("alice")


@pytest.fixture(scope="session")
def bob_delegation_token():
    return delegation_token("bob")


# ---------------- 网关调用助手 ----------------

def rpc(method, params=None, rpc_id=1):
    msg = {"jsonrpc": "2.0", "id": rpc_id, "method": method}
    if params is not None:
        msg["params"] = params
    return msg


def gateway_post(server_id, token, payload, session_id=None, extra_headers=None):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
        "MCP-Protocol-Version": "2025-06-18",
    }
    if session_id:
        headers["MCP-Session-Id"] = session_id
    if extra_headers:
        headers.update(extra_headers)
    return requests.post(f"{GATEWAY}/{server_id}/mcp", json=payload,
                         headers=headers, timeout=15, stream=True)


def gateway_init(server_id, token):
    resp = gateway_post(server_id, token, rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "clientInfo": {"name": "e2e", "version": "1.0"},
    }))
    return resp, resp.headers.get("MCP-Session-Id")


# ---------------- 管理台助手 ----------------

def admin_headers():
    return {"Authorization": f"Bearer {ADMIN_TOKEN}", "Content-Type": "application/json"}


@pytest.fixture(scope="session", autouse=True)
def ensure_test_data():
    """确保测试 Server/策略已注册并发布（幂等）"""
    # Server（若已存在则跳过创建）
    r = requests.get(f"{ADMIN}/api/v1/registry/servers/{SERVER_ID}", headers=admin_headers(), timeout=5)
    if r.json().get("code") != 0:
        server = {
            "serverId": SERVER_ID, "name": "考勤MCP", "baseUrl": MOCK_SERVER_FOR_GATEWAY,
            "resourceUri": MOCK_RESOURCE_URI, "authMode": "user-delegation", "category": "hr",
            "tools": [
                {"toolName": "attendance.query", "description": "查询考勤",
                 "subjectBindings": json.dumps([{"param": "employee_id", "claim": "email", "required": True}]),
                 "rateLimitRpm": 60,
                 "inputSchema": json.dumps({
                     "type": "object",
                     "properties": {"employee_id": {"type": "string"}, "month": {"type": "string"}},
                     "required": ["employee_id"]})},
                {"toolName": "attendance.stream", "description": "流式考勤", "rateLimitRpm": 60},
            ],
        }
        r = requests.post(f"{ADMIN}/api/v1/registry/servers", json=server,
                          headers=admin_headers(), timeout=5)
        assert r.json()["code"] == 0, f"create server failed: {r.text}"

    # 策略：alice USER 通配授权（幂等：仅当不存在通配策略时创建）
    r = requests.get(f"{ADMIN}/api/v1/auth/policies?serverId={SERVER_ID}&status=1",
                     headers=admin_headers(), timeout=5)
    existing = [p for p in r.json()["data"]["list"]
                    if p["granteeType"] == "USER" and p["granteeId"] == "alice"
                    and p["toolName"] == "*"]
    if not existing:
        policy = {"policyName": "alice考勤", "serverId": SERVER_ID, "toolName": "*",
                  "granteeType": "USER", "granteeId": "alice", "dataScope": "self",
                  "constraints": json.dumps({"max_calls_per_minute": 30}), "effect": "ALLOW"}
        r = requests.post(f"{ADMIN}/api/v1/auth/policies", json=policy,
                          headers=admin_headers(), timeout=5)
        pid = r.json()["data"]["id"]
        requests.post(f"{ADMIN}/api/v1/auth/policies/{pid}/approve",
                      json={"approved": True}, headers=admin_headers(), timeout=5)

    # 发布（重建 Redis 快照）
    r = requests.post(f"{ADMIN}/api/v1/registry/servers/{SERVER_ID}/publish",
                      headers=admin_headers(), timeout=5)
    assert r.json()["code"] == 0, f"publish failed: {r.text}"

    yield


@pytest.fixture()
def mock_debug():
    """Mock Server 调试端点（每用例前清空）"""
    requests.post(f"{MOCK_SERVER}/__debug/clear", timeout=5)
    yield lambda: requests.get(f"{MOCK_SERVER}/__debug/requests", timeout=5).json()["requests"]
