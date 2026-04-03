# Wuyinkeji Provider Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Nanobanana2 (image) and Sora2 (video) APIs as alternative providers, with project-level model selection at creation time.

**Architecture:** Each project stores its chosen `imageProvider` and `videoProvider` in `projectInfo` JSON. `AiServiceConfiguration` holds Maps of all provider implementations. Consumer services read the provider from the project and dispatch via the config. No DB schema change needed.

**Tech Stack:** Java 17, Spring Boot, OkHttp, Jackson, MyBatis-Plus, existing OssService for permanent URL storage.

---

### Task 1: Add Provider Keys and Project Creation Support

**Files:**
- Modify: `comic/src/main/java/com/comic/constant/ProjectInfoKeys.java`
- Modify: `comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java`
- Modify: `comic/src/main/java/com/comic/service/project/ProjectService.java`
- Modify: `comic/src/main/java/com/comic/controller/ProjectController.java`

- [ ] **Step 1: Add provider keys to `ProjectInfoKeys.java`**

```java
public static final String IMAGE_PROVIDER = "imageProvider";
public static final String VIDEO_PROVIDER = "videoProvider";
```

- [ ] **Step 2: Add provider fields to `ProjectCreateRequest.java`**

```java
private String imageProvider;   // "seedream" | "nanobanana", default "seedream"
private String videoProvider;   // "vidu" | "sora", default "vidu"
```

- [ ] **Step 3: Update `ProjectService.createProject()` signature and body**

Add `String imageProvider, String videoProvider` parameters. Store in projectInfo:

```java
info.put(ProjectInfoKeys.IMAGE_PROVIDER,
    imageProvider != null ? imageProvider : "seedream");
info.put(ProjectInfoKeys.VIDEO_PROVIDER,
    videoProvider != null ? videoProvider : "vidu");
```

Also update `updateProject()` to handle the new fields:

```java
if (request.getImageProvider() != null) info.put(ProjectInfoKeys.IMAGE_PROVIDER, request.getImageProvider());
if (request.getVideoProvider() != null) info.put(ProjectInfoKeys.VIDEO_PROVIDER, request.getVideoProvider());
```

- [ ] **Step 4: Update `ProjectController.createProject()` to pass new fields**

```java
String projectId = projectService.createProject(
    userId,
    dto.getStoryPrompt(),
    dto.getGenre(),
    dto.getTargetAudience(),
    dto.getTotalEpisodes(),
    dto.getEpisodeDuration(),
    dto.getVisualStyle(),
    dto.getImageProvider(),
    dto.getVideoProvider()
);
```

- [ ] **Step 5: Commit**

```bash
git add comic/src/main/java/com/comic/constant/ProjectInfoKeys.java \
        comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java \
        comic/src/main/java/com/comic/service/project/ProjectService.java \
        comic/src/main/java/com/comic/controller/ProjectController.java
git commit -m "feat: add image/video provider selection to project creation"
```

---

### Task 2: Add Wuyinkeji Configuration

**Files:**
- Create: `comic/src/main/java/com/comic/config/WuyinkejiProperties.java`
- Modify: `comic/src/main/resources/application.yml`

- [ ] **Step 1: Create `WuyinkejiProperties.java`**

Follow the pattern of `ViduProperties.java` (`comic/src/main/java/com/comic/config/ViduProperties.java`):

```java
package com.comic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "comic.wuyinkeji")
public class WuyinkejiProperties {
    private String apiKey;
    private String baseUrl = "https://api.wuyinkeji.com";
    private String soraQueryBaseUrl = "https://csapi.wuyinkeji.com";
}
```

- [ ] **Step 2: Add config to `application.yml`**

Add after the `comic.vidu` section:

```yaml
  # ========== 无因科技 API 配置（Nanobanana / Sora） ==========
  wuyinkeji:
    api-key: ${WUYINKEJI_API_KEY:}
    base-url: https://api.wuyinkeji.com
    sora-query-base-url: https://csapi.wuyinkeji.com
```

- [ ] **Step 3: Commit**

```bash
git add comic/src/main/java/com/comic/config/WuyinkejiProperties.java \
        comic/src/main/resources/application.yml
git commit -m "feat: add Wuyinkeji API configuration"
```

---

### Task 3: Create NanobananaImageService

**Files:**
- Create: `comic/src/main/java/com/comic/ai/image/NanobananaImageService.java`

- [ ] **Step 1: Create `NanobananaImageService.java`**

