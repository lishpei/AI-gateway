package com.corp.mcp.admin.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 创建/更新请求。
 */
@Data
public class McpServerSaveDTO {

    @NotBlank(message = "serverId 不能为空")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "serverId 须匹配 ^[a-z0-9][a-z0-9-]{1,62}$")
    private String serverId;

    @NotBlank(message = "name 不能为空")
    private String name;

    private String description;

    private String category;

    @NotBlank(message = "baseUrl 不能为空")
    @Pattern(regexp = "^https?://\\S+$", message = "baseUrl 必须是合法 http/https URL")
    private String baseUrl;

    /** 后端实例列表：[{"url":"https://...","weight":1}] */
    private List<Map<String, Object>> instances;

    @Pattern(regexp = "^(streamable-http|http-sse)$", message = "protocolType 仅支持 streamable-http/http-sse")
    private String protocolType = "streamable-http";

    @NotBlank(message = "resourceUri 不能为空")
    @Pattern(regexp = "^https?://[^#?\\s]+$", message = "resourceUri 必须是 RFC8707 规范化 URI（无 fragment/query）")
    private String resourceUri;

    @Pattern(regexp = "^(user-delegation|service|none)$", message = "authMode 仅支持 user-delegation/service/none")
    private String authMode = "user-delegation";

    private String healthEndpoint = "/health";

    private String dataClassification = "internal";

    private String ownerTeam;

    private String ownerEmail;

    private String version;

    /** 随 Server 一并提交的工具列表（可选） */
    private List<McpToolSaveDTO> tools;
}
