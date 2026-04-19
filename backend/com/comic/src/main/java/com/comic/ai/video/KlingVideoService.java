package com.comic.ai.video;

import com.comic.config.KlingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
@RequiredArgsConstructor
public class KlingVideoService implements VideoGenerationService {

    private static final String OMNI_VIDEO_ENDPOINT = "/v1/videos/omni-video";

    private final KlingProperties klingProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Semaphore semaphore = new Semaphore(1);

    // ========== JWT Token 生成 ==========

    /**
     * 生成可灵 API 鉴权 JWT token
     * 格式: Header(alg, typ).Payload(iss=accessKey, exp, nbf).Signature(secretKey)
     */
    private String generateToken() {
        long now = System.currentTimeMillis();
        javax.crypto.SecretKey key = Keys.hmacShaKeyFor(
            klingProperties.getSecretKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuer(klingProperties.getAccessKey())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + 1800 * 1000L))
                .setNotBefore(new Date(now - 5000L))
                .signWith(key)
                .compact();
    }

    // ========== 单镜头视频生成（兼容旧接口，内部转调 Omni） ==========

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        return generateAsync(prompt, duration, aspectRatio, referenceImage, false, klingProperties.getModelName());
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak) {
        return generateAsync(prompt, duration, aspectRatio, referenceImage, offPeak, klingProperties.getModelName());
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak, String model) {
        // 转调 Omni API: 单图 + 单镜头
        List<String> imageUrls = Collections.singletonList(referenceImage);
        List<MultiShotPrompt> multiPrompts = Collections.singletonList(new MultiShotPrompt(prompt, duration));
        boolean soundOn = true;
        return generateOmniAsync(imageUrls, multiPrompts, duration, model, soundOn);
    }

    // ========== 多镜头视频生成（兼容旧接口，内部转调 Omni） ==========

    @Override
    public String generateAsyncMultiShot(String referenceImage, List<MultiShotPrompt> multiPrompts,
                                          int totalDuration, String model) {
        // 转调 Omni API: 单图 + 多镜头
        List<String> imageUrls = Collections.singletonList(referenceImage);
        boolean soundOn = true;
        return generateOmniAsync(imageUrls, multiPrompts, totalDuration, model, soundOn);
    }

    // ========== Omni 多图多镜头视频生成（核心方法） ==========

    @Override
    public String generateOmniAsync(List<String> imageUrls, List<MultiShotPrompt> multiPrompts,
                                     int totalDuration, String model, boolean soundOn) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            // model 格式: "kling-v3-omni-std" → model_name="kling-v3-omni", mode="std"
            String effectiveModel = (model != null && !model.isEmpty()) ? model : klingProperties.getModelName();
            String effectiveMode = klingProperties.getMode();
            if (effectiveModel.endsWith("-std")) {
                effectiveModel = effectiveModel.substring(0, effectiveModel.length() - 4);
                effectiveMode = "std";
            } else if (effectiveModel.endsWith("-pro")) {
                effectiveModel = effectiveModel.substring(0, effectiveModel.length() - 4);
                effectiveMode = "pro";
            }

            // 构建 image_list: [{"image_url": "url1"}, {"image_url": "url2"}]
            List<Map<String, Object>> imageList = new ArrayList<>();
            for (String url : imageUrls) {
                Map<String, Object> img = new HashMap<>();
                img.put("image_url", url);
                imageList.add(img);
            }

            // 构建 multi_prompt: [{"index": 1, "prompt": "...", "duration": "3"}]
            List<Map<String, Object>> multiPromptList = new ArrayList<>();
            for (int i = 0; i < multiPrompts.size(); i++) {
                MultiShotPrompt mp = multiPrompts.get(i);
                Map<String, Object> item = new HashMap<>();
                item.put("index", i + 1);
                item.put("prompt", mp.getPrompt());
                item.put("duration", String.valueOf(mp.getDuration()));
                multiPromptList.add(item);
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model_name", effectiveModel);
            requestBody.put("image_list", imageList);
            requestBody.put("multi_shot", true);
            requestBody.put("shot_type", "customize");
            requestBody.put("multi_prompt", multiPromptList);
            requestBody.put("duration", String.valueOf(totalDuration));
            requestBody.put("mode", effectiveMode);
            requestBody.put("sound", soundOn ? "on" : "off");

            log.info("Kling Omni 视频提交: images={}, shots={}, totalDuration={}, model={}, mode={}, sound={}",
                    imageUrls.size(), multiPrompts.size(), totalDuration, effectiveModel, effectiveMode, soundOn ? "on" : "off");

            return submitTask(requestBody);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kling Omni 视频生成被中断", e);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    // ========== 任务提交通用方法 ==========

    private String submitTask(Map<String, Object> requestBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Kling Omni 请求体: {}", jsonBody);

            String token = generateToken();
            Request request = new Request.Builder()
                    .url(klingProperties.getBaseUrl() + OMNI_VIDEO_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Kling Omni API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Kling Omni 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Kling Omni 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode codeNode = root.get("code");
                if (codeNode != null && codeNode.asInt() != 0) {
                    String message = root.has("message") ? root.get("message").asText() : "未知错误";
                    throw new RuntimeException("Kling Omni API 错误: code=" + codeNode.asInt() + ", message=" + message);
                }

                JsonNode dataNode = root.get("data");
                if (dataNode == null) throw new RuntimeException("Kling Omni 响应无 data 字段: " + responseBody);

                String taskId = dataNode.get("task_id").asText();
                log.info("Kling Omni 视频任务已提交: taskId={}", taskId);
                return taskId;
            }
        } catch (IOException e) {
            log.error("Kling Omni 视频生成 IO 异常", e);
            throw new RuntimeException("Kling Omni 视频生成失败: " + e.getMessage(), e);
        }
    }

    // ========== 任务查询 ==========

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            String token = generateToken();
            String url = klingProperties.getBaseUrl() + OMNI_VIDEO_ENDPOINT + "/" + taskId;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询 Kling Omni 任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("Kling Omni 任务状态响应: {}", responseBody);
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询 Kling Omni 任务状态 IO 异常: taskId={}", taskId, e);
            return new TaskStatus(taskId, "unknown", 0, null, e.getMessage());
        }
    }

    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.get("data");
            if (dataNode == null) {
                return new TaskStatus(taskId, "unknown", 0, null, "响应无 data 字段");
            }

            String taskStatus = dataNode.has("task_status") ? dataNode.get("task_status").asText() : "unknown";
            String normalized = normalizeStatus(taskStatus);

            String videoUrl = null;
            Integer credits = null;
            String errorMsg = null;

            // 视频URL
            JsonNode taskResult = dataNode.get("task_result");
            if (taskResult != null) {
                JsonNode videos = taskResult.get("videos");
                if (videos != null && videos.isArray() && videos.size() > 0) {
                    JsonNode firstVideo = videos.get(0);
                    JsonNode urlNode = firstVideo.get("url");
                    if (urlNode != null) videoUrl = urlNode.asText();
                }
            }

            // 积分消耗
            JsonNode deductionNode = dataNode.get("final_unit_deduction");
            if (deductionNode != null && !deductionNode.isNull()) {
                try { credits = Integer.parseInt(deductionNode.asText()); } catch (NumberFormatException ignored) {}
            }

            // 错误信息
            JsonNode statusMsgNode = dataNode.get("task_status_msg");
            if (statusMsgNode != null && !statusMsgNode.isNull()) {
                errorMsg = statusMsgNode.asText();
            }

            int progress = calculateProgress(taskStatus);
            return new TaskStatus(taskId, normalized, progress, videoUrl, errorMsg, null, null, credits);
        } catch (Exception e) {
            log.error("解析 Kling Omni 任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        TaskStatus status = getTaskStatus(taskId);
        if (status.isCompleted() && status.getVideoUrl() != null) return status.getVideoUrl();
        if (status.isFailed()) throw new RuntimeException("Kling Omni 视频生成失败: " + status.getErrorMessage());
        throw new RuntimeException("Kling Omni 视频尚未生成完成，当前状态: " + status.getStatus());
    }

    @Override
    public String getServiceName() { return "Kling-Omni-Video"; }

    private String normalizeStatus(String status) {
        if (status == null) return "unknown";
        switch (status) {
            case "submitted": return "pending";
            case "processing": return "processing";
            case "succeed": return "completed";
            case "failed": return "failed";
            default: return "unknown";
        }
    }

    private int calculateProgress(String status) {
        if (status == null) return 0;
        switch (status) {
            case "submitted": return 20;
            case "processing": return 50;
            case "succeed": return 100;
            case "failed": return 0;
            default: return 0;
        }
    }
}
