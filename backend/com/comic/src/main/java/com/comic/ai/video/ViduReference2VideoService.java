package com.comic.ai.video;

import com.comic.config.ViduProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Vidu Reference2Video 视频生成服务
 * 使用 Vidu API 实现多图参考视频生成（reference2video）
 *
 * API 文档：
 * - 多图参考视频生成：https://api.vidu.cn/ent/v2/reference2video
 * - 查询生成物：https://api.vidu.cn/ent/v2/tasks/{id}/creations
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ViduReference2VideoService implements VideoGenerationService {

    private static final String REFERENCE2VIDEO_ENDPOINT = "/reference2video";
    private static final String QUERY_TASK_ENDPOINT = "/tasks/%s/creations";
    private static final int MAX_IMAGES = 7;

    // Vidu 状态枚举
    private static final String STATE_CREATED = "created";
    private static final String STATE_QUEUEING = "queueing";
    private static final String STATE_PROCESSING = "processing";
    private static final String STATE_SUCCESS = "success";
    private static final String STATE_FAILED = "failed";

    private final ViduProperties viduProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制：允许5个参考图视频并发请求
    private final Semaphore semaphore = new Semaphore(5);

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        throw new UnsupportedOperationException("Reference2Video 服务不支持单图生成，请使用 generateAsyncMultiImage");
    }

    @Override
    public String generateAsyncMultiImage(String prompt, int duration, String aspectRatio,
                                           List<String> referenceImages, List<String> characterNames,
                                           boolean offPeak, String model) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            log.info("Vidu Reference2Video 生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 将简写映射为 Vidu API 实际 model 名
            String effectiveModel = (model != null && !model.isEmpty()) ? model : viduProperties.getModel();
            switch (effectiveModel) {
                case "pro":  effectiveModel = "viduq3-pro"; break;
                case "turbo": effectiveModel = "viduq3-turbo"; break;
                default: break;
            }

            // 限制最多7张图
            List<String> images = referenceImages != null ? new ArrayList<>(referenceImages) : new ArrayList<>();
            if (images.isEmpty()) {
                throw new IllegalArgumentException("参考图不能为空，至少需要1张图片");
            }
            if (images.size() > MAX_IMAGES) {
                log.warn("参考图数量 {} 超过最大限制 {}，截取前 {} 张", images.size(), MAX_IMAGES, MAX_IMAGES);
                images = images.subList(0, MAX_IMAGES);
            }

            // 构建带参考图说明的提示词
            String enhancedPrompt = buildReferenceImagePrompt(prompt, images, characterNames);

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", effectiveModel);
            requestBody.put("images", images);
            requestBody.put("prompt", enhancedPrompt);
            requestBody.put("duration", duration);
            // viduq3 支持 540p/720p/1080p，viduq3-mix 仅支持 720p/1080p
            String resolution = "viduq3-mix".equals(effectiveModel) ? "720p" : "540p";
            requestBody.put("resolution", resolution);
            requestBody.put("aspect_ratio", aspectRatio);
            requestBody.put("watermark", false);
            // viduq3-mix 时不发送 off_peak 字段（完全 omit）
            if (!"viduq3-mix".equals(effectiveModel)) {
                requestBody.put("off_peak", offPeak);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Vidu Reference2Video 请求体: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(viduProperties.getBaseUrl() + REFERENCE2VIDEO_ENDPOINT)
                    .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Vidu Reference2Video API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Vidu Reference2Video 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Vidu Reference2Video 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode taskIdNode = root.get("task_id");
                if (taskIdNode == null) {
                    throw new RuntimeException("无法解析任务 ID: " + responseBody);
                }
                String taskId = taskIdNode.asText();

                log.info("Vidu Reference2Video 视频生成任务已提交: taskId={}, model={}", taskId, effectiveModel);
                return taskId;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Vidu Reference2Video 视频生成被中断", e);
        } catch (IOException e) {
            log.error("Vidu Reference2Video 视频生成 IO 异常", e);
            throw new RuntimeException("Vidu Reference2Video 视频生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Vidu Reference2Video 视频生成异常", e);
            throw new RuntimeException("Vidu Reference2Video 视频生成失败: " + e.getMessage(), e);
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
        return "Vidu-Reference2Video";
    }

    /**
     * 构建带参考图说明的提示词
     * 分镜图用编号描述，角色图用真实名字描述
     */
    private String buildReferenceImagePrompt(String prompt, List<String> images, List<String> characterNames) {
        if (images == null || images.isEmpty()) {
            return prompt;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prompt).append("\n\n参考图片说明：");

        // 角色图数量 = characterNames 的大小
        int storyboardCount = images.size() - (characterNames != null ? characterNames.size() : 0);

        int storyboardIdx = 1;
        int characterIdx = 0;
        for (int i = 0; i < images.size(); i++) {
            if (i < storyboardCount) {
                // 分镜图用编号
                sb.append("\n- 图片").append(i + 1).append("（分镜图").append(storyboardIdx).append("）");
                storyboardIdx++;
            } else {
                // 角色图用真实名字
                String characterName = (characterNames != null && characterIdx < characterNames.size())
                        ? characterNames.get(characterIdx)
                        : "角色" + (characterIdx + 1);
                sb.append("\n- 图片").append(i + 1).append("（角色：").append(characterName).append("）");
                characterIdx++;
            }
        }

        return sb.toString();
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

            // 解析实际进度（API 返回 0-100 的浮点数）
            int progress = calculateProgress(state);  // 默认使用状态计算
            JsonNode progressNode = root.get("progress");
            if (progressNode != null && !progressNode.isNull()) {
                progress = (int) Math.round(progressNode.asDouble());
            }

            // 解析积分消耗
            Integer credits = null;
            JsonNode creditsNode = root.get("credits");
            if (creditsNode != null && !creditsNode.isNull()) {
                credits = creditsNode.asInt();
            }

            return new TaskStatus(taskId, normalizedStatus, progress, videoUrl, errorMessage, null, null, credits);
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
