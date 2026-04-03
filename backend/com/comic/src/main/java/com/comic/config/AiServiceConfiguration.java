package com.comic.config;

import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.image.NanobananaImageService;
import com.comic.ai.image.SeedreamImageService;
import com.comic.ai.text.DeepSeekTextService;
import com.comic.ai.text.TextGenerationService;
import com.comic.ai.video.SoraVideoService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.ai.video.ViduVideoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
            SoraVideoService soraVideoService
    ) {
        Map<String, ImageGenerationService> imageMap = new HashMap<>();
        imageMap.put("seedream", seedreamImageService);
        imageMap.put("nanobanana", nanobananaImageService);
        this.imageServices = imageMap;

        Map<String, VideoGenerationService> videoMap = new HashMap<>();
        videoMap.put("vidu", viduVideoService);
        videoMap.put("sora", soraVideoService);
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