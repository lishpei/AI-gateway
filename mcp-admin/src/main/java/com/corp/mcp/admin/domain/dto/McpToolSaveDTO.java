package com.corp.mcp.admin.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * MCP 工具创建/更新请求。Schema/规则类字段以 JSON 字符串传递，Service 层校验其合法性。
 */
@Data
public class McpToolSaveDTO {

    @NotBlank(message = "toolName 不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.-]{0,127}$", message = "toolName 格式非法")
    private String toolName;

    @NotBlank(message = "description 不能为空")
    private String description;

    /** JSON Schema 字符串 */
    private String inputSchema;

    private String outputSchema;

    private String annotations;

    private String requiredScope;

    @Min(value = 1, message = "rateLimitRpm 必须 >= 1")
    private Integer rateLimitRpm = 60;

    /** 参数绑定校验 JSON：[{"param":"employee_id","claim":"email","required":true}] */
    private String subjectBindings;

    @Pattern(regexp = "^(none|basic|schema)$", message = "validationLevel 仅支持 none/basic/schema")
    private String validationLevel = "basic";

    /** 输出脱敏规则 JSON：[{"pattern":"...","replacement":"***"}] */
    private String outputMasking;

    private String dataClassification;

    private Integer isActive = 1;
}
