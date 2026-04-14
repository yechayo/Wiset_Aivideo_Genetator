# 参考图视频模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Vidu reference2video support — up to 7 reference images (split shots + character images) sent directly to Vidu's reference2video API, bypassing the fusion image step.

**Architecture:** New `ViduReference2VideoService` implements `VideoGenerationService` interface extension (`generateAsyncMultiImage`). `PanelProductionService` branches on `videoRefMode` flag. Frontend adds mode selector in Step1 and conditional rendering in Step4 video tab.

**Tech Stack:** Java Spring Boot (backend), React + TypeScript + Less modules (frontend), OkHttp (HTTP client), Vidu reference2video API

---

## File Structure

### Backend — New Files
| File | Responsibility |
|------|---------------|
| `backend/com/comic/src/main/java/com/comic/ai/video/ViduReference2VideoService.java` | Vidu reference2video API client: build request with images[], call API, parse response |

### Backend — Modified Files
| File | Changes |
|------|---------|
| `backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java` | Add `generateAsyncMultiImage` default method |
| `backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java` | Add `VIDEO_REF_MODE` constant |
| `backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java` | Add `videoRefMode` field |
| `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java` | Store `videoRefMode` in projectInfo on create/update |
| `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` | Add `generateVideoRefByPanelId`, `collectReferenceImagesWithNames`, branch in `doGenerateVideoByPanelId` |
| `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java` | Skip fusion image when `videoRefMode=true` in `approveEpisodeGrid` |
| `backend/com/comic/src/main/java/com/comic/controller/PanelController.java` | Add `POST /{panelId}/video-ref` endpoint |
| `backend/com/comic/src/main/java/com/comic/controller/ProjectController.java` | Pass `videoRefMode` to `ProjectService.createProject()` |

### Frontend — Modified Files
| File | Changes |
|------|---------|
| `frontend/.../services/types/project.types.ts` | Add `videoRefMode` to `CreateProjectRequest` |
| `frontend/.../services/episodeService.ts` | Add `generateVideoRef` function |
| `frontend/.../pages/create/steps/Step1Content.tsx` | Add video mode selector (首帧视频/参考图视频) with model联动 |
| `frontend/.../pages/create/steps/Step4Production.tsx` | Conditional rendering: hide off-peak for mix, hide enhance for ref mode, call `generateVideoRef` |
| `frontend/.../pages/create/steps/components/VideoSegmentRow.tsx` | Show reference image list instead of fusion image when `videoRefMode` |
| `frontend/.../pages/create/steps/types.ts` | Add `videoRefMode` to `SegmentState` |

---

## Task 1: Backend — Extend `VideoGenerationService` Interface

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java`

- [ ] **Step 1: Add `generateAsyncMultiImage` default method to the interface**

After the existing `generateAsync` with 5 params (line 32-34), add a new default method:

```java
import java.util.List;

// After line 34, add:
/**
 * 多图参考视频生成（reference2video 模式）
 *
 * @param prompt          视频描述提示词
 * @param duration        视频时长（秒）
 * @param aspectRatio     宽高比
 * @param referenceImages 参考图 URL 列表（最多7张）
 * @param characterNames  角色名列表（与 referenceImages 后半段角色图对应）
 * @param offPeak         错峰模式
 * @param model           视频模型
 * @return 任务ID
 */
default String generateAsyncMultiImage(String prompt, int duration, String aspectRatio,
                                       List<String> referenceImages, List<String> characterNames,
                                       boolean offPeak, String model) {
    throw new UnsupportedOperationException("多图参考视频生成未实现");
}
```

- [ ] **Step 2: Compile check**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -20`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/video/VideoGenerationService.java
git commit -m "feat(video): add generateAsyncMultiImage default method to VideoGenerationService interface"
```

---

## Task 2: Backend — Create `ViduReference2VideoService`

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/ai/video/ViduReference2VideoService.java`

- [ ] **Step 1: Create ViduReference2VideoService**

Follow the exact pattern of `ViduVideoService.java` (same package, same dependencies). The new service:
- Uses endpoint `/reference2video` instead of `/img2video`
- Sends `images` as an array (max 7) instead of `Collections.singletonList(referenceImage)`
- Does NOT send `off_peak` field at all when model is `viduq3-mix`
- Includes model shorthand mapping (`"pro"` → `"viduq3-pro"`, `"turbo"` → `"viduq3-turbo"`) matching ViduVideoService
- Resolution: viduq3 supports 540p/720p/1080p, viduq3-mix supports 720p/1080p
- Implements `generateAsyncMultiImage` (overrides the default)
- Delegates `getTaskStatus`, `downloadVideo`, `getServiceName` to the same Vidu task query API (identical to ViduVideoService)

