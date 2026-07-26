package com.corp.mcp.admin.service;

import com.corp.mcp.admin.domain.dto.AuthCheckDTO;
import com.corp.mcp.admin.domain.entity.AuthPolicy;
import com.corp.mcp.admin.util.Jsons;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略求值（设计文档第 8 章）——管理台侧实现，供 /auth/check 使用；
 * 网关 Lua 侧按同一语义实现（快照本地求值）。
 *
 * <p>语义：DENY 优先 → ALLOW → 默认拒绝；约束取最严格；dataScope 取最大值。</p>
 */
@Service
@RequiredArgsConstructor
public class PolicyEvaluationService {

    /** dataScope 严重度（越大越宽） */
    private static final Map<String, Integer> SCOPE_LEVEL = Map.of(
            AuthPolicy.SCOPE_SELF, 1,
            AuthPolicy.SCOPE_TEAM, 2,
            AuthPolicy.SCOPE_DEPARTMENT, 3,
            AuthPolicy.SCOPE_ORGANIZATION, 4);

    private final RedisSyncService syncService;
    private final IdPClientService idPClient;

    public AuthCheckDTO.Response evaluate(AuthCheckDTO.Request req) {
        LocalDateTime now = LocalDateTime.now();
        List<AuthPolicy> policies = syncService.selectEffectivePolicies(req.getServerId(), now);

        // 用户角色/组（有用户上下文时）
        List<String> roles = req.getUserId() == null ? List.of() : idPClient.getUserRoles(req.getUserId());
        List<String> groups = req.getUserId() == null ? List.of() : idPClient.getUserGroups(req.getUserId());

        boolean hasAllow = false;
        String denyReason = null;
        String maxDataScope = null;
        Integer minRpm = null;
        String mergedTimeRange = null;

        for (AuthPolicy p : policies) {
            // 1. 工具匹配（精确或 *）
            if (!"*".equals(p.getToolName()) && !p.getToolName().equals(req.getToolName())) {
                continue;
            }
            // 2. 授权对象匹配
            if (!granteeMatches(p, req, roles, groups)) {
                continue;
            }
            // 3. 裁决
            if (AuthPolicy.EFFECT_DENY.equals(p.getEffect())) {
                denyReason = "explicitly denied by policy #" + p.getId();
                AuthCheckDTO.Response r = AuthCheckDTO.Response.deny(denyReason);
                return r;
            }
            // ALLOW：合并约束与 dataScope
            hasAllow = true;
            Map<String, Object> constraints = p.getConstraints() == null ? null : Jsons.toMap(p.getConstraints());
            if (constraints != null) {
                Object rpm = constraints.get("max_calls_per_minute");
                if (rpm instanceof Number n) {
                    minRpm = minRpm == null ? n.intValue() : Math.min(minRpm, n.intValue());
                }
                Object tr = constraints.get("time_range");
                if (tr instanceof String s && !s.isBlank()) {
                    mergedTimeRange = s; // 多条 time_range 时取交集实现复杂，一期取最后一条（快照中罕见）
                }
            }
            if (maxDataScope == null || level(p.getDataScope()) > level(maxDataScope)) {
                maxDataScope = p.getDataScope();
            }
        }

        if (!hasAllow) {
            return AuthCheckDTO.Response.deny("no matching policy");
        }
        // 4. 时间窗口约束
        if (mergedTimeRange != null && !inTimeRange(mergedTimeRange, LocalTime.now())) {
            return AuthCheckDTO.Response.deny("outside allowed time range " + mergedTimeRange);
        }

        AuthCheckDTO.Response r = new AuthCheckDTO.Response();
        r.setAllowed(true);
        r.setDataScope(maxDataScope);
        Map<String, Object> constraints = new HashMap<>();
        if (minRpm != null) {
            constraints.put("max_calls_per_minute", minRpm);
        }
        if (mergedTimeRange != null) {
            constraints.put("time_range", mergedTimeRange);
        }
        r.setConstraints(constraints);
        return r;
    }

    private boolean granteeMatches(AuthPolicy p, AuthCheckDTO.Request req,
                                   List<String> roles, List<String> groups) {
        switch (p.getGranteeType()) {
            case AuthPolicy.GRANTEE_AGENT -> {
                return req.getAgentChain() != null && req.getAgentChain().contains(p.getGranteeId());
            }
            case AuthPolicy.GRANTEE_USER -> {
                return req.getUserId() != null && req.getUserId().equals(p.getGranteeId());
            }
            case AuthPolicy.GRANTEE_ROLE -> {
                return roles.contains(p.getGranteeId());
            }
            case AuthPolicy.GRANTEE_GROUP -> {
                return groups.contains(p.getGranteeId());
            }
            default -> {
                return false;
            }
        }
    }

    private int level(String scope) {
        return SCOPE_LEVEL.getOrDefault(scope == null ? AuthPolicy.SCOPE_SELF : scope, 1);
    }

    /** time_range 格式 "HH:mm-HH:mm"；跨零点（如 22:00-06:00）按环绕处理 */
    static boolean inTimeRange(String range, LocalTime now) {
        try {
            String[] parts = range.split("-");
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            return !now.isBefore(start) || now.isBefore(end);
        } catch (Exception e) {
            return true; // 配置非法时不阻断（管理台录入校验兜底）
        }
    }
}
