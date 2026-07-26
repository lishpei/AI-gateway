package com.corp.agent.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上游 Server Agent 认证凭证（AES-256-GCM 加密存储）。
 */
@Data
@TableName("upstream_credential")
public class UpstreamCredential {

    public static final String TYPE_NONE = "NONE";
    public static final String TYPE_API_KEY = "API_KEY";
    public static final String TYPE_HTTP_BEARER = "HTTP_BEARER";
    public static final String TYPE_HTTP_BASIC = "HTTP_BASIC";
    public static final String TYPE_OAUTH2_CC = "OAUTH2_CLIENT_CREDENTIALS";
    public static final String TYPE_MTLS = "MTLS";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;

    /** NONE/API_KEY/HTTP_BEARER/HTTP_BASIC/OAUTH2_CLIENT_CREDENTIALS/MTLS */
    private String authType;

    /** AES-256-GCM 加密后的认证配置 JSON */
    private String configEnc;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
