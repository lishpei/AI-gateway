package com.corp.mcp.admin.controller;

import com.corp.mcp.admin.common.Result;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.McpServer;
import com.corp.mcp.admin.service.RegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP 市场（设计文档 6.3 市场域）：仅展示活跃 Server。
 */
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketController {

    private final RegistryService registryService;

    @GetMapping("/servers")
    public Result<PageResult<McpServer>> page(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "12") int size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String category) {
        return Result.ok(registryService.pageServers(page, size, keyword, category, McpServer.STATUS_ACTIVE));
    }

    @GetMapping("/servers/{serverId}")
    public Result<Map<String, Object>> detail(@PathVariable String serverId) {
        return Result.ok(registryService.serverDetail(serverId));
    }
}
