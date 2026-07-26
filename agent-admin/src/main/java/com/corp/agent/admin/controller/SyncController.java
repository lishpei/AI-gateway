package com.corp.agent.admin.controller;

import com.corp.agent.admin.common.Result;
import com.corp.agent.admin.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * 节点同步 API（内部，X-Node-Token 校验，设计文档 8.2/8.5 节）。
 */
@RestController
@RequestMapping("/internal/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    /** 增量/全量配置拉取 */
    @GetMapping("/config")
    public Result<Map<String, Object>> config(@RequestParam(defaultValue = "0") long since) {
        return Result.ok(syncService.syncConfig(since));
    }

    /** 节点心跳上报 */
    @PostMapping("/heartbeat")
    public Result<Void> heartbeat(@RequestBody Map<String, Object> body) {
        String nodeId = String.valueOf(body.getOrDefault("nodeId", "unknown"));
        Long seq = body.get("seq") == null ? 0L : ((Number) body.get("seq")).longValue();
        Boolean redisOk = body.get("redisOk") == null || Boolean.parseBoolean(String.valueOf(body.get("redisOk")));
        syncService.recordHeartbeat(nodeId, seq, redisOk);
        return Result.ok();
    }

    /** 节点心跳列表（看板，管理面 token 也可访问——放开给 admin 拦截器外的只读用途需另行放行） */
    @GetMapping("/nodes")
    public Result<Collection<Map<String, Object>>> nodes() {
        return Result.ok(syncService.listHeartbeats());
    }
}