```java
package com.comic.ai.video;

import com.comic.config.ViduProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Vidu 参考图视频生成服务
 * 使用 Vidu reference2video API，支持最多7张参考图
 *
 * API: POST https://api.vidu.com/ent/v2/reference2video
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ViduReference2VideoService implements VideoGenerationService {

    private static final String REFERENCE2VIDEO_ENDPOINT = "/reference2video";
    private static final String QUERY_TASK_ENDPOINT = "/tasks/%s/creations";

    private final ViduProperties viduProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Semaphore semaphore = new Semaphore(1);

    @Override
    public String generateAsyncMultiImage(String prompt, int duration, String aspectRatio,
                                          List<String> referenceImages, List<String> characterNames,
                                          boolean offPeak, String model) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            log.info("Vidu 参考图视频生成: 并发槽位 {}/{}", semaphore.availablePermits(), semaphore.getQueueLength());

            // 模型名映射（与 ViduVideoService 保持一致）
            String effectiveModel = (model != null && !model.isEmpty()) ? model : viduProperties.getModel();
            switch (effectiveModel) {
                case "pro":  effectiveModel = "viduq3-pro"; break;
                case "turbo": effectiveModel = "viduq3-turbo"; break;
                default: break;
            }

            // 限制最多7张图
            List<String> images = referenceImages.size() > 7
                ? referenceImages.subList(0, 7) : referenceImages;
            if (images.size() > 7) {
                log.warn("参考图超过7张，截取前7张");
            }

            // 构建提示词：附加参考图说明（带角色名）
            String fullPrompt = buildReferenceImagePrompt(prompt, images, characterNames);

            // viduq3-mix 不支持 off_peak（不发送该字段）
            boolean isMixModel = "viduq3-mix".equals(effectiveModel);
            boolean effectiveOffPeak = offPeak && !isMixModel;

            // resolution: viduq3 支持 540p/720p/1080p, viduq3-mix 支持 720p/1080p
            String resolution = "720p";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", effectiveModel);
            requestBody.put("images", images);
            requestBody.put("prompt", fullPrompt);
            requestBody.put("duration", duration);
            requestBody.put("resolution", resolution);
            requestBody.put("aspect_ratio", aspectRatio);
            requestBody.put("watermark", false);
            if (!isMixModel) {
                requestBody.put("off_peak", effectiveOffPeak);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("Vidu reference2video 请求体: {}", jsonBody);

            Request request = new Request.Builder()
                .url(viduProperties.getBaseUrl() + REFERENCE2VIDEO_ENDPOINT)
                .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Vidu reference2video API 调用失败: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Vidu 参考图视频生成失败: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Vidu reference2video 响应: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode taskIdNode = root.get("task_id");
                if (taskIdNode == null) {
                    throw new RuntimeException("无法解析任务 ID: " + responseBody);
                }
                String taskId = taskIdNode.asText();
                log.info("Vidu 参考图视频任务已提交: taskId={}, model={}, images={}", taskId, effectiveModel, images.size());
                return taskId;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Vidu 参考图视频生成被中断", e);
        } catch (IOException e) {
            log.error("Vidu 参考图视频生成 IO 异常", e);
            throw new RuntimeException("Vidu 参考图视频生成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Vidu 参考图视频生成异常", e);
            throw new RuntimeException("Vidu 参考图视频生成失败: " + e.getMessage(), e);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    /**
     * 构建带参考图说明的提示词
     * 分镜图用编号（分镜图1、分镜图2...），角色图用真实角色名（角色图-李四）
     */
    private String buildReferenceImagePrompt(String originalPrompt, List<String> images, List<String> characterNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("【参考图说明】\n");

        // 角色图从 images 的后半段开始
        int charStartIndex = Math.max(0, images.size() - characterNames.size());

        for (int i = 0; i < images.size(); i++) {
            if (i < charStartIndex) {
                sb.append(String.format("分镜图%d: 第%d张分镜参考图\n", i + 1, i + 1));
            } else {
                int charIdx = i - charStartIndex;
                if (charIdx < characterNames.size()) {
                    sb.append(String.format("角色图-%s: 角色参考图\n", characterNames.get(charIdx)));
                }
            }
        }

        sb.append("【视频指令】\n");
        sb.append(originalPrompt);
        return sb.toString();
    }

    // ===== 以下为 VideoGenerationService 接口实现 =====
    // 单图模式不适用于此服务，直接抛异常

    @Override
    public String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage) {
        throw new UnsupportedOperationException("ViduReference2VideoService 仅支持多图参考模式，请使用 generateAsyncMultiImage");
    }

    @Override
    public TaskStatus getTaskStatus(String taskId) {
        // 复用 Vidu 相同的任务查询 API
        try {
            String url = viduProperties.getBaseUrl() + String.format(QUERY_TASK_ENDPOINT, taskId);

            Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.warn("查询 Vidu 任务状态失败: code={}, taskId={}, error={}", response.code(), taskId, errorBody);
                    return new TaskStatus(taskId, "unknown", 0, null, "查询失败: " + response.code());
                }

                String responseBody = response.body().string();
                return parseTaskStatus(responseBody, taskId);
            }
        } catch (IOException e) {
            log.error("查询 Vidu 任务状态 IO 异常: taskId={}", taskId, e);
            return new TaskStatus(taskId, "unknown", 0, null, e.getMessage());
        }
    }

    @Override
    public String downloadVideo(String taskId) {
        TaskStatus status = getTaskStatus(taskId);
        if (status.isCompleted() && status.getVideoUrl() != null) {
            return status.getVideoUrl();
        }
        if (status.isFailed()) {
            throw new RuntimeException("视频生成失败: " + status.getErrorMessage());
        }
        throw new RuntimeException("视频尚未生成完成，当前状态: " + status.getStatus());
    }

    @Override
    public String getServiceName() {
        return "Vidu-Reference2Video";
    }

    private TaskStatus parseTaskStatus(String responseBody, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode stateNode = root.get("state");
            String state = stateNode != null ? stateNode.asText() : "unknown";
            String normalizedStatus = normalizeStatus(state);

            JsonNode errCodeNode = root.get("err_code");
            String errorMessage = null;
            if (errCodeNode != null && !errCodeNode.isNull() && errCodeNode.asText().length() > 0) {
                errorMessage = "err_code: " + errCodeNode.asText();
            }

            String videoUrl = null;
            JsonNode creationsNode = root.get("creations");
            if (creationsNode != null && creationsNode.isArray() && creationsNode.size() > 0) {
                JsonNode firstCreation = creationsNode.get(0);
                JsonNode urlNode = firstCreation.get("url");
                if (urlNode != null) videoUrl = urlNode.asText();
            }

            int progress = calculateProgress(state);
            JsonNode progressNode = root.get("progress");
            if (progressNode != null && !progressNode.isNull()) {
                progress = (int) Math.round(progressNode.asDouble());
            }

            Integer credits = null;
            JsonNode creditsNode = root.get("credits");
            if (creditsNode != null && !creditsNode.isNull()) {
                credits = creditsNode.asInt();
            }

            return new TaskStatus(taskId, normalizedStatus, progress, videoUrl, errorMessage, null, null, credits);
        } catch (Exception e) {
            log.error("解析 Vidu 任务状态失败: {}", responseBody, e);
            return new TaskStatus(taskId, "unknown", 0, null, "解析失败");
        }
    }

    private String normalizeStatus(String state) {
        if (state == null) return "unknown";
        switch (state) {
            case "created":
            case "queueing":
                return "pending";
            case "processing":
                return "processing";
            case "success":
                return "completed";
            case "failed":
                return "failed";
            default:
                return "unknown";
        }
    }

    private int calculateProgress(String state) {
        if (state == null) return 0;
        switch (state) {
            case "created": return 10;
            case "queueing": return 20;
            case "processing": return 50;
            case "success": return 100;
            case "failed": return 0;
            default: return 0;
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -20`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/video/ViduReference2VideoService.java
git commit -m "feat(video): add ViduReference2VideoService for reference2video API"
```

---

## Task 3: Backend — Add `VIDEO_REF_MODE` Key + Project DTO + ProjectService

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java`

