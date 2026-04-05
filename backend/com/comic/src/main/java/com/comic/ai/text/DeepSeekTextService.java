package com.comic.ai.text;

import com.comic.exception.BusinessException;
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
import java.util.Iterator;
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

    // NarrationPromptBuilder 无状态，直接实例化即可
    private final NarrationPromptBuilder narrationPromptBuilder = new NarrationPromptBuilder();

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
        return generateEpisodeScript(outlineNode, characters, durationSeconds, visualStyle, totalEpisodes, false);
    }

    /**
     * @param comicCommentary 为 true 时按漫剧解说口播节奏优化正文结构
     */
    public List<Map<String, Object>> generateEpisodeScript(
            String outlineNode, String characters, int durationSeconds, String visualStyle, int totalEpisodes,
            boolean comicCommentary) {
        return generateEpisodeScript(outlineNode, characters, durationSeconds, visualStyle, totalEpisodes, comicCommentary, "");
    }

    /**
     * @param previousSummary 前序章节摘要，用于保持跨章节叙事连贯性（可为空字符串）
     */
    public List<Map<String, Object>> generateEpisodeScript(
            String outlineNode, String characters, int durationSeconds, String visualStyle, int totalEpisodes,
            boolean comicCommentary, String previousSummary) {
        StringBuilder sys = new StringBuilder();
        if (comicCommentary) {
            sys.append("你是一位擅长「漫剧解说」短视频的影视编剧。剧本将拆解为分镜并由旁白口播驱动叙事。\n");
        } else {
            sys.append("你是一位专业的影视编剧。请根据提供的大纲和角色信息，生成结构化分集剧本。\n");
        }
        sys.append("输出格式为纯 JSON 数组，不要包含 markdown 代码块标记。\n")
            .append("每个元素包含以下字段：\n")
            .append("- title: 集标题\n")
            .append("- content: 剧本正文内容（约").append(Math.max(1, durationSeconds / 60)).append("分钟对应的字数，中文约200-250字/分钟）\n")
            .append("- characters: 本集出场角色，逗号分隔\n")
            .append("- keyItems: 本集关键道具/场景，逗号分隔\n")
            .append("- continuityNote: 连贯性备注\n")
            .append("注意：内容要紧凑，适合").append(durationSeconds).append("秒的短视频。\n");
        if (comicCommentary) {
            sys.append("漫剧解说附加要求：content 段落清晰、信息点密集，便于后续按镜头配**逐镜解说词**；每集开头或转折处隐含可口播的「钩子句」，结尾留一点悬念或小结更好。\n");
        }
        sys.append("**重要约束**：必须生成恰好 ").append(totalEpisodes).append(" 集剧本，不要多也不要少！\n")
            .append("**角色名称约束**：characters 字段中的角色名必须与提供的角色描述中【】内的名称完全一致，")
            .append("禁止使用昵称、简称、别名或任何变体。");

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("大纲节点：").append(outlineNode).append("\n")
            .append("角色：").append(characters).append("\n")
            .append("视觉风格：").append(visualStyle).append("\n")
            .append("目标时长：").append(durationSeconds).append("秒\n")
            .append("需要生成的集数：").append(totalEpisodes).append(" 集\n");
        if (previousSummary != null && !previousSummary.isEmpty()) {
            userPrompt.append("前序章节摘要（保持连贯性）：\n").append(previousSummary).append("\n");
        }
        userPrompt.append("请生成恰好 ").append(totalEpisodes).append(" 集结构化分集剧本 JSON。");

        String response = generateStream(sys.toString(), userPrompt.toString());
        return parseJsonArray(response);
    }

    /**
     * 生成分镜脚本（DetailedStoryboardShot 数组）
     */
    public List<Map<String, Object>> generateStoryboard(
            String episodeContent, String characters, int totalDuration, String visualStyle) {
        return generateStoryboard(episodeContent, characters, totalDuration, visualStyle, false);
    }

    /**
     * @param comicCommentary 为 true 时每镜生成必填的 narration（解说口播稿），与画面信息点对齐
     */
    public List<Map<String, Object>> generateStoryboard(
            String episodeContent, String characters, int totalDuration, String visualStyle,
            boolean comicCommentary) {
        return generateStoryboard(episodeContent, characters, totalDuration, visualStyle, comicCommentary, null);
    }

    /**
     * @param comicCommentary 为 true 时每镜生成必填的 narration（解说口播稿），与画面信息点对齐
     * @param revisionNote 退回原因/修改建议，非空时拼入 userPrompt 引导 AI 按建议重新生成
     */
    public List<Map<String, Object>> generateStoryboard(
            String episodeContent, String characters, int totalDuration, String visualStyle,
            boolean comicCommentary, String revisionNote) {
        int recommendedShots = Math.max(1, totalDuration / 4);
        int minShots = Math.max(1, (int) Math.ceil((double) totalDuration / 6));
        int maxShots = Math.max(minShots, totalDuration / 3);

        String systemPrompt = buildStoryboardSystemPrompt(
                totalDuration, minShots, maxShots, recommendedShots, comicCommentary);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("剧本内容：\n").append(episodeContent).append("\n\n")
            .append("角色：").append(characters).append("\n")
            .append("视觉风格：").append(visualStyle).append("\n")
            .append("目标总时长：").append(totalDuration).append("秒\n\n");
        if (revisionNote != null && !revisionNote.trim().isEmpty()) {
            promptBuilder.append("**修改建议**：").append(revisionNote.trim()).append("\n\n");
        }
        promptBuilder.append("请生成详细的分镜脚本 JSON 数组。");

        String response = generateStream(systemPrompt, promptBuilder.toString());
        List<Map<String, Object>> shots = parseJsonArray(response);

        if (shots == null || shots.isEmpty()) {
            throw new BusinessException("分镜生成结果为空，请重试");
        }

        if (comicCommentary) {
            normalizeComicNarrationFields(shots);
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

    /**
     * 生成集级别旁白稿
     *
     * @param episodeContent     集剧本 content
     * @param characters        角色描述
     * @param totalDuration     目标总时长（秒）
     * @param dialogueCount     预计对白 shot 数（用于估算旁白 shot 数）
     * @param narrationPerspective 第一/第三人称
     * @return 旁白稿原文
     */
    public String generateNarrationDraft(String episodeContent, String characters,
                                      int totalDuration, int dialogueCount,
                                      String narrationPerspective) {
        int estimatedWordCount = estimateWordCount(totalDuration, dialogueCount);
        String systemPrompt = narrationPromptBuilder.buildNarrationSystemPrompt(
                narrationPerspective, estimatedWordCount);
        String userPrompt = narrationPromptBuilder.buildNarrationUserPrompt(
                episodeContent, characters, totalDuration, dialogueCount);

        log.info("[NarrationDraft] 生成旁白稿: duration={}s, dialogueCount={}, perspective={}",
                totalDuration, dialogueCount, narrationPerspective);

        String draft;
        try {
            draft = generate(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("[NarrationDraft] 旁白稿生成失败，回退为空串: {}", e.getMessage());
            return "";
        }

        if (draft == null || draft.trim().isEmpty()) {
            log.warn("[NarrationDraft] 旁白稿为空");
            return "";
        }

        // 清理 markdown 代码块
        draft = draft.trim();
        if (draft.startsWith("```")) {
            int endBacktick = draft.lastIndexOf("```");
            if (endBacktick > 3) {
                draft = draft.substring(draft.indexOf("\n") + 1, endBacktick).trim();
            }
        }

        log.info("[NarrationDraft] 旁白稿生成成功: {} 字", draft.length());
        return draft;
    }

    /** 根据时长和对白数量动态估算旁白总字数 */
    private int estimateWordCount(int totalDuration, int dialogueCount) {
        int totalShots = Math.round((float) totalDuration / 3.0f);
        int narrationShots = Math.max(0, totalShots - dialogueCount);
        return (int) (narrationShots * 11 * 1.15);
    }

    /**
     * Panel-Aware 分镜生成（仅解说模式）：AI 感知 Panel 边界，输出嵌套 JSON。
     * 返回 List<List<Map>> — 外层为 Panel，内层为 shots。
     */
    public List<List<Map<String, Object>>> generatePanelAwareStoryboard(
            String episodeContent, String characters, int totalDuration, String visualStyle,
            String revisionNote) {
        return generatePanelAwareStoryboard(episodeContent, characters, totalDuration, visualStyle, revisionNote, null);
    }

    public List<List<Map<String, Object>>> generatePanelAwareStoryboard(
            String episodeContent, String characters, int totalDuration, String visualStyle,
            String revisionNote, String narrationPerspective) {

        String systemPrompt = buildPanelAwareSystemPrompt(totalDuration, true, narrationPerspective);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("剧本内容：\n").append(episodeContent).append("\n\n")
            .append("角色：").append(characters).append("\n")
            .append("视觉风格：").append(visualStyle).append("\n")
            .append("目标总时长：").append(totalDuration).append("秒\n\n");
        if (revisionNote != null && !revisionNote.trim().isEmpty()) {
            promptBuilder.append("**修改建议**：").append(revisionNote.trim()).append("\n\n");
        }
        if ("third_person".equals(narrationPerspective)) {
            promptBuilder.append("**重要：旁白必须使用第三人称叙述，用角色名字或他/她指代，严禁使用「我」**。\n\n");
        } else if ("first_person".equals(narrationPerspective)) {
            promptBuilder.append("**重要：旁白必须使用第一人称「我」叙述，以主角口吻讲述**。\n\n");
        }
        promptBuilder.append("请生成 Panel-Aware 分镜脚本 JSON。");

        String response = generateStream(systemPrompt, promptBuilder.toString());
        List<List<Map<String, Object>>> panelShots = parsePanelAwareJson(response);

        if (panelShots == null || panelShots.isEmpty()) {
            throw new BusinessException("Panel-Aware 分镜生成结果为空，请重试");
        }

        int globalShotNumber = 0;

        for (List<Map<String, Object>> panelShotList : panelShots) {
            normalizeComicNarrationFields(panelShotList);

            // 计算 panelStartTime：统计前面所有 panel 的时长
            int panelStartTime = 0;
            for (List<Map<String, Object>> prevPanel : panelShots) {
                if (prevPanel == panelShotList) break;
                for (Map<String, Object> ps : prevPanel) {
                    panelStartTime += toSafeInt(ps.get("duration"), 3);
                }
            }

            int currentTime = panelStartTime;
            for (Map<String, Object> shot : panelShotList) {
                int duration = toSafeInt(shot.get("duration"), 3);
                duration = Math.max(2, Math.min(4, duration));
                shot.put("duration", duration);
                shot.put("startTime", currentTime);
                currentTime += duration;
                shot.put("endTime", currentTime);
                globalShotNumber++;
                shot.put("globalShotNumber", globalShotNumber);
            }

            // 裁掉超出 10s 的尾部 shots（保底至少 2 个）
            int panelDuration = currentTime - panelStartTime;
            while (panelDuration > 10 && panelShotList.size() > 2) {
                Map<String, Object> removed = panelShotList.remove(panelShotList.size() - 1);
                int removedDur = toSafeInt(removed.get("duration"), 3);
                panelDuration -= removedDur;
                globalShotNumber--;
                currentTime -= removedDur;
            }
            if (!panelShotList.isEmpty()) {
                panelShotList.get(panelShotList.size() - 1).put("endTime", currentTime);
            }
        }

        // 裁掉总时长超出的尾部 panels
        int totalActual = 0;
        for (List<Map<String, Object>> ps : panelShots) {
            for (Map<String, Object> s : ps) {
                totalActual += toSafeInt(s.get("duration"), 3);
            }
        }
        while (totalActual > totalDuration && panelShots.size() > 1) {
            List<Map<String, Object>> removed = panelShots.remove(panelShots.size() - 1);
            for (Map<String, Object> s : removed) {
                totalActual -= toSafeInt(s.get("duration"), 3);
            }
        }

        // 后处理：解析 narrationType 标签，预填 narration（dialogue shot 强制「无」，旁白 shot 预填空）
        for (List<Map<String, Object>> panelShotList : panelShots) {
            for (Map<String, Object> shot : panelShotList) {
                String type = str(shot.get("narrationType"));
                String dialogue = str(shot.get("dialogue"));
                if (!"无".equals(dialogue) && !dialogue.isEmpty()) {
                    // 对白 shot：narration 强制为「无」
                    shot.put("narration", "无");
                } else {
                    // 旁白 shot：预填空字符串（等待 NarrationAllocator 填充）
                    shot.put("narration", "");
                }
                // narrationType 标签本身不再需要，移除避免干扰
                shot.remove("narrationType");
            }
        }

        return panelShots;
    }

    /** 安全取整数值，处理 Number、String、Map 等意外类型 */
    private int toSafeInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        String s = value.toString().trim();
        try { return (int) Double.parseDouble(s); } catch (NumberFormatException e) { return defaultValue; }
    }

    /** 解析 Panel-Aware 嵌套 JSON: { "panels": [ { "panelIndex": 1, "shots": [...] }, ... ] } */
    @SuppressWarnings("unchecked")
    private List<List<Map<String, Object>>> parsePanelAwareJson(String jsonStr) {
        try {
            String cleaned = jsonStr.trim();
            if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
            else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();

            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode panelsNode = root.get("panels");
            if (panelsNode == null || !panelsNode.isArray()) {
                log.warn("Panel-Aware JSON 缺少 panels 字段，fallback 到平铺解析");
                List<Map<String, Object>> flatShots = parseJsonArray(jsonStr);
                if (flatShots != null && !flatShots.isEmpty()) {
                    List<List<Map<String, Object>>> groups = new ArrayList<>();
                    List<Map<String, Object>> current = new ArrayList<>();
                    int dur = 0;
                    for (Map<String, Object> s : flatShots) {
                        int d = toSafeInt(s.get("duration"), 3);
                        if (dur + d > 10 && !current.isEmpty()) {
                            groups.add(current);
                            current = new ArrayList<>();
                            dur = 0;
                        }
                        current.add(s);
                        dur += d;
                    }
                    if (!current.isEmpty()) groups.add(current);
                    return groups;
                }
                return null;
            }

            List<List<Map<String, Object>>> result = new ArrayList<>();
            int panelIdx = 0;
            for (JsonNode panelNode : panelsNode) {
                panelIdx++;
                JsonNode shotsNode = panelNode.get("shots");
                if (shotsNode == null || !shotsNode.isArray()) continue;

                List<Map<String, Object>> shots = new ArrayList<>();
                for (JsonNode shotNode : shotsNode) {
                    Map<String, Object> shot = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = shotNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        JsonNode val = entry.getValue();
                        if (val.isInt() || val.isLong() || val.isDouble()) {
                            shot.put(entry.getKey(), val.numberValue());
                        } else if (val.isBoolean()) {
                            shot.put(entry.getKey(), val.booleanValue());
                        } else if (val.isArray()) {
                            // 解析数组字段（如 characters）
                            List<Object> arr = new ArrayList<>();
                            for (JsonNode elem : val) {
                                if (elem.isTextual()) arr.add(elem.asText());
                                else if (elem.isInt() || elem.isLong()) arr.add(elem.numberValue());
                                else if (elem.isObject()) arr.add(objectMapper.convertValue(elem, Map.class));
                                else arr.add(elem.asText());
                            }
                            shot.put(entry.getKey(), arr);
                        } else if (val.isObject()) {
                            shot.put(entry.getKey(), objectMapper.convertValue(val, Map.class));
                        } else {
                            shot.put(entry.getKey(), val.asText());
                        }
                    }
                    shot.put("panelIndex", panelIdx);
                    shots.add(shot);
                }
                if (!shots.isEmpty()) {
                    result.add(shots);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("解析 Panel-Aware JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildStoryboardSystemPrompt(int totalDuration, int minShots, int maxShots, int recommendedShots,
                                               boolean comicCommentary) {
        String header = comicCommentary
                ? "你是一位专做「漫剧解说」短视频的分镜师。叙事由**旁白口播**主导：每一镜都必须写出观众能直接念出来的解说词。\n"
                : "你是一位专业的影视分镜师。请根据提供的剧本内容，生成详细的分镜脚本。\n";

        StringBuilder sb = new StringBuilder(header);
        sb.append("关键约束：\n")
            .append("- 每个分镜时长：2-4秒\n")
            .append("- 所有分镜时长总和必须尽量接近 ").append(totalDuration).append("秒，不超过 ").append(totalDuration).append("秒\n")
            .append("- 分镜数量范围：").append(minShots).append(" ~ ").append(maxShots).append(" 个（推荐：").append(recommendedShots).append("个）\n")
            .append("- 输出纯 JSON 数组，不要包含 markdown 代码块标记\n\n")
            .append("每个分镜包含以下字段：\n")
            .append("- shotNumber: 镜头编号（从1开始）\n")
            .append("- duration: 时长（秒，2-4）\n")
            .append("- scene: 场景描述（具体的环境细节，包括光线、天气、空间布局）\n")
            .append("- characters: 出场角色数组\n")
            .append(comicCommentary
                    ? "- shotSize: 景别（大远景/远景/全景/中景/中近景/近景/特写/大特写）——解说模式以中景、近景、特写为主（占比 80%+），远景/大远景整集不超过 2 镜\n"
                    : "- shotSize: 景别（大远景/远景/全景/中景/中近景/近景/特写/大特写）\n")
            .append("- cameraAngle: 角度（视平/高位俯拍/低位仰拍/斜拍/越肩/鸟瞰/荷兰角/低角度仰拍/高角度俯拍）\n")
            .append("- cameraMovement: 运镜描述（必须详细描述镜头的动态运动，包括：运镜方式如推/拉/摇/移/跟/升降/环绕/手持晃动/固定等，运动方向和速度如缓慢/匀速/快速/急促，起始位置和结束位置，与主体或场景的关系，营造的视觉氛围。示例：\"镜头从角色眼部特写缓慢开始，逐渐向后拉远至中景，同时向左平移30度，展现场景全貌，营造孤独空旷的压抑氛围\"或\"手持跟拍，从背后跟随角色快速奔跑，镜头有轻微晃动感，增强紧张刺激感\"或\"固定机位正前方，通过大光圈浅景深将焦点从前景的杯子缓慢转移到后景的人物脸上\"。禁止只写\"横移\"、\"推拉\"、\"固定\"等简单词汇！）\n")
            .append("- visualDescription: 画面描述（必须详细描述画面内容，包括角色具体动作姿态、面部表情、身体语言、手势、光影效果、色彩氛围。示例：\"女孩右手紧握裙摆，微微低头，眼眶泛红但强忍着泪水，头顶的夕阳余晖在她发梢形成金色光晕，背景是模糊的校园走廊\"）\n");

        if (comicCommentary) {
            sb.append("- narration: 旁白口播稿（中文口语，字数硬性要求：duration=2 时必须 5~9 字、duration=3 时必须 9~13 字、duration=4 时必须 12~16 字，**超出此范围为失败**；**旁白与对白互斥：有 dialogue 的分镜 narration 填「无」**）\n");
            sb.append("- dialogue: 角色在画面内开口的台词（**有 narration 的分镜 dialogue 填「无」**；对白分镜 narration 必须填「无」）\n")
                .append("- speaker: 说话人（dialogue 为「无」时填「无」；有台词时必须是 characters 中的角色之一，禁止填「旁白」）\n");
        } else {
            sb.append("- dialogue: 对白（无则填\"无\"）\n")
                .append("- speaker: 说话人角色名（无对白则填\"无\"，有对白时必须是 characters 数组中的角色之一）\n");
        }
        sb.append("- dialogueTone: 对白语气（无对白则填\"无\"。必须描述说话人的语气、情绪状态和表演方式。示例：\"愤怒而急促，声音略带颤抖\"或\"温柔低语，带着一丝犹豫和心疼\"或\"震惊地提高音量，难以置信\"或\"冷漠平淡，不带任何感情\"或\"故作轻松但掩饰不住内心的悲伤\"）\n")
            .append("- visualEffects: 视觉特效（无则填\"无\"）\n")
            .append("- audioEffects: 音效（无则填\"无\"）\n")
            .append("- transitionHint: 镜头衔接提示（描述此镜头如何过渡到下一个镜头，确保画面连贯性。示例：\"角色转身走出的动势自然衔接下一镜\"或\"对话结束后的沉默配合画面渐暗\"或\"快速摇镜头模糊转场，配合音效冲击\"。最后一个分镜填写\"最后一个镜头，无需衔接\"）\n\n");

        if (comicCommentary) {
            sb.append("漫剧解说专用规则：\n")
                .append("1.【旁白与对白互斥 - 最高优先级】每一镜 narration 和 dialogue 绝对不能同时存在：\n")
                .append("  - 有 narration 的分镜：dialogue 填「无」、speaker 填「无」。\n")
                .append("  - 有 dialogue 的分镜：narration 填「无」。\n")
                .append("  - 违反此规则（同时有 narration 和 dialogue）视为生成失败。\n\n")
                .append("2.【对白数量强制约束】所有分镜中，**每 9 个分镜必须有且仅有 3 个对白分镜**（其余 6 个为旁白分镜）：\n")
                .append("  - 对白分镜：dialogue 不为「无」，narration 填「无」。\n")
                .append("  - 旁白分镜：narration 不为「无」，dialogue 填「无」、speaker 填「无」。\n")
                .append("  - 对白分镜分布在情感爆发力最强的节点，禁止连续出现，至少间隔 1 个旁白分镜。\n\n")
                .append("3. 仍遵守慢节奏运镜与单主体等视频生成约束；visualDescription 中角色嘴部以自然闭合为主，除非该镜 dialogue 非「无」且说话人在画面中。\n")
                .append("4.【景别倾向】景别以中景、近景、特写为主（占比 80%+），大远景/远景控制在 1-2 镜以内，仅用于开场定场或转场。构图需留出上方约 1/4 区域作为「字幕安全区」，避免关键视觉元素被花字遮挡。\n")
                .append("5.【运镜风格】运镜以缓慢推拉和微平移为主，禁止快速摇移或大幅度环绕。每个镜头需有 2-3 秒画面相对静止的「解说留白」时段，供观众消化旁白信息。\n")
                .append("6.【画面侧重点】visualDescription 应侧重角色情绪状态和场景氛围，而非复杂动作。优先描述：表情变化、眼神方向、身体朝向、光影氛围。避免描述复杂肢体动作、多人互动、快速运动。每镜画面应像一个清晰的「信息单元」——观众看一眼就能理解当前发生的事。\n")
                .append("7.【转场节奏】转场以简洁为主：硬切、淡入淡出、黑场过渡。避免复杂动势衔接或匹配剪辑，保持叙事节奏清晰。\n")
                .append("8.【音效策略 - 强制规则】audioEffects 字段一律填「无」。若需要氛围感，通过 visualDescription 的光影、色彩、构图来传达。\n\n");
        }

        sb.append("重要规则：\n")
            .append("1. dialogue 与 speaker 必须严格对应。如果 dialogue 不为\"无\"，则 speaker 必须是 characters 数组中的某个角色名。\n")
            .append("2. dialogue 与 dialogueTone 必须严格对应。如果 dialogue 不为\"无\"，dialogueTone 不能为\"无\"，要精准刻画角色的情感状态。\n")
            .append("3. 分镜之间必须有连贯性。每个分镜的 transitionHint 要清晰描述画面如何过渡到下一个分镜，确保叙事流畅。\n")
            .append("4. 最后一个分镜的 transitionHint 必须写\"最后一个镜头，无需衔接\"。\n")
            .append("5. cameraMovement 必须具体到运动细节（方式、方向、速度、起始/结束位置、氛围），禁止只写\"横移\"、\"推拉\"、\"固定\"等简单词汇。\n")
            .append("6. visualDescription 必须包含角色的具体动作、表情、身体语言和光影氛围，禁止笼统描述。\n\n")
            .append("**角色名称约束**：characters 数组中的每个角色名必须与提供的角色描述中【】内的名称完全一致，")
            .append("禁止使用昵称、简称、别名或任何变体。例如角色描述为【墨尘（幻影）】，则必须写\"墨尘（幻影）\"，不能写\"墨尘\"或\"幻影\"。\n\n")
            .append("**AI视频生成三原则（必须严格遵守）：**\n")
            .append("1.【单主体原则】每个分镜最多只保留1个角色的动作描写，禁止两个角色同框互动（如拥抱、打斗、接触）。")
            .append("双人对话必须拆分为两个分镜（A的反应镜头 + B的反应镜头），用剪辑快切实现对话效果。")
            .append("静态多人同框（如多人站立场面）允许，但禁止动态互动。\n")
            .append("2.【慢动作原则】cameraMovement 必须使用缓慢运动（缓慢推镜头、微平移、极慢拉远、静止等）。")
            .append("visualDescription 中的角色动作必须是微小动作（微风吹动头发、眼皮微垂、轻微呼吸、手指轻颤、嘴角微动等），")
            .append("禁止描写剧烈运动（快速奔跑、跳跃、翻滚、打斗）。激烈场面通过多分镜快切实现，而非单镜头内的快动。\n");

        if (comicCommentary) {
            sb.append("3.【解说与口型】以 narration 为声画主轴；dialogue 非「无」时，说话人可有克制口型，其余角色闭嘴；无角色台词时全员自然闭嘴，旁白仅存在于 narration 文本中。\n\n");
        } else {
            sb.append("3.【说话人与嘴部约束原则】本视频为音画同步生成，对白和画面同时产出，必须严格区分说话人与非说话人：\n")
                .append("  (a) 当 speaker 是 characters 中的某个画面内角色时：visualDescription 中可以描写该说话人自然的说话神态（如表情、眼神、手势），但禁止详细描写嘴部开合、口型蠕动等唇齿运动。\n")
                .append("  (b) 当 speaker 为旁白、画外音、内心独白，或 speaker 不在 characters 列表中（即不在画面中）时：")
                .append("visualDescription 中所有角色必须保持闭嘴静止，处于倾听、思考或感受状态，绝对禁止任何嘴部动作。\n")
                .append("  (c) 非说话人的画面内角色：嘴巴必须闭合，只能通过眼神、表情、头部动作表达反应，禁止任何嘴部运动。\n")
                .append("  (d) speaker 字段必须精确标注说话人。有对白时 speaker 不可填\"无\"；若对白来自旁白则填\"旁白\"，内心独白则填对应角色名加\"（内心独白）\"。\n")
                .append("  (e) dialogueTone 必须精准描述说话人的语气情绪，帮助视频模型理解谁在说话、以什么情绪说话。\n\n")
                .append("**说话人标注强化规则：**\n")
                .append("- 每个有对白的分镜，visualDescription 的开头必须先写明画面主体角色的状态，再自然引出说话人的反应。\n")
                .append("- 示例（说话人在画面中）：speaker=\"小明\"，visualDescription 应写\"小明微微前倾，眼神认真注视前方，手中紧握信纸，表情从期待逐渐转为感动\"，")
                .append("不要写\"小明张嘴说话\"或\"小明念出信的内容\"。\n")
                .append("- 示例（说话人为旁白）：speaker=\"旁白\"，visualDescription 应写\"画面中所有人保持安静，小明低头沉思，远处夕阳缓缓沉入地平线\"，")
                .append("明确体现画面内角色处于无声状态。\n")
                .append("- 示例（说话人不在画面中）：speaker=\"小红\"但 characters=[\"小明\"]，visualDescription 应写\"小明独自站在窗前，表情凝重地望向窗外\"，")
                .append("体现小明在倾听画面外的声音。");
        }

        return sb.toString();
    }

    private String buildPanelAwareSystemPrompt(int totalDuration, boolean comicCommentary) {
        return buildPanelAwareSystemPrompt(totalDuration, comicCommentary, null);
    }

    private String buildPanelAwareSystemPrompt(int totalDuration, boolean comicCommentary, String narrationPerspective) {
        int targetPanelCount = Math.max(1, (int) Math.ceil((double) totalDuration / 10));

        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专做「漫剧解说」短视频的分镜师。叙事由**旁白口播**主导。\n\n");

        sb.append("【Panel 分组规则 - 必须严格遵守】\n");
        sb.append("本集需分成约 ").append(targetPanelCount).append(" 个 Panel，每个 Panel 是一段连续视频片段。\n");
        sb.append("- 每个 Panel 包含 3-5 个分镜，总时长不超过 10 秒\n");
        sb.append("- 每个分镜时长：2-4 秒\n");
        sb.append("- 整集所有分镜时长总和尽量接近 ").append(totalDuration).append("秒，不超过 ").append(totalDuration).append("秒\n\n");

        sb.append("【输出格式 - 嵌套 JSON】\n");
        sb.append("输出以下结构的 JSON 对象（不要 markdown 代码块标记）：\n");
        sb.append("{\n");
        sb.append("  \"panels\": [\n");
        sb.append("    {\n");
        sb.append("      \"panelIndex\": 1,\n");
        sb.append("      \"shots\": [\n");
        sb.append("        { \"shotNumber\": 1, \"duration\": 2, ... },\n");
        sb.append("        ...\n");
        sb.append("      ]\n");
        sb.append("    },\n");
        sb.append("    ...\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("每个分镜的字段：\n");
        sb.append("- shotNumber: 镜头编号（每个 Panel 内从 1 开始）\n");
        sb.append("- duration: 时长（秒，2-4）\n");
        sb.append("- scene: 场景描述（具体环境细节，包括光线、天气、空间布局）\n");
        sb.append("- characters: 出场角色数组\n");
        sb.append("- shotSize: 景别（解说模式以中景、近景、特写为主，占比 80%+，远景/大远景整集不超过 2 镜）\n");
        sb.append("- cameraAngle: 角度（视平/高位俯拍/低位仰拍/斜拍/越肩/鸟瞰/荷兰角/低角度仰拍/高角度俯拍）\n");
        sb.append("- cameraMovement: 运镜描述（必须详细描述镜头的动态运动，包括：运镜方式如推/拉/摇/移/跟/升降/环绕/手持晃动/固定等，运动方向和速度如缓慢/匀速/快速/急促，起始位置和结束位置，与主体或场景的关系，营造的视觉氛围。示例：\"镜头从角色眼部特写缓慢开始，逐渐向后拉远至中景，同时向左平移30度，展现场景全貌，营造孤独空旷的压抑氛围\"。禁止只写\"横移\"、\"推拉\"、\"固定\"等简单词汇！）\n");
        sb.append("- visualDescription: 画面描述（必须详细描述画面内容，包括角色具体动作姿态、面部表情、身体语言、手势、光影效果、色彩氛围。示例：\"女孩右手紧握裙摆，微微低头，眼眶泛红但强忍着泪水，头顶的夕阳余晖在她发梢形成金色光晕，背景是模糊的校园走廊\"）\n");
        sb.append("- narrationType: 旁白类型标签（**仅填写标签，禁止填旁白文本**。\"narrate\"=此分镜有旁白，\"dialogue\"=此分镜有对白）\n");
        sb.append("- dialogue: 角色在画面内开口的台词（旁白分镜 dialogue 填「无」；对白分镜填写角色台词）\n");
        sb.append("- speaker: 说话人（dialogue 为「无」时填「无」；有台词时必须是 characters 中的角色之一，禁止填「旁白」）\n");
        sb.append("- dialogueTone: 对白语气（无对白则填\"无\"。必须描述说话人的语气、情绪状态和表演方式。示例：\"愤怒而急促，声音略带颤抖\"或\"温柔低语，带着一丝犹豫和心疼\"）\n");
        sb.append("- visualEffects: 视觉特效（无则填\"无\"）\n");
        sb.append("- audioEffects: 音效（无则填\"无\"）\n");
        sb.append("- transitionHint: 镜头衔接提示（描述此镜头如何过渡到下一个镜头，确保画面连贯性。最后一个分镜填写\"最后一个镜头，无需衔接\"）\n\n");

        sb.append("【叙事连贯性规则 - 最高优先级】\n");
        sb.append("1. 整集所有旁白分镜的 narration 连起来必须是一篇完整、流畅的旁白口播稿（对白分镜的 dialogue 不参与旁白连贯性检查）。\n");
        sb.append("   - 有清晰的开场引入 → 中间推进 → 高潮转折 → 结尾收束\n");
        sb.append("   - 句子之间有逻辑递进，禁止跳跃、重复或突兀换话题\n");
        sb.append("2. Panel 边界过渡：\n");
        sb.append("   - 每个 Panel 最后一个旁白分镜的 narration 要为下一个 Panel 留有自然承接点\n");
        sb.append("   - 下一个 Panel 的第一个旁白分镜的 narration 要自然承接上文\n");
        sb.append("   - 禁止在 Panel 边界处突兀地硬切话题\n");
        sb.append("3. Panel 内部：\n");
        sb.append("   - narration 与 visualDescription 严格对齐，解说描述的必须是画面可见的\n");
        sb.append("   - 每个 Panel 内部像一个完整的叙事小节，有起承转合\n");
        sb.append("4. 第一个 Panel 的第一个 shot 要有开场引入感，最后一个 Panel 的最后一个 shot 要有收束感\n\n");

        sb.append("漫剧解说专用规则：\n");
        sb.append("【分镜类型标签 - 必须严格遵守】\n");
        sb.append("- narrationType=\"narrate\"：此分镜是旁白分镜，画面由旁白解说驱动。\n");
        sb.append("  · dialogue 填「无」，speaker 填「无」\n");
        sb.append("  · narrationType 填 \"narrate\"（**禁止填写实际旁白文本**）\n");
        sb.append("- narrationType=\"dialogue\"：此分镜是对白分镜，画面由角色台词驱动。\n");
        sb.append("  · dialogue 填角色台词，speaker 填角色名\n");
        sb.append("  · narrationType 填 \"dialogue\"（**禁止填 \"narrate\"**）\n\n");

        sb.append("【对白数量约束】整集所有分镜中，**每 9 个分镜必须有且仅有 3 个对白分镜**（其余为旁白分镜）：\n");
        sb.append("  - 对白分镜（narrationType=\"dialogue\"）：dialogue 非「无」\n");
        sb.append("  - 旁白分镜（narrationType=\"narrate\"）：dialogue 填「无」\n");
        sb.append("  - 对白分镜禁止连续出现，至少间隔 1 个旁白分镜\n");
        sb.append("  - 整集对白分镜总数偏差不得超过 ±1\n\n");
        sb.append("3. 仍遵守慢节奏运镜与单主体等视频生成约束；visualDescription 中角色嘴部以自然闭合为主，除非该镜 dialogue 非「无」且说话人在画面中。\n");
        sb.append("4.【景别倾向】景别以中景、近景、特写为主（占比 80%+），大远景/远景控制在 1-2 镜以内，仅用于开场定场或转场。构图需留出上方约 1/4 区域作为「字幕安全区」，避免关键视觉元素被花字遮挡。\n");
        sb.append("5.【运镜风格】运镜以缓慢推拉和微平移为主，禁止快速摇移或大幅度环绕。每个镜头需有 2-3 秒画面相对静止的「解说留白」时段，供观众消化旁白信息。\n");
        sb.append("6.【画面侧重点】visualDescription 应侧重角色情绪状态和场景氛围，而非复杂动作。优先描述：表情变化、眼神方向、身体朝向、光影氛围。避免描述复杂肢体动作、多人互动、快速运动。每镜画面应像一个清晰的「信息单元」——观众看一眼就能理解当前发生的事。\n");
        sb.append("7.【转场节奏】转场以简洁为主：硬切、淡入淡出、黑场过渡。避免复杂动势衔接或匹配剪辑，保持叙事节奏清晰。\n");
        sb.append("8.【音效策略 - 强制规则】漫剧解说以旁白口播为唯一声音核心，必须保证解说词清晰可闻：\n");
        sb.append("  - audioEffects 字段一律填「无」。禁止填写任何音效、背景音乐、环境声、打击声等。\n");
        sb.append("  - 若某些画面确实需要氛围感，通过 visualDescription 的光影、色彩、构图来传达，而不是通过音效。\n\n");

        if (narrationPerspective != null && !narrationPerspective.isEmpty()) {
            if ("first_person".equals(narrationPerspective)) {
                sb.append("10.【旁白人称 - 第一人称 · 最高优先级】所有 narration 必须以主角口吻叙述，使用「我」来讲述故事，营造沉浸式代入感。绝对禁止在 narration 中使用第三人称（他/她/主角名字）。\n\n");
            } else if ("third_person".equals(narrationPerspective)) {
                sb.append("10.【旁白人称 - 第三人称 · 最高优先级】所有 narration 必须以旁观者/上帝视角叙述，客观描述故事事件与角色行为。绝对禁止在 narration 中使用第一人称「我」，必须用角色名字或他/她指代。\n\n");
            }
        }

        sb.append("重要规则：\n");
        sb.append("1. dialogue 与 speaker 必须严格对应。如果 dialogue 不为\"无\"，则 speaker 必须是 characters 数组中的某个角色名。\n");
        sb.append("2. dialogue 与 dialogueTone 必须严格对应。如果 dialogue 不为\"无\"，dialogueTone 不能为\"无\"。\n");
        sb.append("3. 分镜之间必须有连贯性。每个分镜的 transitionHint 要清晰描述画面如何过渡到下一个分镜。\n");
        sb.append("4. 每个 Panel 的最后一个 shot 的 transitionHint 填写\"最后一个镜头，无需衔接\"。\n");
        sb.append("5. cameraMovement 必须具体到运动细节，禁止只写\"横移\"、\"推拉\"、\"固定\"等简单词汇。\n");
        sb.append("6. visualDescription 必须包含角色的具体动作、表情、身体语言和光影氛围，禁止笼统描述。\n\n");
        sb.append("**角色名称约束**：characters 数组中的每个角色名必须与提供的角色描述中【】内的名称完全一致，禁止使用昵称、简称、别名或任何变体。\n\n");
        sb.append("**AI视频生成三原则（必须严格遵守）：**\n");
        sb.append("1.【单主体原则】每个分镜最多只保留1个角色的动作描写，禁止两个角色同框互动。双人对话必须拆分为两个分镜，用剪辑快切实现对话效果。静态多人同框允许，但禁止动态互动。\n");
        sb.append("2.【慢动作原则】cameraMovement 必须使用缓慢运动。visualDescription 中的角色动作必须是微小动作，禁止描写剧烈运动。激烈场面通过多分镜快切实现。\n");
        sb.append("3.【解说与口型】以 narration 为声画主轴；dialogue 非「无」时，说话人可有克制口型，其余角色闭嘴；无角色台词时全员自然闭嘴。\n\n");

        return sb.toString();
    }

    /** 漫剧模式：把历史数据里 speaker=旁白 的 dialogue 迁入 narration，避免与新规冲突 */
    private void normalizeComicNarrationFields(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            Object narObj = shot.get("narration");
            String narration = narObj != null ? narObj.toString().trim() : "";
            if (!narration.isEmpty() && !"无".equals(narration)) {
                continue;
            }
            String sp = shot.get("speaker") != null ? shot.get("speaker").toString() : "";
            String dlg = shot.get("dialogue") != null ? shot.get("dialogue").toString() : "";
            if (sp.contains("旁白") && dlg != null && !dlg.isEmpty() && !"无".equals(dlg)) {
                shot.put("narration", dlg);
                shot.put("dialogue", "无");
                shot.put("speaker", "无");
                shot.put("dialogueTone", "无");
            }
        }
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

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }
}
