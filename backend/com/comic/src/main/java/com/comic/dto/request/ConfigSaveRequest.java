package com.comic.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ConfigSaveRequest {

    @NotBlank(message = "配置键不能为空")
    private String configKey;

    private String configValue;

    private String configType;

    private String description;
}
