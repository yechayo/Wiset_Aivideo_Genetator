# 可灵 V3 视频生成集成 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将可灵 v3 图生视频模型集成为新的 `kling` provider，支持单镜头和多镜头视频生成，前后端完整适配。

**Architecture:** 新建 `KlingVideoService` 实现 `VideoGenerationService` 接口，通过 `AiServiceConfiguration` 注册为 `kling` provider。多镜头模式下将 shots 转换为可灵的 `multi_prompt` 结构化参数。前端在 Step1（项目配置）和 Step4（生产页）添加 Kling provider 选项和模型选择。

**Tech Stack:** Spring Boot 2.7.18 (Java 8), OkHttp, jjwt 0.11.5, React + TypeScript

**Spec:** `docs/superpowers/specs/2026-04-19-kling-v3-integration-design.md`

---

## Phase 1: 后端基础（不影响现有代码）

### Task 1: KlingProperties 配置类

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/config/KlingProperties.java`
- Reference: `backend/com/comic/src/main/java/com/comic/config/ViduProperties.java`

- [ ] **Step 1: 创建 KlingProperties 类**

```java
package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "comic.kling")
public class KlingProperties {
    private String accessKey;
    private String secretKey;
    private String baseUrl = "https://api-beijing.klingai.com";
    private String modelName = "kling-v3";
    private String mode = "std";
}
```

- [ ] **Step 2: 在 application.yml 添加配置段**

在 `comic:` 下（`vidu:` 同级）添加：

```yaml
  kling:
    access-key: ${KLING_ACCESS_KEY:}
    secret-key: ${KLING_SECRET_KEY:}
    base-url: https://api-beijing.klingai.com
    model-name: kling-v3
    mode: std
```

文件: `backend/com/comic/src/main/resources/application.yml`

- [ ] **Step 3: 验证编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/config/KlingProperties.java backend/com/comic/src/main/resources/application.yml
git commit -m "feat(kling): add KlingProperties config class and application.yml entry"
```

---

### Task 2: VideoGenerationService 接口扩展

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java`

- [ ] **Step 1: 在接口内添加 MultiShotPrompt 内部类**

在 `TaskStatus` 类之后添加：

```java
/**
 * 多镜头参数（用于可灵等支持结构化多镜头的模型）
 */
class MultiShotPrompt {
    private final String prompt;
    private final int duration;

    public MultiShotPrompt(String prompt, int duration) {
        this.prompt = prompt;
        this.duration = duration;
    }

    public String getPrompt() { return prompt; }
    public int getDuration() { return duration; }
}
```

- [ ] **Step 2: 添加 generateAsyncMultiShot default 方法**

在 `generateAsyncMultiImage` 方法之后添加：

```java
/**
 * 多镜头视频生成（结构化参数模式）
 *
 * @param referenceImage 参考图 URL
 * @param multiPrompts   每个镜头的 prompt 和 duration
 * @param totalDuration  总时长（秒）
 * @param model          视频模型
 * @param mode           模式（std/pro），可为 null
 * @return 任务ID
 */
default String generateAsyncMultiShot(String referenceImage,
                                       java.util.List<MultiShotPrompt> multiPrompts,
                                       int totalDuration,
                                       String model) {
    throw new UnsupportedOperationException("多镜头视频生成未实现");
}
```

- [ ] **Step 3: 验证编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS（Vidu/Grok 不受影响，default 方法无需实现）

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java
git commit -m "feat(video): add MultiShotPrompt and generateAsyncMultiShot to VideoGenerationService interface"
```

---

