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
 *
 * API 文档：https://www.volcengine.com/docs/82379/1520758
 *
 * 支持的功能：
 * - 文生视频
 * - 图生视频（首帧）
 * - 图生视频（首尾帧）
 * - 有声视频生成（Seedance 1.5 pro）
 * - Draft 样片模式
 * - 任务状态查询
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeedanceVideoService implements VideoGenerationService {

    private static final String GENERATE_TASKS_ENDPOINT = "/contents/generations/tasks";
    private static final String QUERY_TASK_ENDPOINT = "/contents/generations/tasks/";
    private static final String LIST_TASKS_ENDPOINT = "/contents/generations/tasks";
    private static final String DELETE_TASK_ENDPOINT = "/contents/generations/tasks/";

    // 任务状态枚举
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";

    private final ArkProperties arkProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(1);

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        return generateVideo(prompt, duration, aspectRatio, referenceImage, null, false, false);
    }

    /**
     * 创建视频生成任务（完整参数版本）
     *
     * @param prompt         文本提示词
     * @param duration       视频时长（秒），支持 -1 表示由模型自动选择
     * @param aspectRatio    宽高比（16:9, 9:16, 1:1, 4:3, 3:4, 21:9, adaptive）
     * @param firstFrameUrl  首帧图片 URL（可选）
     * @param lastFrameUrl   尾帧图片 URL（可选）
     * @param generateAudio  是否生成音频（仅 Seedance 1.5 pro 支持）
     * @param isDraft        是否生成样片（仅 Seedance 1.5 pro 支持）
     * @return 任务 ID
     */
    public String generateVideo(String prompt, Integer duration, String aspectRatio,
                                String firstFrameUrl, String lastFrameUrl,
                                boolean generateAudio, boolean isDraft) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            log.info("Seedance 视频生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 构建内容列表
            List<Map<String, Object>> contents = new ArrayList<>();

            // 添加文本内容
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt);
            contents.add(textContent);

            // 添加首帧图片（图生视频 - 首帧）
            if (firstFrameUrl != null && !firstFrameUrl.isEmpty()) {
                Map<String, Object> imageContent = new HashMap<>();
                imageContent.put("type", "image_url");

                Map<String, String> imageUrl = new HashMap<>();
                imageUrl.put("url", firstFrameUrl);
                imageContent.put("image_url", imageUrl);

                // 首尾帧模式需要指定 role
                if (lastFrameUrl != null && !lastFrameUrl.isEmpty()) {
                    imageContent.put("role", "first_frame");
                }
                contents.add(imageContent);
            }

            // 添加尾帧图片（图生视频 - 首尾帧）
            if (lastFrameUrl != null && !lastFrameUrl.isEmpty()) {
                Map<String, Object> imageContent = new HashMap<>();
                imageContent.put("type", "image_url");

                Map<String, String> imageUrl = new HashMap<>();
                imageUrl.put("url", lastFrameUrl);
                imageContent.put("image_url", imageUrl);
                imageContent.put("role", "last_frame");

                contents.add(imageContent);
            }

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkProperties.getSeedanceModel());
            requestBody.put("content", contents);

            // 添加参数（使用新方式，在 request body 中直接传入参数）
            if (aspectRatio != null && !aspectRatio.isEmpty()) {
                requestBody.put("ratio", aspectRatio);
            }
            if (duration != null) {
                requestBody.put("duration", duration);
            }
            requestBody.put("watermark", false);
            requestBody.put("camera_fixed", false);

            // Seedance 1.5 pro 特性
            if (arkProperties.getSeedanceModel().contains("1-5-pro")) {
                requestBody.put("generate_audio", generateAudio);
                if (isDraft) {
                    requestBody.put("draft", true);
                    requestBody.put("resolution", "480p");  // Draft 模式必须使用 480p
                } else {
                    requestBody.put("resolution", "720p");
                }
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Seedance 请求体: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + GENERATE_TASKS_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Seedance API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Seedance 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Seedance 响应: {}", responseBody);
                String taskId = parseTaskId(responseBody);

                log.info("Seedance 视频生成任务已提交: taskId={}, model={}", taskId, arkProperties.getSeedanceModel());
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
            if (acquired) {
                semaphore.release();
            }
        }
    }

    /**
     * 基于样片任务 ID 生成正式视频
     *
     * @param draftTaskId 样片任务 ID
     * @return 正式视频任务 ID
     */
    public String generateFromDraft(String draftTaskId) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> draftContent = new HashMap<>();
            draftContent.put("type", "draft_task");

            Map<String, String> draftTask = new HashMap<>();
            draftTask.put("id", draftTaskId);
            draftContent.put("draft_task", draftTask);

            contents.add(draftContent);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkProperties.getSeedanceModel());
            requestBody.put("content", contents);
            requestBody.put("watermark", false);
            requestBody.put("resolution", "720p");

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + GENERATE_TASKS_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("基于样片生成视频失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("基于样片生成视频失败: " + response.code());
                }

                String responseBody = response.body().string();
                String taskId = parseTaskId(responseBody);

                log.info("基于样片生成正式视频任务已提交: taskId={}, draftTaskId={}", taskId, draftTaskId);
                return taskId;
            }

        } catch (Exception e) {
            log.error("基于样片生成视频异常", e);
            throw new RuntimeException("基于样片生成视频失败: " + e.getMessage(), e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + QUERY_TASK_ENDPOINT + taskId)
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("任务状态响应: {}", responseBody);
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询任务状态 IO 异常: taskId={}", taskId, e);
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
     *
     * 官方 API 响应格式：
     * {
     *   "id": "cgt-xxx",
     *   "model": "doubao-seedance-1-5-pro-251215",
     *   "status": "succeeded",
     *   "content": {
     *     "video_url": "https://...",
     *     "last_frame_url": "https://..."
     *   },
     *   "resolution": "720p",
     *   "ratio": "16:9",
     *   "duration": 5,
     *   "generate_audio": true,
     *   "draft": false,
     *   "usage": {
     *     "completion_tokens": 1000,
     *     "total_tokens": 1000
     *   },
     *   "error": null
     * }
     */
    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析状态
            JsonNode statusNode = root.get("status");
            String statusStr = statusNode != null ? statusNode.asText() : "unknown";

            // 转换状态到统一格式
            String normalizedStatus = normalizeStatus(statusStr);

            // 解析 content
            JsonNode contentNode = root.get("content");
            String videoUrl = null;
            String lastFrameUrl = null;

            if (contentNode != null) {
                JsonNode videoUrlNode = contentNode.get("video_url");
                if (videoUrlNode != null) {
                    videoUrl = videoUrlNode.asText();
                }

                JsonNode lastFrameUrlNode = contentNode.get("last_frame_url");
                if (lastFrameUrlNode != null) {
                    lastFrameUrl = lastFrameUrlNode.asText();
                }
            }

            // 解析错误信息
            JsonNode errorNode = root.get("error");
            String errorMessage = null;
            if (errorNode != null && !errorNode.isNull()) {
                JsonNode messageNode = errorNode.get("message");
                errorMessage = messageNode != null ? messageNode.asText() : errorNode.asText();
            }

            // 解析其他信息
            JsonNode durationNode = root.get("duration");
            Integer duration = durationNode != null ? durationNode.asInt() : null;

            JsonNode resolutionNode = root.get("resolution");
            String resolution = resolutionNode != null ? resolutionNode.asText() : null;

            // 计算进度
            int progress = calculateProgress(statusStr);

            return new TaskStatus(taskId, normalizedStatus, progress, videoUrl, errorMessage,
                    lastFrameUrl, createMetadata(resolution, duration));
        } catch (Exception e) {
            log.error("解析任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    /**
     * 标准化状态值
     */
    private String normalizeStatus(String status) {
        if (status == null) {
            return "unknown";
        }
        switch (status) {
            case STATUS_QUEUED:
                return "pending";
            case STATUS_RUNNING:
                return "processing";
            case STATUS_SUCCEEDED:
                return "completed";
            case STATUS_FAILED:
            case STATUS_EXPIRED:
                return "failed";
            case STATUS_CANCELLED:
                return "cancelled";
            default:
                return "unknown";
        }
    }

    /**
     * 根据状态计算进度
     */
    private int calculateProgress(String status) {
        if (status == null) {
            return 0;
        }
        switch (status) {
            case STATUS_QUEUED:
                return 10;
            case STATUS_RUNNING:
                return 50;
            case STATUS_SUCCEEDED:
                return 100;
            case STATUS_FAILED:
            case STATUS_EXPIRED:
            case STATUS_CANCELLED:
                return 0;
            default:
                return 0;
        }
    }

    /**
     * 创建元数据字符串
     */
    private String createMetadata(String resolution, Integer duration) {
        StringBuilder sb = new StringBuilder();
        if (resolution != null) {
            sb.append("resolution=").append(resolution);
        }
        if (duration != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("duration=").append(duration).append("s");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 等待任务完成（阻塞方法）
     *
     * @param taskId 任务 ID
     * @param timeoutSeconds 超时时间（秒）
     * @return 任务状态
     */
    public TaskStatus waitForCompletion(String taskId, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (true) {
            TaskStatus status = getTaskStatus(taskId);

            if (status.isCompleted()) {
                log.info("视频生成完成: taskId={}", taskId);
                return status;
            }

            if (status.isFailed()) {
                log.error("视频生成失败: taskId={}, error={}", taskId, status.getErrorMessage());
                return status;
            }

            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                log.warn("等待视频生成超时: taskId={}, timeout={}s", taskId, timeoutSeconds);
                return status;
            }

            try {
                Thread.sleep(5000); // 每 5 秒查询一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待视频生成被中断: taskId={}", taskId);
                return status;
            }
        }
    }

    /**
     * 取消或删除任务
     *
     * @param taskId 任务 ID
     * @return 是否成功
     */
    public boolean cancelOrDeleteTask(String taskId) {
        try {
            Request request = new Request.Builder()
                    .url(arkProperties.getBaseUrl() + DELETE_TASK_ENDPOINT + taskId)
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .delete()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("任务已取消/删除: taskId={}", taskId);
                    return true;
                } else {
                    log.warn("取消/删除任务失败: code={}, taskId={}", response.code(), taskId);
                    return false;
                }
            }
        } catch (IOException e) {
            log.error("取消/删除任务 IO 异常: taskId={}", taskId, e);
            return false;
        }
    }

    /**
     * 批量查询任务状态
     *
     * @param taskIds 任务 ID 列表
     * @return 任务状态列表
     */
    public List<TaskStatus> batchGetTaskStatus(List<String> taskIds) {
        List<TaskStatus> result = new ArrayList<>();
        for (String taskId : taskIds) {
            result.add(getTaskStatus(taskId));
        }
        return result;
    }

    /**
     * 列出指定条件的任务
     *
     * @param status 任务状态过滤（可选）
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 任务列表
     */
    public List<Map<String, Object>> listTasks(String status, int pageNum, int pageSize) {
        try {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(arkProperties.getBaseUrl() + LIST_TASKS_ENDPOINT).newBuilder()
                    .addQueryParameter("page_num", String.valueOf(pageNum))
                    .addQueryParameter("page_size", String.valueOf(pageSize));

            if (status != null && !status.isEmpty()) {
                urlBuilder.addQueryParameter("filter.status", status);
            }

            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .addHeader("Authorization", "Bearer " + arkProperties.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("列出任务失败: code={}", response.code());
                    return new ArrayList<>();
                }

                String responseBody = response.body().string();
                return parseTaskList(responseBody);
            }
        } catch (IOException e) {
            log.error("列出任务 IO 异常", e);
            return new ArrayList<>();
        }
    }

    /**
     * 解析任务列表
     */
    private List<Map<String, Object>> parseTaskList(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode itemsNode = root.get("items");

            if (itemsNode == null || !itemsNode.isArray()) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode item : itemsNode) {
                Map<String, Object> taskMap = new HashMap<>();
                taskMap.put("id", item.get("id").asText());
                taskMap.put("status", item.get("status").asText());
                taskMap.put("model", item.get("model").asText());
                taskMap.put("created_at", item.get("created_at").asLong());

                // 解析 content
                JsonNode contentNode = item.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    JsonNode videoUrlNode = contentNode.get("video_url");
                    if (videoUrlNode != null) {
                        taskMap.put("video_url", videoUrlNode.asText());
                    }
                }

                result.add(taskMap);
            }

            return result;
        } catch (Exception e) {
            log.error("解析任务列表失败: {}", responseBody, e);
            return new ArrayList<>();
        }
    }
}