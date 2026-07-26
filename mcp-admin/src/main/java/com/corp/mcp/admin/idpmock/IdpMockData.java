package com.corp.mcp.admin.idpmock;

import java.util.List;
import java.util.Map;

/**
 * IdP Mock 预置数据：测试用户、Agent、OAuth 客户端。
 * 对应《MCP网关详细设计文档》6.5 节 mock-data 预置。
 */
public final class IdpMockData {

    private IdpMockData() {
    }

    /** 测试用户：userId → 用户信息 */
    public static final Map<String, Map<String, Object>> USERS = Map.of(
            "alice", Map.of(
                    "user_id", "alice",
                    "name", "Alice Zhang",
                    "email", "alice@corp.com",
                    "password", "alice123",
                    "org_id", "org-corp",
                    "dept_id", "dept-hr",
                    "roles", List.of("employee", "hr-viewer"),
                    "groups", List.of("hr-staff")),
            "bob", Map.of(
                    "user_id", "bob",
                    "name", "Bob Li",
                    "email", "bob@corp.com",
                    "password", "bob123",
                    "org_id", "org-corp",
                    "dept_id", "dept-eng",
                    "roles", List.of("employee"),
                    "groups", List.of("eng-staff")));

    /** 测试 Agent：agentId → Agent 信息 */
    public static final Map<String, Map<String, Object>> AGENTS = Map.of(
            "employee-assistant", Map.of(
                    "agent_id", "employee-assistant",
                    "name", "员工助手(中台Agent)",
                    "type", "platform",
                    "owner", "ai-platform",
                    "scopes", List.of("a2a", "mcp"),
                    "status", "active"),
            "business-agent", Map.of(
                    "agent_id", "business-agent",
                    "name", "业务Agent",
                    "type", "business",
                    "owner", "hr-team",
                    "scopes", List.of("mcp"),
                    "status", "active"));

    /** OAuth 客户端凭证：clientId → clientSecret（含网关自身 client） */
    public static final Map<String, String> CLIENTS = Map.of(
            "employee-assistant", "agent-secret-1",
            "business-agent", "agent-secret-2",
            "mcp-gateway", "dev-gateway-secret");

    public static Map<String, Object> publicUserInfo(String userId) {
        Map<String, Object> user = USERS.get(userId);
        if (user == null) {
            return null;
        }
        // 不返回密码
        return user.entrySet().stream()
                .filter(e -> !"password".equals(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
