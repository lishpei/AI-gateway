package com.corp.agent.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.corp.agent.admin.domain.entity.*;
import com.corp.agent.admin.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点配置同步（拉取模型，设计文档第 8 章）。
 *
 * <p>GET /internal/v1/sync/config?since={seq}：
 * <ul>
 *   <li>since=0 或早于日志最小保留 seq → 全量快照（fullSync=true）</li>
 *   <li>否则增量：seq&gt;since 的变更按实体合并（最后写胜），payload 实时组装</li>
 * </ul>
 * 心跳：内存 Map 保存各节点水位（看板用）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private static final int INCREMENTAL_LIMIT = 1000;

    private final ConfigChangeLogMapper changeLogMapper;
    private final AgentCardMapper agentMapper;
    private final UpstreamCredentialMapper credentialMapper;
    private final CallerMapper callerMapper;
    private final CallerCredentialMapper callerCredentialMapper;
    private final CallerAgentAclMapper aclMapper;
    private final CryptoService cryptoService;
    private final PublishService publishService;

    /** 节点心跳：nodeId → {seq, redisOk, ts} */
    private final Map<String, Map<String, Object>> heartbeats = new ConcurrentHashMap<>();

    public Map<String, Object> syncConfig(long since) {
        Long maxSeq = changeLogMapper.selectObjs(
                        new LambdaQueryWrapper<ConfigChangeLog>()
                                .select(ConfigChangeLog::getSeq)
                                .orderByDesc(ConfigChangeLog::getSeq)
                                .last("LIMIT 1"))
                .stream().findFirst().map(o -> ((Number) o).longValue()).orElse(0L);
        Long minSeq = changeLogMapper.selectObjs(
                        new LambdaQueryWrapper<ConfigChangeLog>()
                                .select(ConfigChangeLog::getSeq)
                                .orderByAsc(ConfigChangeLog::getSeq)
                                .last("LIMIT 1"))
                .stream().findFirst().map(o -> ((Number) o).longValue()).orElse(0L);

        Map<String, Object> data = new HashMap<>();
        if (since <= 0 || (minSeq > 0 && since < minSeq)) {
            // 全量快照
            data.put("fullSync", true);
            data.put("seq", maxSeq);
            data.put("snapshot", buildSnapshot());
            return data;
        }

        // 增量
        List<ConfigChangeLog> logs = changeLogMapper.selectList(
                new LambdaQueryWrapper<ConfigChangeLog>()
                        .gt(ConfigChangeLog::getSeq, since)
                        .orderByAsc(ConfigChangeLog::getSeq)
                        .last("LIMIT " + INCREMENTAL_LIMIT));

        // 按实体合并（同实体保留最大 seq 的最后一次）
        Map<String, ConfigChangeLog> merged = new LinkedHashMap<>();
        for (ConfigChangeLog log : logs) {
            merged.put(log.getEntityType() + "|" + log.getEntityId(), log);
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        long newWatermark = since;
        for (ConfigChangeLog log : merged.values()) {
            newWatermark = Math.max(newWatermark, log.getSeq());
            changes.add(toChangeEvent(log));
        }
        // 若增量被截断（超过 LIMIT），水位取最后一条的 seq，节点下轮继续
        if (!logs.isEmpty() && logs.size() >= INCREMENTAL_LIMIT) {
            newWatermark = logs.get(logs.size() - 1).getSeq();
        }

        data.put("fullSync", false);
        data.put("seq", newWatermark);
        data.put("changes", changes);
        return data;
    }

    // ---------- payload 组装 ----------

    private Map<String, Object> toChangeEvent(ConfigChangeLog log) {
        Map<String, Object> event = new HashMap<>();
        event.put("entityType", log.getEntityType());
        event.put("entityId", log.getEntityId());
        event.put("operation", log.getOperation());
        event.put("payload", ConfigChangeLog.OP_DELETE.equals(log.getOperation())
                ? null : buildPayload(log.getEntityType(), log.getEntityId()));
        return event;
    }

    private Object buildPayload(String entityType, String entityId) {
        return switch (entityType) {
            case ConfigChangeLog.TYPE_AGENT, ConfigChangeLog.TYPE_UPSTREAM_CRED -> buildAgentPayload(entityId);
            case ConfigChangeLog.TYPE_CALLER_CRED -> buildCallerCredPayload(entityId);
            case ConfigChangeLog.TYPE_ACL -> buildAclPayload(entityId);
            case ConfigChangeLog.TYPE_CALLER -> null; // 节点仅清缓存（由配套 CALLER_CRED 事件收敛状态）
            default -> null;
        };
    }

    /** AGENT payload：{id, cardJson, etag, endpointUrl, upstreamAuthType, upstreamAuthConfig(解密), capabilities} */
    private Map<String, Object> buildAgentPayload(String agentId) {
        AgentCard card = agentMapper.selectById(agentId);
        if (card == null) {
            return null;
        }
        String cardJson = publishService.buildPublicCardJson(card);
        String etag = PublishService.sha256Hex(cardJson).substring(0, 16);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", agentId);
        payload.put("cardJson", cardJson);
        payload.put("etag", etag);
        payload.put("endpointUrl", card.getEndpointUrl());
        payload.put("capabilities", card.getCapabilities());

        UpstreamCredential cred = credentialMapper.selectOne(
                new LambdaQueryWrapper<UpstreamCredential>().eq(UpstreamCredential::getAgentId, agentId));
        if (cred != null && !UpstreamCredential.TYPE_NONE.equals(cred.getAuthType())) {
            payload.put("upstreamAuthType", cred.getAuthType());
            payload.put("upstreamAuthConfig", cryptoService.decrypt(cred.getConfigEnc()));
        } else {
            payload.put("upstreamAuthType", "NONE");
            payload.put("upstreamAuthConfig", "{}");
        }
        return payload;
    }

    /** CALLER_CRED payload（entityId = key_hash）：{callerId, callerName, callerStatus, keyStatus, expiresAt} */
    private Map<String, Object> buildCallerCredPayload(String keyHash) {
        CallerCredential cred = callerCredentialMapper.selectOne(
                new LambdaQueryWrapper<CallerCredential>().eq(CallerCredential::getApiKeyHash, keyHash));
        if (cred == null) {
            return null;
        }
        Caller caller = callerMapper.selectById(cred.getCallerId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("callerId", cred.getCallerId());
        payload.put("callerName", caller != null ? caller.getName() : "");
        payload.put("callerStatus", caller != null ? caller.getStatus() : 0);
        payload.put("keyStatus", cred.getStatus());
        payload.put("expiresAt", cred.getExpiresAt() == null ? "" : cred.getExpiresAt().toString().replace('T', ' '));
        return payload;
    }

    /** ACL payload（entityId = caller_id）：{agentIds[]} */
    private Map<String, Object> buildAclPayload(String callerId) {
        List<String> agentIds = aclMapper.selectList(new LambdaQueryWrapper<CallerAgentAcl>()
                        .eq(CallerAgentAcl::getCallerId, callerId))
                .stream().map(CallerAgentAcl::getAgentId).toList();
        return Map.of("agentIds", agentIds);
    }

    // ---------- 全量快照 ----------

    private Map<String, Object> buildSnapshot() {
        // 已发布且启用的 Agent
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentCard card : agentMapper.selectList(new LambdaQueryWrapper<AgentCard>()
                .eq(AgentCard::getStatus, 1)
                .isNotNull(AgentCard::getPublishedSeq))) {
            agents.add(buildAgentPayload(card.getId()));
        }

        // 全部有效调用方 Key
        List<Map<String, Object>> callerCreds = new ArrayList<>();
        for (CallerCredential cred : callerCredentialMapper.selectList(null)) {
            Map<String, Object> p = buildCallerCredPayload(cred.getApiKeyHash());
            if (p != null) {
                p.put("keyHash", cred.getApiKeyHash());
                callerCreds.add(p);
            }
        }

        // 全部 ACL
        List<Map<String, Object>> acls = new ArrayList<>();
        for (Caller caller : callerMapper.selectList(null)) {
            acls.add(Map.of("callerId", caller.getId(), "agentIds",
                    ((Map<String, Object>) buildAclPayload(caller.getId())).get("agentIds")));
        }

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("agents", agents);
        snapshot.put("callerCreds", callerCreds);
        snapshot.put("acls", acls);
        return snapshot;
    }

    // ---------- 心跳 ----------

    public void recordHeartbeat(String nodeId, Long seq, Boolean redisOk) {
        Map<String, Object> hb = new HashMap<>();
        hb.put("nodeId", nodeId);
        hb.put("seq", seq);
        hb.put("redisOk", redisOk);
        hb.put("ts", LocalDateTime.now().toString());
        heartbeats.put(nodeId, hb);
    }

    public Collection<Map<String, Object>> listHeartbeats() {
        return heartbeats.values();
    }
}
