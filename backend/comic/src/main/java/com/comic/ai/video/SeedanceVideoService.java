package com.comic.ai.video;

import com.comic.config.ArkProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Seedance 视频生成服务（HTTP API 方式）
 * 使用火山引擎 Ark HTTP API 调用 Seedance 模型生成视频
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeedanceVideoService implements VideoGenerationService {

    private final ArkProperties arkProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(1);

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        try {
            semaphore.acquire();
            log.info("Seedance 视频生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 构建内容列表（Java 8 兼容）
            List<Map<String, Object>> contents = new ArrayList<>();

            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt);
            contents.add(textContent);

            // 如果有参考图片，添加图片
            if (referenceImage != null && !referenceImage.isEmpty()) {
                Map<String, Object> imageContent = new HashMap<>();
                imageContent.put("type", "image_url");

                Map<String, String> imageUrl = new HashMap<>();
                imageUrl.put("url", referenceImage);
                imageContent.put("image_url", imageUrl);

                contents.add(imageContent);
            }

            // 构建请求体（Java 8 兼容）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkProperties.getSeedanceModel());
            requestBody.put("content", contents);
            requestBody.put("generate_audio", true);
            requestBody.put("ratio", aspectRatio);
            requestBody.put("duration", (long) duration);
            requestBody.put("watermark", false);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + "/content/generate")
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Seedance API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Seedance 视频生成失败: " + response.code());
                }

                String responseBody = response.body().string();
                String taskId = parseTaskId(responseBody);

                log.info("Seedance 视频生成任务已提交: {}", taskId);
                return taskId;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Seedance 视频生成被中断", e);
        } catch (IOException e) {
            log.error("Seedance 视频生成 IO 异常", e);
            throw new RuntimeException("Seedance 视频生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Seedance 视频生成异常", e);
            throw new RuntimeException("Seedance 视频生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        // 查询任务状态
        try {
            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + "/content/tasks/" + taskId)
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("查询任务状态失败: {}", response.code());
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败");
                }

                String responseBody = response.body().string();
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询任务状态 IO 异常", e);
            return new TaskStatus(taskId, "unknown", 0, null, e.getMessage());
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        // 从任务状态中获取视频 URL
        TaskStatus status = getTaskStatus(taskId);
        if (status.getVideoUrl() != null) {
            return status.getVideoUrl();
        }
        throw new RuntimeException("视频尚未生成完成");
    }

    @Override
    public String getServiceName() {
        return "Seedance-Video";
    }

    /**
     * 解析任务 ID
     */
    private String parseTaskId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode id = root.get("id");
            if (id != null) {
                return id.asText();
            }
            throw new RuntimeException("无法解析任务 ID: " + responseBody);
        } catch (Exception e) {
            log.error("解析任务 ID 失败: {}", responseBody, e);
            throw new RuntimeException("解析任务 ID 失败", e);
        }
    }

    /**
     * 解析任务状态
     */
    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode status = root.get("status");
            JsonNode progress = root.get("progress");
            JsonNode result = root.get("result");

            String statusStr = status != null ? status.asText() : "unknown";
            int progressInt = progress != null ? progress.asInt() : 0;
            String resultStr = result != null ? result.asText() : null;

            return new TaskStatus(taskId, statusStr, progressInt, resultStr, null);
        } catch (Exception e) {
            log.error("解析任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }
}
