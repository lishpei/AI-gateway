package com.corp.agent.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.corp.agent.admin.common.BizException;
import com.corp.agent.admin.domain.dto.AgentCardSaveDTO;
import com.corp.agent.admin.domain.entity.AgentCard;
import com.corp.agent.admin.domain.entity.ConfigChangeLog;
import com.corp.agent.admin.domain.entity.UpstreamCredential;
import com.corp.agent.admin.domain.dto.PageResult;
import com.corp.agent.admin.domain.dto.UpstreamCredentialSaveDTO;
import com.corp.agent.admin.mapper.AgentCardMapper;
import com.corp.agent.admin.mapper.ConfigChangeLogMapper;
import com.corp.agent.admin.mapper.UpstreamCredentialMapper;
import com.corp.agent.admin.util.Jsons;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Agent Card 管理：CRUD + 字段校验（设计文档 5.5 节）+ 上游凭证设置。
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final List<String> CAPABILITY_KEYS =
            List.of("streaming", "pushNotifications", "extendedAgentCard", "extensions");

    private final AgentCardMapper agentMapper;
    private final UpstreamCredentialMapper credentialMapper;
    private final ConfigChangeLogMapper changeLogMapper;
    private final CryptoService cryptoService;

    // ---------- CRUD ----------

    @Transactional
    public AgentCard create(AgentCardSaveDTO dto) {
        if (agentMapper.selectById(dto.getId()) != null) {
            throw BizException.conflict("agent id 已存在: " + dto.getId());
        }
        validate(dto);
        AgentCard card = new AgentCard();
        applyDto(card, dto);
        agentMapper.insert(card);
        return card;
    }

    @Transactional
    public AgentCard update(String id, AgentCardSaveDTO dto) {
        AgentCard card = requireCard(id);
        if (!id.equals(dto.getId())) {
            throw BizException.badRequest("agent id 不可修改");
        }
        validate(dto);
        applyDto(card, dto);
        agentMapper.updateById(card);
        return card;
    }

    public AgentCard requireCard(String id) {
        AgentCard card = agentMapper.selectById(id);
        if (card == null) {
            throw BizException.notFound("agent " + id);
        }
        return card;
    }

    public PageResult<AgentCard> page(int page, int size, String keyword, Integer status) {
        Page<AgentCard> p = agentMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<AgentCard>()
                        .like(keyword != null && !keyword.isBlank(), AgentCard::getName, keyword)
                        .or()
                        .like(keyword != null && !keyword.isBlank(), AgentCard::getId, keyword)
                        .eq(status != null, AgentCard::getStatus, status)
                        .orderByDesc(AgentCard::getUpdatedAt));
        return PageResult.of(p);
    }

    @Transactional
    public void delete(String id) {
        AgentCard card = requireCard(id);
        agentMapper.deleteById(id);
        credentialMapper.delete(new LambdaQueryWrapper<UpstreamCredential>()
                .eq(UpstreamCredential::getAgentId, id));
        // 级联删除 ACL 授权（调用方侧由各自 ACL 全量替换语义覆盖，此处仅记 Agent 删除事件）
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_AGENT, id, ConfigChangeLog.OP_DELETE));
    }

    // ---------- 上游凭证 ----------

    @Transactional
    public void saveUpstreamCredential(String agentId, UpstreamCredentialSaveDTO dto, boolean mergeWithExisting) {
        requireCard(agentId);
        validateCredentialConfig(dto.getAuthType(), dto.getConfig());

        Map<String, Object> configToSave = dto.getConfig();
        // 秘密字段留空 = 保持原值（merge 语义）
        if (mergeWithExisting) {
            UpstreamCredential existing = credentialMapper.selectOne(
                    new LambdaQueryWrapper<UpstreamCredential>().eq(UpstreamCredential::getAgentId, agentId));
            if (existing != null && existing.getAuthType().equals(dto.getAuthType())) {
                Map<String, Object> existingConfig =
                        Jsons.toMap(cryptoService.decrypt(existing.getConfigEnc()));
                configToSave = mergeSecrets(existingConfig, dto.getConfig());
            }
        }

        UpstreamCredential cred = credentialMapper.selectOne(
                new LambdaQueryWrapper<UpstreamCredential>().eq(UpstreamCredential::getAgentId, agentId));
        if (cred == null) {
            cred = new UpstreamCredential();
            cred.setAgentId(agentId);
            cred.setAuthType(dto.getAuthType());
            cred.setConfigEnc(cryptoService.encrypt(Jsons.toJson(configToSave)));
            credentialMapper.insert(cred);
        } else {
            cred.setAuthType(dto.getAuthType());
            cred.setConfigEnc(cryptoService.encrypt(Jsons.toJson(configToSave)));
            credentialMapper.updateById(cred);
        }
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_UPSTREAM_CRED, agentId, ConfigChangeLog.OP_UPSERT));
    }

    @Transactional
    public void deleteUpstreamCredential(String agentId) {
        requireCard(agentId);
        credentialMapper.delete(new LambdaQueryWrapper<UpstreamCredential>()
                .eq(UpstreamCredential::getAgentId, agentId));
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_UPSTREAM_CRED, agentId, ConfigChangeLog.OP_DELETE));
    }

    /** 查询上游凭证类型（不回显秘密） */
    public Map<String, Object> getUpstreamCredentialInfo(String agentId) {
        UpstreamCredential cred = credentialMapper.selectOne(
                new LambdaQueryWrapper<UpstreamCredential>().eq(UpstreamCredential::getAgentId, agentId));
        if (cred == null) {
            return Map.of("authType", "NONE");
        }
        return Map.of("authType", cred.getAuthType(), "updatedAt", cred.getUpdatedAt().toString());
    }

    // ---------- 校验（设计文档 5.5 节） ----------

    private void validate(AgentCardSaveDTO dto) {
        // capabilities 仅允许约定键
        for (String key : dto.getCapabilities().keySet()) {
            if (!CAPABILITY_KEYS.contains(key)) {
                throw BizException.badRequest("capabilities 不支持键: " + key);
            }
        }
        // skills 必填字段 + id 唯一
        var seen = new java.util.HashSet<String>();
        for (Map<String, Object> skill : dto.getSkills()) {
            for (String field : List.of("id", "name", "description", "tags")) {
                if (skill.get(field) == null) {
                    throw BizException.badRequest("skill 缺少必填字段: " + field);
                }
            }
            if (!seen.add(String.valueOf(skill.get("id")))) {
                throw BizException.badRequest("skill id 重复: " + skill.get("id"));
            }
        }
        // 媒体类型格式
        validateMediaTypes(dto.getDefaultInputModes(), "defaultInputModes");
        validateMediaTypes(dto.getDefaultOutputModes(), "defaultOutputModes");
        // securitySchemes 判别联合：每个方案恰好含五种之一
        if (dto.getSecuritySchemes() != null) {
            List<String> types = List.of("apiKeySecurityScheme", "httpAuthSecurityScheme",
                    "oauth2SecurityScheme", "openIdConnectSecurityScheme", "mtlsSecurityScheme");
            for (Map.Entry<String, Object> e : dto.getSecuritySchemes().entrySet()) {
                if (!(e.getValue() instanceof Map)) {
                    throw BizException.badRequest("securitySchemes." + e.getKey() + " 必须是对象");
                }
                long count = types.stream().filter(t -> ((Map<?, ?>) e.getValue()).containsKey(t)).count();
                if (count != 1) {
                    throw BizException.badRequest(
                            "securitySchemes." + e.getKey() + " 必须恰好含一种方案类型（五种判别联合）");
                }
            }
        }
    }

    private void validateMediaTypes(List<String> modes, String field) {
        for (String m : modes) {
            if (m == null || !m.matches("^[\\w.+-]+/[\\w.+-]+$")) {
                throw BizException.badRequest(field + " 含非法媒体类型: " + m);
            }
        }
    }

    private void validateCredentialConfig(String authType, Map<String, Object> config) {
        if ("NONE".equals(authType) || "MTLS".equals(authType)) {
            return;
        }
        List<String> required = switch (authType) {
            case "API_KEY" -> List.of("location", "name", "value");
            case "HTTP_BEARER" -> List.of("token");
            case "HTTP_BASIC" -> List.of("username", "password");
            case "OAUTH2_CLIENT_CREDENTIALS" -> List.of("tokenUrl", "clientId", "clientSecret");
            default -> List.of();
        };
        for (String field : required) {
            // 秘密字段允许留空（merge 时保持原值），仅要求键存在性或已有值
            if (!config.containsKey(field) && !isSecretField(field)) {
                throw BizException.badRequest("config 缺少字段: " + field);
            }
        }
        if ("API_KEY".equals(authType)) {
            Object location = config.get("location");
            if (location != null && !List.of("header", "query").contains(String.valueOf(location))) {
                throw BizException.badRequest("API_KEY location 仅支持 header/query");
            }
        }
    }

    private boolean isSecretField(String field) {
        return List.of("value", "token", "password", "clientSecret").contains(field);
    }

    /** 秘密字段留空则沿用旧值 */
    private Map<String, Object> mergeSecrets(Map<String, Object> oldConfig, Map<String, Object> newConfig) {
        Map<String, Object> merged = new java.util.HashMap<>(newConfig);
        for (String field : List.of("value", "token", "password", "clientSecret")) {
            Object v = newConfig.get(field);
            if ((v == null || String.valueOf(v).isBlank()) && oldConfig.containsKey(field)) {
                merged.put(field, oldConfig.get(field));
            }
        }
        return merged;
    }

    private void applyDto(AgentCard card, AgentCardSaveDTO dto) {
        card.setId(dto.getId());
        card.setName(dto.getName());
        card.setDescription(dto.getDescription());
        card.setProviderOrganization(dto.getProviderOrganization());
        card.setProviderUrl(dto.getProviderUrl());
        card.setVersion(dto.getVersion());
        card.setDocumentationUrl(dto.getDocumentationUrl());
        card.setIconUrl(dto.getIconUrl());
        card.setProtocolVersion(dto.getProtocolVersion());
        card.setEndpointUrl(dto.getEndpointUrl());
        card.setCapabilities(Jsons.toJson(dto.getCapabilities()));
        card.setSecuritySchemes(dto.getSecuritySchemes() == null ? null : Jsons.toJson(dto.getSecuritySchemes()));
        card.setSecurityRequirements(dto.getSecurityRequirements() == null ? null : Jsons.toJson(dto.getSecurityRequirements()));
        card.setDefaultInputModes(Jsons.toJson(dto.getDefaultInputModes()));
        card.setDefaultOutputModes(Jsons.toJson(dto.getDefaultOutputModes()));
        card.setSkills(Jsons.toJson(dto.getSkills()));
        card.setSignatures(dto.getSignatures() == null ? null : Jsons.toJson(dto.getSignatures()));
        card.setStatus(dto.getStatus());
    }
}
