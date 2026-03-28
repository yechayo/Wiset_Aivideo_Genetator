package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Vidu 视频生成 API 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "comic.vidu")
public class ViduProperties {

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 基础 URL
     */
    private String baseUrl = "https://api.vidu.cn/ent/v2";

    /**
     * 视频模型
     */
    private String model = "viduq3-turbo";
}