- [ ] **Step 1: Add `VIDEO_REF_MODE` to `ProjectInfoKeys.java`**

After line 20 (`VIDEO_MODEL`), add:

```java
public static final String VIDEO_REF_MODE = "videoRefMode";
```

- [ ] **Step 2: Add `videoRefMode` to `ProjectCreateRequest.java`**

After line 18 (`private String videoModel;`), add:

```java
private Boolean videoRefMode;    // true=参考图视频模式, false/null=首帧视频模式
```

- [ ] **Step 3: Store `videoRefMode` in `ProjectService.java`**

In `createProject()` (after line 144):
```java
if (videoRefMode != null) info.put(ProjectInfoKeys.VIDEO_REF_MODE, videoRefMode);
```

In `updateProject()` (after line 175):
```java
if (request.getVideoRefMode() != null) info.put(ProjectInfoKeys.VIDEO_REF_MODE, request.getVideoRefMode());
```

**Important:** `ProjectService.java` needs to pass `videoRefMode` through the method parameter chain. Check the method signature:

Read the `createProject` method signature to see if `videoRefMode` is already available. If not, add it as a parameter:

```java
// In createProject method, extract from request:
Boolean videoRefMode = request.getVideoRefMode();
// ... after line 144:
if (videoRefMode != null) info.put(ProjectInfoKeys.VIDEO_REF_MODE, videoRefMode);
```

Similarly in `updateProject`:
```java
if (request.getVideoRefMode() != null) info.put(ProjectInfoKeys.VIDEO_REF_MODE, request.getVideoRefMode());
```

- [ ] **Step 4: Compile check**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -20`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java \
        backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java \
        backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java
git commit -m "feat(project): add videoRefMode field to project config"
```

---

## Task 4: Backend — 修改 `ProjectController` 传递 `videoRefMode`

**文件:**
- 修改: `backend/com/comic/src/main/java/com/comic/controller/ProjectController.java`

- [ ] **Step 1: 在 `createProject` 调用中传递 `videoRefMode`**

找到 `ProjectController.createProject()` 方法中调用 `projectService.createProject(...)` 的位置，在参数列表末尾添加 `request.getVideoRefMode()`。

- [ ] **Step 2: 编译检查**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -20`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/ProjectController.java
git commit -m "feat(project): pass videoRefMode from controller to service"
```

---

## Task 5: Backend — 修改 `EpisodeController.approveEpisodeGrid` 跳过融合图

**文件:**
- 修改: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java`

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java`

- [ ] **Step 1: Add videoRefMode check and skip fusion image generation**

