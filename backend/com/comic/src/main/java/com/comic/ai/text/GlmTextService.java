package com.comic.ai.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * GLM 文本生成服务（智谱 AI 官方 API）
 * 使用智谱 AI 官方 API 调用 GLM 模型进行文本生成
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GlmTextService implements TextGenerationService {

    @Value("${comic.glm.api-key}")
    private String apiKey;

    @Value("${comic.glm.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${comic.glm.model:glm-4-flash}")
    private String model;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(5);

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        try {
            semaphore.acquire();
            log.info("GLM 文本生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 构建请求体（Java 8 兼容）
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
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 8192);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("GLM API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("GLM 文本生成失败: " + response.code());
                }

                String responseBody = response.body().string();
                String content = parseResponse(responseBody);

                log.info("GLM 文本生成完成: {}", content.substring(0, Math.min(100, content.length())));
                return content;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GLM 文本生成被中断", e);
        } catch (IOException e) {
            log.error("GLM 文本生成 IO 异常", e);
            throw new RuntimeException("GLM 文本生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("GLM 文本生成异常", e);
            throw new RuntimeException("GLM 文本生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public String generateStream(String systemPrompt, String userPrompt) {
        // 暂时使用同步方式
        return generate(systemPrompt, userPrompt);
    }

    @Override
    public String getServiceName() {
        return "GLM-Text";
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return semaphore.availablePermits();
    }

    /**
     * 解析响应
     */
    private String parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null) {
                        return content.asText();
                    }
                }
            }
            throw new RuntimeException("无法解析 GLM 响应: " + responseBody);
        } catch (Exception e) {
            log.error("解析响应失败: {}", responseBody, e);
            throw new RuntimeException("解析响应失败", e);
        }
    }
}
