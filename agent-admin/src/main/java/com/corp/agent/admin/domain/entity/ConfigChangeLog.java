package com.corp.agent.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 配置变更流水（节点增量同步的版本水位）。
 */
@Data
@NoArgsConstructor
@TableName("config_change_log")
public class ConfigChangeLog {

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_UPSTREAM_CRED = "UPSTREAM_CRED";
    public static final String TYPE_CALLER = "CALLER";
    public static final String TYPE_CALLER_CRED = "CALLER_CRED";
    public static final String TYPE_ACL = "ACL";

    public static final String OP_UPSERT = "UPSERT";
    public static final String OP_DELETE = "DELETE";

    @TableId(type = IdType.AUTO)
    private Long seq;

    private String entityType;

    private String entityId;

    private String operation;

    private LocalDateTime changedAt;

    public ConfigChangeLog(String entityType, String entityId, String operation) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.operation = operation;
    }
}