In `approveEpisodeGrid()` method, after the `splitShots`/`visualStyle` reading (line 303) and before the grouping loop (line 315), add:

```java
// 判断项目是否使用参考图视频模式
Project project = projectRepository.findByProjectId(projectId);
boolean videoRefMode = project != null
    && Boolean.TRUE.equals(project.getProjectInfo().get("videoRefMode"));
```

Then in the Panel creation loop (line 347, after `panelInfo.put("gridImages", ...)`), add:

```java
panelInfo.put("videoRefMode", videoRefMode);
```

Replace the fusion image block (lines 349-357) with:

```java
if (!videoRefMode) {
    // 首帧视频模式：生成融合参考图
    try {
        BufferedImage fusionImage = gridImageService.createFusionImageForPanelWithNames(group, charRefsWithNames);
        String fusionUrl = gridImageService.uploadFusionImageForPanel(fusionImage, episodeId, group);
        panelInfo.put("fusionImageUrl", fusionUrl);
    } catch (Exception e) {
        log.error("Panel 融合图生成失败: episodeId={}, shots={}", episodeId, group.size(), e);
    }
} else {
    // 参考图视频模式：跳过融合图
    panelInfo.put("fusionImageUrl", null);
}
```

**Note:** The `ProjectRepository` import already exists in `EpisodeController.java`. If not, add:
```java
import com.comic.repository.ProjectRepository;
```

- [ ] **Step 2: Compile check**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -20`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java
git commit -m "feat(grid): skip fusion image generation when videoRefMode=true"
```

---

## Task 6: Backend — Add `generateVideoRefByPanelId` to `PanelProductionService`

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: Add `ViduReference2VideoService` dependency injection**

After line 60 (`private final ViduVideoService viduVideoService;`), add:

```java
private final ViduReference2VideoService viduReference2VideoService;
```

Update the constructor to include this parameter (find the `@Autowired` constructor starting at line 76).

- [ ] **Step 2: Add `collectReferenceImagesWithNames` private method**

Add after the `getInt` method (line 661):

```java
/**
 * 收集参考图及角色名：分镜图优先（最多3张）+ 角色图补足至7张
 * 返回 Pair: left=参考图URL列表, right=角色名列表
 */
@SuppressWarnings("unchecked")
private Pair<List<String>, List<String>> collectReferenceImagesWithNames(Panel panel) {
    Long episodeId = panel.getEpisodeId();
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null || episode.getEpisodeInfo() == null) {
        throw new BusinessException("剧集信息不存在");
    }

    List<String> refImageUrls = new ArrayList<>();
    List<String> charNames = new ArrayList<>();

    // 1. 从 episodeInfo.splitShots 取分镜图（最多3张）
    List<Map<String, Object>> splitShots = (List<Map<String, Object>>) episode.getEpisodeInfo().get("splitShots");
    if (splitShots != null) {
        int shotCount = Math.min(3, splitShots.size());
        for (int i = 0; i < shotCount; i++) {
            String url = (String) splitShots.get(i).get("splitImageUrl");
            if (url != null && !url.isEmpty()) refImageUrls.add(url);
        }
    }

    // 2. 角色图补足至7张（含角色名）
    List<GridImageService.CharRef> charRefs = gridImageService.getCharacterReferencesWithNamesForEpisode(episodeId);
    for (GridImageService.CharRef cr : charRefs) {
        if (refImageUrls.size() >= 7) break;
        if (cr.url != null && !cr.url.isEmpty() && !refImageUrls.contains(cr.url)) {
            refImageUrls.add(cr.url);
            charNames.add(cr.name != null ? cr.name : "未知角色");
        }
    }

    if (refImageUrls.isEmpty()) {
        throw new BusinessException("参考图数量不足，无法生成视频");
    }

    log.info("收集参考图: panelId={}, 分镜图={}, 角色图={}, 总计={}",
        panel.getId(), refImageUrls.size() - charNames.size(), charNames.size(), refImageUrls.size());
    return new Pair<>(refImageUrls, charNames);
}
```

**Note:** `Pair` is `org.springframework.data.util.Pair` or `javafx.util.Pair`. Check existing imports. If neither is available, use a simple inner class or `AbstractMap.SimpleEntry`. Alternatively use `java.util.AbstractMap.SimpleEntry<List<String>, List<String>>`.

- [ ] **Step 3: Add `generateVideoRefByPanelId` public method**

Add after `generateVideoByPanelId` (after line 359):

```java
/**
 * 参考图视频生成（videoRefMode=true 时调用）
 */
public void generateVideoRefByPanelId(Long panelId, boolean offPeak, String customPrompt, String videoModel) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) throw new BusinessException("分镜不存在");
    Map<String, Object> info = panel.getPanelInfo();
    String gridStatus = info != null ? getStr(info, "gridStatus") : null;
    if (!"approved".equals(gridStatus)) {
        throw new BusinessException("九宫格未审核通过，请先审核");
    }
    if (customPrompt != null && !customPrompt.trim().isEmpty()) {
        info.put("customVideoPrompt", customPrompt);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
    }
    self().doGenerateVideoRefByPanelId(panelId, offPeak, videoModel);
}
```

