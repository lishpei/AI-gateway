package com.corp.mcp.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 工具元数据（对应 mcp_tools）。
 */
@Data
@TableName("mcp_tools")
public class McpTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String serverId;

    private String toolName;

    private String description;

    /** MCP inputSchema（JSON Schema） */
    private String inputSchema;

    private String outputSchema;

    /** MCP annotations，如 {"readOnlyHint":true} */
    private String annotations;

    /** Token Exchange 请求的 scope，默认 mcp:{serverId}:{toolName} */
    private String requiredScope;

    /** 默认每分钟限流（可被策略约束覆盖） */
    private Integer rateLimitRpm;

    /** 参数绑定校验 JSON：[{"param":"employee_id","claim":"email","required":true}] */
    private String subjectBindings;

    /** 输入校验级别：none/basic/schema */
    private String validationLevel;

    /** 输出脱敏规则 JSON：[{"pattern":"...","replacement":"***"}] */
    private String outputMasking;

    private String dataClassification;

    private Integer isActive;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
