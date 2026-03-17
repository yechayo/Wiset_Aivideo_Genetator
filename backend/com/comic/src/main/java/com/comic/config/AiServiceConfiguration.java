package com.comic.config;

import com.comic.ai.image.SeedreamImageService;
import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.text.DeepSeekTextService;
import com.comic.ai.text.TextGenerationService;
import com.comic.ai.video.SeedanceVideoService;
import com.comic.ai.video.VideoGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI 服务配置
 * 根据配置文件自动选择对应的服务实现
 */
@Configuration
@Slf4j
public class AiServiceConfiguration {

    // ========== 文本生成服务配置 ==========

    @Bean
    @Primary
    public TextGenerationService textGenerationService(
            DeepSeekTextService deepSeekTextService
    ) {
        log.info("=================================================");
        log.info("🤖 文本生成服务配置");
        log.info("=================================================");
        log.info("✅ 使用 DeepSeek（思考模式）进行文本生成（剧本、对话等）");
        return deepSeekTextService;
    }

    // ========== 图片生成服务配置 ==========

    @Value("${comic.ai.image.provider:seedream}")
    private String imageProvider;

    @Bean
    @Primary
    public ImageGenerationService imageGenerationService(
            SeedreamImageService seedreamImageService
    ) {
        log.info("=================================================");
        log.info("🎨 图片生成服务配置");
        log.info("=================================================");
        log.info("提供商: {}", imageProvider);

        ImageGenerationService service;
        switch (imageProvider.toLowerCase()) {
            case "seedream":
                log.info("✅ 使用 Seedream 进行图片生成（角色立绘、分镜图等）");
                service = seedreamImageService;
                break;
            default:
                log.warn("⚠️  未知的图片提供商: {}，使用默认的 Seedream", imageProvider);
                service = seedreamImageService;
                break;
        }
        return service;
    }

    // ========== 视频生成服务配置 ==========

    @Value("${comic.ai.video.provider:seedance}")
    private String videoProvider;

    @Bean
    @Primary
    public VideoGenerationService videoGenerationService(
            SeedanceVideoService seedanceVideoService
    ) {
        log.info("=================================================");
        log.info("🎬 视频生成服务配置");
        log.info("=================================================");
        log.info("提供商: {}", videoProvider);

        VideoGenerationService service;
        switch (videoProvider.toLowerCase()) {
            case "seedance":
                log.info("✅ 使用 Seedance 进行视频生成（分镜视频等）");
                service = seedanceVideoService;
                break;
            default:
                log.warn("⚠️  未知的视频提供商: {}，使用默认的 Seedance", videoProvider);
                service = seedanceVideoService;
                break;
        }
        return service;
    }
}
