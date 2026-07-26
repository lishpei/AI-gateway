package com.corp.mcp.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.corp.mcp.admin.common.BizException;
import com.corp.mcp.admin.domain.dto.McpServerSaveDTO;
import com.corp.mcp.admin.domain.dto.McpToolSaveDTO;
import com.corp.mcp.admin.domain.dto.PageResult;
import com.corp.mcp.admin.domain.entity.McpServer;
import com.corp.mcp.admin.domain.entity.McpTool;
import com.corp.mcp.admin.mapper.McpServerMapper;
import com.corp.mcp.admin.mapper.McpToolMapper;
import com.corp.mcp.admin.util.Jsons;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * MCP Server/Tool 注册管理（设计文档 6.3 注册域 API）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryService {

    private final McpServerMapper serverMapper;
    private final McpToolMapper toolMapper;
    private final RedisSyncService syncService;

    // ---------- Server CRUD ----------

    @Transactional
    public McpServer createServer(McpServerSaveDTO dto, String operator) {
        requireServerAbsent(dto.getServerId());
        McpServer server = new McpServer();
        applyDto(server, dto);
        server.setStatus(McpServer.STATUS_DRAFT);
        server.setHealthStatus("unknown");
        server.setTotalCalls(0L);
        server.setCreatedBy(operator);
        serverMapper.insert(server);
        saveTools(dto.getServerId(), dto.getTools());
        return server;
    }

    @Transactional
    public McpServer updateServer(String serverId, McpServerSaveDTO dto, String operator) {
        McpServer server = requireServer(serverId);
        if (!serverId.equals(dto.getServerId())) {
            throw BizException.badRequest("serverId 不可修改");
        }
        applyDto(server, dto);
        serverMapper.updateById(server);
        if (dto.getTools() != null) {
            saveTools(serverId, dto.getTools());
        }
        // 已发布的 Server 更新后自动重建快照
        republishIfActive(server);
        return server;
    }

    public McpServer requireServer(String serverId) {
        McpServer server = serverMapper.selectOne(
                new LambdaQueryWrapper<McpServer>().eq(McpServer::getServerId, serverId));
        if (server == null) {
            throw BizException.notFound("mcp server " + serverId);
        }
        return server;
    }

    public PageResult<McpServer> pageServers(int page, int size, String keyword, String category, Integer status) {
        Page<McpServer> p = serverMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<McpServer>()
                        .like(keyword != null && !keyword.isBlank(), McpServer::getName, keyword)
                        .or()
                        .like(keyword != null && !keyword.isBlank(), McpServer::getServerId, keyword)
                        .eq(category != null && !category.isBlank(), McpServer::getCategory, category)
                        .eq(status != null, McpServer::getStatus, status)
                        .orderByDesc(McpServer::getUpdatedAt));
        return PageResult.of(p);
    }

    // ---------- 发布/废弃/删除 ----------

    @Transactional
    public long publish(String serverId) {
        McpServer server = requireServer(serverId);
        server.setStatus(McpServer.STATUS_ACTIVE);
        serverMapper.updateById(server);
        return syncService.publishServerSnapshot(serverId);
    }

    @Transactional
    public void deprecate(String serverId) {
        McpServer server = requireServer(serverId);
        server.setStatus(McpServer.STATUS_DEPRECATED);
        serverMapper.updateById(server);
        syncService.unpublish(serverId);
    }

    @Transactional
    public void deleteServer(String serverId) {
        McpServer server = requireServer(serverId);
        serverMapper.deleteById(server.getId());
        toolMapper.delete(new LambdaQueryWrapper<McpTool>().eq(McpTool::getServerId, serverId));
        syncService.unpublish(serverId);
    }

    // ---------- Tool CRUD ----------

    public List<McpTool> listTools(String serverId) {
        requireServer(serverId);
        return toolMapper.selectList(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId)
                .orderByAsc(McpTool::getToolName));
    }

    @Transactional
    public McpTool saveTool(String serverId, McpToolSaveDTO dto) {
        McpServer server = requireServer(serverId);
        validateToolJson(dto);
        McpTool tool = toolMapper.selectOne(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId)
                .eq(McpTool::getToolName, dto.getToolName()));
        if (tool == null) {
            tool = new McpTool();
            tool.setServerId(serverId);
            applyToolDto(tool, dto);
            toolMapper.insert(tool);
        } else {
            applyToolDto(tool, dto);
            toolMapper.updateById(tool);
        }
        republishIfActive(server);
        return tool;
    }

    @Transactional
    public void deleteTool(String serverId, String toolName) {
        McpServer server = requireServer(serverId);
        toolMapper.delete(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId)
                .eq(McpTool::getToolName, toolName));
        republishIfActive(server);
    }

    // ---------- 内部 ----------

    private void republishIfActive(McpServer server) {
        if (server.getStatus() != null && server.getStatus() == McpServer.STATUS_ACTIVE) {
            syncService.publishServerSnapshot(server.getServerId());
        }
    }

    private void requireServerAbsent(String serverId) {
        Long count = serverMapper.selectCount(
                new LambdaQueryWrapper<McpServer>().eq(McpServer::getServerId, serverId));
        if (count > 0) {
            throw BizException.conflict("serverId 已存在: " + serverId);
        }
    }

    private void applyDto(McpServer server, McpServerSaveDTO dto) {
        server.setServerId(dto.getServerId());
        server.setName(dto.getName());
        server.setDescription(dto.getDescription());
        server.setCategory(dto.getCategory());
        server.setBaseUrl(dto.getBaseUrl());
        server.setInstances(dto.getInstances() == null ? null : Jsons.toJson(dto.getInstances()));
        server.setProtocolType(dto.getProtocolType());
        server.setResourceUri(dto.getResourceUri());
        server.setAuthMode(dto.getAuthMode());
        server.setHealthEndpoint(dto.getHealthEndpoint());
        server.setDataClassification(dto.getDataClassification());
        server.setOwnerTeam(dto.getOwnerTeam());
        server.setOwnerEmail(dto.getOwnerEmail());
        server.setVersion(dto.getVersion());
    }

    private void saveTools(String serverId, List<McpToolSaveDTO> tools) {
        if (tools == null) {
            return;
        }
        for (McpToolSaveDTO dto : tools) {
            validateToolJson(dto);
            McpTool tool = toolMapper.selectOne(new LambdaQueryWrapper<McpTool>()
                    .eq(McpTool::getServerId, serverId)
                    .eq(McpTool::getToolName, dto.getToolName()));
            if (tool == null) {
                tool = new McpTool();
                tool.setServerId(serverId);
                applyToolDto(tool, dto);
                toolMapper.insert(tool);
            } else {
                applyToolDto(tool, dto);
                toolMapper.updateById(tool);
            }
        }
    }

    private void applyToolDto(McpTool tool, McpToolSaveDTO dto) {
        tool.setToolName(dto.getToolName());
        tool.setDescription(dto.getDescription());
        tool.setInputSchema(dto.getInputSchema());
        tool.setOutputSchema(dto.getOutputSchema());
        tool.setAnnotations(dto.getAnnotations());
        tool.setRequiredScope(dto.getRequiredScope());
        tool.setRateLimitRpm(dto.getRateLimitRpm());
        tool.setSubjectBindings(dto.getSubjectBindings());
        tool.setValidationLevel(dto.getValidationLevel());
        tool.setOutputMasking(dto.getOutputMasking());
        tool.setDataClassification(dto.getDataClassification());
        tool.setIsActive(dto.getIsActive());
    }

    private void validateToolJson(McpToolSaveDTO dto) {
        Jsons.requireValidJson(dto.getInputSchema(), "inputSchema");
        Jsons.requireValidJson(dto.getOutputSchema(), "outputSchema");
        Jsons.requireValidJson(dto.getAnnotations(), "annotations");
        Jsons.requireValidJson(dto.getSubjectBindings(), "subjectBindings");
        Jsons.requireValidJson(dto.getOutputMasking(), "outputMasking");
    }

    /** 供 Market 查询的简要信息组装 */
    public Map<String, Object> serverDetail(String serverId) {
        McpServer server = requireServer(serverId);
        List<McpTool> tools = listTools(serverId);
        return Map.of("server", server, "tools", tools);
    }
}