- [ ] **Step 4: Add `doGenerateVideoRefByPanelId` async method**

Add after `doGenerateVideoByPanelId` (after line 481):

```java
@Async
public void doGenerateVideoRefByPanelId(Long panelId, boolean offPeak, String overrideVideoModel) {
    try {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();

        // 更新状态
        info.put("videoStatus", "generating");
        info.remove("videoProgress");
        info.remove("videoCredits");
        info.remove("errorMessage");
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);

        // 收集参考图
        Pair<List<String>, List<String>> refPair = collectReferenceImagesWithNames(panel);
        List<String> refImages = refPair.getKey();
        List<String> charNames = refPair.getValue();

        // 构建提示词
        String prompt = resolveFinalVideoPrompt(info, buildAutoMultiShotPrompt(panel, info));

        // 计算总时长
        int totalDuration = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
        if (shots != null) {
            for (Map<String, Object> shot : shots) {
                Object dur = shot.get("duration");
                if (dur instanceof Number) totalDuration += ((Number) dur).intValue();
            }
        }
        if (totalDuration <= 0) totalDuration = 5;
        if (totalDuration > 10) totalDuration = 10;

        // 获取视频模型
        String projectId = getProjectIdByPanelIdForProvider(panelId);
        String videoModel = (overrideVideoModel != null && !overrideVideoModel.trim().isEmpty())
            ? overrideVideoModel
            : (projectId != null ? getVideoModel(projectId) : null);

        // viduq3-mix 强制关闭 off_peak
        boolean effectiveOffPeak = offPeak;
        if (videoModel != null && videoModel.contains("mix")) {
            effectiveOffPeak = false;
        }

        // 调用参考图视频生成服务
        String aspectRatio = getAspectRatio(projectId);
        String taskId = viduReference2VideoService.generateAsyncMultiImage(
            prompt, totalDuration, aspectRatio, refImages, charNames, effectiveOffPeak, videoModel);

        info.put("videoTaskId", taskId);
        info.put("offPeak", effectiveOffPeak);
        if (videoModel != null && !videoModel.isEmpty()) {
            info.put("videoModel", videoModel);
        }
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);

        // 使用 viduReference2VideoService 轮询
        self().pollNewVideoTaskWithService(panelId, taskId, effectiveOffPeak, viduReference2VideoService);
        log.info("参考图视频生成已提交: panelId={}, taskId={}, images={}, offPeak={}",
            panelId, taskId, refImages.size(), effectiveOffPeak);
    } catch (Exception e) {
        log.error("参考图视频生成失败: panelId={}", panelId, e);
        updatePanelState(panelId, "videoStatus", "failed", e.getMessage());
        publishPanelFailure(panelId, e.getMessage());
        throw new BusinessException("参考图视频生成失败: " + e.getMessage());
    }
}
```

- [ ] **Step 5: Add `pollNewVideoTaskWithService` method (reuse polling logic with injected service)**

The existing `pollNewVideoTask` (line 484) gets the video service via `aiServiceConfig`. For reference2video, we need to use `viduReference2VideoService` directly. Add a new polling method:

```java
@Async
public void pollNewVideoTaskWithService(Long panelId, String taskId, boolean offPeak,
                                         VideoGenerationService videoService) {
    int intervalSeconds = 5;
    int maxPolls = offPeak ? 2880 : 120;

    try {
        for (int i = 0; i < maxPolls; i++) {
            VideoGenerationService.TaskStatus status = videoService.getTaskStatus(taskId);
            if (status == null) { Thread.sleep(intervalSeconds * 1000L); continue; }

            // 更新进度和积分
            Panel progressPanel = panelRepository.selectById(panelId);
            if (progressPanel != null) {
                Map<String, Object> info = progressPanel.getPanelInfo();
                info.put("videoProgress", status.getProgress());
                if (status.getCredits() != null) info.put("videoCredits", status.getCredits());
                progressPanel.setPanelInfo(info);
                panelRepository.updateById(progressPanel);
            }

            switch (status.getStatus()) {
                case "completed":
                    String videoUrl = status.getVideoUrl();
                    if (videoUrl == null) videoUrl = videoService.downloadVideo(status.getTaskId());
                    boolean videoUrlPermanent = false;
                    try {
                        String ossVideoUrl = ossService.uploadVideoFromUrl(videoUrl, null);
                        videoUrl = ossVideoUrl;
                        videoUrlPermanent = true;
                    } catch (Exception e) {
                        log.error("视频上传OSS失败: panelId={}", panelId, e);
                    }
                    Panel panel = panelRepository.selectById(panelId);
                    if (panel != null) {
                        Map<String, Object> info = panel.getPanelInfo();
                        info.put("videoUrl", videoUrl);
                        info.put("videoUrlPermanent", videoUrlPermanent);
                        info.put("videoStatus", "completed");
                        info.put("videoProgress", 100);
                        if (status.getCredits() != null) info.put("videoCredits", status.getCredits());
                        info.put("errorMessage", null);
                        panel.setPanelInfo(info);
                        panelRepository.updateById(panel);
                    }
                    log.info("参考图视频生成完成: panelId={}", panelId);
                    String projId = getProjectIdByPanelId(panelId);
                    if (projId != null && panel != null) {
                        eventPublisher.publishPanelVideoDone(projId, panel.getEpisodeId(), panelId, videoUrl);
                    }
                    return;
                case "failed":
                    String errMsg = status.getErrorMessage();
                    updatePanelState(panelId, "videoStatus", "failed", errMsg);
                    publishPanelFailure(panelId, errMsg);
                    log.error("参考图视频生成失败: panelId={}, error={}", panelId, errMsg);
                    return;
                default:
                    Thread.sleep(intervalSeconds * 1000L);
                    break;
            }
        }
        log.warn("参考图视频轮询超时: panelId={}", panelId);
        updatePanelState(panelId, "videoStatus", "failed", "视频生成超时");
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("参考图视频轮询被中断: panelId={}", panelId);
    } catch (Exception e) {
        log.error("参考图视频轮询异常: panelId={}", panelId, e);
        updatePanelState(panelId, "videoStatus", "failed", e.getMessage());
        publishPanelFailure(panelId, e.getMessage());
    }
}
```

