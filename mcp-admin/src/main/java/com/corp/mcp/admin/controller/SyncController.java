package com.corp.mcp.admin.controller;

import com.corp.mcp.admin.common.Result;
import com.corp.mcp.admin.service.RedisSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 同步状态（设计文档 6.3 同步域）。
 */
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final RedisSyncService syncService;
    private final RedisConnectionFactory redisConnectionFactory;

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> result = new HashMap<>();
        boolean redisOk;
        try {
            redisConnectionFactory.getConnection().ping();
            redisOk = true;
        } catch (Exception e) {
            redisOk = false;
        }
        result.put("redisOk", redisOk);
        return Result.ok(result);
    }

    @GetMapping("/status/{serverId}")
    public Result<Map<String, Object>> serverStatus(@PathVariable String serverId) {
        return Result.ok(syncService.snapshotStatus(serverId));
    }
}
