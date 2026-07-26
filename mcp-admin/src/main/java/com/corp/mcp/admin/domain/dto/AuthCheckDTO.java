package com.corp.mcp.admin.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 权限检查（/api/v1/auth/check）：网关兜底与前端预检共用。
 */
public class AuthCheckDTO {

    @Data
    public static class Request {
        /** 委托链（agent id 有序列表，末端为直接调用方） */
        private List<String> agentChain;

        /** 终端用户 ID（服务级调用可为空） */
        private String userId;

        @NotBlank(message = "serverId 不能为空")
        private String serverId;

        @NotBlank(message = "toolName 不能为空")
        private String toolName;
    }

    @Data
    public static class Response {
        private boolean allowed;
        private String reason;
        private String dataScope;
        /** 合并后的约束（最严格值），如 {"max_calls_per_minute":30} */
        private Map<String, Object> constraints;
        /** 快照版本（便于排障） */
        private Long snapshotVersion;

        public static Response deny(String reason) {
            Response r = new Response();
            r.setAllowed(false);
            r.setReason(reason);
            return r;
        }
    }
}
