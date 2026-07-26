package com.corp.agent.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Card 全量定义（对应 agent_card，A2A v1.0.0 字段）。
 */
@Data
@TableName("agent_card")
public class AgentCard {

    @TableId
    private String id;

    private String name;

    private String description;

    private String providerOrganization;

    private String providerUrl;

    private String version;

    private String documentationUrl;

    private String iconUrl;

    /** 上游声明的 A2A 协议版本（Major.Minor） */
    private String protocolVersion;

    /** 上游真实 A2A 端点（内部，不出现在对外 Card 中） */
    private String endpointUrl;

    /** AgentCapabilities JSON */
    private String capabilities;

    private String securitySchemes;

    private String securityRequirements;

    private String defaultInputModes;

    private String defaultOutputModes;

    /** AgentSkill 数组 JSON */
    private String skills;

    private String signatures;

    /** 1:启用 0:禁用 */
    private Integer status;

    /** 最近一次发布对应的 config_change_log.seq */
    private Long publishedSeq;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
