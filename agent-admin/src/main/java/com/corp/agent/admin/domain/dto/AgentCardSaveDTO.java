package com.corp.agent.admin.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent Card 创建/更新请求（A2A v1.0.0 字段）。
 */
@Data
public class AgentCardSaveDTO {

    @NotBlank(message = "id 不能为空")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "id 须匹配 ^[a-z0-9][a-z0-9-]{1,62}$")
    private String id;

    @NotBlank(message = "name 不能为空")
    private String name;

    private String description;

    private String providerOrganization;

    private String providerUrl;

    @NotBlank(message = "version 不能为空")
    private String version;

    private String documentationUrl;

    private String iconUrl;

    @Pattern(regexp = "^\\d+\\.\\d+$", message = "protocolVersion 须为 Major.Minor 格式")
    private String protocolVersion = "1.0";

    @NotBlank(message = "endpointUrl 不能为空")
    @Pattern(regexp = "^https?://\\S+$", message = "endpointUrl 必须是合法 http/https URL")
    private String endpointUrl;

    /** AgentCapabilities：{streaming, pushNotifications, extendedAgentCard, extensions[]} */
    @NotNull(message = "capabilities 不能为空")
    private Map<String, Object> capabilities;

    /** 对外声明的安全方案（不含秘密） */
    private Map<String, Object> securitySchemes;

    private List<Object> securityRequirements;

    @NotNull(message = "defaultInputModes 不能为空")
    private List<String> defaultInputModes;

    @NotNull(message = "defaultOutputModes 不能为空")
    private List<String> defaultOutputModes;

    /** AgentSkill 数组 */
    @NotNull(message = "skills 不能为空")
    private List<Map<String, Object>> skills;

    private List<Object> signatures;

    /** 1:启用 0:禁用 */
    private Integer status = 1;
}
