package com.corp.mcp.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP Server 注册表（对应 mcp_servers）。
 * JSON 列（instances）在实体中以 String 存储，Service 层负责转换。
 */
@Data
@TableName("mcp_servers")
public class McpServer {

    /** 状态：草稿 */
    public static final int STATUS_DRAFT = 0;
    /** 状态：待审 */
    public static final int STATUS_PENDING = 1;
    /** 状态：活跃（已发布） */
    public static final int STATUS_ACTIVE = 2;
    /** 状态：废弃 */
    public static final int STATUS_DEPRECATED = 3;

    /** 认证模式：需要用户委托 */
    public static final String AUTH_MODE_USER_DELEGATION = "user-delegation";
    /** 认证模式：服务级 */
    public static final String AUTH_MODE_SERVICE = "service";
    /** 认证模式：无认证 */
    public static final String AUTH_MODE_NONE = "none";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 唯一标识，^[a-z0-9][a-z0-9-]{1,62}$ */
    private String serverId;

    private String name;

    private String description;

    /** 分类：hr/finance/office/dev/... */
    private String category;

    /** 默认后端地址（无实例列表时使用） */
    private String baseUrl;

    /** 后端实例列表 JSON：[{"url":"https://...","weight":1}] */
    private String instances;

    /** 协议类型：streamable-http / http-sse */
    private String protocolType;

    /** RFC8707 规范化资源 URI，Token Exchange 的 audience */
    private String resourceUri;

    /** 认证模式：user-delegation / service / none */
    private String authMode;

    private String healthEndpoint;

    /** 数据分类：public/internal/confidential/restricted */
    private String dataClassification;

    private String ownerTeam;

    private String ownerEmail;

    /** 0:草稿 1:待审 2:活跃 3:废弃 */
    private Integer status;

    private String version;

    private String healthStatus;

    private LocalDateTime healthCheckedAt;

    private Long totalCalls;

    private Integer avgLatencyMs;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
