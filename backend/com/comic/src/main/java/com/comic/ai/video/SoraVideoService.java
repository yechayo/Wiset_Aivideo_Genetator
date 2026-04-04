package com.comic.ai.video;

import com.comic.config.WuyinkejiProperties;
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
 * Sora2 视频生成服务
 * 使用无因科技 Sora2 API 实现图生视频
 *
 * API 文档：
 * - 提交任务：POST https://api.wuyinkeji.com/api/async/video_sora2
 * - 查询任务：GET  https://csapi.wuyinkeji.com/api/sora2/detail?id=<taskId>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SoraVideoService implements VideoGenerationService {

    private static final String SUBMIT_ENDPOINT = "/api/async/video_sora2";
    private static final String QUERY_ENDPOINT = "/api/sora2/detail";

    private final WuyinkejiProperties wuyinkejiProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(1);

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            log.info("Sora2 视频生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 默认值处理
            String safeAspectRatio = (aspectRatio != null && !aspectRatio.trim().isEmpty()) ? aspectRatio : "16:9";
            String safeDuration = (duration > 0) ? String.valueOf(duration) : "10";

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("prompt", prompt);
            requestBody.put("aspectRatio", safeAspectRatio);
            requestBody.put("duration", safeDuration);
            requestBody.put("size", "small");

            // 参考图（可选）
            if (referenceImage != null && !referenceImage.trim().isEmpty()) {
                requestBody.put("url", referenceImage);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Sora2 请求体: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(wuyinkejiProperties.getBaseUrl() + SUBMIT_ENDPOINT)
                    .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Sora2 API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Sora2 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Sora2 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                int code = root.has("code") ? root.get("code").asInt() : -1;
                if (code != 200) {
                    String msg = root.has("msg") ? root.get("msg").asText() : responseBody;
                    log.error("Sora2 API 返回错误: code={}, msg={}", code, msg);
                    throw new RuntimeException("Sora2 视频生成失败: " + msg);
                }

                JsonNode dataNode = root.get("data");
                if (dataNode == null || !dataNode.has("id")) {
                    throw new RuntimeException("无法解析任务 ID: " + responseBody);
                }

                String taskId = dataNode.get("id").asText();
                log.info("Sora2 视频生成任务已提交: taskId={}", taskId);
                return taskId;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sora2 视频生成被中断", e);
        } catch (IOException e) {
            log.error("Sora2 视频生成 IO 异常", e);
            throw new RuntimeException("Sora2 视频生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Sora2 视频生成异常", e);
            throw new RuntimeException("Sora2 视频生成失败: " + e.getMessage(), e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak) {
        // Sora2 不支持错峰模式，忽略 offPeak 参数
        return generateAsync(prompt, duration, aspectRatio, referenceImage);
    }

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            String url = wuyinkejiProperties.getSoraQueryBaseUrl() + QUERY_ENDPOINT + "?key=" + wuyinkejiProperties.getApiKey() + "&id=" + taskId;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询 Sora2 任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("Sora2 任务状态响应: {}", responseBody);
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询 Sora2 任务状态 IO 异常: taskId={}", taskId, e);
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
        return "Sora-Video";
    }

    /**
     * 解析 Sora2 任务状态
     * <p>
     * API 返回状态码：
     * 0 = 排队中 (queuing)
     * 1 = 生成成功 (success)
     * 2 = 生成失败 (failed)
     * 3 = 生成中 (generating)
     */
    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.has("code") ? root.get("code").asInt() : -1;
            if (code != 200) {
                String msg = root.has("msg") ? root.get("msg").asText() : "未知错误";
                log.warn("Sora2 查询返回非 200: code={}, msg={}, taskId={}", code, msg, taskId);
                return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + msg);
            }

            JsonNode dataNode = root.get("data");
            if (dataNode == null || dataNode.isNull()) {
                return new TaskStatus(taskId, "unknown", 0, null, "响应 data 为空");
            }

            // 解析状态码
            int status = dataNode.has("status") ? dataNode.get("status").asInt() : -1;
            String normalizedStatus;
            int progress;
            String videoUrl = null;
            String errorMessage = null;

            switch (status) {
                case 0: // 排队中
                    normalizedStatus = "pending";
                    progress = 10;
                    break;
                case 3: // 生成中
                    normalizedStatus = "processing";
                    progress = 50;
                    break;
                case 1: // 生成成功
                    normalizedStatus = "completed";
                    progress = 100;
                    videoUrl = extractResultUrl(dataNode);
                    if (videoUrl == null) {
                        normalizedStatus = "failed";
                        errorMessage = "任务成功但无视频URL";
                    }
                    break;
                case 2: {
                    // 可能是成功（result 数组有视频）也可能是失败
                    String url = extractResultUrl(dataNode);
                    if (url != null) {
                        normalizedStatus = "completed";
                        progress = 100;
                        videoUrl = url;
                    } else {
                        normalizedStatus = "failed";
                        progress = 0;
                        if (dataNode.has("fail_reason") && !dataNode.get("fail_reason").isNull()) {
                            errorMessage = dataNode.get("fail_reason").asText();
                        }
                        if (errorMessage == null && dataNode.has("message") && !dataNode.get("message").isNull()) {
                            errorMessage = dataNode.get("message").asText();
                        }
                    }
                    break;
                }
                default:
                    normalizedStatus = "unknown";
                    progress = 0;
                    break;
            }

            return new TaskStatus(taskId, normalizedStatus, progress, videoUrl, errorMessage);
        } catch (Exception e) {
            log.error("解析 Sora2 任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    /**
     * 从 API 响应中提取结果 URL
     * 优先取 remote_url，其次取 result 数组第一个元素
     */
    private String extractResultUrl(JsonNode dataNode) {
        if (dataNode.has("remote_url") && !dataNode.get("remote_url").isNull()) {
            String url = dataNode.get("remote_url").asText();
            if (!url.isEmpty()) return url;
        }
        if (dataNode.has("result") && dataNode.get("result").isArray()) {
            JsonNode result = dataNode.get("result");
            if (result.size() > 0 && !result.get(0).isNull()) {
                String url = result.get(0).asText();
                if (!url.isEmpty()) return url;
            }
        }
        return null;
    }
}