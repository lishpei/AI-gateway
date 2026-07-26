package com.corp.mcp.admin.controller;

import com.corp.mcp.admin.common.Result;
import com.corp.mcp.admin.domain.dto.McpServerSaveDTO;
import com.corp.mcp.admin.domain.dto.McpToolSaveDTO;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.McpServer;
import com.corp.mcp.admin.domain.entity.McpTool;
import com.corp.mcp.admin.service.RegistryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 注册管理（设计文档 6.3 注册域）。
 */
@RestController
@RequestMapping("/api/v1/registry")
@RequiredArgsConstructor
public class RegistryController {

    private final RegistryService registryService;

    @PostMapping("/servers")
    public Result<McpServer> create(@Valid @RequestBody McpServerSaveDTO dto,
                                    @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Result.ok(registryService.createServer(dto, operator));
    }

    @PutMapping("/servers/{serverId}")
    public Result<McpServer> update(@PathVariable String serverId,
                                    @Valid @RequestBody McpServerSaveDTO dto,
                                    @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Result.ok(registryService.updateServer(serverId, dto, operator));
    }

    @GetMapping("/servers")
    public Result<PageResult<McpServer>> page(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) Integer status) {
        return Result.ok(registryService.pageServers(page, size, keyword, category, status));
    }

    @GetMapping("/servers/{serverId}")
    public Result<Map<String, Object>> detail(@PathVariable String serverId) {
        return Result.ok(registryService.serverDetail(serverId));
    }

    @DeleteMapping("/servers/{serverId}")
    public Result<Void> delete(@PathVariable String serverId) {
        registryService.deleteServer(serverId);
        return Result.ok();
    }

    @PostMapping("/servers/{serverId}/publish")
    public Result<Map<String, Object>> publish(@PathVariable String serverId) {
        long version = registryService.publish(serverId);
        return Result.ok(Map.of("serverId", serverId, "snapshotVersion", version));
    }

    @PostMapping("/servers/{serverId}/deprecate")
    public Result<Void> deprecate(@PathVariable String serverId) {
        registryService.deprecate(serverId);
        return Result.ok();
    }

    // ---------- 工具管理 ----------

    @GetMapping("/servers/{serverId}/tools")
    public Result<List<McpTool>> listTools(@PathVariable String serverId) {
        return Result.ok(registryService.listTools(serverId));
    }

    @PostMapping("/servers/{serverId}/tools")
    public Result<McpTool> saveTool(@PathVariable String serverId,
                                    @Valid @RequestBody McpToolSaveDTO dto) {
        return Result.ok(registryService.saveTool(serverId, dto));
    }

    @PutMapping("/servers/{serverId}/tools/{toolName}")
    public Result<McpTool> updateTool(@PathVariable String serverId, @PathVariable String toolName,
                                      @Valid @RequestBody McpToolSaveDTO dto) {
        dto.setToolName(toolName);
        return Result.ok(registryService.saveTool(serverId, dto));
    }

    @DeleteMapping("/servers/{serverId}/tools/{toolName}")
    public Result<Void> deleteTool(@PathVariable String serverId, @PathVariable String toolName) {
        registryService.deleteTool(serverId, toolName);
        return Result.ok();
    }
}
