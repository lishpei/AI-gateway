package com.corp.agent.admin.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 调用方创建/更新请求。
 */
@Data
public class CallerSaveDTO {

    @NotBlank(message = "id 不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]{1,62}$", message = "id 格式非法")
    private String id;

    @NotBlank(message = "name 不能为空")
    private String name;

    private String description;

    private Integer status = 1;
}
