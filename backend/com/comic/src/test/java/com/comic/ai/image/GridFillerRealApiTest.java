package com.comic.ai.image;

import com.comic.ai.PanelPromptBuilder;
import com.comic.service.panel.GridImageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宫格图占位场景填充 — Nanobanana 真实 API 测试
 *
 * 测试场景：7个分镜 + 3x3网格 = 2个占位格子
 * 验证：占位场景 prompt 是否正确构建 + 图片生成 + 切割
 */
class GridFillerRealApiTest {

    private static final int GRID_WIDTH = 3840;
    private static final int GRID_HEIGHT = 2160;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final long TIMEOUT_MS = 300_000; // 5分钟超时

    private static OkHttpClient httpClient;
    private static ObjectMapper objectMapper;
    private static String apiKey;
    private static String baseUrl;
    private static PanelPromptBuilder promptBuilder;

    @BeforeAll
    static void setUp() {
        apiKey = readEnvOrFile("WUYINKEJI_API_KEY");
        baseUrl = "https://api.wuyinkeji.com";

        System.out.println("API Key: " + apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4));

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        objectMapper = new ObjectMapper();
        promptBuilder = new PanelPromptBuilder();
    }

    @Test
    @DisplayName("7分镜 + 3x3网格 — Nanobanana 占位场景填充真实 API 测试")
    void testGridWithFillerScenes() throws Exception {
        // === 1. 构造7个分镜（3x3网格有2个空位） ===
        List<Map<String, Object>> shots = new ArrayList<>();
        String[] scenes = {
                "中景 — 林晓星站在天台上，俯瞰城市夜景，风吹起她的长发",
                "近景 — 林晓星的侧脸，眼神坚定，远处城市灯光模糊",
                "全景 — 城市夜景全景，高楼林立，霓虹闪烁",
                "中景 — 陈墨从阴影中走出，嘴角带着冷笑",
                "特写 — 陈墨手中的U盘，反射着屏幕的蓝光",
                "中景 — 林晓星转身面对陈墨，双手握拳",
                "远景 — 天台上两人的对峙剪影，背后是巨大的月亮"
        };
        for (String scene : scenes) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", scene);
            shot.put("scene", "城市天台");
            shot.put("shotSize", "中景");
            shot.put("cameraAngle", "平视");
            shots.add(shot);
        }

        // === 2. 构建 Prompt ===
        String prompt = promptBuilder.buildGridPrompt("ANIME", shots, null, 3, 3);

        System.out.println("\n========== PROMPT (前800字) ==========");
        System.out.println(prompt.substring(0, Math.min(800, prompt.length())));
        System.out.println("... (总长度: " + prompt.length() + " 字符)\n");

        // 验证 prompt 不包含黑格指令
        assertFalse(prompt.contains("纯黑色填充"), "Prompt 不应包含黑格指令");
        // 验证 prompt 包含占位场景
        assertTrue(prompt.contains("无角色"), "Prompt 应包含占位场景");
        assertTrue(prompt.contains("第3行第1列"), "应有第3行第1列占位");
        assertTrue(prompt.contains("第3行第2列"), "应有第3行第2列占位");

        // === 3. 提交 Nanobanana 异步任务 ===
        System.out.println("提交 Nanobanana 异步图片生成任务...");
        String taskId = submitTask(prompt);
        System.out.println("任务已提交, taskId=" + taskId);

        // === 4. 轮询等待结果 ===
        System.out.println("等待生成完成（轮询中）...");
        String imageUrl = pollForResult(taskId);
        System.out.println("图片 URL: " + imageUrl);

        // === 5. 下载图片 ===
        System.out.println("下载图片...");
        BufferedImage gridImage = downloadImage(imageUrl);
        System.out.println("图片尺寸: " + gridImage.getWidth() + "x" + gridImage.getHeight());

        // === 6. 保存原始宫格图 ===
        File outputDir = new File("test-output");
        outputDir.mkdirs();
        File gridFile = new File(outputDir, "grid-filler-7shots-3x3.png");
        ImageIO.write(gridImage, "png", gridFile);
        System.out.println("原始宫格图已保存: " + gridFile.getAbsolutePath());

        // === 7. 切割宫格 ===
        List<BufferedImage> subImages = GridImageService.splitGridImage(gridImage, 3, 3);
        System.out.println("切割得到 " + subImages.size() + " 个子图");
        assertEquals(9, subImages.size(), "3x3网格应切割为9个子图");

        // === 8. 保存所有子图 ===
        for (int i = 0; i < subImages.size(); i++) {
            String label = (i < 7) ? "shot" : "filler";
            File subFile = new File(outputDir, "grid-" + label + "-" + (i + 1) + ".png");
            ImageIO.write(subImages.get(i), "png", subFile);
            BufferedImage sub = subImages.get(i);
            System.out.println("  子图" + (i + 1) + " (" + label + "): " + sub.getWidth() + "x" + sub.getHeight());
        }

        // === 9. 基本验证 ===
        BufferedImage firstSub = subImages.get(0);
        assertTrue(firstSub.getWidth() > 100, "子图宽度应大于100px");
        assertTrue(firstSub.getHeight() > 50, "子图高度应大于50px");

        System.out.println("\n========== 测试完成 ==========");
        System.out.println("请检查 test-output/ 目录:");
        System.out.println("  grid-filler-7shots-3x3.png  — 完整宫格");
        System.out.println("  grid-shot-*.png             — 实际分镜 (1-7)");
        System.out.println("  grid-filler-*.png           — 占位场景 (8-9)");
    }

    // ==================== Nanobanana API ====================

    private String submitTask(String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("prompt", prompt);
        requestBody.put("size", "4K");
        requestBody.put("aspectRatio", "16:9");

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        System.out.println("请求参数: " + jsonBody.substring(0, Math.min(200, jsonBody.length())) + "...");

        Request request = new Request.Builder()
                .url(baseUrl + "/api/async/image_nanoBanana2")
                .addHeader("Authorization", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("提交任务失败: " + response.code() + " - " + responseBody);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                throw new RuntimeException("提交任务返回错误: " + responseBody);
            }
            String taskId = root.path("data").path("id").asText();
            if (taskId == null || taskId.isEmpty()) {
                throw new RuntimeException("返回空 taskId: " + responseBody);
            }
            return taskId;
        }
    }

    private String pollForResult(String taskId) throws Exception {
        String url = baseUrl + "/api/async/detail?key=" + apiKey + "&id=" + taskId;
        Request request = new Request.Builder().url(url).get().build();

        long startTime = System.currentTimeMillis();
        while (true) {
            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                throw new RuntimeException("生成超时 (" + (TIMEOUT_MS / 1000) + "s), taskId=" + taskId);
            }

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    System.out.println("  查询失败: " + response.code() + "，3秒后重试...");
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                JsonNode root = objectMapper.readTree(responseBody);
                int code = root.path("code").asInt(-1);
                if (code != 200) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                JsonNode data = root.path("data");
                int status = data.path("status").asInt(-1);
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("  [" + elapsed + "s] status=" + status);

                switch (status) {
                    case 1: {
                        String imageUrl = extractImageUrl(data);
                        if (imageUrl != null && !imageUrl.isEmpty()) return imageUrl;
                        throw new RuntimeException("任务成功但无图片URL, taskId=" + taskId);
                    }
                    case 2: {
                        String imageUrl = extractImageUrl(data);
                        if (imageUrl != null && !imageUrl.isEmpty()) return imageUrl;
                        String failReason = data.path("fail_reason").asText("");
                        throw new RuntimeException("生成失败: " + failReason + ", taskId=" + taskId);
                    }
                    default:
                        Thread.sleep(POLL_INTERVAL_MS);
                        break;
                }
            }
        }
    }

    private String extractImageUrl(JsonNode data) {
        String remoteUrl = data.path("remote_url").asText();
        if (remoteUrl != null && !remoteUrl.isEmpty()) return remoteUrl;
        JsonNode result = data.path("result");
        if (result.isArray() && result.size() > 0) {
            String url = result.get(0).asText();
            if (url != null && !url.isEmpty()) return url;
        }
        return null;
    }

    // ==================== 工具方法 ====================

    private BufferedImage downloadImage(String url) throws Exception {
        try (InputStream is = new URL(url).openStream()) {
            BufferedImage img = ImageIO.read(is);
            assertNotNull(img, "下载的图片不应为 null");
            return img;
        }
    }

    private static String readEnvOrFile(String key) {
        String val = System.getenv(key);
        if (val != null && !val.isEmpty()) return val;

        // 按优先级搜索 .env 文件
        String[] paths = {".env", "../.env", "com/.env", "../../com/.env", "backend/com/.env"};
        for (String p : paths) {
            try {
                java.io.File envFile = new java.io.File(p);
                if (envFile.exists()) {
                    for (String line : java.nio.file.Files.readAllLines(envFile.toPath())) {
                        line = line.trim();
                        if (line.startsWith(key + "=")) {
                            String v = line.substring(key.length() + 1).trim();
                            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                            System.out.println("从 " + p + " 读取到 " + key);
                            return v;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        throw new IllegalStateException("请设置 " + key + " 环境变量或在 .env 文件中配置");
    }
}
