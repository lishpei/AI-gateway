package com.corp.agent.admin.controller;

import com.corp.agent.admin.common.Result;
import com.corp.agent.admin.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

/**
 * 运行看板（管理台 token 访问）。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SyncService syncService;

    /** 各数据面节点同步水位 */
    @GetMapping("/nodes")
    public Result<Collection<Map<String, Object>>> nodes() {
        return Result.ok(syncService.listHeartbeats());
    }
}
