package com.comic.ai.image;

import com.comic.config.ArkProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Seedream 图片生成服务（HTTP API 方式）
 * 使用火山引擎 Ark HTTP API 调用 Seedream 模型生成图片
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeedreamImageService implements ImageGenerationService {

    private final ArkProperties arkProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(2);

    @Override
    public String generate(String prompt, int width, int height, String style) {
        try {
            semaphore.acquire();
            log.info("Seedream 图片生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            String enhancedPrompt = enhancePrompt(prompt, style);
            String size = getSizeString(width, height);

            // 构建请求体（Java 8 兼容）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkProperties.getSeedreamModel());
            requestBody.put("prompt", enhancedPrompt);
            requestBody.put("size", size);
            requestBody.put("output_format", "png");
            requestBody.put("watermark", false);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + "/images/generations")
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Seedream API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Seedream 图片生成失败: " + response.code());
                }

                String responseBody = response.body().string();
                String imageUrl = parseResponse(responseBody);

                log.info("Seedream 图片生成完成: {}", imageUrl);
                return imageUrl;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Seedream 图片生成被中断", e);
        } catch (IOException e) {
            log.error("Seedream 图片生成 IO 异常", e);
            throw new RuntimeException("Seedream 图片生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Seedream 图片生成异常", e);
            throw new RuntimeException("Seedream 图片生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public String generateWithReference(String prompt, String referenceImage, int width, int height) {
        // Seedream 支持参考图，需要使用特殊的 API
        log.warn("Seedream 参考图功能待实现，使用普通生成");
        return generate(prompt, width, height, "realistic");
    }

    @Override
    public String getServiceName() {
        return "Seedream-Image";
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return semaphore.availablePermits();
    }

    /**
     * 增强提示词
     */
    private String enhancePrompt(String prompt, String style) {
        String stylePrompt;
        switch (style.toLowerCase()) {
            case "anime":
                stylePrompt = "anime style, manga art, vibrant colors, high quality";
                break;
            case "realistic":
                stylePrompt = "photorealistic, highly detailed, professional photography, 8k";
                break;
            case "comic":
                stylePrompt = "comic book style, bold lines, vibrant colors, detailed";
                break;
            default:
                stylePrompt = "high quality, detailed, professional";
                break;
        }
        return stylePrompt + ", " + prompt;
    }

    /**
     * 获取尺寸字符串
     */
    private String getSizeString(int width, int height) {
        // Seedream 支持的尺寸：1K, 2K, 4K 或 宽x高
        if (width >= 2048 || height >= 2048) {
            return "2K";
        }
        return "1024x1024";
    }

    /**
     * 解析响应
     */
    private String parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode firstItem = data.get(0);
                JsonNode url = firstItem.get("url");
                if (url != null) {
                    return url.asText();
                }
            }
            throw new RuntimeException("无法解析 Seedream 响应: " + responseBody);
        } catch (Exception e) {
            log.error("解析响应失败: {}", responseBody, e);
            throw new RuntimeException("解析响应失败", e);
        }
    }
}