- [ ] **Step 6: Compile check**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -30`
Expected: BUILD SUCCESS (fix any import/type issues)

- [ ] **Step 7: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat(video): add reference video generation with multi-image support in PanelProductionService"
```

---

## Task 7: Backend — Add `POST /video-ref` Endpoint to `PanelController`

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/PanelController.java`

- [ ] **Step 1: Add the `video-ref` endpoint**

After the existing `generateVideo` endpoint (line 188), add:

```java
@PostMapping("/{panelId}/video-ref")
@Operation(summary = "参考图视频生成（多图参考 → Vidu reference2video）")
public Result<Void> generateVideoRef(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId,
        @RequestBody(required = false) Map<String, Object> body) {
    boolean offPeak = body != null && Boolean.TRUE.equals(body.get("offPeak"));
    String customPrompt = body != null ? (String) body.get("customPrompt") : null;
    String videoModel = body != null ? (String) body.get("videoModel") : null;
    panelProductionService.generateVideoRefByPanelId(panelId, offPeak, customPrompt, videoModel);
    return Result.ok();
}
```

- [ ] **Step 2: Compile check**

Run: `cd backend/com && mvn compile -pl . -q 2>&1 | head -20`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/PanelController.java
git commit -m "feat(api): add POST /panels/{panelId}/video-ref endpoint for reference video generation"
```

---

## Task 8: Frontend — Add `videoRefMode` to Types

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/project.types.ts`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts`

- [ ] **Step 1: Add `videoRefMode` to `CreateProjectRequest` in `project.types.ts`**

After line 40 (`videoModel?: string;`), add:

```typescript
videoRefMode?: boolean;
```

- [ ] **Step 2: Add `videoRefMode` to `SegmentState` in `types.ts`**

After line 47 (`videoCredits?: number | null;`), add:

```typescript
videoRefMode?: boolean | null;
```

- [ ] **Step 2b: Add `videoRefMode` to `ProjectInfoData` in `project.types.ts`**

在 `ProjectInfoData` 接口中也添加 `videoRefMode?: boolean;`（确保 `project?.projectInfo?.videoRefMode` 有正确的类型推导）。

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/types/project.types.ts \
        frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
git commit -m "feat(types): add videoRefMode to CreateProjectRequest and SegmentState"
```

---

## Task 9: Frontend — Add `generateVideoRef` to `episodeService.ts`

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts`

- [ ] **Step 1: Add `generateVideoRef` function**

After the existing `generateVideo` function (line 272), add:

```typescript
/** 参考图视频模式生成视频 */
export async function generateVideoRef(
  projectId: string,
  episodeId: number,
  panelId: number,
  offPeak: boolean = false,
  customPrompt?: string,
  videoModel?: string,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video-ref`,
    { offPeak, customPrompt, videoModel },
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/episodeService.ts
git commit -m "feat(api): add generateVideoRef service function"
```

---

## Task 10: Frontend — Step1 Video Mode Selector

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`

- [ ] **Step 1: Add video mode state and options**

After line 67 (videoProviderOptions), add:

```typescript
// 视频模式选项
const videoModeOptions = [
  { value: 'first_frame', label: '首帧视频' },
  { value: 'reference_images', label: '参考图视频' },
];
```

After line 157 (protagonistVoiceId state), add:

```typescript
const [videoRefMode, setVideoRefMode] = useState<boolean>(() =>
  info?.videoRefMode === true
);
```

- [ ] **Step 2: Add model auto-switch when selecting reference mode**

Add a `useEffect` or handler that:
- When `videoRefMode` becomes `true` and `videoModel` is not `viduq3-mix`, set it to `viduq3-mix`

This can be done inline in the onChange handler of the video mode selector. Add the selector JSX after the videoProvider selector (after line 438):

