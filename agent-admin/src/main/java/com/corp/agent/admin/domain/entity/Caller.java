package com.corp.agent.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Client Agent 调用方。
 */
@Data
@TableName("caller")
public class Caller {

    @TableId
    private String id;

    private String name;

    private String description;

    /** 1:启用 0:禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