### Task 3: KlingVideoService 核心实现

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/ai/video/KlingVideoService.java`
- Reference: `backend/com/comic/src/main/java/com/comic/ai/video/ViduVideoService.java`
- Reference: `docs/可灵图生视频api.md`

- [ ] **Step 1: 创建 KlingVideoService 基础结构**

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

    private static final String IMAGE2VIDEO_ENDPOINT = "/v1/videos/image2video";

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
                .setNotBefore(new Date(now))
                .signWith(key)
                .compact();
    }

    // ========== 单镜头视频生成 ==========

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
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            String effectiveModel = (model != null && !model.isEmpty()) ? model : klingProperties.getModelName();
            String effectiveMode = klingProperties.getMode();
            // model 格式: "kling-v3-std" 或 "kling-v3-pro" → 提取 model_name 和 mode
            if (effectiveModel.contains("-std")) {
                effectiveModel = effectiveModel.replace("-std", "");
                effectiveMode = "std";
            } else if (effectiveModel.contains("-pro")) {
                effectiveModel = effectiveModel.replace("-pro", "");
                effectiveMode = "pro";
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model_name", effectiveModel);
            requestBody.put("image", referenceImage);
            requestBody.put("prompt", prompt);
            requestBody.put("duration", String.valueOf(duration));
            requestBody.put("mode", effectiveMode);

            return submitTask(requestBody);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kling 视频生成被中断", e);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    // ========== 多镜头视频生成 ==========

    @Override
    public String generateAsyncMultiShot(String referenceImage, List<MultiShotPrompt> multiPrompts,
                                          int totalDuration, String model) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            String effectiveModel = (model != null && !model.isEmpty()) ? model : klingProperties.getModelName();
            String effectiveMode = klingProperties.getMode();
            // model 格式: "kling-v3-std" → model_name="kling-v3", mode="std"
            if (effectiveModel.contains("-std")) {
                effectiveModel = effectiveModel.replace("-std", "");
                effectiveMode = "std";
            } else if (effectiveModel.contains("-pro")) {
                effectiveModel = effectiveModel.replace("-pro", "");
                effectiveMode = "pro";
            }

            // 构建 multi_prompt 数组
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
            requestBody.put("image", referenceImage);
            requestBody.put("multi_shot", true);
            requestBody.put("shot_type", "customize");
            requestBody.put("multi_prompt", multiPromptList);
            requestBody.put("duration", String.valueOf(totalDuration));
            requestBody.put("mode", effectiveMode);

            log.info("Kling 多镜头提交: shots={}, totalDuration={}, model={}, mode={}",
                    multiPrompts.size(), totalDuration, effectiveModel, effectiveMode);

            return submitTask(requestBody);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kling 多镜头视频生成被中断", e);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    // ========== 任务提交通用方法 ==========

    private String submitTask(Map<String, Object> requestBody) {
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Kling 请求体: {}", jsonBody);

            String token = generateToken();
            Request request = new Request.Builder()
                    .url(klingProperties.getBaseUrl() + IMAGE2VIDEO_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Kling API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Kling 视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Kling 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode codeNode = root.get("code");
                if (codeNode != null && codeNode.asInt() != 0) {
                    String message = root.has("message") ? root.get("message").asText() : "未知错误";
                    throw new RuntimeException("Kling API 错误: code=" + codeNode.asInt() + ", message=" + message);
                }

                JsonNode dataNode = root.get("data");
                if (dataNode == null) throw new RuntimeException("Kling 响应无 data 字段: " + responseBody);

                String taskId = dataNode.get("task_id").asText();
                log.info("Kling 视频任务已提交: taskId={}", taskId);
                return taskId;
            }
        } catch (IOException e) {
            log.error("Kling 视频生成 IO 异常", e);
            throw new RuntimeException("Kling 视频生成失败: " + e.getMessage(), e);
        }
    }

    // ========== 任务查询 ==========

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            String token = generateToken();
            String url = klingProperties.getBaseUrl() + IMAGE2VIDEO_ENDPOINT + "/" + taskId;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询 Kling 任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                log.debug("Kling 任务状态响应: {}", responseBody);
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询 Kling 任务状态 IO 异常: taskId={}", taskId, e);
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

            // 视频URL
            JsonNode taskResult = dataNode.get("task_result");
            if (taskResult != null) {
                JsonNode videos = taskResult.get("videos");
                if (videos != null && videos.isArray() && videos.size() > 0) {
                    JsonNode firstVideo = videos.get(0);
                    JsonNode urlNode = firstVideo.get("url");
                    if (urlNode != null) videoUrl = urlNode.asText();
                }
            }

            // 积分消耗
            JsonNode deductionNode = dataNode.get("final_unit_deduction");
            if (deductionNode != null && !deductionNode.isNull()) {
                try { credits = Integer.parseInt(deductionNode.asText()); } catch (NumberFormatException ignored) {}
            }

            // 错误信息
            JsonNode statusMsgNode = dataNode.get("task_status_msg");
            if (statusMsgNode != null && !statusMsgNode.isNull()) {
                errorMsg = statusMsgNode.asText();
            }

            int progress = calculateProgress(taskStatus);
            return new TaskStatus(taskId, normalized, progress, videoUrl, errorMsg, null, null, credits);
        } catch (Exception e) {
            log.error("解析 Kling 任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        TaskStatus status = getTaskStatus(taskId);
        if (status.isCompleted() && status.getVideoUrl() != null) return status.getVideoUrl();
        if (status.isFailed()) throw new RuntimeException("Kling 视频生成失败: " + status.getErrorMessage());
        throw new RuntimeException("Kling 视频尚未生成完成，当前状态: " + status.getStatus());
    }

    @Override
    public String getServiceName() { return "Kling-Video"; }

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

- [ ] **Step 2: 验证编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/video/KlingVideoService.java
git commit -m "feat(kling): add KlingVideoService with single-shot and multi-shot support"
```

