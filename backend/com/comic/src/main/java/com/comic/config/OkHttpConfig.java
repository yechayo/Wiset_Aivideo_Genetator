package com.comic.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttpClient 配置
 * 用于 AI 服务的 HTTP 调用
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)  // Nanobanana 等上游转发可能超过 30s，避免客户端先断开
                .readTimeout(600, TimeUnit.SECONDS)  // 10 分钟，兼容 DeepSeek 思考模式 + 长耗时生图
                .writeTimeout(120, TimeUnit.SECONDS)  // 大 prompt + 多参考图上传
                .build();
    }
}