Follow the pattern of `SeedreamImageService.java` (`comic/src/main/java/com/comic/ai/image/SeedreamImageService.java`). Key differences:
- Implements `ImageGenerationService`
- Async API wrapped as sync (submit → poll → wait → return)
- Uses `WuyinkejiProperties` for config
- Polls `GET /api/async/detail?id={taskId}` every 3 seconds, timeout 3 minutes
- Status mapping: 0=queuing, 1=success, 2=failed, 3=generating

```java
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

@Service
@Slf4j
@RequiredArgsConstructor
public class NanobananaImageService implements ImageGenerationService {

    private static final String SUBMIT_ENDPOINT = "/api/async/image_nanoBanana2";
    private static final String QUERY_ENDPOINT = "/api/async/detail?id=";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int TIMEOUT_MS = 180000; // 3 minutes

    private final WuyinkejiProperties wuyinkejiProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OssService ossService;

    private final Semaphore semaphore = new Semaphore(2);

    @Override
    public String generate(String prompt, int width, int height, String style) {
        return doGenerate(prompt, width, height, null);
    }

    @Override
    public String generateWithReference(String prompt, String referenceImage, int width, int height) {
        return doGenerate(prompt, width, height, Collections.singletonList(referenceImage));
    }

    @Override
    public String generateWithMultipleReferences(String prompt, List<String> referenceImages, int width, int height) {
        return doGenerate(prompt, width, height, referenceImages);
    }

    private String doGenerate(String prompt, int width, int height, List<String> urls) {
        try {
            semaphore.acquire();

            // 1. Submit task
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", prompt);
            body.put("aspectRatio", toAspectRatio(width, height));
            if (urls != null && !urls.isEmpty()) {
                body.put("urls", urls);
            }

            String taskId = submitTask(body);

            // 2. Poll for result
            String remoteUrl = pollForResult(taskId);

            // 3. Upload to OSS
            String ossUrl = ossService.uploadImageFromUrl(remoteUrl, null);
            log.info("Nanobanana 图片生成完成: taskId={}, ossUrl={}", taskId, ossUrl);
            return ossUrl;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Nanobanana 图片生成被中断", e);
        } catch (Exception e) {
            log.error("Nanobanana 图片生成失败", e);
            throw new RuntimeException("Nanobanana 图片生成失败: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    private String submitTask(Map<String, Object> body) throws IOException {
        String jsonBody = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(wuyinkejiProperties.getBaseUrl() + SUBMIT_ENDPOINT)
                .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Nanobanana 提交失败: " + response.code() + " - " + responseBody);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.get("code").asInt() != 200) {
                throw new RuntimeException("Nanobanana 提交失败: " + responseBody);
            }
            String taskId = root.get("data").get("id").asText();
            log.info("Nanobanana 任务已提交: taskId={}", taskId);
            return taskId;
        }
    }

    private String pollForResult(String taskId) throws IOException, InterruptedException {
        String url = wuyinkejiProperties.getBaseUrl() + QUERY_ENDPOINT + taskId;
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
            Thread.sleep(POLL_INTERVAL_MS);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode root = objectMapper.readTree(responseBody);

                if (root.get("code").asInt() != 200) {
                    log.warn("Nanobanana 查询失败: {}", responseBody);
                    continue;
                }

                JsonNode data = root.get("data");
                int status = data.get("status").asInt();

                if (status == 1) {
                    // Success
                    return data.get("remote_url").asText();
                } else if (status == 2) {
                    // Failed
                    String reason = data.has("fail_reason") ? data.get("fail_reason").asText() : "未知原因";
                    throw new RuntimeException("Nanobanana 生成失败: " + reason);
                }
                // status 0 (queuing) or 3 (generating) → continue polling
            }
        }

        throw new RuntimeException("Nanobanana 生成超时: taskId=" + taskId);
    }

    private String toAspectRatio(int width, int height) {
        int gcd = gcd(width, height);
        return (width / gcd) + ":" + (height / gcd);
    }

    private int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    @Override
    public String getServiceName() {
        return "Nanobanana-Image";
    }

    @Override
    public int getAvailableConcurrentSlots() {
        return semaphore.availablePermits();
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn compile -pl comic -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add comic/src/main/java/com/comic/ai/image/NanobananaImageService.java
git commit -m "feat: add NanobananaImageService for async image generation"
```

---

### Task 4: Create SoraVideoService

**Files:**
- Create: `comic/src/main/java/com/comic/ai/video/SoraVideoService.java`

