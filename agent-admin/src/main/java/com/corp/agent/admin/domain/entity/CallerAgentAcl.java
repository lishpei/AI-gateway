package com.corp.agent.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用方可访问的 Agent 白名单。
 */
@Data
@TableName("caller_agent_acl")
public class CallerAgentAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String callerId;

    private String agentId;

    private LocalDateTime createdAt;
}
