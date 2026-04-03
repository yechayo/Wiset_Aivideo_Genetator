package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 无因科技 API 配置属性
 * 用于 Nanobanana（图片）和 Sora（视频）
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

    /**
     * Sora 查询基础 URL（Sora 查询接口在不同域名）
     */
    private String soraQueryBaseUrl = "https://csapi.wuyinkeji.com";
}