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
    private String model = "viduq3-pro";

    /**
     * 错峰模式（积分更低，48小时内生成）
     */
    private boolean offPeak = false;

    /**
     * 是否启用提示词增强（默认开启）
     */
    private boolean promptEnhanceEnabled = true;

    /**
     * 提示词增强 API 路径
     */
    private String promptEnhanceEndpoint = "/prompt-enhance";
}
