package com.comic.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 火山引擎 Ark SDK 配置属性
 * 仅用于 Seedream（图片）和 Seedance（视频）
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "comic.ark")
public class ArkProperties {

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 基础 URL
     */
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";

    /**
     * Seedream 图片模型
     */
    private String seedreamModel = "doubao-seedream-5-0-lite-260128";

    /**
     * Seedance 视频模型
     */
    private String seedanceModel = "doubao-seedance-1-5-pro-251215";

    /**
     * 获取 API Key（用于调试）
     */
    public String getApiKey() {
        log.debug("ArkProperties.getApiKey() called, apiKey={}", apiKey);
        return apiKey;
    }
}
