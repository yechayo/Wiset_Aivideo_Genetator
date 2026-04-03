package com.comic.ai.image;

import com.comic.config.WuyinkejiProperties;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Nanobanana2 图片生成服务（异步轮询方式）
 * 通过吾印科技 API 提交异步任务，轮询获取生成结果
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NanobananaImageService implements ImageGenerationService {

    private final WuyinkejiProperties wuyinkejiProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OssService ossService;

    // 并发控制
    private final Semaphore semaphore = new Semaphore(2);

    // 轮询间隔（毫秒）
    private static final long POLL_INTERVAL_MS = 3000;
    // 超时时间（毫秒）
    private static final long TIMEOUT_MS = 180_000;

    // 支持的宽高比
    private static final Set<String> SUPPORTED_ASPECT_RATIOS = Set.of(
            "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "5:4", "4:5", "21:9"
    );

    @Override
    public String generate(String prompt, int width, int height, String style) {
        return doGenerate(prompt, width, height, null);
    }

    @Override
    public String generateWithReference(String prompt, String referenceImage, int width, int height) {
        List<String> urls = referenceImage != null ? List.of(referenceImage) : null;
        return doGenerate(prompt, width, height, urls);
    }

    @Override
    public String generateWithMultipleReferences(String prompt, List<String> referenceImages, int width, int height) {
        return doGenerate(prompt, width, height, referenceImages);
    }

    @Override
    public String getServiceName() {
        return "Nanobanana-Image";
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return semaphore.availablePermits();
    }

    /**
     * 核心生成逻辑：提交异步任务并轮询结果
     */
    private String doGenerate(String prompt, int width, int height, List<String> urls) {
        try {
            semaphore.acquire();
            log.info("Nanobanana2 图片生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            String aspectRatio = computeAspectRatio(width, height);

            // 1. 提交异步任务
            String taskId = submitTask(prompt, aspectRatio, urls);
            log.info("Nanobanana2 任务已提交, taskId={}", taskId);

            // 2. 轮询任务状态
            String remoteUrl = pollForResult(taskId);

            // 3. 上传到 OSS，返回永久 URL
            String ossUrl = ossService.uploadImageFromUrl(remoteUrl, null);
            log.info("Nanobanana2 图片生成完成，已转存 OSS: {}", ossUrl);
            return ossUrl;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Nanobanana2 图片生成被中断", e);
        } catch (Exception e) {
            log.error("Nanobanana2 图片生成异常", e);
            throw new RuntimeException("Nanobanana2 图片生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * 提交异步图片生成任务
     */
    private String submitTask(String prompt, String aspectRatio, List<String> urls) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("prompt", prompt);
        requestBody.put("aspectRatio", aspectRatio);
        if (urls != null && !urls.isEmpty()) {
            requestBody.put("urls", urls);
        }

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.info("Nanobanana2 提交任务参数: {}", jsonBody);

        Request request = new Request.Builder()
                .url(wuyinkejiProperties.getBaseUrl() + "/api/async/image_nanoBanana2")
                .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Nanobanana2 提交任务失败: {} - {}", response.code(), responseBody);
                throw new RuntimeException("Nanobanana2 提交任务失败: " + response.code() + " - " + responseBody);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                log.error("Nanobanana2 提交任务返回错误: code={}, body={}", code, responseBody);
                throw new RuntimeException("Nanobanana2 提交任务失败: " + responseBody);
            }

            JsonNode data = root.path("data");
            String taskId = data.path("id").asText();
            if (taskId == null || taskId.isEmpty()) {
                throw new RuntimeException("Nanobanana2 提交任务返回空 taskId: " + responseBody);
            }
            return taskId;
        }
    }

    /**
     * 轮询任务结果，直到成功、失败或超时
     */
    private String pollForResult(String taskId) throws IOException, InterruptedException {
        String url = wuyinkejiProperties.getBaseUrl() + "/api/async/detail?id=" + taskId;
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                .get()
                .build();

        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                throw new RuntimeException("Nanobanana2 图片生成超时（" + (TIMEOUT_MS / 1000) + "秒）, taskId=" + taskId);
            }

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("Nanobanana2 查询任务失败: {} - {}，将重试", response.code(), responseBody);
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }

                JsonNode root = objectMapper.readTree(responseBody);
                int code = root.path("code").asInt(-1);
                if (code != 200) {
                    log.warn("Nanobanana2 查询任务返回非200: code={}, body={}，将重试", code, responseBody);
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }

                JsonNode data = root.path("data");
                int status = data.path("status").asInt(-1);

                switch (status) {
                    case 1:
                        // 成功
                        String remoteUrl = data.path("remote_url").asText();
                        if (remoteUrl == null || remoteUrl.isEmpty()) {
                            throw new RuntimeException("Nanobanana2 任务成功但 remote_url 为空, taskId=" + taskId);
                        }
                        return remoteUrl;

                    case 2:
                        // 失败
                        String failReason = data.path("fail_reason").asText("未知原因");
                        throw new RuntimeException("Nanobanana2 图片生成失败: " + failReason + ", taskId=" + taskId);

                    case 0:
                    case 3:
                    default:
                        // 排队中(0) / 生成中(3)，继续轮询
                        log.debug("Nanobanana2 任务状态: status={}, taskId={}", status, taskId);
                        sleep(POLL_INTERVAL_MS);
                        break;
                }
            }
        }
    }

    /**
     * 根据 width/height 计算宽高比字符串
     * 使用 GCD 约分，若匹配支持列表则返回对应字符串，否则返回 "auto"
     */
    private String computeAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "auto";
        }
        int gcd = gcd(width, height);
        int w = width / gcd;
        int h = height / gcd;
        String ratio = w + ":" + h;
        if (SUPPORTED_ASPECT_RATIOS.contains(ratio)) {
            return ratio;
        }
        return "auto";
    }

    /**
     * 计算最大公约数（欧几里得算法）
     */
    private int gcd(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    /**
     * 安全休眠
     */
    private void sleep(long millis) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(millis);
    }
}