package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 无因科技 API 配置属性
 * 用于 Nanobanana（图片）
 */
@Data
@Component
@ConfigurationProperties(prefix = "comic.wuyinkeji")
public class WuyinkejiProperties {

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 基础 URL（Nanobanana 提交和查询）
     */
    private String baseUrl = "https://api.wuyinkeji.com";
}