```tsx
{/* 视频模式选择（仅 Vidu） */}
{videoProvider === 'vidu' && (
  <div className={styles.configSection}>
    <label className={styles.configLabel}>视频模式</label>
    <Select
      options={videoModeOptions}
      value={videoRefMode ? 'reference_images' : 'first_frame'}
      onChange={(val) => {
        const isRef = val === 'reference_images';
        setVideoRefMode(isRef);
        if (isRef && videoModel !== 'viduq3-mix') {
          setVideoModel('viduq3-mix');
        }
      }}
    />
    {videoRefMode && (
      <span style={{ fontSize: 'var(--font-size-sm)', color: 'var(--color-text-muted)', marginTop: 4 }}>
        已自动切换至 viduq3-mix，不支持错峰模式
      </span>
    )}
  </div>
)}
```

**Note:** The `Select` component here follows the existing pattern at line 423-428. If `Select` doesn't accept `value` as string, check how it's used elsewhere in the file.

- [ ] **Step 3: Add `videoRefMode` to request data**

In `handleSubmit`, in `requestData` object (line 209-223), add after line 218 (`videoModel,`):

```typescript
videoRefMode: videoRefMode || undefined,
```

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx
git commit -m "feat(step1): add video mode selector (首帧视频/参考图视频) with auto model switch"
```

---

## Task 11: Frontend — Step4 Conditional Rendering for Reference Video Mode

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoSegmentRow.tsx`

- [ ] **Step 1: Add `videoRefMode` state in Step4Production.tsx**

After the `videoModel` state (around line 352-362), add:

```typescript
const isVideoRefMode = project?.projectInfo?.videoRefMode === true;
```

- [ ] **Step 2: Hide off-peak toggle when `isVideoRefMode` and model is `viduq3-mix`**

At the off-peak toggle (lines 1617-1624), wrap with condition:

```tsx
{!(isVideoRefMode && videoModel === 'mix') && (
<button
  className={`${styles.offPeakToggle} ${offPeak ? styles.offPeakActive : ''}`}
  onClick={toggleOffPeak}
>
  <span className={styles.toggleTrack}><span className={styles.toggleThumb} /></span>
  <span className={styles.toggleLabel}>错峰</span>
</button>
)}
```

**Note:** The `videoModel` in Step4 is `'pro' | 'turbo'`, not the full model name. Need to add a derived check. Actually, for Step4, we should check the actual project videoModel. Add:

```typescript
const projectVideoModel = project?.projectInfo?.videoModel || '';
const isMixModel = projectVideoModel.includes('mix');
```

Then use `!(isVideoRefMode && isMixModel)` for the off-peak toggle.

- [ ] **Step 3: Add `generateVideoRef` import and create `handleGenerateVideoRef`**

At the top of Step4Production.tsx, add import:

```typescript
import { generateVideo, generateVideoRef, ... } from '../../services/episodeService';
```

Create a new handler `handleGenerateVideoRef` (similar to `handleGenerateVideo` at line 1026, but calling `generateVideoRef`):

```typescript
const handleGenerateVideoRef = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
  if (!projectId) return;
  const key = `${episodeId}-${panelId}`;
  setGeneratingVideoKeys(prev => new Set(prev).add(key));

  const abort = new AbortController();
  generateVideoAbortRef.current = abort;
  let retries = 0;

  const poll = async () => {
    while (retries < VIDEO_POLL_MAX_RETRIES && !abort.signal.aborted) {
      await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL));
      if (abort.signal.aborted) return;
      retries++;
      try {
        const res = await getBatchProductionStatuses(projectId, episodeId);
        if ((res.code !== 0 && res.code !== 200) || !res.data) continue;
        const panelStatus = res.data.find((s: any) => s.panelId === Number(panelId));
        if (panelStatus) {
          await refreshProductionStatuses(episodeId);
          if (panelStatus.videoStatus === 'completed' || panelStatus.videoStatus === 'failed') {
            setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
            if (panelStatus.videoStatus === 'failed') alert('视频生成失败');
            return;
          }
        }
      } catch { /* continue */ }
    }
    setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
  };
  poll();

  setChapters(prev => prev.map(ch => ({
    ...ch,
    episodes: ch.episodes.map(ep =>
      ep.episodeId === episodeId
        ? {
            ...ep,
            segments: ep.segments.map(seg =>
              seg.panelData?.panelId === panelId
                ? { ...seg, pipelineStep: 'video_generating' as const, videoProgress: 0, videoStatus: 'generating' as any }
                : seg
            ),
          }
        : ep
    ),
  })));

  // viduq3-mix 强制 offPeak=false
  const effectiveOffPeak = isMixModel ? false : offPeak;
  generateVideoRef(projectId, episodeId, Number(panelId), effectiveOffPeak, customPrompt, projectVideoModel || undefined)
    .catch((err: any) => {
      abort.abort();
      alert(err?.response?.data?.message || err?.message || '生成视频失败');
      setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
    });
}, [projectId, offPeak, refreshProductionStatuses, isMixModel, projectVideoModel]);
```

- [ ] **Step 4: Route `onGenerateVideo` to the correct handler based on mode**

When passing `onGenerateVideo` to `VideoSegmentRow` (in the video tab rendering around line 1900+), use:

```tsx
onGenerateVideo={isVideoRefMode ? handleGenerateVideoRef : handleGenerateVideo}
```

- [ ] **Step 5: Hide prompt enhance button in reference mode**

Find the "润色" (enhance) buttons in the video tab and wrap with:

