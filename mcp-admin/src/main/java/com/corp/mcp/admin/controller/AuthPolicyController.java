package com.corp.mcp.admin.controller;

import com.corp.mcp.admin.common.Result;
import com.corp.mcp.admin.domain.dto.AuthCheckDTO;
import com.corp.mcp.admin.domain.dto.AuthPolicySaveDTO;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.AuthPolicy;
import com.corp.mcp.admin.service.AuthPolicyService;
import com.corp.mcp.admin.service.PolicyEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 授权管理（设计文档 6.3 授权域）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthPolicyController {

    private final AuthPolicyService policyService;
    private final PolicyEvaluationService evaluationService;

    @GetMapping("/policies")
    public Result<PageResult<AuthPolicy>> page(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size,
                                               @RequestParam(required = false) String serverId,
                                               @RequestParam(required = false) String granteeType,
                                               @RequestParam(required = false) Integer status) {
        return Result.ok(policyService.pagePolicies(page, size, serverId, granteeType, status));
    }

    @GetMapping("/policies/{id}")
    public Result<AuthPolicy> detail(@PathVariable Long id) {
        return Result.ok(policyService.requirePolicy(id));
    }

    @PostMapping("/policies")
    public Result<AuthPolicy> create(@Valid @RequestBody AuthPolicySaveDTO dto,
                                     @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Result.ok(policyService.create(dto, operator));
    }

    @PostMapping("/policies/batch")
    public Result<List<AuthPolicy>> createBatch(@Valid @RequestBody List<AuthPolicySaveDTO> dtos,
                                                @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Result.ok(policyService.createBatch(dtos, operator));
    }

    @PutMapping("/policies/{id}")
    public Result<AuthPolicy> update(@PathVariable Long id,
                                     @Valid @RequestBody AuthPolicySaveDTO dto,
                                     @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Result.ok(policyService.update(id, dto, operator));
    }

    @PostMapping("/policies/{id}/approve")
    public Result<AuthPolicy> approve(@PathVariable Long id,
                                      @RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return Result.ok(policyService.approve(id, approved, comment, operator));
    }

    @DeleteMapping("/policies/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        policyService.revoke(id);
        return Result.ok();
    }

    @DeleteMapping("/policies/batch")
    public Result<Void> revokeBatch(@RequestBody Map<String, List<Long>> body) {
        policyService.revokeBatch(body.get("policyIds"));
        return Result.ok();
    }

    /** 权限检查：网关兜底 + 前端预检（设计文档 8.1 求值语义） */
    @PostMapping("/check")
    public Result<AuthCheckDTO.Response> check(@Valid @RequestBody AuthCheckDTO.Request request) {
        return Result.ok(evaluationService.evaluate(request));
    }
}