- [ ] **Step 1: Create `SoraVideoService.java`**

Follow the pattern of `ViduVideoService.java` (`comic/src/main/java/com/comic/ai/video/ViduVideoService.java`). Key differences:
- Uses `WuyinkejiProperties` for config
- Submit: `POST /api/async/video_sora2`
- Query: `GET /api/sora2/detail?id={taskId}` (note: different base URL — `soraQueryBaseUrl`)
- Status mapping: 0→pending, 3→processing, 1→completed, 2→failed

```java
package com.comic.ai.video;

import com.comic.config.WuyinkejiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class SoraVideoService implements VideoGenerationService {

    private static final String SUBMIT_ENDPOINT = "/api/async/video_sora2";
    private static final String QUERY_ENDPOINT = "/api/sora2/detail?id=";

    private final WuyinkejiProperties wuyinkejiProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Semaphore semaphore = new Semaphore(1);

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        return generateAsync(prompt, duration, aspectRatio, referenceImage, false);
    }

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage, boolean offPeak) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            Map<String, Object> body = new HashMap<>();
            body.put("prompt", prompt);
            body.put("aspectRatio", aspectRatio != null ? aspectRatio : "16:9");
            body.put("duration", String.valueOf(duration));
            body.put("size", "small");
            if (referenceImage != null && !referenceImage.isEmpty()) {
                body.put("url", referenceImage);
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            log.debug("Sora 请求体: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(wuyinkejiProperties.getBaseUrl() + SUBMIT_ENDPOINT)
                    .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Sora 视频生成失败: " + response.code() + " - " + responseBody);
                }
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.get("code").asInt() != 200) {
                    throw new RuntimeException("Sora 视频生成失败: " + responseBody);
                }
                String taskId = root.get("data").get("id").asText();
                log.info("Sora 视频生成任务已提交: taskId={}", taskId);
                return taskId;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sora 视频生成被中断", e);
        } catch (IOException e) {
            throw new RuntimeException("Sora 视频生成失败: " + e.getMessage(), e);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        try {
            String url = wuyinkejiProperties.getSoraQueryBaseUrl() + QUERY_ENDPOINT + taskId;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", wuyinkejiProperties.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                JsonNode root = objectMapper.readTree(responseBody);
                if (root.get("code").asInt() != 200) {
                    return new TaskStatus(taskId, "unknown", 0, null, "查询返回错误: " + responseBody);
                }

                JsonNode data = root.get("data");
                int status = data.get("status").asInt();

                String normalizedStatus;
                int progress;
                String videoUrl = null;
                String errorMsg = null;

                switch (status) {
                    case 0: // queuing
                        normalizedStatus = "pending";
                        progress = 10;
                        break;
                    case 3: // generating
                        normalizedStatus = "processing";
                        progress = 50;
                        break;
                    case 1: // success
                        normalizedStatus = "completed";
                        progress = 100;
                        videoUrl = data.has("remote_url") ? data.get("remote_url").asText() : null;
                        break;
                    case 2: // failed
                        normalizedStatus = "failed";
                        progress = 0;
                        errorMsg = data.has("fail_reason") ? data.get("fail_reason").asText() : "未知原因";
                        break;
                    default:
                        normalizedStatus = "unknown";
                        progress = 0;
                }

                return new TaskStatus(taskId, normalizedStatus, progress, videoUrl, errorMsg);
            }
        } catch (IOException e) {
            log.error("查询 Sora 任务状态异常: taskId={}", taskId, e);
            return new TaskStatus(taskId, "unknown", 0, null, e.getMessage());
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        TaskStatus status = getTaskStatus(taskId);
        if (status.isCompleted() && status.getVideoUrl() != null) return status.getVideoUrl();
        if (status.isFailed()) throw new RuntimeException("视频生成失败: " + status.getErrorMessage());
        throw new RuntimeException("视频尚未生成完成: " + status.getStatus());
    }

    @Override
    public String getServiceName() {
        return "Sora-Video";
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn compile -pl comic -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add comic/src/main/java/com/comic/ai/video/SoraVideoService.java
git commit -m "feat: add SoraVideoService for async video generation"
```

---

### Task 5: Refactor AiServiceConfiguration for Provider Dispatch

**Files:**
- Modify: `comic/src/main/java/com/comic/config/AiServiceConfiguration.java`

- [ ] **Step 1: Rewrite `AiServiceConfiguration.java`**

