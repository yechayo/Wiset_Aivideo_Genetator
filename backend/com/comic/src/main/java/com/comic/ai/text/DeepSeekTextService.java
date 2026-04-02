package com.comic.ai.text;

import com.comic.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class DeepSeekTextService implements TextGenerationService {

    @Value("${comic.deepseek.api-key}")
    private String apiKey;

    @Value("${comic.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${comic.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${comic.deepseek.max-tokens:16384}")
    private int maxTokens;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Semaphore semaphore = new Semaphore(5);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 3000;

    public DeepSeekTextService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        try {
            semaphore.acquire();
            log.info("DeepSeek text generation: permits={}, queue={}",
                    semaphore.availablePermits(), semaphore.getQueueLength());

            Map<String, Object> requestBody = buildRequestBody(systemPrompt, userPrompt);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "empty body";
                        log.error("DeepSeek API failed: {} - {}", response.code(), errorBody);
                        throw new RuntimeException("DeepSeek text generation failed: " + response.code());
                    }

                    String responseBody = response.body() != null ? response.body().string() : "";
                    String content = parseResponse(responseBody);
                    log.info("DeepSeek text generation complete: {}",
                            content.substring(0, Math.min(100, content.length())));
                    return content;
                } catch (IOException e) {
                    lastException = e;
                    log.warn("DeepSeek connection issue on attempt {}/{}: {}",
                            attempt, MAX_RETRIES, e.getMessage());
                    if (attempt < MAX_RETRIES) {
                        sleepBeforeRetry(attempt);
                    }
                } catch (RuntimeException e) {
                    lastException = e;
                    if (attempt < MAX_RETRIES) {
                        log.warn("DeepSeek request failed on attempt {}/{}: {}",
                                attempt, MAX_RETRIES, e.getMessage());
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    throw e;
                }
            }

            throw new RuntimeException("DeepSeek text generation failed after retries: "
                    + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DeepSeek text generation interrupted", e);
        } catch (IOException e) {
            log.error("DeepSeek text generation IO error", e);
            throw new RuntimeException("DeepSeek text generation failed: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public String generateStream(String systemPrompt, String userPrompt) {
        try {
            semaphore.acquire();
            log.info("DeepSeek stream generation: permits={}, queue={}",
                    semaphore.availablePermits(), semaphore.getQueueLength());

            Map<String, Object> requestBody = buildRequestBody(systemPrompt, userPrompt);
            requestBody.put("stream", true);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "empty body";
                        log.error("DeepSeek stream API failed: {} - {}", response.code(), errorBody);
                        throw new RuntimeException("DeepSeek stream failed: " + response.code());
                    }

                    StringBuilder contentBuilder = new StringBuilder();
                    StringBuilder reasoningBuilder = new StringBuilder();
                    String finishReason = null;

                    if (response.body() != null) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data: ")) continue;
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) break;

                            try {
                                JsonNode chunk = objectMapper.readTree(data);
                                JsonNode choices = chunk.path("choices");
                                if (choices.isArray() && choices.size() > 0) {
                                    JsonNode delta = choices.get(0).path("delta");
                                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                                        reasoningBuilder.append(delta.get("reasoning_content").asText());
                                    }
                                    if (delta.has("content") && !delta.get("content").isNull()) {
                                        contentBuilder.append(delta.get("content").asText());
                                    }
                                    if (choices.get(0).hasNonNull("finish_reason")) {
                                        finishReason = choices.get(0).get("finish_reason").asText();
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Failed to parse SSE chunk: {}", data);
                            }
                        }
                    }

                    String content = contentBuilder.toString();
                    if (content.trim().isEmpty() && reasoningBuilder.length() > 0) {
                        log.info("DeepSeek stream: empty content, falling back to reasoning_content");
                        content = reasoningBuilder.toString();
                    }
                    if (content.trim().isEmpty()) {
                        throw new RuntimeException("DeepSeek stream returned empty content");
                    }
                    if (isTruncatedResponse(finishReason, content)) {
                        log.warn("DeepSeek stream output truncated (finish_reason=length)");
                    }

                    log.info("DeepSeek stream complete: {}",
                            content.substring(0, Math.min(100, content.length())));
                    return content;

                } catch (IOException e) {
                    lastException = e;
                    log.warn("DeepSeek stream IO issue on attempt {}/{}: {}",
                            attempt, MAX_RETRIES, e.getMessage());
                    if (attempt < MAX_RETRIES) {
                        sleepBeforeRetry(attempt);
                    }
                } catch (RuntimeException e) {
                    lastException = e;
                    if (attempt < MAX_RETRIES) {
                        log.warn("DeepSeek stream failed on attempt {}/{}: {}",
                                attempt, MAX_RETRIES, e.getMessage());
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    throw e;
                }
            }

            throw new RuntimeException("DeepSeek stream failed after retries: "
                    + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DeepSeek stream interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("DeepSeek stream failed: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public String getServiceName() {
        return "DeepSeek-Text";
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return semaphore.availablePermits();
    }

    private String parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new RuntimeException("Unable to parse DeepSeek response: " + responseBody);
            }

            JsonNode firstChoice = choices.get(0);
            String finishReason = firstChoice.hasNonNull("finish_reason")
                    ? firstChoice.get("finish_reason").asText()
                    : null;
            JsonNode message = firstChoice.path("message");
            if (!message.isObject()) {
                throw new RuntimeException("Unable to parse DeepSeek response: " + responseBody);
            }

            JsonNode reasoningContent = message.get("reasoning_content");
            String reasoning = null;
            if (reasoningContent != null && !reasoningContent.isNull()) {
                reasoning = reasoningContent.asText();
                log.debug("DeepSeek reasoning: {}", reasoning);
            }

            JsonNode content = message.get("content");
            if (content != null && !content.isNull()) {
                String contentText = content.asText();
                if (isTruncatedResponse(finishReason, contentText)) {
                    log.warn("DeepSeek output truncated (finish_reason=length), attempting repair");
                    return contentText;
                }
                if (!contentText.trim().isEmpty()) {
                    return contentText;
                }
            }

            if (reasoning != null && !reasoning.trim().isEmpty()) {
                log.info("DeepSeek returned empty content, falling back to reasoning_content");
                return reasoning;
            }

            throw new RuntimeException("Unable to parse DeepSeek response: " + responseBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse DeepSeek response: {}", responseBody, e);
            throw new RuntimeException("Failed to parse DeepSeek response", e);
        }
    }

    private boolean isTruncatedResponse(String finishReason, String contentText) {
        if (!"length".equalsIgnoreCase(finishReason)) {
            return false;
        }
        String trimmedContent = contentText == null ? "" : contentText.trim();
        // 检测 JSON 对象或数组是否被截断
        boolean isJsonObject = trimmedContent.startsWith("{");
        boolean isJsonArray = trimmedContent.startsWith("[");
        if (isJsonObject) return !trimmedContent.endsWith("}");
        if (isJsonArray) return !trimmedContent.endsWith("]");
        return false;
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        requestBody.put("messages", messages);
        requestBody.put("max_tokens", maxTokens);

        return requestBody;
    }

    private void sleepBeforeRetry(int attempt) throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MS * attempt);
    }

    /**
     * 生成结构化分集剧本（JSON 格式）
     */
    public List<Map<String, Object>> generateEpisodeScript(
            String outlineNode, String characters, int durationSeconds, String visualStyle, int totalEpisodes) {
        String systemPrompt = "你是一位专业的影视编剧。请根据提供的大纲和角色信息，生成结构化分集剧本。\n"
            + "输出格式为纯 JSON 数组，不要包含 markdown 代码块标记。\n"
            + "每个元素包含以下字段：\n"
            + "- title: 集标题\n"
            + "- content: 剧本正文内容（约" + (durationSeconds / 60) + "分钟对应的字数，中文约200-250字/分钟）\n"
            + "- characters: 本集出场角色，逗号分隔\n"
            + "- keyItems: 本集关键道具/场景，逗号分隔\n"
            + "- continuityNote: 连贯性备注\n"
            + "注意：内容要紧凑，适合" + durationSeconds + "秒的短视频。\n"
            + "**重要约束**：必须生成恰好 " + totalEpisodes + " 集剧本，不要多也不要少！\n"
            + "**角色名称约束**：characters 字段中的角色名必须与提供的角色描述中【】内的名称完全一致，"
            + "禁止使用昵称、简称、别名或任何变体。";

        String userPrompt = "大纲节点：" + outlineNode + "\n"
            + "角色：" + characters + "\n"
            + "视觉风格：" + visualStyle + "\n"
            + "目标时长：" + durationSeconds + "秒\n"
            + "需要生成的集数：" + totalEpisodes + " 集\n"
            + "请生成恰好 " + totalEpisodes + " 集结构化分集剧本 JSON。";

        String response = generateStream(systemPrompt, userPrompt);
        return parseJsonArray(response);
    }

    /**
     * 生成分镜脚本（DetailedStoryboardShot 数组）
     */
    public List<Map<String, Object>> generateStoryboard(
            String episodeContent, String characters, int totalDuration, String visualStyle) {
        int recommendedShots = Math.max(1, totalDuration / 4);
        int minShots = Math.max(1, (int) Math.ceil((double) totalDuration / 6));
        int maxShots = Math.max(minShots, totalDuration / 3);

        String systemPrompt = "你是一位专业的影视分镜师。请根据提供的剧本内容，生成详细的分镜脚本。\n"
            + "关键约束：\n"
            + "- 每个分镜时长：2-4秒\n"
            + "- 所有分镜时长总和必须尽量接近 " + totalDuration + "秒，不超过 " + totalDuration + "秒\n"
            + "- 分镜数量范围：" + minShots + " ~ " + maxShots + " 个（推荐：" + recommendedShots + "个）\n"
            + "- 输出纯 JSON 数组，不要包含 markdown 代码块标记\n\n"
            + "每个分镜包含以下字段：\n"
            + "- shotNumber: 镜头编号（从1开始）\n"
            + "- duration: 时长（秒，2-4）\n"
            + "- scene: 场景描述\n"
            + "- characters: 出场角色数组\n"
            + "- shotSize: 景别（大远景/远景/全景/中景/中近景/近景/特写/大特写）\n"
            + "- cameraAngle: 角度（视平/高位俯拍/低位仰拍/斜拍/越肩/鸟瞰）\n"
            + "- cameraMovement: 运镜（固定/横移/俯仰/横摇/升降/轨道推拉/变焦推拉/正跟随/倒跟随/环绕/滑轨横移）\n"
            + "- visualDescription: 画面描述\n"
            + "- dialogue: 对白（无则填\"无\"）\n"
            + "- speaker: 说话人角色名（无对白则填\"无\"，有对白时必须是 characters 数组中的角色之一）\n"
            + "- visualEffects: 视觉特效（无则填\"无\"）\n"
            + "- audioEffects: 音效（无则填\"无\"）\n\n"
            + "重要：dialogue 与 speaker 必须严格对应。如果 dialogue 不为\"无\"，则 speaker 必须是 characters 数组中的某个角色名，表示该角色正在说这句台词。\n\n"
            + "**角色名称约束**：characters 数组中的每个角色名必须与提供的角色描述中【】内的名称完全一致，"
            + "禁止使用昵称、简称、别名或任何变体。例如角色描述为【墨尘（幻影）】，则必须写\"墨尘（幻影）\"，不能写\"墨尘\"或\"幻影\"。";

        String userPrompt = "剧本内容：\n" + episodeContent + "\n\n"
            + "角色：" + characters + "\n"
            + "视觉风格：" + visualStyle + "\n"
            + "目标总时长：" + totalDuration + "秒\n\n"
            + "请生成详细的分镜脚本 JSON 数组。";

        String response = generateStream(systemPrompt, userPrompt);
        List<Map<String, Object>> shots = parseJsonArray(response);

        if (shots == null || shots.isEmpty()) {
            throw new BusinessException("分镜生成结果为空，请重试");
        }

        // 钳制时长到 2-4 秒，并自动计算 startTime/endTime
        int currentTime = 0;
        for (Map<String, Object> shot : shots) {
            int duration = ((Number) shot.get("duration")).intValue();
            duration = Math.max(2, Math.min(4, duration));
            shot.put("duration", duration);
            shot.put("startTime", currentTime);
            currentTime += duration;
            shot.put("endTime", currentTime);
        }

        // 如果总时长超出目标，从末尾移除多余分镜
        while (currentTime > totalDuration && !shots.isEmpty()) {
            Map<String, Object> removed = shots.remove(shots.size() - 1);
            currentTime -= ((Number) removed.get("duration")).intValue();
        }
        // 修正最后一个分镜的 endTime
        if (!shots.isEmpty()) {
            Map<String, Object> lastShot = shots.get(shots.size() - 1);
            lastShot.put("endTime", currentTime);
        }

        return shots;
    }

    /** 解析 DeepSeek 返回的 JSON 数组，自动修复截断 JSON */
    private List<Map<String, Object>> parseJsonArray(String jsonStr) {
        String cleaned = jsonStr.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            // 尝试修复截断的 JSON（DeepSeek 响应可能被截断）
            String repaired = attemptRepairTruncatedJson(cleaned);
            if (repaired != null) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(repaired, new TypeReference<List<Map<String, Object>>>() {});
                } catch (JsonProcessingException e2) {
                    throw new BusinessException("JSON 解析失败: " + e2.getMessage());
                }
            }
            throw new BusinessException("JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 尝试修复截断的 JSON 数组。
     * 使用 brace-counting 找到真正的顶层对象闭合位置（跳过字符串内部的 }），
     * 然后从最后一个有效闭合位置截断并补全 ]。
     */
    private String attemptRepairTruncatedJson(String json) {
        if (!json.startsWith("[")) return null;

        // 收集所有顶层对象的 } 位置（depth=0 时遇到的 }）
        List<Integer> validClosings = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) validClosings.add(i);
            }
        }

        if (validClosings.isEmpty()) return null;

        // 从最后一个有效闭合位置往前尝试
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<List<Map<String, Object>>> type = new TypeReference<List<Map<String, Object>>>() {};

        for (int i = validClosings.size() - 1; i >= 0; i--) {
            String candidate = json.substring(0, validClosings.get(i) + 1) + "]";
            try {
                List<Map<String, Object>> result = mapper.readValue(candidate, type);
                log.warn("修复截断JSON: 保留 {}/{} 个对象", result.size(), validClosings.size());
                return candidate;
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }
}
