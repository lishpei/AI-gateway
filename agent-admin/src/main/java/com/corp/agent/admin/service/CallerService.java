package com.corp.agent.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.corp.agent.admin.common.BizException;
import com.corp.agent.admin.domain.dto.CallerSaveDTO;
import com.corp.agent.admin.domain.dto.PageResult;
import com.corp.agent.admin.domain.entity.Caller;
import com.corp.agent.admin.domain.entity.CallerAgentAcl;
import com.corp.agent.admin.domain.entity.CallerCredential;
import com.corp.agent.admin.domain.entity.ConfigChangeLog;
import com.corp.agent.admin.mapper.CallerAgentAclMapper;
import com.corp.agent.admin.mapper.CallerCredentialMapper;
import com.corp.agent.admin.mapper.CallerMapper;
import com.corp.agent.admin.mapper.ConfigChangeLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调用方管理：CRUD、API Key 生成/吊销（明文仅返回一次）、ACL 全量替换。
 * 所有变更写 config_change_log 供节点增量同步。
 */
@Service
@RequiredArgsConstructor
public class CallerService {

    private final CallerMapper callerMapper;
    private final CallerCredentialMapper credentialMapper;
    private final CallerAgentAclMapper aclMapper;
    private final ConfigChangeLogMapper changeLogMapper;
    private final SecureRandom random = new SecureRandom();

    // ---------- Caller CRUD ----------

    @Transactional
    public Caller create(CallerSaveDTO dto) {
        if (callerMapper.selectById(dto.getId()) != null) {
            throw BizException.conflict("caller id 已存在: " + dto.getId());
        }
        Caller caller = new Caller();
        applyDto(caller, dto);
        callerMapper.insert(caller);
        return caller;
    }

    @Transactional
    public Caller update(String id, CallerSaveDTO dto) {
        Caller caller = requireCaller(id);
        applyDto(caller, dto);
        callerMapper.updateById(caller);
        // 状态变化影响其所有 Key：补发每个有效 CALLER_CRED 变更使节点收敛
        List<CallerCredential> creds = credentialMapper.selectList(
                new LambdaQueryWrapper<CallerCredential>().eq(CallerCredential::getCallerId, id));
        for (CallerCredential c : creds) {
            changeLogMapper.insert(new ConfigChangeLog(
                    ConfigChangeLog.TYPE_CALLER_CRED, c.getApiKeyHash(), ConfigChangeLog.OP_UPSERT));
        }
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_CALLER, id, ConfigChangeLog.OP_UPSERT));
        return caller;
    }

    public Caller requireCaller(String id) {
        Caller caller = callerMapper.selectById(id);
        if (caller == null) {
            throw BizException.notFound("caller " + id);
        }
        return caller;
    }

    public PageResult<Caller> page(int page, int size) {
        Page<Caller> p = callerMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Caller>().orderByDesc(Caller::getUpdatedAt));
        return PageResult.of(p);
    }

    @Transactional
    public void delete(String id) {
        requireCaller(id);
        // 级联删除 Key 与 ACL，并记变更
        List<CallerCredential> creds = credentialMapper.selectList(
                new LambdaQueryWrapper<CallerCredential>().eq(CallerCredential::getCallerId, id));
        for (CallerCredential c : creds) {
            changeLogMapper.insert(new ConfigChangeLog(
                    ConfigChangeLog.TYPE_CALLER_CRED, c.getApiKeyHash(), ConfigChangeLog.OP_DELETE));
        }
        credentialMapper.delete(new LambdaQueryWrapper<CallerCredential>()
                .eq(CallerCredential::getCallerId, id));
        aclMapper.delete(new LambdaQueryWrapper<CallerAgentAcl>()
                .eq(CallerAgentAcl::getCallerId, id));
        callerMapper.deleteById(id);
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_ACL, id, ConfigChangeLog.OP_DELETE));
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_CALLER, id, ConfigChangeLog.OP_DELETE));
    }

    // ---------- API Key ----------

    /**
     * 生成 API Key：gwk_ + 40位随机十六进制；明文仅本响应返回一次，库中只存 SHA-256。
     */
    @Transactional
    public Map<String, Object> generateKey(String callerId, String keyName, LocalDateTime expiresAt) {
        requireCaller(callerId);
        String apiKey = "gwk_" + randomHex(40);
        CallerCredential cred = new CallerCredential();
        cred.setCallerId(callerId);
        cred.setKeyName(keyName == null || keyName.isBlank() ? "default" : keyName);
        cred.setApiKeyPrefix(apiKey.substring(0, 12));
        cred.setApiKeyHash(PublishService.sha256Hex(apiKey));
        cred.setStatus(1);
        cred.setExpiresAt(expiresAt);
        credentialMapper.insert(cred);
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_CALLER_CRED, cred.getApiKeyHash(), ConfigChangeLog.OP_UPSERT));
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("apiKey", apiKey);
        result.put("prefix", cred.getApiKeyPrefix());
        result.put("credentialId", cred.getId());
        return result;
    }

    public List<CallerCredential> listKeys(String callerId) {
        requireCaller(callerId);
        return credentialMapper.selectList(new LambdaQueryWrapper<CallerCredential>()
                .eq(CallerCredential::getCallerId, callerId)
                .orderByDesc(CallerCredential::getCreatedAt));
    }

    @Transactional
    public void revokeKey(String callerId, Long credentialId) {
        CallerCredential cred = credentialMapper.selectById(credentialId);
        if (cred == null || !cred.getCallerId().equals(callerId)) {
            throw BizException.notFound("credential " + credentialId);
        }
        cred.setStatus(0);
        credentialMapper.updateById(cred);
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_CALLER_CRED, cred.getApiKeyHash(), ConfigChangeLog.OP_UPSERT));
    }

    // ---------- ACL ----------

    @Transactional
    public void replaceAcl(String callerId, List<String> agentIds) {
        requireCaller(callerId);
        aclMapper.delete(new LambdaQueryWrapper<CallerAgentAcl>()
                .eq(CallerAgentAcl::getCallerId, callerId));
        for (String agentId : new java.util.LinkedHashSet<>(agentIds)) {
            CallerAgentAcl acl = new CallerAgentAcl();
            acl.setCallerId(callerId);
            acl.setAgentId(agentId);
            aclMapper.insert(acl);
        }
        changeLogMapper.insert(new ConfigChangeLog(
                ConfigChangeLog.TYPE_ACL, callerId, ConfigChangeLog.OP_UPSERT));
    }

    public List<String> getAcl(String callerId) {
        requireCaller(callerId);
        return aclMapper.selectList(new LambdaQueryWrapper<CallerAgentAcl>()
                        .eq(CallerAgentAcl::getCallerId, callerId))
                .stream().map(CallerAgentAcl::getAgentId).toList();
    }

    private void applyDto(Caller caller, CallerSaveDTO dto) {
        caller.setId(dto.getId());
        caller.setName(dto.getName());
        caller.setDescription(dto.getDescription());
        caller.setStatus(dto.getStatus());
    }

    /** 生成 len 个十六进制字符（len/2 字节） */
    private String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        byte[] buf = new byte[len / 2];
        random.nextBytes(buf);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
