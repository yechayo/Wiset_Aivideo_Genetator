# Kling Omni 架构重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Kling 视频生成从 image2video API 迁移到 Omni omni-video API，使用多图参考 + `<<<image_N>>>` prompt 引用替代融合图。

**Architecture:** 后端改写 `KlingVideoService`（端点、请求体、查询端点全部切换到 Omni），在 `VideoGenerationService` 接口新增 `generateOmniAsync` 方法。`PanelProductionService` 新增 Kling 分支实现贪心分组（max 6 镜头 / 7 图 / 15s 每组）。前端更新模型选项并锁定 provider。

**Tech Stack:** Spring Boot 2.7.18 (Java 8), OkHttp, JJWT, React + TypeScript

**Spec:** `docs/superpowers/specs/2026-04-19-kling-omni-architecture-design.md`

---

## File Map

| 文件 | 操作 | 职责 |
|------|------|------|
| `backend/com/comic/src/main/java/com/comic/ai/video/KlingVideoService.java` | 重写 | Omni API 调用：提交、查询、JWT |
| `backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java` | 修改 | 新增 `generateOmniAsync` 接口方法 |
| `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` | 修改 | Kling Omni 分支 + 贪心分组逻辑 |
| `backend/com/comic/src/main/java/com/comic/config/KlingProperties.java` | 修改 | 默认模型改为 `kling-v3-omni` |
| `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` | 修改 | 模型选项更新 + provider 锁定 |
| `backend/com/comic/src/test/java/com/comic/ai/video/KlingOmniVideoServiceTest.java` | 创建 | Omni API 真实集成测试 |

---

### Task 1: KlingProperties 默认模型更新

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/config/KlingProperties.java:33`

- [ ] **Step 1: 修改默认模型名称**

将 `modelName` 默认值从 `kling-v3` 改为 `kling-v3-omni`:

```java
// KlingProperties.java 第 33 行
private String modelName = "kling-v3-omni";
```

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/config/KlingProperties.java
git commit -m "refactor(kling): 默认模型改为 kling-v3-omni"
```

---

### Task 2: VideoGenerationService 接口新增 generateOmniAsync

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java` (在 `generateAsyncMultiShot` 方法后添加)

- [ ] **Step 1: 添加 Omni 接口方法**

在 `VideoGenerationService.java` 第 69 行（`generateAsyncMultiShot` 之后）添加:

```java
/**
 * Omni 多图多镜头视频生成
 *
 * @param imageUrls   参考图 URL 列表（分镜图 + 角色图，最多 7 张）
 * @param multiPrompts 每个镜头的 prompt（含 <<<image_N>>> 引用）和 duration
 * @param totalDuration 总时长（秒，3-15）
 * @param model       视频模型（如 "kling-v3-omni-std", "kling-v3-omni-pro"）
 * @param soundOn     是否生成声音
 * @return 任务ID
 */
