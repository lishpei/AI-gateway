package com.corp.mcp.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.corp.mcp.admin.domain.dto.AuditLogBatchDTO;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.AuditLog;
import com.corp.mcp.admin.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志：批量接收（网关上报）、多维查询、基础统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /** 批量入库（网关批量上报） */
    public int batchSave(List<AuditLogBatchDTO.Item> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        List<AuditLog> logs = items.stream().map(this::toEntity).toList();
        int count = 0;
        for (AuditLog log : logs) {
            count += auditLogMapper.insert(log);
        }
        return count;
    }

    public PageResult<AuditLog> pageLogs(int page, int size, LocalDateTime startTime, LocalDateTime endTime,
                                         String userId, String agentId, String toolName, String serverId,
                                         String policyDecision) {
        Page<AuditLog> p = auditLogMapper.selectPage(new Page<>(page, size),
                buildQuery(startTime, endTime, userId, agentId, toolName, serverId, policyDecision)
                        .orderByDesc(AuditLog::getTimestamp));
        return PageResult.of(p);
    }

    /** 基础统计：总调用量、平均延迟、拒绝率、Top 工具/Agent（按时间窗） */
    public Map<String, Object> statistics(LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<AuditLog> base = buildQuery(startTime, endTime, null, null, null, null, null);
        Long total = auditLogMapper.selectCount(base);

        LambdaQueryWrapper<AuditLog> denyQuery = buildQuery(startTime, endTime, null, null, null, null, null)
                .eq(AuditLog::getPolicyDecision, "deny");
        Long denyCount = auditLogMapper.selectCount(denyQuery);

        List<AuditLog> sample = auditLogMapper.selectList(base
                .select(AuditLog::getLatencyMs, AuditLog::getToolName, AuditLog::getCallerAgentId)
                .last("LIMIT 10000"));
        double avgLatency = sample.stream()
                .filter(l -> l.getLatencyMs() != null)
                .mapToInt(AuditLog::getLatencyMs)
                .average().orElse(0);

        Map<String, Long> topTools = new HashMap<>();
        Map<String, Long> topAgents = new HashMap<>();
        for (AuditLog l : sample) {
            if (l.getToolName() != null) {
                topTools.merge(l.getToolName(), 1L, Long::sum);
            }
            if (l.getCallerAgentId() != null) {
                topAgents.merge(l.getCallerAgentId(), 1L, Long::sum);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalCalls", total);
        result.put("avgLatency", Math.round(avgLatency * 10) / 10.0);
        result.put("denyRate", total == 0 ? 0 : Math.round(denyCount * 10000.0 / total) / 100.0);
        result.put("topTools", topTools.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10).toList());
        result.put("topAgents", topAgents.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10).toList());
        return result;
    }

    private LambdaQueryWrapper<AuditLog> buildQuery(LocalDateTime startTime, LocalDateTime endTime,
                                                    String userId, String agentId, String toolName,
                                                    String serverId, String policyDecision) {
        return new LambdaQueryWrapper<AuditLog>()
                .ge(startTime != null, AuditLog::getTimestamp, startTime)
                .le(endTime != null, AuditLog::getTimestamp, endTime)
                .eq(userId != null && !userId.isBlank(), AuditLog::getDelegatorUserId, userId)
                .eq(agentId != null && !agentId.isBlank(), AuditLog::getCallerAgentId, agentId)
                .eq(toolName != null && !toolName.isBlank(), AuditLog::getToolName, toolName)
                .eq(serverId != null && !serverId.isBlank(), AuditLog::getServerId, serverId)
                .eq(policyDecision != null && !policyDecision.isBlank(), AuditLog::getPolicyDecision, policyDecision);
    }

    private AuditLog toEntity(AuditLogBatchDTO.Item item) {
        AuditLog log = new AuditLog();
        log.setRequestId(item.getRequestId());
        log.setTraceId(item.getTraceId());
        log.setTimestamp(item.getTimestamp() == null ? LocalDateTime.now() : item.getTimestamp());
        log.setCallerAgentId(item.getCallerAgentId());
        log.setDelegationChain(item.getDelegationChain());
        log.setDelegatorUserId(item.getDelegatorUserId());
        log.setDelegatorOrgId(item.getDelegatorOrgId());
        log.setJsonrpcMethod(item.getJsonrpcMethod());
        log.setToolName(item.getToolName());
        log.setServerId(item.getServerId());
        log.setRequestArgsHash(item.getRequestArgsHash());
        log.setAuthResult(item.getAuthResult());
        log.setPolicyDecision(item.getPolicyDecision());
        log.setDenyReason(item.getDenyReason());
        log.setTokenExchanged(item.getTokenExchanged());
        log.setLatencyMs(item.getLatencyMs());
        log.setUpstreamLatencyMs(item.getUpstreamLatencyMs());
        log.setResponseStatus(item.getResponseStatus());
        log.setResponseSize(item.getResponseSize());
        log.setClientIp(item.getClientIp());
        return log;
    }
}
