package com.corp.mcp.admin.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权策略创建/更新请求。
 */
@Data
public class AuthPolicySaveDTO {

    private String policyName;

    @NotBlank(message = "serverId 不能为空")
    private String serverId;

    /** 工具名，*=全部 */
    private String toolName = "*";

    @NotBlank(message = "granteeType 不能为空")
    @Pattern(regexp = "^(AGENT|USER|ROLE|GROUP)$", message = "granteeType 仅支持 AGENT/USER/ROLE/GROUP")
    private String granteeType;

    @NotBlank(message = "granteeId 不能为空")
    private String granteeId;

    private String granteeName;

    @Pattern(regexp = "^(self|team|department|organization)$", message = "dataScope 非法")
    private String dataScope = "self";

    /** 约束 JSON：{"max_calls_per_minute":60,"time_range":"09:00-18:00"} */
    private String constraints;

    @Pattern(regexp = "^(ALLOW|DENY)$", message = "effect 仅支持 ALLOW/DENY")
    private String effect = "ALLOW";

    private LocalDateTime effectiveTime;

    private LocalDateTime expiryTime;
}
