package com.corp.agent.admin.controller;

import com.corp.agent.admin.common.Result;
import com.corp.agent.admin.domain.dto.CallerSaveDTO;
import com.corp.agent.admin.domain.dto.PageResult;
import com.corp.agent.admin.domain.entity.Caller;
import com.corp.agent.admin.domain.entity.CallerCredential;
import com.corp.agent.admin.service.CallerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调用方管理（设计文档 5.4.2 节）。
 */
@RestController
@RequestMapping("/api/v1/callers")
@RequiredArgsConstructor
public class CallerController {

    private final CallerService callerService;

    @PostMapping
    public Result<Caller> create(@Valid @RequestBody CallerSaveDTO dto) {
        return Result.ok(callerService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<Caller> update(@PathVariable String id, @Valid @RequestBody CallerSaveDTO dto) {
        return Result.ok(callerService.update(id, dto));
    }

    @GetMapping
    public Result<PageResult<Caller>> page(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return Result.ok(callerService.page(page, size));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        callerService.delete(id);
        return Result.ok();
    }

    // ---------- API Key ----------

    /** 生成 API Key（明文仅本响应返回一次） */
    @PostMapping("/{id}/credentials")
    public Result<Map<String, Object>> generateKey(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String keyName = body == null ? null : (String) body.get("keyName");
        LocalDateTime expiresAt = null;
        if (body != null && body.get("expiresAt") != null) {
            expiresAt = LocalDateTime.parse(String.valueOf(body.get("expiresAt")).replace(' ', 'T'));
        }
        return Result.ok(callerService.generateKey(id, keyName, expiresAt));
    }

    @GetMapping("/{id}/credentials")
    public Result<List<CallerCredential>> listKeys(@PathVariable String id) {
        return Result.ok(callerService.listKeys(id));
    }

    @DeleteMapping("/{id}/credentials/{credId}")
    public Result<Void> revokeKey(@PathVariable String id, @PathVariable Long credId) {
        callerService.revokeKey(id, credId);
        return Result.ok();
    }

    // ---------- ACL ----------

    @PutMapping("/{id}/acl")
    public Result<Void> replaceAcl(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        callerService.replaceAcl(id, body.getOrDefault("agentIds", List.of()));
        return Result.ok();
    }

    @GetMapping("/{id}/acl")
    public Result<List<String>> getAcl(@PathVariable String id) {
        return Result.ok(callerService.getAcl(id));
    }
}