---

### Task 4: AiServiceConfiguration 注册 Kling provider

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/config/AiServiceConfiguration.java`

- [ ] **Step 1: 添加 KlingVideoService 到构造函数和 videoMap**

在构造函数参数添加 `KlingVideoService klingVideoService`，在 videoMap 中添加注册：

构造函数参数改为：
```java
public AiServiceConfiguration(
        SeedreamImageService seedreamImageService,
        NanobananaImageService nanobananaImageService,
        ViduVideoService viduVideoService,
        GrokVideoService grokVideoService,
        KlingVideoService klingVideoService
) {
```

videoMap 添加一行：
```java
videoMap.put("kling", klingVideoService);
```

- [ ] **Step 2: 验证编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/config/AiServiceConfiguration.java
git commit -m "feat(kling): register KlingVideoService in AiServiceConfiguration"
```

---

## Phase 2: 后端集成（最小改动）

### Task 5: PanelProductionService 添加 Kling 多镜头分支

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: 在 doGenerateVideoByPanelId 方法中添加 Kling 分支**

找到 `doGenerateVideoByPanelId` 方法中构建 taskId 的位置（约第 549-556 行），将：

```java
String projectId = getProjectIdByPanelIdForProvider(panelId);
VideoGenerationService videoService = aiServiceConfig.getVideoService(
    getVideoProvider(projectId != null ? projectId : ""));
// 优先使用请求级别的 videoModel 覆盖，其次使用项目级配置
String videoModel = (overrideVideoModel != null && !overrideVideoModel.trim().isEmpty())
    ? overrideVideoModel
    : (projectId != null ? getVideoModel(projectId) : null);
String taskId = videoService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak, videoModel);
```

替换为：

```java
String projectId = getProjectIdByPanelIdForProvider(panelId);
String videoProvider = getVideoProvider(projectId != null ? projectId : "");
VideoGenerationService videoService = aiServiceConfig.getVideoService(videoProvider);
// 优先使用请求级别的 videoModel 覆盖，其次使用项目级配置
String videoModel = (overrideVideoModel != null && !overrideVideoModel.trim().isEmpty())
    ? overrideVideoModel
    : (projectId != null ? getVideoModel(projectId) : null);

// Kling 多镜头：将 shots 转为结构化参数
String taskId;
if ("kling".equals(videoProvider) && shots != null && shots.size() > 1) {
    List<VideoGenerationService.MultiShotPrompt> multiPrompts = new ArrayList<>();
    for (Map<String, Object> shot : shots) {
        String shotPrompt = getStr(shot, "visualDescription");
        if (shotPrompt == null || shotPrompt.isEmpty()) shotPrompt = getStr(shot, "sceneDescription");
        int shotDuration = 3; // 默认3秒
        Object dur = shot.get("duration");
        if (dur instanceof Number) shotDuration = ((Number) dur).intValue();
        if (shotDuration < 1) shotDuration = 1;
        multiPrompts.add(new VideoGenerationService.MultiShotPrompt(shotPrompt, shotDuration));
    }
    taskId = videoService.generateAsyncMultiShot(fusionImageUrl, multiPrompts, totalDuration, videoModel);
} else {
    taskId = videoService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak, videoModel);
}
```

注意：`shots` 变量在此方法中已存在（第 539 行 `List<Map<String, Object>> shots = ...`），无需重复声明。

- [ ] **Step 2: 放宽 Kling 时长限制**

在同一方法中，找到时长限制（约第 547-548 行）：

```java
if (totalDuration <= 0) totalDuration = 5;
if (totalDuration > 10) totalDuration = 10;
```

替换为：

```java
if (totalDuration <= 0) totalDuration = 5;
int maxDuration = "kling".equals(videoProvider) ? 15 : 10;
if (totalDuration > maxDuration) totalDuration = maxDuration;
```

- [ ] **Step 3: 验证编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat(kling): add multi-shot branch and 15s duration limit for Kling provider"
```

---

## Phase 3: 前端适配

### Task 6: Step1Content.tsx 添加 Kling provider 和模型选项

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`

- [ ] **Step 1: 在 videoProviderOptions 添加 Kling 选项**

找到 `videoProviderOptions` 数组（约第 64-67 行），添加 Kling：

```typescript
const videoProviderOptions = [
  { value: 'vidu', label: 'Vidu' },
  { value: 'grok', label: 'Grok' },
  { value: 'kling', label: 'Kling V3' },
];
```

- [ ] **Step 2: 添加 Kling 模型选项数组**

在 `viduRefModelOptions` 之后添加：

```typescript
const klingModelOptions = [
  { value: 'kling-v3-std', label: 'Kling V3 Std（标准）' },
  { value: 'kling-v3-pro', label: 'Kling V3 Pro（高品质）' },
];
```

- [ ] **Step 3: 修改视频模式选择逻辑**

找到视频模式选择的条件渲染（`videoProvider === 'vidu'` 处），将模式选择改为对 Vidu 专属：

保持 `videoProvider === 'vidu'` 条件不变。Kling 不显示模式选择（暂仅首帧模式）。

- [ ] **Step 4: 修改视频模型选择 UI**

找到模型选择的条件渲染（`videoProvider === 'vidu'`），添加 Kling 分支：

在已有的 Vidu 模型选择块之后添加：

```typescript
{videoProvider === 'kling' && (
  <div className={styles.configSection}>
    <label className={styles.configLabel}>视频模型</label>
    <Select
      options={klingModelOptions}
      value={videoModel}
      onChange={(val) => {
        setVideoModel(val);
        if (projectId) updateProject(projectId, { videoModel: val } as any);
      }}
    />
  </div>
)}
```

- [ ] **Step 5: 处理 provider 切换时的默认模型**

找到 `videoProvider` 的 `onChange` 回调，在切换到 kling 时设置默认模型：

```typescript
onChange={(val) => {
  setVideoProvider(val);
  if (val !== 'vidu') setVideoRefMode(false);
  if (val === 'kling') setVideoModel('kling-v3-std');
}}
```

- [ ] **Step 6: 验证前端编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 7: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx
git commit -m "feat(kling): add Kling V3 provider and model options in Step1Content"
```

---

### Task 7: Step4Production.tsx 添加 Kling Tab 和模型标签

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

- [ ] **Step 1: 添加 isKling 判断**

找到 `const isGrok = ...`（约第 362 行），在其后添加：

```typescript
const isKling = videoProvider.toLowerCase() === 'kling';
```

- [ ] **Step 2: 在提供商 Tab 添加 Kling 按钮**

找到提供商 Tab 区域（约第 1826-1844 行），在 Grok 按钮之后添加：

```tsx
{!isVideoRefMode && (
  <button
    className={`${styles.headerVideoProviderTab} ${isKling ? styles.headerVideoProviderTabActive : ''}`}
    onClick={() => handleVideoProviderChange('kling')}
  >
    Kling
  </button>
)}
```

注意：此按钮应放在已有的 `!isVideoRefMode` 条件块内（与 Grok 并列）。

- [ ] **Step 3: 添加 Kling 模型选择标签**

在 Vidu 参考图模型标签之后，添加 Kling 模型标签：

```tsx
{isKling && (
  <div className={styles.headerVideoModelTabs}>
    <span className={styles.headerVideoModelLabel}>模式：</span>
    <button
      className={`${styles.headerVideoModelTab} ${videoModel === 'kling-v3-std' ? styles.headerVideoModelTabActive : ''}`}
      onClick={async () => {
        setVideoModel('kling-v3-std');
        if (projectId) await updateProject(projectId, { videoModel: 'kling-v3-std' } as any);
      }}
    >
      Std
    </button>
    <button
      className={`${styles.headerVideoModelTab} ${videoModel === 'kling-v3-pro' ? styles.headerVideoModelTabActive : ''}`}
      onClick={async () => {
        setVideoModel('kling-v3-pro');
        if (projectId) await updateProject(projectId, { videoModel: 'kling-v3-pro' } as any);
      }}
    >
      Pro
    </button>
  </div>
)}
```

- [ ] **Step 4: Kling 时隐藏错峰开关**

找到错峰开关的显示条件（约第 1999 行），在条件中排除 Kling：

将条件从：
```typescript
{!(isVideoRefMode && isMixModel) && (
```
改为：
```typescript
{!isKling && !(isVideoRefMode && isMixModel) && (
```

- [ ] **Step 5: handleVideoProviderChange 切换 Kling 时设置默认模型**

找到 `handleVideoProviderChange`（约第 375 行），在切换到 Kling 时重置 videoModel：

```typescript
const handleVideoProviderChange = useCallback(async (provider: string) => {
    if (!projectId) {
      setVideoProvider(provider);
      if (provider === 'kling') setVideoModel('kling-v3-std');
      return;
    }
    try {
      const updates: any = { videoProvider: provider };
      if (provider === 'kling') {
        updates.videoModel = 'kling-v3-std';
        setVideoModel('kling-v3-std');
      }
      await updateProject(projectId, updates);
      setVideoProvider(provider);
    } catch (err) {
      console.error('更新视频提供商失败:', err);
      alert('更新视频提供商失败，请重试');
    }
}, [projectId]);
```

- [ ] **Step 6: handleGenerateVideo 传递 Kling 模型参数**

找到 `handleGenerateVideo` 中的 `generateVideo(...)` 调用（约第 1272 行），确保 videoModel 参数对 Kling 也生效：

```typescript
generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt,
  isVidu ? videoModel : (isKling ? videoModel : undefined))
```

- [ ] **Step 7: 验证前端编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 8: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat(kling): add Kling provider tab, model selector, and hide off-peak in Step4"
```

---

### Task 8: VideoSegmentRow.tsx 兼容 Kling 模型名显示

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoSegmentRow.tsx`

- [ ] **Step 1: 修改模型标签显示逻辑**

找到模型标签显示（约第 172-178 行）中的 `{segment.videoModel === 'pro' ? 'Pro' : ...}`，扩展为兼容 Kling：

将模型标签渲染改为：
```typescript
{segment.videoModel && (
  <span className={styles.panelVideoTag}>
    {segment.videoModel === 'pro' ? 'Pro'
      : segment.videoModel === 'turbo' ? 'Turbo'
      : segment.videoModel === 'kling-v3-std' ? 'Kling Std'
      : segment.videoModel === 'kling-v3-pro' ? 'Kling Pro'
      : segment.videoModel}
  </span>
)}
```

- [ ] **Step 2: 验证前端编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoSegmentRow.tsx
git commit -m "feat(kling): display Kling model names in VideoSegmentRow tags"
```

---

## Phase 4: 集成验证

### Task 9: 端到端集成测试

- [ ] **Step 1: 启动后端**

Run: `cd backend/com/comic && mvn spring-boot:run`
验证日志中出现：`AI 服务配置初始化: 图片=[...], 视频=[vidu, grok, kling]`

- [ ] **Step 2: 启动前端**

Run: `cd frontend/wiset_aivideo_generator && npm run dev`

- [ ] **Step 3: 配置 Kling API Key**

确认环境变量 `KLING_ACCESS_KEY` 和 `KLING_SECRET_KEY` 已设置（或在 application.yml 中直接填写）。

- [ ] **Step 4: 验证 Step1 项目配置**

1. 打开项目创建页
2. 确认视频提供商下拉框显示 "Kling V3" 选项
3. 选择 Kling V3，确认模型下拉框显示 Std/Pro
4. 保存项目配置

- [ ] **Step 5: 验证 Step4 生产页**

1. 打开已有项目的 Step4 页面
2. 切换 provider 为 Kling
3. 确认显示 Std/Pro 模型标签
4. 确认错峰开关已隐藏
5. 选择一个 panel 点击"生成视频"
6. 观察后端日志确认 Kling API 调用成功
7. 等待视频生成完成

- [ ] **Step 6: 验证原有功能未受影响**

1. 切换回 Vidu provider
2. 生成视频确认流程正常
3. 切换到 Grok provider 确认正常

- [ ] **Step 7: 最终 Commit**

如有修复，提交所有改动。