Replace the current single-`@Primary` bean pattern with provider Maps and dispatch methods:

```java
package com.comic.config;

import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.image.NanobananaImageService;
import com.comic.ai.image.SeedreamImageService;
import com.comic.ai.text.DeepSeekTextService;
import com.comic.ai.text.TextGenerationService;
import com.comic.ai.video.SoraVideoService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.ai.video.ViduVideoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

@Configuration
@Slf4j
public class AiServiceConfiguration {

    private final Map<String, ImageGenerationService> imageServices;
    private final Map<String, VideoGenerationService> videoServices;

    public AiServiceConfiguration(
            SeedreamImageService seedreamImageService,
            NanobananaImageService nanobananaImageService,
            ViduVideoService viduVideoService,
            SoraVideoService soraVideoService
    ) {
        this.imageServices = Map.of(
                "seedream", seedreamImageService,
                "nanobanana", nanobananaImageService
        );
        this.videoServices = Map.of(
                "vidu", viduVideoService,
                "sora", soraVideoService
        );
        log.info("AI 服务配置: 图片={}, 视频={}", imageServices.keySet(), videoServices.keySet());
    }

    // ========== 文本生成服务（保持不变） ==========

    @Bean
    @Primary
    public TextGenerationService textGenerationService(DeepSeekTextService deepSeekTextService) {
        log.info("文本生成服务: DeepSeek");
        return deepSeekTextService;
    }

    // ========== 默认 Bean（向后兼容，不确定 provider 时使用） ==========

    @Bean
    @Primary
    public ImageGenerationService imageGenerationService(SeedreamImageService seedreamImageService) {
        return seedreamImageService;
    }

    @Bean
    @Primary
    public VideoGenerationService videoGenerationService(ViduVideoService viduVideoService) {
        return viduVideoService;
    }

    // ========== Provider 分发 ==========

    public ImageGenerationService getImageService(String provider) {
        ImageGenerationService service = imageServices.get(provider);
        if (service == null) {
            log.warn("未知的图片 provider: {}, 使用默认 seedream", provider);
            return imageServices.get("seedream");
        }
        return service;
    }

    public VideoGenerationService getVideoService(String provider) {
        VideoGenerationService service = videoServices.get(provider);
        if (service == null) {
            log.warn("未知的视频 provider: {}, 使用默认 vidu", provider);
            return videoServices.get("vidu");
        }
        return service;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn compile -pl comic -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add comic/src/main/java/com/comic/config/AiServiceConfiguration.java
git commit -m "refactor: add provider dispatch Maps to AiServiceConfiguration"
```

---

### Task 6: Update GridImageService for Provider Dispatch

**Files:**
- Modify: `comic/src/main/java/com/comic/service/panel/GridImageService.java`

- [ ] **Step 1: Add helper method to get project's image provider**

Add `AiServiceConfiguration` injection and a helper to resolve provider from episode → project:

```java
import com.comic.ai.image.ImageGenerationService;
import com.comic.config.AiServiceConfiguration;
// ...
@Resource private AiServiceConfiguration aiServiceConfig;
```

Add helper method:

```java
private ImageGenerationService getImageServiceForEpisode(Long episodeId) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) {
        log.warn("Episode 不存在: {}, 使用默认 seedream", episodeId);
        return aiServiceConfig.getImageService("seedream");
    }
    Project project = ... // need projectRepository
    // Actually, GridImageService doesn't have projectRepository.
    // We need to add it.
}
```

Wait — `GridImageService` doesn't have `projectRepository`. We need to either add it or pass the provider from the caller. Since both callers (`PanelProductionService` and `ProjectController`) have access to the project, passing the provider is cleaner.

Revised approach: Add an `imageProvider` parameter to the public methods. Callers read it from the project and pass it in.

- [ ] **Step 2: Replace `SeedreamImageService` injection with `AiServiceConfiguration`**

Change:
```java
@Resource private SeedreamImageService seedreamImageService;
```
To:
```java
@Resource private AiServiceConfiguration aiServiceConfig;
```

- [ ] **Step 3: Update `generateGridsForPanel` to accept `imageProvider`**

Change signature from:
```java
public void generateGridsForPanel(Long panelId, String customHint)
```
To:
```java
public void generateGridsForPanel(Long panelId, String imageProvider, String customHint)
```

Replace `seedreamImageService.generate(...)` calls with:
```java
ImageGenerationService imageService = aiServiceConfig.getImageService(imageProvider);
```
Then use `imageService.generate(...)` and `imageService.generateWithMultipleReferences(...)` instead.

