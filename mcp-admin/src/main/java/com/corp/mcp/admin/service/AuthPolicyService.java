package com.corp.mcp.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.corp.mcp.admin.common.BizException;
import com.corp.mcp.admin.domain.dto.AuthPolicySaveDTO;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.AuthPolicy;
import com.corp.mcp.admin.domain.entity.McpServer;
import com.corp.mcp.admin.mapper.AuthPolicyMapper;
import com.corp.mcp.admin.util.Jsons;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 授权策略管理：CRUD + 审批 + 批量操作。每次变更后重建该 Server 的策略快照。
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyService {

    private final AuthPolicyMapper policyMapper;
    private final RegistryService registryService;
    private final RedisSyncService syncService;

    public PageResult<AuthPolicy> pagePolicies(int page, int size, String serverId,
                                               String granteeType, Integer status) {
        Page<AuthPolicy> p = policyMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<AuthPolicy>()
                        .eq(serverId != null && !serverId.isBlank(), AuthPolicy::getServerId, serverId)
                        .eq(granteeType != null && !granteeType.isBlank(), AuthPolicy::getGranteeType, granteeType)
                        .eq(status != null, AuthPolicy::getStatus, status)
                        .orderByDesc(AuthPolicy::getUpdatedAt));
        return PageResult.of(p);
    }

    public AuthPolicy requirePolicy(Long id) {
        AuthPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw BizException.notFound("policy " + id);
        }
        return policy;
    }

    @Transactional
    public AuthPolicy create(AuthPolicySaveDTO dto, String operator) {
        validate(dto);
        AuthPolicy policy = new AuthPolicy();
        applyDto(policy, dto);
        policy.setStatus(AuthPolicy.STATUS_PENDING);
        policy.setCreatedBy(operator);
        policyMapper.insert(policy);
        return policy;
    }

    @Transactional
    public List<AuthPolicy> createBatch(List<AuthPolicySaveDTO> dtos, String operator) {
        return dtos.stream().map(d -> create(d, operator)).toList();
    }

    @Transactional
    public AuthPolicy update(Long id, AuthPolicySaveDTO dto, String operator) {
        AuthPolicy policy = requirePolicy(id);
        validate(dto);
        applyDto(policy, dto);
        // 修改后需重新审批
        policy.setStatus(AuthPolicy.STATUS_PENDING);
        policy.setApprovedBy(null);
        policy.setApprovedAt(null);
        policyMapper.updateById(policy);
        republishIfServerActive(policy.getServerId());
        return policy;
    }

    /** 审批：approved=true → 生效；false → 撤销 */
    @Transactional
    public AuthPolicy approve(Long id, boolean approved, String comment, String operator) {
        AuthPolicy policy = requirePolicy(id);
        policy.setStatus(approved ? AuthPolicy.STATUS_EFFECTIVE : AuthPolicy.STATUS_REVOKED);
        policy.setApprovedBy(operator);
        policy.setApprovedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        republishIfServerActive(policy.getServerId());
        return policy;
    }

    /** 撤销策略 */
    @Transactional
    public void revoke(Long id) {
        AuthPolicy policy = requirePolicy(id);
        policy.setStatus(AuthPolicy.STATUS_REVOKED);
        policyMapper.updateById(policy);
        republishIfServerActive(policy.getServerId());
    }

    @Transactional
    public void revokeBatch(List<Long> ids) {
        ids.forEach(this::revoke);
    }

    private void republishIfServerActive(String serverId) {
        McpServer server = registryService.requireServer(serverId);
        if (server.getStatus() != null && server.getStatus() == McpServer.STATUS_ACTIVE) {
            syncService.publishServerSnapshot(serverId);
        }
    }

    private void validate(AuthPolicySaveDTO dto) {
        registryService.requireServer(dto.getServerId());
        Jsons.requireValidJson(dto.getConstraints(), "constraints");
        if (dto.getEffectiveTime() != null && dto.getExpiryTime() != null
                && !dto.getEffectiveTime().isBefore(dto.getExpiryTime())) {
            throw BizException.badRequest("effectiveTime 必须早于 expiryTime");
        }
    }

    private void applyDto(AuthPolicy policy, AuthPolicySaveDTO dto) {
        policy.setPolicyName(dto.getPolicyName() == null || dto.getPolicyName().isBlank()
                ? dto.getServerId() + ":" + dto.getToolName() + "→" + dto.getGranteeType() + ":" + dto.getGranteeId()
                : dto.getPolicyName());
        policy.setServerId(dto.getServerId());
        policy.setToolName(dto.getToolName() == null || dto.getToolName().isBlank() ? "*" : dto.getToolName());
        policy.setGranteeType(dto.getGranteeType());
        policy.setGranteeId(dto.getGranteeId());
        policy.setGranteeName(dto.getGranteeName());
        policy.setDataScope(dto.getDataScope());
        policy.setConstraints(dto.getConstraints());
        policy.setEffect(dto.getEffect());
        policy.setEffectiveTime(dto.getEffectiveTime());
        policy.setExpiryTime(dto.getExpiryTime());
    }
}
