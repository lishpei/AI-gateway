package com.corp.mcp.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权策略（对应 auth_policies）。
 * 一条策略 = (server, tool或*, grantee, effect, dataScope, constraints)。
 */
@Data
@TableName("auth_policies")
public class AuthPolicy {

    /** 状态：待审 */
    public static final int STATUS_PENDING = 0;
    /** 状态：生效 */
    public static final int STATUS_EFFECTIVE = 1;
    /** 状态：过期 */
    public static final int STATUS_EXPIRED = 2;
    /** 状态：撤销 */
    public static final int STATUS_REVOKED = 3;

    public static final String EFFECT_ALLOW = "ALLOW";
    public static final String EFFECT_DENY = "DENY";

    public static final String GRANTEE_AGENT = "AGENT";
    public static final String GRANTEE_USER = "USER";
    public static final String GRANTEE_ROLE = "ROLE";
    public static final String GRANTEE_GROUP = "GROUP";

    /** dataScope 取值（从小到大） */
    public static final String SCOPE_SELF = "self";
    public static final String SCOPE_TEAM = "team";
    public static final String SCOPE_DEPARTMENT = "department";
    public static final String SCOPE_ORGANIZATION = "organization";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String policyName;

    private String serverId;

    /** 工具名，*=全部 */
    private String toolName;

    /** AGENT/USER/ROLE/GROUP */
    private String granteeType;

    private String granteeId;

    private String granteeName;

    /** self/team/department/organization（上下文传递，资源侧执行） */
    private String dataScope;

    /** 约束 JSON：{"max_calls_per_minute":60,"time_range":"09:00-18:00"} */
    private String constraints;

    /** ALLOW/DENY，DENY 优先 */
    private String effect;

    private LocalDateTime effectiveTime;

    private LocalDateTime expiryTime;

    /** 0:待审 1:生效 2:过期 3:撤销 */
    private Integer status;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
