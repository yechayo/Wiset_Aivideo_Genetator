package com.comic.ai.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * DeepSeek 文本生成服务（思考模式）
 * 使用 DeepSeek Reasoner 模型进行文本生成，支持思考链
 */
@Service
@Slf4j
public class DeepSeekTextService implements TextGenerationService {

    @Value("${comic.deepseek.api-key}")
    private String apiKey;

    @Value("${comic.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${comic.deepseek.model:deepseek-reasoner}")
    private String model;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(5);

    // 重试配置
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
            log.info("DeepSeek 文本生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 构建请求体
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
            requestBody.put("max_tokens", 8192);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            // 带重试的请求执行
            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null ? response.body().string() : "无响应体";
                            log.error("DeepSeek API 调用失败: {} - {}", response.code(), errorBody);
                            throw new RuntimeException("DeepSeek 文本生成失败: " + response.code());
                        }

                        String responseBody = response.body().string();
                        String content = parseResponse(responseBody);

                        log.info("DeepSeek 文本生成完成: {}", content.substring(0, Math.min(100, content.length())));
                        return content;
                    }
                } catch (SocketException | SocketTimeoutException e) {
                    lastException = e;
                    log.warn("DeepSeek 连接异常 (第{}/{}次): {}", attempt, MAX_RETRIES, e.getMessage());
                    if (attempt < MAX_RETRIES) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("DeepSeek 文本生成被中断", ie);
                        }
                    }
                }
            }
            throw new RuntimeException("DeepSeek 文本生成失败（重试" + MAX_RETRIES + "次后仍失败）: " + lastException.getMessage(), lastException);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DeepSeek 文本生成被中断", e);
        } catch (IOException e) {
            log.error("DeepSeek 文本生成 IO 异常", e);
            throw new RuntimeException("DeepSeek 文本生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("DeepSeek 文本生成异常", e);
            throw new RuntimeException("DeepSeek 文本生成失败: " + e.getMessage(), e);
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
        return "DeepSeek-Text";
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return semaphore.availablePermits();
    }

    /**
     * 解析响应
     * DeepSeek Reasoner 的响应格式与标准 OpenAI 格式相同，但 message 中包含 reasoning_content 字段
     */
    private String parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    // 思考模式：reasoning_content 可能包含实际输出内容
                    JsonNode reasoningContent = message.get("reasoning_content");
                    String reasoning = null;
                    if (reasoningContent != null && !reasoningContent.isNull()) {
                        reasoning = reasoningContent.asText();
                        log.debug("DeepSeek 思考过程: {}", reasoning);
                    }

                    JsonNode content = message.get("content");
                    if (content != null) {
                        String contentText = content.asText();
                        // content 为空或不含有效数据时，回退到 reasoning_content
                        if (!contentText.trim().isEmpty()) {
                            return contentText;
                        }
                    }

                    // content 为空时，尝试从 reasoning_content 中提取
                    if (reasoning != null && !reasoning.trim().isEmpty()) {
                        log.info("content 为空，回退使用 reasoning_content");
                        return reasoning;
                    }
                }
            }
            throw new RuntimeException("无法解析 DeepSeek 响应: " + responseBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析响应失败: {}", responseBody, e);
            throw new RuntimeException("解析响应失败", e);
        }
    }
}