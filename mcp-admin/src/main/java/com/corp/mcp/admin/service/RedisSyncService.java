package com.corp.mcp.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.corp.mcp.admin.domain.entity.AuthPolicy;
import com.corp.mcp.admin.domain.entity.McpServer;
import com.corp.mcp.admin.domain.entity.McpTool;
import com.corp.mcp.admin.mapper.AuthPolicyMapper;
import com.corp.mcp.admin.mapper.McpServerMapper;
import com.corp.mcp.admin.mapper.McpToolMapper;
import com.corp.mcp.admin.util.Jsons;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册表/策略快照同步服务 —— Redis 配置数据的<b>唯一写入方</b>（设计文档 6.4）。
 *
 * <p>Key 布局：
 * <ul>
 *   <li>{@code mcp:server:cfg:{serverId}} — Hash，Server 路由配置</li>
 *   <li>{@code mcp:server:tools:{serverId}} — Hash，field=toolName，value=工具元数据 JSON</li>
 *   <li>{@code mcp:policy:ver:{serverId}} — String，快照版本（自增）</li>
 *   <li>{@code mcp:policy:snapshot:{serverId}} — String，生效策略快照 JSON</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSyncService {

    public static final String KEY_SERVER_CFG = "mcp:server:cfg:";
    public static final String KEY_SERVER_TOOLS = "mcp:server:tools:";
    public static final String KEY_POLICY_VER = "mcp:policy:ver:";
    public static final String KEY_POLICY_SNAPSHOT = "mcp:policy:snapshot:";

    private final StringRedisTemplate redis;
    private final McpServerMapper serverMapper;
    private final McpToolMapper toolMapper;
    private final AuthPolicyMapper policyMapper;

    /**
     * 发布 Server 完整快照（配置 + 工具 + 策略）到 Redis。
     * 在 Server 发布/更新、工具变更、策略变更后调用。
     */
    public long publishServerSnapshot(String serverId) {
        McpServer server = serverMapper.selectOne(
                new LambdaQueryWrapper<McpServer>().eq(McpServer::getServerId, serverId));
        if (server == null) {
            log.warn("publish snapshot skipped, server not found: {}", serverId);
            return -1;
        }

        // 1. Server 配置
        Map<String, String> cfg = new HashMap<>();
        cfg.put("base_url", nullToEmpty(server.getBaseUrl()));
        cfg.put("instances", server.getInstances() == null ? "[]" : server.getInstances());
        cfg.put("resource_uri", nullToEmpty(server.getResourceUri()));
        cfg.put("auth_mode", nullToEmpty(server.getAuthMode()));
        cfg.put("health_endpoint", nullToEmpty(server.getHealthEndpoint()));
        cfg.put("status", String.valueOf(server.getStatus()));
        cfg.put("protocol_type", nullToEmpty(server.getProtocolType()));
        redis.opsForHash().putAll(KEY_SERVER_CFG + serverId, cfg);

        // 2. 工具元数据（全量重建）
        redis.delete(KEY_SERVER_TOOLS + serverId);
        List<McpTool> tools = toolMapper.selectList(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId)
                .eq(McpTool::getIsActive, 1));
        if (!tools.isEmpty()) {
            Map<String, String> toolMap = new HashMap<>();
            for (McpTool t : tools) {
                toolMap.put(t.getToolName(), buildToolMetaJson(t));
            }
            redis.opsForHash().putAll(KEY_SERVER_TOOLS + serverId, toolMap);
        }

        // 3. 策略快照（生效中且在有效期内）
        List<AuthPolicy> policies = selectEffectivePolicies(serverId, LocalDateTime.now());
        Long version = redis.opsForValue().increment(KEY_POLICY_VER + serverId);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("version", version);
        snapshot.put("policies", policies.stream().map(this::toSnapshotItem).toList());
        redis.opsForValue().set(KEY_POLICY_SNAPSHOT + serverId, Jsons.toJson(snapshot));

        log.info("published snapshot: server={}, tools={}, policies={}, version={}",
                serverId, tools.size(), policies.size(), version);
        return version;
    }

    /** 从 Redis 移除 Server 全部运行时配置（废弃/删除时调用） */
    public void unpublish(String serverId) {
        redis.delete(KEY_SERVER_CFG + serverId);
        redis.delete(KEY_SERVER_TOOLS + serverId);
        redis.delete(KEY_POLICY_SNAPSHOT + serverId);
        redis.delete(KEY_POLICY_VER + serverId);
        log.info("unpublished server config: {}", serverId);
    }

    /** 查询当前快照版本（同步状态看板用） */
    public Map<String, Object> snapshotStatus(String serverId) {
        Map<String, Object> status = new HashMap<>();
        status.put("serverId", serverId);
        String ver = redis.opsForValue().get(KEY_POLICY_VER + serverId);
        status.put("snapshotVersion", ver == null ? null : Long.parseLong(ver));
        status.put("cfgExists", Boolean.TRUE.equals(redis.hasKey(KEY_SERVER_CFG + serverId)));
        status.put("toolCount", redis.opsForHash().size(KEY_SERVER_TOOLS + serverId));
        return status;
    }

    /** 查询生效策略：status=生效 且 (effectiveTime 为空或已到) 且 (expiryTime 为空或未到) */
    public List<AuthPolicy> selectEffectivePolicies(String serverId, LocalDateTime now) {
        return policyMapper.selectList(new LambdaQueryWrapper<AuthPolicy>()
                .eq(AuthPolicy::getServerId, serverId)
                .eq(AuthPolicy::getStatus, AuthPolicy.STATUS_EFFECTIVE)
                .and(w -> w.isNull(AuthPolicy::getEffectiveTime).or().le(AuthPolicy::getEffectiveTime, now))
                .and(w -> w.isNull(AuthPolicy::getExpiryTime).or().gt(AuthPolicy::getExpiryTime, now)));
    }

    private Map<String, Object> toSnapshotItem(AuthPolicy p) {
        Map<String, Object> item = new HashMap<>();
        item.put("policyId", p.getId());
        item.put("toolName", p.getToolName());
        item.put("granteeType", p.getGranteeType());
        item.put("granteeId", p.getGranteeId());
        item.put("effect", p.getEffect());
        item.put("dataScope", p.getDataScope());
        item.put("constraints", p.getConstraints() == null ? null : Jsons.toMap(p.getConstraints()));
        item.put("effectiveTime", p.getEffectiveTime() == null ? null : p.getEffectiveTime().toString());
        item.put("expiryTime", p.getExpiryTime() == null ? null : p.getExpiryTime().toString());
        return item;
    }

    private String buildToolMetaJson(McpTool t) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("description", t.getDescription());
        meta.put("required_scope", t.getRequiredScope());
        meta.put("rate_limit_rpm", t.getRateLimitRpm());
        meta.put("subject_bindings", t.getSubjectBindings() == null ? null : Jsons.toMapList(t.getSubjectBindings()));
        meta.put("validation_level", t.getValidationLevel());
        meta.put("output_masking", t.getOutputMasking() == null ? null : Jsons.toMapList(t.getOutputMasking()));
        meta.put("input_schema", t.getInputSchema() == null ? null : Jsons.toMap(t.getInputSchema()));
        meta.put("is_active", t.getIsActive());
        return Jsons.toJson(meta);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
