package com.corp.mcp.admin.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网关审计日志批量上报载荷。
 */
@Data
public class AuditLogBatchDTO {

    private List<Item> logs;

    @Data
    public static class Item {
        private String requestId;
        private String traceId;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
        private LocalDateTime timestamp;

        private String callerAgentId;
        private String delegationChain;
        private String delegatorUserId;
        private String delegatorOrgId;
        private String jsonrpcMethod;
        private String toolName;
        private String serverId;
        private String requestArgsHash;
        private String authResult;
        private String policyDecision;
        private String denyReason;
        private Integer tokenExchanged;
        private Integer latencyMs;
        private Integer upstreamLatencyMs;
        private Integer responseStatus;
        private Integer responseSize;
        private String clientIp;
    }
}
