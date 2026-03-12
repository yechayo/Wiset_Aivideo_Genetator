package com.comic.ai;

import com.comic.common.AiCallException;
import com.comic.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Claude API 调用服务（Java 8 版）
 *
 * 用 OkHttp 直接调用 Anthropic REST API，替代 Spring AI。
 * 核心功能：
 *   1. 信号量控制最大并发数，防止触发 API 限流（HTTP 429）
 *   2. 统一超时设置
 *   3. 异常统一包装为 AiCallException
 */
@Service
@Slf4j
public class ClaudeService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL    = "claude-opus-4-5";
    private static final MediaType JSON  = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final Semaphore concurrencyLimiter;
    private final String apiKey;

    public ClaudeService(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.apiKey = System.getenv("CLAUDE_API_KEY");
        this.concurrencyLimiter = new Semaphore(aiProperties.getMaxConcurrent());

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(aiProperties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 同步调用 Claude，返回完整文本响应
     *
     * @param systemPrompt 系统提示词（角色设定 + 格式约束）
     * @param userPrompt   用户提示词（具体任务）
     * @return AI 回复的文本内容
     */
    public String call(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new AiCallException("未设置 CLAUDE_API_KEY 环境变量");
        }

        boolean acquired = false;
        long start = System.currentTimeMillis();

        try {
            acquired = concurrencyLimiter.tryAcquire(
                    aiProperties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                throw new AiCallException("AI 服务繁忙（并发已满），请稍后重试");
            }

            String requestBody = buildRequestBody(systemPrompt, userPrompt);

            Request request = new Request.Builder()
                    .url(API_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    throw new AiCallException("Claude API 返回错误 " + response.code() + ": " + errBody);
                }

                String responseBody = response.body().string();
                String content = parseContent(responseBody);

                log.info("Claude 调用成功，耗时 {}ms，响应长度 {} 字符",
                        System.currentTimeMillis() - start, content.length());
                return content;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException("调用被中断", e);
        } catch (AiCallException e) {
            throw e;
        } catch (IOException e) {
            throw new AiCallException("网络请求失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Claude API 调用异常", e);
            throw new AiCallException("调用失败: " + e.getMessage(), e);
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
        }
    }

    /** 获取当前可用并发槽位（用于监控） */
    public int getAvailableConcurrentSlots() {
        return concurrencyLimiter.availablePermits();
    }

    // ================== 私有方法 ==================

    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 4096);
        body.put("system", systemPrompt);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        body.put("messages", Collections.singletonList(userMessage));

        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private String parseContent(String responseBody) throws Exception {
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) {
            throw new AiCallException("Claude 返回内容为空");
        }
        Object text = content.get(0).get("text");
        if (text == null) {
            throw new AiCallException("Claude 返回内容缺少 text 字段");
        }
        return text.toString();
    }
}
