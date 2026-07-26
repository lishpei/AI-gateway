package com.corp.mcp.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（对应 audit_logs，生产按 timestamp 年分区）。
 */
@Data
@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private String traceId;

    /** 毫秒精度时间戳 */
    private LocalDateTime timestamp;

    /** 委托链末端（直接调用方） */
    private String callerAgentId;

    /** 完整委托链 JSON */
    private String delegationChain;

    /** 终端用户 sub（无用户委托时为 null） */
    private String delegatorUserId;

    private String delegatorOrgId;

    /** MCP 方法名 */
    private String jsonrpcMethod;

    private String toolName;

    private String serverId;

    /** SHA-256(规范化 arguments)，不记录原始参数 */
    private String requestArgsHash;

    /** success/failed */
    private String authResult;

    /** allow/deny/n-a */
    private String policyDecision;

    private String denyReason;

    private Integer tokenExchanged;

    private Integer latencyMs;

    private Integer upstreamLatencyMs;

    private Integer responseStatus;

    private Integer responseSize;

    private String clientIp;

    private LocalDateTime createdAt;
}
