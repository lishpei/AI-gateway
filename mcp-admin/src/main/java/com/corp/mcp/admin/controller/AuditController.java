package com.corp.mcp.admin.controller;

import com.corp.mcp.admin.common.Result;
import com.corp.mcp.admin.domain.dto.AuditLogBatchDTO;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.AuditLog;
import com.corp.mcp.admin.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计日志（设计文档 6.3 审计域）。
 * POST /logs/batch 为内部接口（网关调用，X-Internal-Key 校验）。
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** 网关批量上报（内部接口） */
    @PostMapping("/logs/batch")
    public Result<Map<String, Integer>> batchReceive(@RequestBody AuditLogBatchDTO batch) {
        int saved = auditService.batchSave(batch.getLogs());
        return Result.ok(Map.of("received", saved));
    }

    @GetMapping("/logs")
    public Result<PageResult<AuditLog>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String serverId,
            @RequestParam(required = false) String policyDecision) {
        return Result.ok(auditService.pageLogs(page, size, startTime, endTime,
                userId, agentId, toolName, serverId, policyDecision));
    }

    @GetMapping("/statistics")
    public Result<Map<String, Object>> statistics(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.ok(auditService.statistics(startTime, endTime));
    }
}
