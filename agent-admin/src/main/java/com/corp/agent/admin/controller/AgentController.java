package com.corp.agent.admin.controller;

import com.corp.agent.admin.common.Result;
import com.corp.agent.admin.domain.dto.AgentCardSaveDTO;
import com.corp.agent.admin.domain.dto.PageResult;
import com.corp.agent.admin.domain.dto.UpstreamCredentialSaveDTO;
import com.corp.agent.admin.domain.entity.AgentCard;
import com.corp.agent.admin.service.AgentService;
import com.corp.agent.admin.service.PublishService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent Card 管理（设计文档 5.4.1 节）。
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final PublishService publishService;

    @Value("${agent.gateway-base-url}")
    private String gatewayBaseUrl;

    @PostMapping
    public Result<AgentCard> create(@Valid @RequestBody AgentCardSaveDTO dto) {
        return Result.ok(agentService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<AgentCard> update(@PathVariable String id,
                                    @Valid @RequestBody AgentCardSaveDTO dto) {
        return Result.ok(agentService.update(id, dto));
    }

    @GetMapping
    public Result<PageResult<AgentCard>> page(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status) {
        return Result.ok(agentService.page(page, size, keyword, status));
    }

    @GetMapping("/{id}")
    public Result<AgentCard> detail(@PathVariable String id) {
        return Result.ok(agentService.requireCard(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        agentService.delete(id);
        return Result.ok();
    }

    /** Card 预览：返回对外暴露的完整 Card JSON（占位符已替换） */
    @GetMapping(value = "/{id}/card-preview", produces = "application/json")
    public String cardPreview(@PathVariable String id) {
        return publishService.cardPreview(id, gatewayBaseUrl);
    }

    @PostMapping("/{id}/publish")
    public Result<Map<String, Object>> publish(@PathVariable String id) {
        long seq = publishService.publish(id);
        return Result.ok(Map.of("agentId", id, "publishedSeq", seq));
    }

    @PostMapping("/{id}/unpublish")
    public Result<Void> unpublish(@PathVariable String id) {
        publishService.unpublish(id);
        return Result.ok();
    }

    // ---------- 上游凭证 ----------

    @GetMapping("/{id}/upstream-credential")
    public Result<Map<String, Object>> getCredential(@PathVariable String id) {
        return Result.ok(agentService.getUpstreamCredentialInfo(id));
    }

    @PutMapping("/{id}/upstream-credential")
    public Result<Void> saveCredential(@PathVariable String id,
                                       @Valid @RequestBody UpstreamCredentialSaveDTO dto,
                                       @RequestParam(defaultValue = "true") boolean merge) {
        agentService.saveUpstreamCredential(id, dto, merge);
        return Result.ok();
    }

    @DeleteMapping("/{id}/upstream-credential")
    public Result<Void> deleteCredential(@PathVariable String id) {
        agentService.deleteUpstreamCredential(id);
        return Result.ok();
    }
}
