package com.corp.agent.admin.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 上游凭证设置请求。config 明文结构按 authType 分（设计文档 7.3 节）。
 */
@Data
public class UpstreamCredentialSaveDTO {

    @NotBlank(message = "authType 不能为空")
    @Pattern(regexp = "^(NONE|API_KEY|HTTP_BEARER|HTTP_BASIC|OAUTH2_CLIENT_CREDENTIALS|MTLS)$",
            message = "authType 非法")
    private String authType;

    /** 明文配置（服务端加密入库；响应中永不回显） */
    @NotNull(message = "config 不能为空")
    private Map<String, Object> config;
}
