package com.corp.agent.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.corp.agent.admin.common.BizException;
import com.corp.agent.admin.domain.entity.AgentCard;
import com.corp.agent.admin.domain.entity.ConfigChangeLog;
import com.corp.agent.admin.domain.entity.UpstreamCredential;
import com.corp.agent.admin.mapper.AgentCardMapper;
import com.corp.agent.admin.mapper.ConfigChangeLogMapper;
import com.corp.agent.admin.mapper.UpstreamCredentialMapper;
import com.corp.agent.admin.util.Jsons;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布服务（设计文档 5.6 节）：
 * 校验 → 拼装对外 Card（含 {{GW_BASE}} 占位符）→ 同事务写变更日志 → 更新 published_seq。
 * 节点经拉取模型在轮询间隔内生效（最终一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishService {

    /** Card 中的网关地址占位符（数据面服务时替换为 scheme://host） */
    public static final String GW_BASE_PLACEHOLDER = "{{GW_BASE}}";
    /** 对外 Card 中网关自身的 API Key 方案名 */
    public static final String GATEWAY_KEY_SCHEME = "gateway-key";

    private final AgentCardMapper agentMapper;
    private final UpstreamCredentialMapper credentialMapper;
    private final ConfigChangeLogMapper changeLogMapper;
    private final CryptoService cryptoService;

    @Transactional
    public long publish(String agentId) {
        AgentCard card = requireCard(agentId);
        if (card.getStatus() == null || card.getStatus() != 1) {
            throw BizException.badRequest("Agent 已禁用，不能发布");
        }

        // 1. 拼装对外 Card JSON + etag
        String cardJson = buildPublicCardJson(card);
        String etag = sha256Hex(cardJson).substring(0, 16);

        // 2. 变更日志：AGENT UPSERT
        ConfigChangeLog agentChange = new ConfigChangeLog(
                ConfigChangeLog.TYPE_AGENT, agentId, ConfigChangeLog.OP_UPSERT);
        changeLogMapper.insert(agentChange);

        // 3. 上游凭证存在 → 同步下发（payload 在同步时实时组装，此处仅记事件）
        UpstreamCredential cred = credentialMapper.selectOne(
                new LambdaQueryWrapper<UpstreamCredential>().eq(UpstreamCredential::getAgentId, agentId));
        if (cred != null) {
            changeLogMapper.insert(new ConfigChangeLog(
                    ConfigChangeLog.TYPE_UPSTREAM_CRED, agentId, ConfigChangeLog.OP_UPSERT));
        }

        // 4. 更新 published_seq
        card.setPublishedSeq(agentChange.getSeq());
        agentMapper.updateById(card);

        log.info("published agent card: {}, seq={}", agentId, agentChange.getSeq());
        return agentChange.getSeq();
    }

    @Transactional
    public void unpublish(String agentId) {
        AgentCard card = requireCard(agentId);
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_AGENT, agentId, ConfigChangeLog.OP_DELETE));
        card.setPublishedSeq(null);
        agentMapper.updateById(card);
    }

    /** 预览：返回对外 Card JSON（占位符已替换为配置的网关地址） */
    public String cardPreview(String agentId, String gatewayBaseUrl) {
        AgentCard card = requireCard(agentId);
        return buildPublicCardJson(card).replace(GW_BASE_PLACEHOLDER, gatewayBaseUrl);
    }

    /**
     * 拼装对外 Card（A2A v1.0.0）：supportedInterfaces 由网关生成指向自身代理入口；
     * 上游 endpoint_url 不出现；securitySchemes 固定注入网关 API Key 方案；
     * signatures 因 URL 重写失效而移除（一期不做重签名）。
     */
    public String buildPublicCardJson(AgentCard card) {
        Map<String, Object> json = new HashMap<>();
        json.put("name", card.getName());
        json.put("description", card.getDescription());

        Map<String, Object> iface = new HashMap<>();
        iface.put("url", GW_BASE_PLACEHOLDER + "/" + card.getId() + "/a2a");
        iface.put("protocolBinding", "JSONRPC");
        iface.put("protocolVersion", card.getProtocolVersion());
        json.put("supportedInterfaces", List.of(iface));

        if (card.getProviderOrganization() != null || card.getProviderUrl() != null) {
            Map<String, Object> provider = new HashMap<>();
            provider.put("organization", card.getProviderOrganization());
            provider.put("url", card.getProviderUrl());
            json.put("provider", provider);
        }
        json.put("version", card.getVersion());
        if (card.getDocumentationUrl() != null) {
            json.put("documentationUrl", card.getDocumentationUrl());
        }
        if (card.getIconUrl() != null) {
            json.put("iconUrl", card.getIconUrl());
        }
        json.put("capabilities", Jsons.toMap(card.getCapabilities()));

        // 对外安全声明：固定为网关自身的 API Key 方案（第一跳认证）
        Map<String, Object> apiKeyScheme = new HashMap<>();
        Map<String, Object> apiKeyDetail = new HashMap<>();
        apiKeyDetail.put("location", "header");
        apiKeyDetail.put("name", "X-API-Key");
        apiKeyScheme.put("apiKeySecurityScheme", apiKeyDetail);
        json.put("securitySchemes", Map.of(GATEWAY_KEY_SCHEME, apiKeyScheme));
        json.put("securityRequirements", List.of(
                Map.of("schemes", Map.of(GATEWAY_KEY_SCHEME, Map.of("list", List.of())))));

        json.put("defaultInputModes", Jsons.toList(card.getDefaultInputModes()));
        json.put("defaultOutputModes", Jsons.toList(card.getDefaultOutputModes()));
        json.put("skills", Jsons.toList(card.getSkills()));
        // signatures 移除：URL 被网关重写后原签名必然失效（响应头 X-Gateway-Card-Rewritten 标识）
        return Jsons.toJson(json);
    }

    public AgentCard requireCard(String agentId) {
        AgentCard card = agentMapper.selectById(agentId);
        if (card == null) {
            throw BizException.notFound("agent " + agentId);
        }
        return card;
    }

    /** JDK 原生 SHA-256 hex */
    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
