package com.comic.ai.video;

import com.comic.config.ViduProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Vidu 视频生成服务
 * 使用 Vidu API 实现图生视频
 *
 * API 文档：
 * - 图生视频：https://api.vidu.cn/ent/v2/img2video
 * - 查询生成物：https://api.vidu.cn/ent/v2/tasks/{id}/creations
 * - 取消任务：https://api.vidu.cn/ent/v2/tasks/{id}/cancel
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ViduVideoService implements VideoGenerationService {

    private static final String IMG2VIDEO_ENDPOINT = "/img2video";
    private static final String QUERY_TASK_ENDPOINT = "/tasks/%s/creations";
    private static final String CANCEL_TASK_ENDPOINT = "/tasks/%s/cancel";

    // Vidu 状态枚举
    private static final String STATE_CREATED = "created";
    private static final String STATE_QUEUEING = "queueing";
    private static final String STATE_PROCESSING = "processing";
    private static final String STATE_SUCCESS = "success";
    private static final String STATE_FAILED = "failed";

    private final ViduProperties viduProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(1);

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        return generateAsync(prompt, duration, aspectRatio, referenceImage, viduProperties.isOffPeak());
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            log.info("Vidu 视频生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", viduProperties.getModel());
            requestBody.put("images", Collections.singletonList(referenceImage));
            requestBody.put("prompt", prompt);
            requestBody.put("duration", duration);
            requestBody.put("watermark", false);
            requestBody.put("off_peak", offPeak);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Vidu 请求体: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(viduProperties.getBaseUrl() + IMG2VIDEO_ENDPOINT)
                    .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Vidu API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Vidu 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Vidu 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode taskIdNode = root.get("task_id");
                if (taskIdNode == null) {
                    throw new RuntimeException("无法解析任务 ID: " + responseBody);
                }
                String taskId = taskIdNode.asText();

                log.info("Vidu 视频生成任务已提交: taskId={}, model={}", taskId, viduProperties.getModel());
                return taskId;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Vidu 视频生成被中断", e);
        } catch (IOException e) {
            log.error("Vidu 视频生成 IO 异常", e);
            throw new RuntimeException("Vidu 视频生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Vidu 视频生成异常", e);
            throw new RuntimeException("Vidu 视频生成失败: " + e.getMessage(), e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            String url = viduProperties.getBaseUrl() + String.format(QUERY_TASK_ENDPOINT, taskId);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询 Vidu 任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("Vidu 任务状态响应: {}", responseBody);
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询 Vidu 任务状态 IO 异常: taskId={}", taskId, e);
            return new TaskStatus(taskId, "unknown", 0, null, e.getMessage());
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        TaskStatus status = getTaskStatus(taskId);
        if (status.isCompleted() && status.getVideoUrl() != null) {
            return status.getVideoUrl();
        }
        if (status.isFailed()) {
            throw new RuntimeException("视频生成失败: " + status.getErrorMessage());
        }
        throw new RuntimeException("视频尚未生成完成，当前状态: " + status.getStatus());
    }

    @Override
    public String getServiceName() {
        return "Vidu-Video";
    }

    /**
     * 取消任务
     */
    public boolean cancelTask(String taskId) {
        try {
            String url = viduProperties.getBaseUrl() + String.format(CANCEL_TASK_ENDPOINT, taskId);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("id", taskId);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("Vidu 任务已取消: taskId={}", taskId);
                    return true;
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("取消 Vidu 任务失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return false;
                }
            }
        } catch (IOException e) {
            log.error("取消 Vidu 任务 IO 异常: taskId={}", taskId, e);
            return false;
        }
    }

    /**
     * 解析任务状态
     */
    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析状态
            JsonNode stateNode = root.get("state");
            String state = stateNode != null ? stateNode.asText() : "unknown";
            String normalizedStatus = normalizeStatus(state);

            // 解析错误码
            JsonNode errCodeNode = root.get("err_code");
            String errorMessage = null;
            if (errCodeNode != null && !errCodeNode.isNull() && errCodeNode.asText().length() > 0) {
                errorMessage = "err_code: " + errCodeNode.asText();
            }

            // 解析 creations 获取视频 URL
            String videoUrl = null;
            JsonNode creationsNode = root.get("creations");
            if (creationsNode != null && creationsNode.isArray() && creationsNode.size() > 0) {
                JsonNode firstCreation = creationsNode.get(0);
                JsonNode urlNode = firstCreation.get("url");
                if (urlNode != null) {
                    videoUrl = urlNode.asText();
                }
            }

            int progress = calculateProgress(state);
            return new TaskStatus(taskId, normalizedStatus, progress, videoUrl, errorMessage);
        } catch (Exception e) {
            log.error("解析 Vidu 任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    private String normalizeStatus(String state) {
        if (state == null) return "unknown";
        switch (state) {
            case STATE_CREATED:
            case STATE_QUEUEING:
                return "pending";
            case STATE_PROCESSING:
                return "processing";
            case STATE_SUCCESS:
                return "completed";
            case STATE_FAILED:
                return "failed";
            default:
                return "unknown";
        }
    }

    private int calculateProgress(String state) {
        if (state == null) return 0;
        switch (state) {
            case STATE_CREATED:
                return 10;
            case STATE_QUEUEING:
                return 20;
            case STATE_PROCESSING:
                return 50;
            case STATE_SUCCESS:
                return 100;
            case STATE_FAILED:
                return 0;
            default:
                return 0;
        }
    }
}
