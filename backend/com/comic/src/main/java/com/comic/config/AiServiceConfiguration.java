package com.comic.config;

import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.image.NanobananaImageService;
import com.comic.ai.image.SeedreamImageService;
import com.comic.ai.text.DeepSeekTextService;
import com.comic.ai.text.TextGenerationService;
import com.comic.ai.video.GrokVideoService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.ai.video.ViduVideoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 服务配置
 * 根据项目配置动态选择对应的图片/视频生成服务实现
 */
@Configuration
@Slf4j
public class AiServiceConfiguration {

    private final Map<String, ImageGenerationService> imageServices;
    private final Map<String, VideoGenerationService> videoServices;

    public AiServiceConfiguration(
            SeedreamImageService seedreamImageService,
            NanobananaImageService nanobananaImageService,
            ViduVideoService viduVideoService,
            GrokVideoService grokVideoService
    ) {
        Map<String, ImageGenerationService> imageMap = new HashMap<>();
        imageMap.put("seedream", seedreamImageService);
        imageMap.put("nanobanana", nanobananaImageService);
        this.imageServices = imageMap;

        Map<String, VideoGenerationService> videoMap = new HashMap<>();
        videoMap.put("vidu", viduVideoService);
        videoMap.put("grok", grokVideoService);
        this.videoServices = videoMap;
        log.info("AI 服务配置初始化: 图片={}, 视频={}", imageServices.keySet(), videoServices.keySet());
    }

    // ========== 文本生成服务（保持不变） ==========

    @Bean
    @Primary
    public TextGenerationService textGenerationService(DeepSeekTextService deepSeekTextService) {
        log.info("文本生成服务: DeepSeek");
        return deepSeekTextService;
    }

    /**
     * DeepSeek Reasoner Bean — 用于分镜 Agent 的规划决策（deepseek-reasoner 模型）
     */
    @Bean("reasoner")
    public DeepSeekTextService deepseekReasonerTextService(OkHttpClient httpClient,
                                                            ObjectMapper objectMapper,
                                                            Environment env) {
        DeepSeekTextService reasoner = new DeepSeekTextService(httpClient, objectMapper);
        try {
            setField(reasoner, "apiKey", env.getProperty("comic.deepseek-reasoner.api-key", ""));
            setField(reasoner, "baseUrl", env.getProperty("comic.deepseek-reasoner.base-url", "https://api.deepseek.com"));
            setField(reasoner, "model", env.getProperty("comic.deepseek-reasoner.model", "deepseek-reasoner"));
            setField(reasoner, "maxTokens", Integer.parseInt(env.getProperty("comic.deepseek-reasoner.max-tokens", "4096")));
            setField(reasoner, "narrationRefinementEnabled", false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure DeepSeek Reasoner", e);
        }
        log.info("DeepSeek Reasoner 配置完成: model={}", env.getProperty("comic.deepseek-reasoner.model", "deepseek-reasoner"));
        return reasoner;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ========== 默认 Bean（向后兼容，不确定 provider 时使用） ==========

    @Bean
    @Primary
    public ImageGenerationService imageGenerationService(SeedreamImageService seedreamImageService) {
        return seedreamImageService;
    }

    @Bean
    @Primary
    public VideoGenerationService videoGenerationService(ViduVideoService viduVideoService) {
        return viduVideoService;
    }

    // ========== Provider 分发 ==========

    public ImageGenerationService getImageService(String provider) {
        ImageGenerationService service = imageServices.get(provider);
        if (service == null) {
            log.warn("未知的图片 provider: {}, 使用默认 seedream", provider);
            return imageServices.get("seedream");
        }
        return service;
    }

    public VideoGenerationService getVideoService(String provider) {
        VideoGenerationService service = videoServices.get(provider);
        if (service == null) {
            log.warn("未知的视频 provider: {}, 使用默认 vidu", provider);
            return videoServices.get("vidu");
        }
        return service;
    }
}