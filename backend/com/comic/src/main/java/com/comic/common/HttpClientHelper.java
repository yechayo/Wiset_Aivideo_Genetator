package com.comic.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * HTTP客户端通用工具类
 * <p>
 * 提供统一的HTTP请求处理、错误重试、JSON序列化、日志记录功能
 * <p>
 * 使用示例:
 * <pre>{@code
 * String response = httpClientHelper.executeRequest(
 *     "https://api.example.com/endpoint",
 *     requestBody,
 *     Map.of("Authorization", "Bearer token"),
 *     httpClient,
 *     "MyAPI"
 * );
 * }</pre>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HttpClientHelper {

    private final ObjectMapper objectMapper;

    /**
     * 默认请求配置
     */
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    /**
     * 执行HTTP POST请求
     *
     * @param url         请求URL
     * @param requestBody 请求体（将被序列化为JSON）
     * @param headers     请求头
     * @param httpClient  OkHttp客户端实例
     * @param serviceName 服务名称（用于日志）
     * @return 响应体内容
     * @throws AiCallException 请求失败时抛出
     */
    public String executeRequest(
            String url,
            Object requestBody,
            Map<String, String> headers,
            OkHttpClient httpClient,
            String serviceName) throws AiCallException {
        return executeRequest(url, requestBody, headers, httpClient, serviceName, DEFAULT_MAX_RETRIES);
    }

    /**
     * 执行HTTP POST请求（带重试）
     *
     * @param url         请求URL
     * @param requestBody 请求体（将被序列化为JSON）
     * @param headers     请求头
     * @param httpClient  OkHttp客户端实例
     * @param serviceName 服务名称（用于日志）
     * @param maxRetries  最大重试次数
     * @return 响应体内容
     * @throws AiCallException 请求失败时抛出
     */
    public String executeRequest(
            String url,
            Object requestBody,
            Map<String, String> headers,
            OkHttpClient httpClient,
            String serviceName,
            int maxRetries) throws AiCallException {

        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                if (attempt > 0) {
                    long delayMs = DEFAULT_RETRY_DELAY_MS * (1L << (attempt - 1)); // 指数退避
                    log.warn("{} 请求重试 {}/{}，等待 {}ms", serviceName, attempt, maxRetries, delayMs);
                    Thread.sleep(delayMs);
                }

                String jsonBody = objectMapper.writeValueAsString(requestBody);
                log.debug("{} 请求参数: {}", serviceName, jsonBody);

                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE));

                // 添加请求头
                if (headers != null) {
                    headers.forEach(requestBuilder::addHeader);
                }

                Request request = requestBuilder.build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";

                    if (!response.isSuccessful()) {
                        log.error("{} API 调用失败: {} - {}", serviceName, response.code(), responseBody);
                        throw new AiCallException(serviceName + " API 调用失败: " + response.code() + " - " + responseBody);
                    }

                    log.debug("{} 响应: {}", serviceName, responseBody);
                    return responseBody;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiCallException(serviceName + " 请求被中断", e);
            } catch (IOException e) {
                lastException = e;
                log.warn("{} 请求IO异常 (尝试 {}/{}): {}", serviceName, attempt + 1, maxRetries + 1, e.getMessage());
            } catch (Exception e) {
                lastException = e;
                log.error("{} 请求异常 (尝试 {}/{}): {}", serviceName, attempt + 1, maxRetries + 1, e.getMessage(), e);
                // 对于非IO异常，不重试
                throw new AiCallException(serviceName + " 请求失败: " + e.getMessage(), e);
            }
            attempt++;
        }

        log.error("{} 请求失败，已达最大重试次数", serviceName);
        throw new AiCallException(serviceName + " 请求失败，已达最大重试次数: " + lastException.getMessage(), lastException);
    }

    /**
     * 执行HTTP GET请求
     *
     * @param url         请求URL
     * @param headers     请求头
     * @param httpClient  OkHttp客户端实例
     * @param serviceName 服务名称（用于日志）
     * @return 响应体内容
     * @throws AiCallException 请求失败时抛出
     */
    public String executeGetRequest(
            String url,
            Map<String, String> headers,
            OkHttpClient httpClient,
            String serviceName) throws AiCallException {

        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .get();

            if (headers != null) {
                headers.forEach(requestBuilder::addHeader);
            }

            Request request = requestBuilder.build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("{} GET 请求失败: {} - {}", serviceName, response.code(), responseBody);
                    throw new AiCallException(serviceName + " GET 请求失败: " + response.code() + " - " + responseBody);
                }

                log.debug("{} GET 响应: {}", serviceName, responseBody);
                return responseBody;
            }

        } catch (IOException e) {
            log.error("{} GET 请求IO异常", serviceName, e);
            throw new AiCallException(serviceName + " GET 请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行带并发的HTTP请求（自动管理信号量）
     *
     * @param supplier    实际执行请求的函数
     * @param semaphore   信号量
     * @param serviceName 服务名称
     * @param <T>         返回类型
     * @return 响应结果
     * @throws AiCallException 请求失败时抛出
     */
    public <T> T executeWithConcurrencyControl(
            Supplier<T> supplier,
            java.util.concurrent.Semaphore semaphore,
            String serviceName) throws AiCallException {

        try {
            semaphore.acquire();
            log.info("{} 请求: 并发槽位 {}/{}", serviceName, semaphore.availablePermits(), semaphore.getQueueLength());

            return supplier.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException(serviceName + " 请求被中断", e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * 创建标准Authorization请求头
     *
     * @param apiKey API密钥
     * @return 包含Authorization头的Map
     */
    public static Map<String, String> createAuthHeaders(String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * 构建完整的请求URL
     *
     * @param baseUrl    基础URL
     * @param endpoint   端点路径
     * @return 完整URL
     */
    public static String buildUrl(String baseUrl, String endpoint) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return base + path;
    }
}
