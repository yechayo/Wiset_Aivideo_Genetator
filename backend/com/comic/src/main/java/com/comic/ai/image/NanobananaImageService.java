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
import java.util.concurrent.TimeUnit;

/**
 * Nanobanana2 图片生成服务（异步轮询方式）
 * 通过吾印科技 API 提交异步任务，轮询获取生成结果。
 * 不在此做进程内并发排队：官方接口约 100 QPS，由上游与 HTTP 客户端承载并发。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NanobananaImageService implements ImageGenerationService {

    private final WuyinkejiProperties wuyinkejiProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OssService ossService;

    // 轮询间隔（毫秒）
    private static final long POLL_INTERVAL_MS = 3000;
    // 超时时间（毫秒）
    private static final long TIMEOUT_MS = 86_400_000; // 24小时

    // 支持的尺寸
    private static final Set<String> SUPPORTED_SIZES = new HashSet<>(java.util.Arrays.asList(
            "1K", "2K", "4K"
    ));

    /**
     * 根据 width 推算尺寸档位
     */
    private String computeSize(int width) {
        if (width >= 3000) return "4K";
        if (width >= 1500) return "2K";
        return "1K";
    }
    private static final Set<String> SUPPORTED_ASPECT_RATIOS = new HashSet<>(java.util.Arrays.asList(
            "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "5:4", "4:5", "21:9"
    ));

    @Override
    public String generate(String prompt, int width, int height, String style) {
        return doGenerate(prompt, width, height, null);
    }

    @Override
    public String generateWithReference(String prompt, String referenceImage, int width, int height) {
        List<String> urls = referenceImage != null ? Collections.singletonList(referenceImage) : null;
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

    /** 仅提交任务，返回 taskId（用于异步测试） */
    public String submitOnly(String prompt, int width, int height, List<String> urls) throws IOException {
        String aspectRatio = computeAspectRatio(width, height);
        String size = computeSize(width);
        return submitTask(prompt, size, aspectRatio, urls);
    }

    /**
     * 单次查询任务状态（非阻塞）
     * @return Map: status(submitted/generating/done/failed), imageUrl(仅done时), error(仅failed时)
     */
    public Map<String, Object> checkStatus(String taskId) throws IOException {
        String url = wuyinkejiProperties.getBaseUrl() + "/api/async/detail?key=" + wuyinkejiProperties.getApiKey() + "&id=" + taskId;
        Request request = new Request.Builder().url(url).get().build();
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                result.put("status", "error");
                result.put("error", "HTTP " + response.code() + ": " + responseBody);
                return result;
            }
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                result.put("status", "error");
                result.put("error", "API code=" + code + ": " + responseBody);
                return result;
            }
            JsonNode data = root.path("data");
            int status = data.path("status").asInt(-1);
            log.info("Nanobanana2 状态查询: taskId={}, status={}", taskId, status);

            switch (status) {
                case 0:
                    result.put("status", "queued");
                    break;
                case 3:
                    result.put("status", "generating");
                    break;
                case 1: {
                    String imageUrl = extractImageUrl(data);
                    result.put("status", "done");
                    result.put("imageUrl", imageUrl);
                    break;
                }
                case 2: {
                    String imageUrl = extractImageUrl(data);
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        result.put("status", "done");
                        result.put("imageUrl", imageUrl);
                    } else {
                        String failReason = data.path("fail_reason").asText("");
                        String message = data.path("message").asText("");
                        result.put("status", "failed");
                        result.put("error", !failReason.isEmpty() ? failReason : (!message.isEmpty() ? message : "未知原因"));
                    }
                    break;
                }
                default:
                    result.put("status", "unknown");
                    result.put("rawStatus", status);
            }
        }
        return result;
    }

    /** 转存 OSS */
    public String transferToOss(String remoteUrl) {
        return ossService.uploadImageFromUrl(remoteUrl, null);
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return Integer.MAX_VALUE;
    }

    /**
     * 核心生成逻辑：提交异步任务并轮询结果
     */
    private String doGenerate(String prompt, int width, int height, List<String> urls) {
        try {
            String aspectRatio = computeAspectRatio(width, height);
            String size = computeSize(width);

            // 1. 提交异步任务
            String taskId = submitTask(prompt, size, aspectRatio, urls);
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
        }
    }

    // 重试次数
    private static final int MAX_RETRIES = 3;
    // 重试退避基数（毫秒）
    private static final long RETRY_BACKOFF_BASE_MS = 5000;

    /**
     * 提交异步图片生成任务（含重试机制）
     */
    private String submitTask(String prompt, String size, String aspectRatio, List<String> urls) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("prompt", prompt);
        requestBody.put("size", size);
        requestBody.put("aspectRatio", aspectRatio);
        if (urls != null && !urls.isEmpty()) {
            requestBody.put("urls", urls);
        }

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.info("Nanobanana2 提交任务参数: {} (prompt长度={})", jsonBody.substring(0, Math.min(jsonBody.length(), 200)) + "...", prompt.length());

        Request request = new Request.Builder()
                .url(wuyinkejiProperties.getBaseUrl() + "/api/async/image_nanoBanana2")
                .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        RuntimeException lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
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
                        // 只对上游超时（500 + Connection timed out）进行重试
                        if (code == 500 && responseBody.contains("timed out") && attempt < MAX_RETRIES) {
                            lastException = new RuntimeException("Nanobanana2 提交任务失败: " + responseBody);
                            long backoff = RETRY_BACKOFF_BASE_MS * (1L << attempt);
                            log.warn("Nanobanana2 上游超时，第{}次重试，等待{}ms", attempt + 1, backoff);
                            try { sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException("重试被中断", ie); }
                            continue;
                        }
                        throw new RuntimeException("Nanobanana2 提交任务失败: " + responseBody);
                    }

                    JsonNode data = root.path("data");
                    String taskId = data.path("id").asText();
                    if (taskId == null || taskId.isEmpty()) {
                        throw new RuntimeException("Nanobanana2 提交任务返回空 taskId: " + responseBody);
                    }
                    if (attempt > 0) {
                        log.info("Nanobanana2 第{}次重试成功, taskId={}", attempt, taskId);
                    }
                    return taskId;
                }
            } catch (IOException e) {
                // 网络层异常也重试
                if (attempt < MAX_RETRIES) {
                    lastException = new RuntimeException("Nanobanana2 提交任务网络异常: " + e.getMessage(), e);
                    long backoff = RETRY_BACKOFF_BASE_MS * (1L << attempt);
                    log.warn("Nanobanana2 网络异常，第{}次重试，等待{}ms: {}", attempt + 1, backoff, e.getMessage());
                    try { sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException("重试被中断", ie); }
                    continue;
                }
                throw new RuntimeException("Nanobanana2 提交任务网络异常: " + e.getMessage(), e);
            }
        }
        throw lastException;
    }

    /**
     * 轮询任务结果，直到成功、失败或超时
     */
    private String pollForResult(String taskId) throws IOException, InterruptedException {
        String url = wuyinkejiProperties.getBaseUrl() + "/api/async/detail?key=" + wuyinkejiProperties.getApiKey() + "&id=" + taskId;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                throw new RuntimeException("Nanobanana2 图片生成超时（" + (TIMEOUT_MS / 1000) + "秒）, taskId=" + taskId);
            }

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.debug("Nanobanana2 查询响应: taskId={}, httpCode={}, body={}", taskId, response.code(), responseBody);
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
                log.info("Nanobanana2 查询结果: taskId={}, status={}", taskId, status);

                switch (status) {
                    case 1: {
                        // 成功 — 优先从 remote_url 取，其次从 result 数组取
                        String remoteUrl = extractImageUrl(data);
                        if (remoteUrl == null || remoteUrl.isEmpty()) {
                            throw new RuntimeException("Nanobanana2 任务成功但无图片URL, taskId=" + taskId);
                        }
                        return remoteUrl;
                    }

                    case 2: {
                        // 可能是成功（result 数组有图）也可能是失败
                        String imageUrl = extractImageUrl(data);
                        if (imageUrl != null && !imageUrl.isEmpty()) {
                            return imageUrl;
                        }
                        String failReason = data.path("fail_reason").asText("");
                        String message = data.path("message").asText("");
                        String reason = !failReason.isEmpty() ? failReason : (!message.isEmpty() ? message : "未知原因");
                        throw new RuntimeException("Nanobanana2 图片生成失败: " + reason + ", taskId=" + taskId);
                    }

                    case 0:
                    case 3:
                    default:
                        // 排队中(0) / 生成中(3)，继续轮log.info("Nanobanana2 任务状态: status={}, taskId={}", status, taskId);
                        sleep(POLL_INTERVAL_MS);
                        break;
                }
            }
        }
    }

    /**
     * 从 API 响应中提取图片 URL
     * 优先取 remote_url 字段，其次取 result 数组第一个元素
     */
    private String extractImageUrl(JsonNode data) {
        String remoteUrl = data.path("remote_url").asText();
        if (remoteUrl != null && !remoteUrl.isEmpty()) {
            return remoteUrl;
        }
        JsonNode result = data.path("result");
        if (result.isArray() && result.size() > 0) {
            String url = result.get(0).asText();
            if (url != null && !url.isEmpty()) {
                return url;
            }
        }
        return null;
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
        // 近似匹配：找最接近的支持比例（误差 < 2%）
        double actual = (double) w / h;
        String closest = "auto";
        double minDiff = Double.MAX_VALUE;
        for (String supported : SUPPORTED_ASPECT_RATIOS) {
            String[] parts = supported.split(":");
            double supportedRatio = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            double diff = Math.abs(actual - supportedRatio) / supportedRatio;
            if (diff < minDiff) {
                minDiff = diff;
                closest = supported;
            }
        }
        if (minDiff < 0.02) {
            log.info("宽高比近似匹配: {}x{} ({}:{}) -> {}", width, height, w, h, closest);
            return closest;
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