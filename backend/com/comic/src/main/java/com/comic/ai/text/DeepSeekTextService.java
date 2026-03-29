package com.comic.ai.text;

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

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class DeepSeekTextService implements TextGenerationService {

    @Value("${comic.ai.text.deepseek.apiKey}")
    private String apiKey;

    @Value("${comic.ai.text.deepseek.baseUrl:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${comic.ai.text.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${comic.ai.text.deepseek.maxTokens:8192}")
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

            Map<String, Object> requestBody = new HashMap<String, Object>();
            requestBody.put("model", model);

            List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
            Map<String, String> systemMsg = new HashMap<String, String>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            Map<String, String> userMsg = new HashMap<String, String>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("max_tokens", maxTokens);

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
                } catch (SocketException | SocketTimeoutException e) {
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
                    throw new RuntimeException("DeepSeek output truncated (finish_reason=length)");
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
        return trimmedContent.startsWith("{") && !trimmedContent.endsWith("}");
    }

    private void sleepBeforeRetry(int attempt) throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MS * attempt);
    }
}
