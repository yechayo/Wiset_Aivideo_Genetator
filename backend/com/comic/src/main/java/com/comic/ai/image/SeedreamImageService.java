package com.comic.ai.image;

import com.comic.config.ArkProperties;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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
    private final OssService ossService;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(2);

    @Override
    public String generate(String prompt, int width, int height, String style) {
        try {
            semaphore.acquire();
            log.info("Seedream 图片生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            String size = getSizeString(width, height);

            // 构建请求体
            Map<String, Object> requestBody = buildRequestBody(prompt, size);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.info("Seedream 请求参数: {}", jsonBody);

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
                    throw new RuntimeException("Seedream 图片生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                String tempUrl = parseResponse(responseBody);

                // 转存到 OSS，获取永久公网 URL
                String ossUrl = ossService.uploadImageFromUrl(tempUrl, null);
                log.info("Seedream 图片生成完成，已转存 OSS: {}", ossUrl);
                return ossUrl;
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
        try {
            semaphore.acquire();
            log.info("Seedream 参考图生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            String size = getSizeString(width, height);

            Map<String, Object> requestBody = buildRequestBody(prompt, size);
            requestBody.put("image", referenceImage);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.info("Seedream 参考图请求参数: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + "/images/generations")
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Seedream 参考图 API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Seedream 参考图生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                String tempUrl = parseResponse(responseBody);

                String ossUrl = ossService.uploadImageFromUrl(tempUrl, null);
                log.info("Seedream 参考图生成完成，已转存 OSS: {}", ossUrl);
                return ossUrl;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Seedream 参考图生成被中断", e);
        } catch (IOException e) {
            log.error("Seedream 参考图生成 IO 异常", e);
            throw new RuntimeException("Seedream 参考图生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Seedream 参考图生成异常", e);
            throw new RuntimeException("Seedream 参考图生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public String generateWithMultipleReferences(String prompt, List<String> referenceImages, int width, int height) {
        if (referenceImages == null || referenceImages.isEmpty()) {
            throw new IllegalArgumentException("参考图列表不能为空");
        }
        if (referenceImages.size() > 14) {
            throw new IllegalArgumentException("参考图数量不能超过14张，当前: " + referenceImages.size());
        }
        try {
            semaphore.acquire();
            log.info("Seedream 多参考图生成: 并发槽位 {}/{}, 参考图数量: {}", semaphore.availablePermits(), semaphore.getQueueLength(), referenceImages.size());

            String size = getSizeString(width, height);

            Map<String, Object> requestBody = buildRequestBody(prompt, size);
            // 多图输入：image 字段传 List<String>
            requestBody.put("image", referenceImages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.info("Seedream 多参考图请求参数: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + "/images/generations")
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Seedream 多参考图 API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Seedream 多参考图生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                String tempUrl = parseResponse(responseBody);

                String ossUrl = ossService.uploadImageFromUrl(tempUrl, null);
                log.info("Seedream 多参考图生成完成，已转存 OSS: {}", ossUrl);
                return ossUrl;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Seedream 多参考图生成被中断", e);
        } catch (IOException e) {
            log.error("Seedream 多参考图生成 IO 异常", e);
            throw new RuntimeException("Seedream 多参考图生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Seedream 多参考图生成异常", e);
            throw new RuntimeException("Seedream 多参考图生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
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
     * 获取尺寸字符串
     * Seedream 5.0 总像素需在 [3686400, 10404496] 范围内，宽高比在 [1/16, 16]
     * Seedream 4.0 总像素需在 [921600, 16777216] 范围内，宽高比在 [1/16, 16]
     * 当输入尺寸不满足当前模型要求时，按相同宽高比放大到最小像素要求
     */
    private String getSizeString(int width, int height) {
        int totalPixels = width * height;
        boolean isV5 = arkProperties.getSeedreamModel().contains("5-0") || arkProperties.getSeedreamModel().contains("5.0");
        int minPixels = isV5 ? 3686400 : 921600;
        int maxPixels = isV5 ? 10404496 : 16777216;

        if (totalPixels < minPixels) {
            double scale = Math.sqrt((double) minPixels / totalPixels);
            int newWidth = (int) Math.ceil(width * scale);
            int newHeight = (int) Math.ceil(height * scale);
            newWidth = (newWidth + 63) / 64 * 64;
            newHeight = (newHeight + 63) / 64 * 64;
            log.info("尺寸自动调整: {}x{} -> {}x{} (模型={}, 最小像素={})", width, height, newWidth, newHeight, arkProperties.getSeedreamModel(), minPixels);
            return newWidth + "x" + newHeight;
        }

        if (totalPixels > maxPixels) {
            double scale = Math.sqrt((double) maxPixels / totalPixels);
            int newWidth = (int) Math.floor(width * scale);
            int newHeight = (int) Math.floor(height * scale);
            newWidth = (newWidth / 64) * 64;
            newHeight = (newHeight / 64) * 64;
            if (newWidth < 64) newWidth = 64;
            if (newHeight < 64) newHeight = 64;
            log.info("尺寸自动调整(缩小): {}x{} -> {}x{} (模型={}, 最大像素={})", width, height, newWidth, newHeight, arkProperties.getSeedreamModel(), maxPixels);
            return newWidth + "x" + newHeight;
        }

        return width + "x" + height;
    }

    /**
     * 构建请求体，根据模型版本适配参数
     * Seedream 5.0 不支持 sequential_image_generation，使用 output_format 代替
     * Seedream 4.0 支持 sequential_image_generation，不支持 output_format
     */
    private Map<String, Object> buildRequestBody(String prompt, String size) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", arkProperties.getSeedreamModel());
        requestBody.put("prompt", prompt);
        requestBody.put("size", size);
        requestBody.put("response_format", "url");
        requestBody.put("watermark", false);

        boolean isV5 = arkProperties.getSeedreamModel().contains("5-0") || arkProperties.getSeedreamModel().contains("5.0");
        if (isV5) {
            // 5.0: 使用 output_format，不传 sequential_image_generation
            requestBody.put("output_format", "png");
        } else {
            // 4.0: 使用 sequential_image_generation
            requestBody.put("sequential_image_generation", "disabled");
        }

        return requestBody;
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
