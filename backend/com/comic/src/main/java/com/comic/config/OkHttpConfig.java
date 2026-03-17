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
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // DeepSeek 思考模式响应较慢，需要 5 分钟
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }
}
