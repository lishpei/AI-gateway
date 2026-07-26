package com.corp.agent.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用方 API Key（仅存 SHA-256 哈希）。
 */
@Data
@TableName("caller_credential")
public class CallerCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String callerId;

    private String keyName;

    /** Key 前 8 位（日志/界面识别用） */
    private String apiKeyPrefix;

    /** SHA-256(API Key) 十六进制 */
    private String apiKeyHash;

    /** 1:启用 0:吊销 */
    private Integer status;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
