package com.corp.mcp.admin.service;

import com.corp.mcp.admin.util.Jsons;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 认证中心（IdP）客户端。dev 环境指向内嵌 IdP Mock，生产指向企业认证中心。
 * 管理台侧用途：策略配置时查询用户角色/组、Agent 信息。
 */
@Slf4j
@Service
public class IdPClientService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${mcp.idp.base-url}")
    private String idpBaseUrl;

    /**
     * 查询用户上下文：{user_id, email, org_id, dept_id, roles[], groups[]}。
     * 用户不存在时返回 null。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserContext(String userId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(idpBaseUrl + "/api/users/" + userId))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                log.warn("idp getUserContext failed: status={}, body={}", response.statusCode(), response.body());
                return null;
            }
            return Jsons.fromJson(response.body(), Map.class);
        } catch (Exception e) {
            log.error("idp getUserContext error: {}", e.getMessage());
            return null;
        }
    }

    /** 查询用户角色列表（不存在返回空表） */
    public List<String> getUserRoles(String userId) {
        Map<String, Object> ctx = getUserContext(userId);
        if (ctx == null) {
            return List.of();
        }
        Object roles = ctx.get("roles");
        return roles instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** 查询用户组列表（不存在返回空表） */
    public List<String> getUserGroups(String userId) {
        Map<String, Object> ctx = getUserContext(userId);
        if (ctx == null) {
            return List.of();
        }
        Object groups = ctx.get("groups");
        return groups instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** 查询 Agent 信息：{agent_id, name, type, owner, scopes[], status} */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAgent(String agentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(idpBaseUrl + "/api/agents/" + agentId))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return Jsons.fromJson(response.body(), Map.class);
        } catch (Exception e) {
            log.error("idp getAgent error: {}", e.getMessage());
            return null;
        }
    }
}