```tsx
{!isVideoRefMode && (
  <button ...>润色</button>
)}
```

This applies to:
- Episode-level "批量润色" button (line ~1886-1890)
- Chapter-level "润色" button (line ~1829-1833)
- Project-level "润色" button in toolbar

- [ ] **Step 6: In the prompt modal, show reference info instead of fusion image**

In the prompt modal section (lines 1993-1999), replace:

```tsx
{seg?.fusionImageUrl && (
  <div className={styles.modalFusionWrap}>
    <span className={styles.modalFusionLabel}>融合参考图</span>
    <img src={seg.fusionImageUrl} alt="融合参考图" className={styles.modalFusionImg} />
  </div>
)}
```

With:

```tsx
{!isVideoRefMode && seg?.fusionImageUrl && (
  <div className={styles.modalFusionWrap}>
    <span className={styles.modalFusionLabel}>融合参考图</span>
    <img src={seg.fusionImageUrl} alt="融合参考图" className={styles.modalFusionImg} />
  </div>
)}
{isVideoRefMode && (
  <div className={styles.modalFusionWrap}>
    <span className={styles.modalFusionLabel}>参考图视频模式</span>
    <span style={{ fontSize: 'var(--font-size-sm)', color: 'var(--color-text-muted)' }}>
      使用分镜切分图 + 角色图作为参考，无需融合图
    </span>
  </div>
)}
```

- [ ] **Step 7: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat(step4): add conditional rendering for reference video mode"
```

---

## Task 12: Frontend — VideoSegmentRow Reference Image Display

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoSegmentRow.tsx`

- [ ] **Step 1: Add `isVideoRefMode` prop to VideoSegmentRow**

Add to the `VideoSegmentRowProps` interface:

```typescript
isVideoRefMode?: boolean;
```

Add to the destructured props:

```typescript
isVideoRefMode = false,
```

- [ ] **Step 2: Conditionally show reference image info instead of fusion image**

Replace the fusion image display (lines 172-182):

```tsx
{!isVideoRefMode && segment.fusionImageUrl && (
  <div className={styles.panelDetailSection}>
    <span className={styles.panelDetailLabel}>融合参考图</span>
    <img
      src={segment.fusionImageUrl}
      alt="融合参考图"
      className={`${styles.panelFusionImage} ${styles.clickableImage}`}
      onClick={() => onOpenLightbox(segment.fusionImageUrl!)}
    />
  </div>
)}
{isVideoRefMode && (
  <div className={styles.panelDetailSection}>
    <span className={styles.panelDetailLabel}>参考图模式</span>
    <span className={styles.panelVideoTag}>分镜切分图 + 角色图</span>
  </div>
)}
```

- [ ] **Step 3: Pass `isVideoRefMode` from Step4Production**

In Step4Production.tsx, when rendering `VideoSegmentRow` (around line 1920+), add:

```tsx
isVideoRefMode={isVideoRefMode}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoSegmentRow.tsx \
        frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat(ui): show reference image info in VideoSegmentRow for ref mode"
```

---

## Task 13: Frontend — Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Run frontend build**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -20`
Expected: Build completes with no errors

- [ ] **Step 2: Fix any TypeScript errors if found**

Address any type errors that appear during the build.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve TypeScript build errors"
```

---

## Task 14: Backend — Full Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Run backend build**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 2: Fix any compilation errors**

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve backend compilation errors"
```

---

## Implementation Notes

### Key Patterns to Follow

1. **Backend service pattern**: Follow `ViduVideoService.java` exactly — same annotations (`@Service`, `@Slf4j`, `@RequiredArgsConstructor`), same HTTP client pattern (OkHttp), same error handling (try-finally with semaphore).
2. **Frontend component pattern**: Follow existing `Step4Production.tsx` patterns — `useCallback` for handlers, optimistic UI updates, polling loop for status.
3. **CSS pattern**: Use existing design token variables (`--color-*`, `--space-*`, `--radius-*`, etc.) for any new styles.
4. **API pattern**: Follow `PanelController.java` — `@RequestBody(required = false) Map<String, Object>`, extract params with null checks, return `Result.ok()`.

### Vidu API Differences: viduq3 vs viduq3-mix

| Feature | viduq3 | viduq3-mix |
|---------|--------|------------|
| Duration | 3-16s | 1-16s |
| Resolution | 540p, 720p, 1080p | 720p, 1080p |
| Off-peak | Supported | **Not supported** (force false) |
| Camera | Intelligent switching | Scene transitions |

### Data Flow Summary

```
Step1: videoRefMode=true → projectInfo.videoRefMode=true

Step4b approve: videoRefMode=true → panelInfo.fusionImageUrl=null, panelInfo.videoRefMode=true

Step4c video tab: videoRefMode=true →
  - collectReferenceImagesWithNames(panel)
  - → splitShots[0..2].splitImageUrl + charRefs[0..N].url (max 7)
  - → ViduReference2VideoService.generateAsyncMultiImage(prompt, duration, ratio, images, charNames, offPeak, model)
  - → pollNewVideoTaskWithService(panelId, taskId, offPeak, viduReference2VideoService)
```
