package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MiniMax TTS 语音合成配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "comic.minimax-tts")
public class MiniMaxTtsProperties {

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 基础 URL
     */
    private String baseUrl = "https://api.minimaxi.com";

    /**
     * TTS 模型
     */
    private String model = "speech-2.8-hd";
}