default String generateOmniAsync(java.util.List<String> imageUrls,
                                  java.util.List<MultiShotPrompt> multiPrompts,
                                  int totalDuration,
                                  String model,
                                  boolean soundOn) {
    throw new UnsupportedOperationException("Omni 多图多镜头视频生成未实现");
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java
git commit -m "feat(kling): VideoGenerationService 新增 generateOmniAsync 接口方法"
```

---

### Task 3: KlingVideoService Omni 改写

**Files:**
- Rewrite: `backend/com/comic/src/main/java/com/comic/ai/video/KlingVideoService.java`

这是核心任务。将整个 KlingVideoService 从 image2video API 改为 Omni API。

- [ ] **Step 1: 改写常量和 generateAsync 方法**

```java
package com.comic.ai.video;

import com.comic.config.KlingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
@RequiredArgsConstructor
public class KlingVideoService implements VideoGenerationService {

    private static final String OMNI_VIDEO_ENDPOINT = "/v1/videos/omni-video";
    private static final int MAX_SHOTS_PER_GROUP = 6;
    private static final int MAX_IMAGES_PER_GROUP = 7;
    private static final int MAX_DURATION_PER_GROUP = 15;

    private final KlingProperties klingProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Semaphore semaphore = new Semaphore(1);

    // ========== JWT Token 生成 ==========

    /**
     * 生成可灵 API 鉴权 JWT token
     * 格式: Header(alg, typ).Payload(iss=accessKey, exp, nbf).Signature(secretKey)
     */
    private String generateToken() {
        long now = System.currentTimeMillis();
        javax.crypto.SecretKey key = Keys.hmacShaKeyFor(
            klingProperties.getSecretKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuer(klingProperties.getAccessKey())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + 1800 * 1000L))
                .setNotBefore(new Date(now - 5000L))
                .signWith(key)
                .compact();
    }

    // ========== 兼容旧接口（内部转调 Omni） ==========

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        return generateAsync(prompt, duration, aspectRatio, referenceImage, false, klingProperties.getModelName());
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak) {
        return generateAsync(prompt, duration, aspectRatio, referenceImage, offPeak, klingProperties.getModelName());
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak, String model) {
        // 单镜头兼容：用 Omni API，单图 + 单镜头
        List<String> imageUrls = referenceImage != null ? Collections.singletonList(referenceImage) : Collections.emptyList();
        MultiShotPrompt singlePrompt = new MultiShotPrompt(prompt, duration);
        return generateOmniAsync(imageUrls, Collections.singletonList(singlePrompt), duration, model, true);
    }

    @Override
    public String generateAsyncMultiShot(String referenceImage, List<MultiShotPrompt> multiPrompts,
                                          int totalDuration, String model) {
        // 旧多镜头兼容：单图转 Omni
        List<String> imageUrls = referenceImage != null ? Collections.singletonList(referenceImage) : Collections.emptyList();
        return generateOmniAsync(imageUrls, multiPrompts, totalDuration, model, true);
    }

    // ========== Omni 多图多镜头（核心方法） ==========

    @Override
    public String generateOmniAsync(List<String> imageUrls, List<MultiShotPrompt> multiPrompts,
                                     int totalDuration, String model, boolean soundOn) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            // 解析 model: "kling-v3-omni-std" → model_name="kling-v3-omni", mode="std"
            String effectiveModel = (model != null && !model.isEmpty()) ? model : klingProperties.getModelName();
            String effectiveMode = klingProperties.getMode();
            if (effectiveModel.endsWith("-std")) {
                effectiveModel = effectiveModel.substring(0, effectiveModel.length() - 4);
                effectiveMode = "std";
            } else if (effectiveModel.endsWith("-pro")) {
                effectiveModel = effectiveModel.substring(0, effectiveModel.length() - 4);
                effectiveMode = "pro";
            }

            // 构建 image_list
            List<Map<String, Object>> imageList = new ArrayList<>();
            for (String url : imageUrls) {
                Map<String, Object> img = new HashMap<>();
                img.put("image_url", url);
                imageList.add(img);
            }

            // 构建 multi_prompt
            List<Map<String, Object>> multiPromptList = new ArrayList<>();
            for (int i = 0; i < multiPrompts.size(); i++) {
                MultiShotPrompt mp = multiPrompts.get(i);
                Map<String, Object> item = new HashMap<>();
                item.put("index", i + 1);
                item.put("prompt", mp.getPrompt());
                item.put("duration", String.valueOf(mp.getDuration()));
                multiPromptList.add(item);
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model_name", effectiveModel);
            requestBody.put("image_list", imageList);
            requestBody.put("multi_shot", true);
            requestBody.put("shot_type", "customize");
            requestBody.put("multi_prompt", multiPromptList);
            requestBody.put("duration", String.valueOf(totalDuration));
            requestBody.put("mode", effectiveMode);
            requestBody.put("sound", soundOn ? "on" : "off");

            log.info("Kling Omni 提交: images={}, shots={}, totalDuration={}, model={}, mode={}",
                    imageUrls.size(), multiPrompts.size(), totalDuration, effectiveModel, effectiveMode);

            return submitTask(requestBody);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kling Omni 视频生成被中断", e);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    // ========== 任务提交通用方法 ==========

    private String submitTask(Map<String, Object> requestBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Kling Omni 请求体: {}", jsonBody);

            String token = generateToken();
            Request request = new Request.Builder()
                    .url(klingProperties.getBaseUrl() + OMNI_VIDEO_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Kling Omni API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Kling Omni 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Kling Omni 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode codeNode = root.get("code");
                if (codeNode != null && codeNode.asInt() != 0) {
                    String message = root.has("message") ? root.get("message").asText() : "未知错误";
                    throw new RuntimeException("Kling Omni API 错误: code=" + codeNode.asInt() + ", message=" + message);
                }

                JsonNode dataNode = root.get("data");
                if (dataNode == null) throw new RuntimeException("Kling Omni 响应无 data 字段: " + responseBody);

                String taskId = dataNode.get("task_id").asText();
                log.info("Kling Omni 视频任务已提交: taskId={}", taskId);
                return taskId;
            }
        } catch (IOException e) {
            log.error("Kling Omni 视频生成 IO 异常", e);
            throw new RuntimeException("Kling Omni 视频生成失败: " + e.getMessage(), e);
        }
    }

    // ========== 任务查询 ==========

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            String token = generateToken();
            String url = klingProperties.getBaseUrl() + OMNI_VIDEO_ENDPOINT + "/" + taskId;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询 Kling Omni 任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("Kling Omni 任务状态响应: {}", responseBody);
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询 Kling Omni 任务状态 IO 异常: taskId={}", taskId, e);
            return new TaskStatus(taskId, "unknown", 0, null, e.getMessage());
        }
    }

    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.get("data");
            if (dataNode == null) {
                return new TaskStatus(taskId, "unknown", 0, null, "响应无 data 字段");
            }

            String taskStatus = dataNode.has("task_status") ? dataNode.get("task_status").asText() : "unknown";
            String normalized = normalizeStatus(taskStatus);

            String videoUrl = null;
            Integer credits = null;
            String errorMsg = null;

            JsonNode taskResult = dataNode.get("task_result");
            if (taskResult != null) {
                JsonNode videos = taskResult.get("videos");
                if (videos != null && videos.isArray() && videos.size() > 0) {
                    JsonNode firstVideo = videos.get(0);
                    JsonNode urlNode = firstVideo.get("url");
                    if (urlNode != null) videoUrl = urlNode.asText();
                }
            }

            JsonNode deductionNode = dataNode.get("final_unit_deduction");
            if (deductionNode != null && !deductionNode.isNull()) {
                try { credits = Integer.parseInt(deductionNode.asText()); } catch (NumberFormatException ignored) {}
            }

            JsonNode statusMsgNode = dataNode.get("task_status_msg");
            if (statusMsgNode != null && !statusMsgNode.isNull()) {
                errorMsg = statusMsgNode.asText();
            }

            int progress = calculateProgress(taskStatus);
            return new TaskStatus(taskId, normalized, progress, videoUrl, errorMsg, null, null, credits);
        } catch (Exception e) {
            log.error("解析 Kling Omni 任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        TaskStatus status = getTaskStatus(taskId);
        if (status.isCompleted() && status.getVideoUrl() != null) return status.getVideoUrl();
        if (status.isFailed()) throw new RuntimeException("Kling Omni 视频生成失败: " + status.getErrorMessage());
        throw new RuntimeException("Kling Omni 视频尚未生成完成，当前状态: " + status.getStatus());
    }

    @Override
    public String getServiceName() { return "Kling-Omni-Video"; }

    private String normalizeStatus(String status) {
        if (status == null) return "unknown";
        switch (status) {
            case "submitted": return "pending";
            case "processing": return "processing";
            case "succeed": return "completed";
            case "failed": return "failed";
            default: return "unknown";
        }
    }

    private int calculateProgress(String status) {
        if (status == null) return 0;
        switch (status) {
            case "submitted": return 20;
            case "processing": return 50;
            case "succeed": return 100;
            case "failed": return 0;
            default: return 0;
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/video/KlingVideoService.java
git commit -m "refactor(kling): KlingVideoService 从 image2video 迁移到 Omni omni-video API"
```

---

### Task 4: PanelProductionService Kling Omni 分支 + 贪心分组

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

**改动范围**: 第 510-604 行 (`doGenerateVideoByPanelId` 方法)

- [ ] **Step 1: 移除 Kling 对 fusionImageUrl 的强制依赖**

第 521-524 行的融合图检查需要改为：仅非 Kling provider 要求融合图。

将:
```java
String fusionImageUrl = getStr(info, "fusionImageUrl");
if (fusionImageUrl == null) {
    throw new BusinessException("融合参考图不存在，请先生成九宫格");
}
```

改为:
```java
String fusionImageUrl = getStr(info, "fusionImageUrl");
String projectId = getProjectIdByPanelIdForProvider(panelId);
String videoProvider = getVideoProvider(projectId != null ? projectId : "");
// 仅非 Kling provider 要求融合图
if (!"kling".equals(videoProvider) && fusionImageUrl == null) {
    throw new BusinessException("融合参考图不存在，请先生成九宫格");
}
```

同时将第 547 行的 `projectId` 声明移到 fusionImageUrl 检查之前（因为上面已经声明了，需要去掉重复声明）。

注意：第 547 行原有的 `String projectId = ...` 需要删除，因为上面已声明。

- [ ] **Step 2: 替换 Kling 多镜头分支为 Omni 贪心分组**

将第 558-586 行的 Kling 分支替换为:

```java
String taskId;
if ("kling".equals(videoProvider)) {
    // ========== Kling Omni：多图参考 + 贪心分组 ==========
    taskId = submitKlingOmniGroups(panel, info, shots, totalDuration, videoService, videoModel);
} else {
    // 非 Kling：使用融合图
    if (fusionImageUrl == null) {
        throw new BusinessException("融合参考图不存在，请先生成九宫格");
    }
    taskId = videoService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak, videoModel);
}
```

- [ ] **Step 3: 实现 submitKlingOmniGroups 方法**

在 `PanelProductionService` 中添加新方法（在 `doGenerateVideoByPanelId` 方法之后）:

```java
/**
 * Kling Omni 贪心分组提交
 * 约束：每组镜头≤6, 图片≤7, 时长≤15s
 * image_list 填充策略：分镜图优先 + 角色图补位
 */
@SuppressWarnings("unchecked")
private String submitKlingOmniGroups(Panel panel, Map<String, Object> info,
                                     List<Map<String, Object>> shots, int totalDuration,
                                     VideoGenerationService videoService, String videoModel) {
    // 1. 收集分镜图 URL
    List<String> shotImageUrls = new ArrayList<>();
    if (shots != null) {
        for (Map<String, Object> shot : shots) {
            String url = (String) shot.get("splitImageUrl");
            if (url != null && !url.isEmpty()) shotImageUrls.add(url);
        }
    }

    // 2. 收集角色参考图（仅当前 panel 出场角色）
    List<String> charImageUrls = new ArrayList<>();
    List<String> charNames = new ArrayList<>();
    java.util.Set<String> panelCharNames = new java.util.HashSet<>();
    if (shots != null) {
        for (Map<String, Object> shot : shots) {
            List<String> chars = (List<String>) shot.get("characters");
            if (chars != null) panelCharNames.addAll(chars);
        }
    }
    Long episodeId = panel.getEpisodeId();
    List<GridImageService.CharRef> charRefs = gridImageService.getCharacterReferencesWithNamesForEpisode(episodeId);
    for (GridImageService.CharRef cr : charRefs) {
        if (cr.url != null && !cr.url.isEmpty()
            && cr.name != null && panelCharNames.contains(cr.name)) {
            charImageUrls.add(cr.url);
            charNames.add(cr.name);
        }
    }

    // 3. 贪心分组
    List<KlingOmniGroup> groups = buildOmniGroups(shots, shotImageUrls, totalDuration);

    // 4. 每组提交（暂时只支持单组，多组需要后续拼接）
    // TODO: 多组视频拼接（当前一个 panel 生成一个视频任务）
    KlingOmniGroup primaryGroup = groups.get(0);

    // 5. 构建 image_list：分镜图优先 + 角色图补位
    List<String> imageList = new ArrayList<>(primaryGroup.shotImageUrls);
    int remaining = 7 - imageList.size();
    for (int i = 0; i < remaining && i < charImageUrls.size(); i++) {
        if (!imageList.contains(charImageUrls.get(i))) {
            imageList.add(charImageUrls.get(i));
        }
    }

    // 6. 构建 multi_prompt（含 <<<image_N>>> 引用）
    int shotCount = primaryGroup.shotImageUrls.size();
    List<VideoGenerationService.MultiShotPrompt> omniPrompts = new ArrayList<>();
    for (int i = 0; i < primaryGroup.shots.size(); i++) {
        Map<String, Object> shot = primaryGroup.shots.get(i);
        String desc = getStr(shot, "visualDescription");
        if (desc == null || desc.isEmpty()) desc = getStr(shot, "sceneDescription");
        if (desc == null) desc = "";

        int shotDuration = 3;
        Object dur = shot.get("duration");
        if (dur instanceof Number) shotDuration = ((Number) dur).intValue();
        if (shotDuration < 1) shotDuration = 1;

        // 构建 prompt：<<<image_N>>> + 镜头描述
        StringBuilder promptBuilder = new StringBuilder();
        if (i < shotCount) {
            promptBuilder.append("<<<image_").append(i + 1).append(">>> ");
        }
        promptBuilder.append(desc);

        // 角色图引用
        for (int j = 0; j < charImageUrls.size() && (shotCount + j) < imageList.size(); j++) {
            promptBuilder.append(", ").append(charNames.get(j))
                .append(" <<<image_").append(shotCount + j + 1).append(">>>");
        }

        omniPrompts.add(new VideoGenerationService.MultiShotPrompt(promptBuilder.toString(), shotDuration));
    }

    // 时长校准
    int shotSum = omniPrompts.stream().mapToInt(VideoGenerationService.MultiShotPrompt::getDuration).sum();
    if (shotSum != primaryGroup.totalDuration && !omniPrompts.isEmpty()) {
        int diff = primaryGroup.totalDuration - shotSum;
        VideoGenerationService.MultiShotPrompt last = omniPrompts.get(omniPrompts.size() - 1);
        int adjusted = last.getDuration() + diff;
        if (adjusted < 1) adjusted = 1;
        omniPrompts.set(omniPrompts.size() - 1,
            new VideoGenerationService.MultiShotPrompt(last.getPrompt(), adjusted));
    }

    log.info("Kling Omni 分组: panelId={}, images={}, shots={}, duration={}, groups={}",
        panel.getId(), imageList.size(), omniPrompts.size(), primaryGroup.totalDuration, groups.size());

    return videoService.generateOmniAsync(imageList, omniPrompts, primaryGroup.totalDuration, videoModel, true);
}

/**
 * 贪心分组数据结构
 */
private static class KlingOmniGroup {
    List<Map<String, Object>> shots = new ArrayList<>();
    List<String> shotImageUrls = new ArrayList<>();
    int totalDuration = 0;
}

/**
 * 贪心分组算法
 * 约束：每组镜头≤6, 图片≤7(镜头数+角色数), 时长≤15s
 */
private List<KlingOmniGroup> buildOmniGroups(List<Map<String, Object>> shots,
                                              List<String> shotImageUrls, int totalDuration) {
    if (shots == null || shots.isEmpty()) {
        KlingOmniGroup single = new KlingOmniGroup();
        single.totalDuration = Math.max(totalDuration, 3);
        return Collections.singletonList(single);
    }

    List<KlingOmniGroup> groups = new ArrayList<>();
    KlingOmniGroup current = new KlingOmniGroup();

    for (int i = 0; i < shots.size(); i++) {
        Map<String, Object> shot = shots.get(i);
        int shotDuration = 3;
        Object dur = shot.get("duration");
        if (dur instanceof Number) shotDuration = ((Number) dur).intValue();
        if (shotDuration < 1) shotDuration = 1;

        boolean wouldExceedShots = current.shots.size() >= 6;
        boolean wouldExceedDuration = current.totalDuration + shotDuration > 15;

        if ((wouldExceedShots || wouldExceedDuration) && !current.shots.isEmpty()) {
            groups.add(current);
            current = new KlingOmniGroup();
        }

        current.shots.add(shot);
        if (i < shotImageUrls.size()) {
            current.shotImageUrls.add(shotImageUrls.get(i));
        }
        current.totalDuration += shotDuration;
    }

    if (!current.shots.isEmpty()) {
        groups.add(current);
    }

    // 校正每组最小时长为 3s
    for (KlingOmniGroup g : groups) {
        if (g.totalDuration < 3) g.totalDuration = 3;
    }

    return groups;
}
```

- [ ] **Step 4: 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat(kling): PanelProductionService Kling Omni 贪心分组 + 多图参考"
```

---

### Task 5: 前端 Step4 模型选项更新

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

**改动点**: 模型类型从 `kling-v3-std`/`kling-v3-pro` 改为 `kling-v3-omni-std`/`kling-v3-omni-pro`

- [ ] **Step 1: 更新 videoModel 类型定义**

找到 videoModel 的 `useState` 类型定义（约第 424 行），将 Kling 模型名改为 Omni:

```typescript
const [videoModel, setVideoModel] = useState<'pro' | 'mix' | 'q3' | 'turbo' | 'kling-v3-omni-std' | 'kling-v3-omni-pro'>(() =>
  (localStorage.getItem('video_model') as 'pro' | 'mix' | 'q3' | 'turbo' | 'kling-v3-omni-std' | 'kling-v3-omni-pro') || 'turbo'
);
```

- [ ] **Step 2: 更新 useEffect 中的 Kling model 同步**

将:
```typescript
if (isKling && videoModel !== 'kling-v3-std' && videoModel !== 'kling-v3-pro') {
  setVideoModel('kling-v3-std');
}
```

改为:
```typescript
if (isKling && videoModel !== 'kling-v3-omni-std' && videoModel !== 'kling-v3-omni-pro') {
  setVideoModel('kling-v3-omni-std');
}
```

- [ ] **Step 3: 更新 handleVideoProviderChange 中的 Kling model 默认值**

将 `setVideoModel('kling-v3-std')` 全部改为 `setVideoModel('kling-v3-omni-std')`。
将 `updates.videoModel = 'kling-v3-std'` 改为 `updates.videoModel = 'kling-v3-omni-std'`。

- [ ] **Step 4: 更新 UI 中的 Kling model 切换按钮**

将 `videoModel === 'kling-v3-std'` 改为 `videoModel === 'kling-v3-omni-std'`。
将 `videoModel === 'kling-v3-pro'` 改为 `videoModel === 'kling-v3-omni-pro'`。
将 `updateProject(projectId, { videoModel: 'kling-v3-std' })` 改为 `updateProject(projectId, { videoModel: 'kling-v3-omni-std' })`。
将 `updateProject(projectId, { videoModel: 'kling-v3-pro' })` 改为 `updateProject(projectId, { videoModel: 'kling-v3-omni-pro' })`。

- [ ] **Step 5: 全局搜索替换确认**

搜索整个文件中所有 `kling-v3-std` 和 `kling-v3-pro` 出现的地方，全部替换为对应的 Omni 版本。

- [ ] **Step 6: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat(kling): 前端模型选项更新为 kling-v3-omni-std/pro"
```

---

### Task 6: Omni API 集成测试

**Files:**
- Modify: `backend/com/comic/src/test/java/com/comic/ai/video/KlingVideoServiceTest.java`（已有，更新为 Omni）

- [ ] **Step 1: 更新测试为 Omni API 调用**

将现有测试方法中的 `generateAsync` 调用改为 `generateOmniAsync`，传入 `image_list` 和 `multi_prompt`:

```java
@Test
void testOmniSingleShotGeneration() throws Exception {
    KlingProperties props = new KlingProperties();
    // 从 application.yml 或环境变量读取
    props.setAccessKey(System.getenv().getOrDefault("KLING_ACCESS_KEY", "ARQneJtTMHMNAyhLNA4m94ytfgQggDJL"));
    props.setSecretKey(System.getenv().getOrDefault("KLING_SECRET_KEY", "bNdCMGJCFDTkQDAmGPB4et8yRPPMPNQT"));

    OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build();
    ObjectMapper mapper = new ObjectMapper();

    KlingVideoService service = new KlingVideoService(props, client, mapper);

    // 使用一张参考图 + 单镜头
    String imageUrl = "https://img.zcool.cn/community/01ea9e5c7c7d9ea801208f8b4e7e7f.jpg";
    List<String> images = Collections.singletonList(imageUrl);
    List<VideoGenerationService.MultiShotPrompt> prompts = Collections.singletonList(
        new VideoGenerationService.MultiShotPrompt("一只可爱的猫咪在草地上奔跑，阳光明媚", 5)
    );

    String taskId = service.generateOmniAsync(images, prompts, 5, "kling-v3-omni-std", true);
    assertNotNull(taskId);
    System.out.println("Omni Task ID: " + taskId);

    // 轮询等待完成（最多 5 分钟）
    for (int i = 0; i < 60; i++) {
        Thread.sleep(5000);
        VideoGenerationService.TaskStatus status = service.getTaskStatus(taskId);
        System.out.println("Poll " + i + ": status=" + status.getStatus() + " progress=" + status.getProgress());

        if (status.isCompleted()) {
            System.out.println("Video URL: " + status.getVideoUrl());
            System.out.println("Credits: " + status.getCredits());
            assertNotNull(status.getVideoUrl());
            return;
        }
        if (status.isFailed()) {
            fail("Video generation failed: " + status.getErrorMessage());
            return;
        }
    }
    fail("Timeout waiting for video generation");
}
```

- [ ] **Step 2: 运行测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -Dtest=KlingVideoServiceTest#testOmniSingleShotGeneration -pl . -q`
Expected: PASS (视频生成成功)

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/test/java/com/comic/ai/video/KlingVideoServiceTest.java
git commit -m "test(kling): Omni API 集成测试"
```

---

### Task 7: 端到端验证 + 清理

- [ ] **Step 1: 全项目编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全局搜索残留旧 API 引用**

搜索项目中所有 `image2video` 字符串引用，确认没有残留:
```bash
grep -r "image2video" backend/com/comic/src/ --include="*.java"
```
Expected: 0 matches (所有引用已迁移到 omni-video)

- [ ] **Step 3: 搜索残留旧模型名**

搜索项目中所有 `kling-v3-std` 和 `kling-v3-pro` 字符串（不包含 `omni`）:
```bash
grep -r "kling-v3-std\|kling-v3-pro" --include="*.java" --include="*.tsx" --include="*.ts" backend/ frontend/
```
Expected: 0 matches

- [ ] **Step 4: 最终提交（如有清理改动）**

```bash
git add -A
git commit -m "chore(kling): 清理残留 image2video 引用和旧模型名"
```
