package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kling 视频生成 API 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "comic.kling")
public class KlingProperties {

    /**
     * Access Key
     */
    private String accessKey;

    /**
     * Secret Key
     */
    private String secretKey;

    /**
     * 基础 URL
     */
    private String baseUrl = "https://api-beijing.klingai.com";

    /**
     * 视频模型名称
     */
    private String modelName = "kling-v3";

    /**
     * 生成模式（std / high）
     */
    private String mode = "std";
}