There are 4 call sites inside `generateGridsForPanel` (lines 88, 91, 176, 179 equivalent).

- [ ] **Step 4: Update `generateGridsForEpisode` to accept `imageProvider`**

Same pattern:
```java
public void generateGridsForEpisode(Long episodeId, List<Map<String, Object>> shots, String visualStyle, String imageProvider)
```

Replace the 2 `seedreamImageService` calls with `aiServiceConfig.getImageService(imageProvider)`.

- [ ] **Step 5: Keep backward-compatible overloads**

```java
public void generateGridsForPanel(Long panelId) {
    generateGridsForPanel(panelId, "seedream", null);
}
public void generateGridsForPanel(Long panelId, String customHint) {
    generateGridsForPanel(panelId, "seedream", customHint);
}
```

- [ ] **Step 6: Commit**

```bash
git add comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "refactor: GridImageService uses provider dispatch via AiServiceConfiguration"
```

---

### Task 7: Update PanelProductionService for Provider Dispatch

**Files:**
- Modify: `comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: Add `AiServiceConfiguration` dependency**

```java
private final AiServiceConfiguration aiServiceConfig;
```

Add to constructor. Keep `VideoGenerationService videoGenerationService` and `ViduVideoService viduVideoService` for now (viduVideoService is used for enhancePrompt).

- [ ] **Step 2: Add helper to read provider from project**

```java
private String getImageProvider(String projectId) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null || project.getProjectInfo() == null) return "seedream";
    Object provider = project.getProjectInfo().get(ProjectInfoKeys.IMAGE_PROVIDER);
    return provider != null ? provider.toString() : "seedream";
}

private String getVideoProvider(String projectId) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null || project.getProjectInfo() == null) return "vidu";
    Object provider = project.getProjectInfo().get(ProjectInfoKeys.VIDEO_PROVIDER);
    return provider != null ? provider.toString() : "vidu";
}
```

- [ ] **Step 3: Update video generation calls to use dispatched service**

In `doGenerateVideoByPanelId`, replace direct use of `videoGenerationService` with:
```java
VideoGenerationService videoService = aiServiceConfig.getVideoService(getVideoProvider(projectId));
```

Use this `videoService` for `generateAsync()`, `getTaskStatus()`, and `downloadVideo()` calls within that method.

- [ ] **Step 4: Update GridImageService calls to pass imageProvider**

Where `gridImageService.generateGridsForPanel(panelId, customHint)` is called, change to:
```java
String imageProvider = getImageProvider(projectId);
gridImageService.generateGridsForPanel(panelId, imageProvider, customHint);
```

Same for `generateGridsForEpisode`.

- [ ] **Step 5: Commit**

```bash
git add comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "refactor: PanelProductionService dispatches to project-selected providers"
```

---

### Task 8: Update CharacterImageGenerationService Callers (if needed)

**Files:**
- Check: `comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java`

- [ ] **Step 1: Verify `CharacterImageGenerationService` uses interface**

It already injects `ImageGenerationService` (the interface), so it automatically gets the `@Primary` bean. No changes needed for the default provider.

If per-project provider selection is also needed here, add the same pattern (read from project, dispatch via `AiServiceConfiguration`). But character images are generated during the asset phase and belong to a specific project, so this should use the project's provider.

- [ ] **Step 2: If needed, add provider dispatch**

Add `AiServiceConfiguration` and resolve the image service per project:
```java
String imageProvider = getProjectImageProvider(projectId);
ImageGenerationService imageService = aiServiceConfig.getImageService(imageProvider);
```

- [ ] **Step 3: Commit (if changes made)**

---

### Task 9: Integration Verification

**Files:** None (verification only)

- [ ] **Step 1: Full compilation check**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn compile -pl comic -q 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 2: Check for remaining direct SeedreamImageService references**

Run: `grep -rn "SeedreamImageService" comic/src/main/java/ --include="*.java" | grep -v "AiServiceConfiguration" | grep -v "import"`

Expected: No results (all consumers should now use interface or dispatch)

- [ ] **Step 3: Verify the full flow**

Trace the flow:
1. `ProjectController.createProject()` → stores `imageProvider` in `projectInfo`
2. `PanelProductionService` reads `imageProvider` / `videoProvider` from project
3. Dispatches to correct service via `AiServiceConfiguration`
4. `GridImageService` receives `imageProvider` from caller, dispatches correctly