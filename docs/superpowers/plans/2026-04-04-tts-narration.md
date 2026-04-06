# TTS 旁白系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在漫剧解说模式下，为每个 panel 生成 TTS 旁白音频，集成到 4c 视频生成 Tab，用户可单独或批量触发。

**Architecture:**
- 新建 `ViduTtsService` 调用 Vidu TTS 同步接口，文本拼接用停顿标记 `<#N#>` 实现旁白与角色对白的交替
- 音频上传 OSS 持久化，结果写入 `panelInfo.ttsAudioUrl` / `ttsStatus` / `ttsCredits`
- 新增 TTS 端点（单个 + 批量），`getBatchProductionStatuses` 扩展返回 TTS 字段
- `StateChangeEventPublisher` 新增 `panel:tts_done` / `panel:tts_failed` 事件驱动前端刷新
- Step1 新增旁白视角 + 音色配置，ComicCommentaryPanelPromptBuilder 移除 narration 文本、注入音色描述

**Tech Stack:** Java Spring Boot, OkHttp, Jackson, MyBatis-Plus JSON column, Aliyun OSS, Redis SSE

---

## 文件结构

```
backend/com/comic/src/main/java/com/comic/
├── ai/
│   └── text/
│       └── ViduTtsService.java          # NEW: TTS API 调用
├── config/
│   └── ViduProperties.java               # MODIFY: 新增 ttsEndpoint 配置
├── constant/
│   └── ProjectInfoKeys.java             # MODIFY: 新增 3 个常量
├── controller/
│   └── PanelController.java             # MODIFY: 新增 2 个 TTS 端点
├── dto/request/
│   └── ProjectCreateRequest.java       # MODIFY: 新增 3 个字段
├── service/
│   ├── oss/
│   │   └── OssService.java             # MODIFY: 新增 uploadAudioFromUrl()
│   ├── panel/
│   │   └── PanelService.java           # MODIFY: 新增 TTS 生成逻辑
│   └── production/
│       └── PanelProductionService.java # MODIFY: getProductionStatus 新增 TTS 字段
├── statemachine/service/
│   └── StateChangeEventPublisher.java  # MODIFY: 新增 TTS 事件发布方法

frontend/wiset_aivideo_generator/src/
├── pages/create/steps/
│   ├── Step1Content.tsx                # MODIFY: 旁白配置 UI
│   └── Step4Production.tsx             # MODIFY: 左右两栏 + TTS 控制
├── pages/create/steps/
│   └── Step4Production.module.less    # MODIFY: 两栏布局样式
├── services/
│   └── episodeService.ts               # MODIFY: 新增 TTS API 调用
└── types.ts                            # MODIFY: SegmentState 新增 TTS 字段
```

---

## Task 1: 后端 — ProjectInfoKeys + ProjectCreateRequest 新增字段

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java`
- Modify: `backend/com/comic/src/main/java/com/comic/controller/ProjectController.java:66-78`
- Modify: `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java:121-176`

- [ ] **Step 1: 在 ProjectInfoKeys 新增 3 个常量**

在 `ProjectInfoKeys.java` 文件末尾（`PRODUCTION_MODE` 行之后）新增：

```java
public static final String NARRATION_PERSPECTIVE = "narrationPerspective";
public static final String NARRATION_VOICE_ID = "narrationVoiceId";
public static final String PROTAGONIST_VOICE_ID = "protagonistVoiceId";
```

- [ ] **Step 2: 在 ProjectCreateRequest 新增 3 个字段**

在 `productionMode` 字段下方新增：

```java
private String narrationPerspective;
private String narrationVoiceId;
private String protagonistVoiceId;
```

- [ ] **Step 3: ProjectService.createProject() 写入新字段**

在 `ProjectService.java:143`（`info.put(PRODUCTION_MODE, ...)` 之后）新增：

```java
if (narrationPerspective != null) info.put(NARRATION_PERSPECTIVE, narrationPerspective);
if (narrationVoiceId != null) info.put(NARRATION_VOICE_ID, narrationVoiceId);
if (protagonistVoiceId != null) info.put(PROTAGONIST_VOICE_ID, protagonistVoiceId);
```

修改方法签名追加 3 个参数：`String narrationPerspective, String narrationVoiceId, String protagonistVoiceId`。

- [ ] **Step 4（补充）: ProjectController 调用处同步追加参数**

在 `ProjectController.java:66-78` 的 `projectService.createProject(...)` 调用中，追加 3 个参数：

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
    dto.getVideoProvider(),
    dto.getVideoModel(),
    dto.getProductionMode(),
    dto.getNarrationPerspective(),    // 新增
    dto.getNarrationVoiceId(),         // 新增
    dto.getProtagonistVoiceId()        // 新增
);
```

- [ ] **Step 5: ProjectService.updateProject() 处理新字段**

在 `ProjectService.java:171-173`（`productionMode` 处理块之后）新增：

```java
if (request.getNarrationPerspective() != null) info.put(NARRATION_PERSPECTIVE, request.getNarrationPerspective());
if (request.getNarrationVoiceId() != null) info.put(NARRATION_VOICE_ID, request.getNarrationVoiceId());
if (request.getProtagonistVoiceId() != null) info.put(PROTAGONIST_VOICE_ID, request.getProtagonistVoiceId());
```

- [ ] **Step 6: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java
git commit -m "feat: add narration perspective and voice ID fields to project"
```

---

## Task 2: 后端 — ViduTtsService 新建

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/ai/text/ViduTtsService.java`
- Modify: `backend/com/comic/src/main/java/com/comic/config/ViduProperties.java:43`（新增 ttsEndpoint）

- [ ] **Step 1: ViduProperties 新增 TTS endpoint 配置**

在 `ViduProperties.java:43`（`promptEnhanceEndpoint` 之后）新增：

```java
private String ttsEndpoint = "/audio-tts";
```

- [ ] **Step 2: 创建 ViduTtsService**

```java
package com.comic.ai.text;

import com.comic.config.ViduProperties;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Vidu TTS 旁白生成服务
 * 调用 POST https://api.vidu.cn/ent/v2/audio-tts（同步接口）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViduTtsService {

    private final ViduProperties viduProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OssService ossService;

    /**
     * TTS 文本拼接算法
     * 按 shot 顺序拼接：
     * - 无台词 shot：输出 narration 文本
     * - 有台词 shot：插入 <#duration#> 静音标记
     *
     * @param shots panelInfo["shots"] 列表，每个 shot 为 Map
     * @return 拼接后的 TTS 文本，空字符串表示所有 shot 都有台词（跳过 TTS）
     */
    public String buildTtsText(List<Map<String, Object>> shots) {
        if (shots == null || shots.isEmpty()) return "";

        List<String> segments = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            boolean hasDialogue = hasDialogue(shot);
            String narration = extractNarration(shot);  // 复用已有逻辑

            if (hasDialogue) {
                // 有台词：插入静音停顿
                Object durationObj = shot.get("duration");
                if (durationObj != null) {
                    double duration = toDouble(durationObj);
                    segments.add("<#" + duration + "#>");
                }
            } else if (narration != null && !narration.isEmpty() && !"无".equals(narration)) {
                // 无台词有旁白：输出旁白文本
                segments.add(narration);
            }
        }

        String result = String.join("", segments);
        // 移除首段停顿（前面没有语音）
        if (result.startsWith("<#")) {
            int endIdx = result.indexOf(">");
            if (endIdx > 0) {
                result = result.substring(endIdx + 1);
            }
        }
        return result;
    }

    /**
     * 生成 TTS 音频
     *
     * @param ttsText 拼接好的 TTS 文本
     * @param voiceId Vidu voice_id
     * @return OSS 持久化音频 URL
     */
    public String generate(String ttsText, String voiceId) {
        if (ttsText == null || ttsText.isEmpty()) {
            throw new IllegalArgumentException("TTS 文本为空，无需生成");
        }
        if (voiceId == null || voiceId.isEmpty()) {
            throw new IllegalArgumentException("voiceId 不能为空");
        }

        // 构建请求体（参考 docs/vidu-tts.md）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("text", ttsText);
        requestBody.put("voice_setting_voice_id", voiceId);
        requestBody.put("voice_setting_speed", 1.0);
        requestBody.put("voice_setting_volume", 0);
        requestBody.put("voice_setting_pitch", 0);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("序列化 TTS 请求体失败", e);
        }

        Request request = new Request.Builder()
                .url(viduProperties.getBaseUrl() + viduProperties.getTtsEndpoint())
                .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                log.error("Vidu TTS API 调用失败: {} - {}", response.code(), errorBody);
                throw new RuntimeException("Vidu TTS 生成失败: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("Vidu TTS 响应: {}", responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            String state = root.path("state").asText();
            String fileUrl = root.path("file_url").asText();
            int credits = root.path("credits").asInt(0);

            if (!"success".equals(state) || fileUrl.isEmpty()) {
                throw new RuntimeException("Vidu TTS 生成失败: state=" + state + ", file_url=" + fileUrl);
            }

            // 上传至 OSS 持久化
            String ossUrl = ossService.uploadAudioFromUrl(fileUrl);
            log.info("Vidu TTS 完成: 消耗 credits={}, OSS URL={}", credits, ossUrl);
            return ossUrl;

        } catch (IOException e) {
            throw new RuntimeException("Vidu TTS 请求异常", e);
        }
    }

    /**
     * 判断 shot 是否有台词
     */
    private boolean hasDialogue(Map<String, Object> shot) {
        Object dialogue = shot.get("dialogue");
        if (dialogue == null) return false;
        String d = dialogue.toString().trim();
        return !d.isEmpty() && !"无".equals(d);
    }

    /**
     * 提取 narration 文本
     * 优先读 narration 字段，fallback 到 speaker="旁白" + dialogue
     */
    private String extractNarration(Map<String, Object> shot) {
        if (shot == null) return null;
        Object n = shot.get("narration");
        if (n != null) {
            String s = n.toString().trim();
            if (!s.isEmpty() && !"无".equals(s)) return s;
        }
        String speaker = shot.get("speaker") != null ? shot.get("speaker").toString() : "";
        String dialogue = shot.get("dialogue") != null ? shot.get("dialogue").toString() : "";
        if (speaker.contains("旁白") && !dialogue.isEmpty() && !"无".equals(dialogue.trim())) {
            return dialogue.trim();
        }
        return null;
    }

    private double toDouble(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try { return Double.parseDouble(obj.toString()); }
        catch (Exception e) { return 0; }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/text/ViduTtsService.java backend/com/comic/src/main/java/com/comic/config/ViduProperties.java
git commit -m "feat: add ViduTtsService for narration audio generation"
```

---

## Task 3: 后端 — OssService 新增 uploadAudioFromUrl

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/oss/OssService.java`

参考 `uploadVideoFromUrl()`（第 68-104 行）实现 `uploadAudioFromUrl()`，逻辑相同但使用音频 MIME type。

- [ ] **Step 1: 在 OssService 新增 uploadAudioFromUrl 方法**

在 `uploadVideoFromUrl()` 方法之后新增：

```java
/**
 * 从 URL 下载音频并上传至 OSS
 * @param url 远程音频 URL
 * @return OSS 公共访问 URL
 */
public String uploadAudioFromUrl(String url) {
    if (url == null || url.isEmpty()) {
        throw new IllegalArgumentException("URL 不能为空");
    }
    try {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("下载音频失败: " + response.code());
            }
            byte[] audioBytes = response.body().bytes();
            String originalFilename = extractFilename(url, "audio.mp3");
            String ossPath = "tts/" + UUID.randomUUID().toString() + "/" + originalFilename;
            String ossUrl = uploadBytes(audioBytes, ossPath, "audio/mpeg");
            log.info("音频上传至 OSS: {} -> {}", url, ossUrl);
            return ossUrl;
        }
    } catch (IOException e) {
        throw new RuntimeException("下载或上传音频失败: " + url, e);
    }
}

private String extractFilename(String url, String defaultName) {
    String path = url;
    try {
        path = new URL(url).getPath();
    } catch (Exception ignored) {}
    String name = path.substring(path.lastIndexOf('/') + 1);
    return name.contains(".") ? name : defaultName;
}

private String uploadBytes(byte[] data, String key, String contentType) {
    // 复用现有的 uploadFromInputStream 逻辑
    ByteArrayInputStream bais = new ByteArrayInputStream(data);
    return uploadFromInputStream(bais, data.length, key, contentType);
}
```

注意：如果 `uploadFromInputStream` 返回完整 URL（不是 key），则直接返回其结果。

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/oss/OssService.java
git commit -m "feat: add uploadAudioFromUrl to OssService"
```

---

## Task 4: 后端 — PanelController 新增 TTS 端点 + PanelService TTS 生成逻辑

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/PanelController.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/PanelService.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java:210-239`
- Modify: `backend/com/comic/src/main/java/com/comic/statemachine/service/StateChangeEventPublisher.java`

- [ ] **Step 1: StateChangeEventPublisher 新增 TTS 事件方法**

在 `StateChangeEventPublisher.java:122`（文件末尾 `}` 前）新增：

```java
// ===== Panel 级别事件（TTS 旁白） =====

public void publishPanelTtsDone(String projectId, Long episodeId, Long panelId, String ttsAudioUrl) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("episodeId", episodeId);
    payload.put("panelId", panelId);
    payload.put("ttsAudioUrl", ttsAudioUrl);
    payload.put("ttsStatus", "completed");
    publishToRedis(projectId, "panel:tts_done", payload);
}

public void publishPanelTtsFailed(String projectId, Long episodeId, Long panelId, String error) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("episodeId", episodeId);
    payload.put("panelId", panelId);
    payload.put("ttsStatus", "failed");
    payload.put("error", error);
    publishToRedis(projectId, "panel:tts_failed", payload);
}
```

- [ ] **Step 2: PanelService 新增 TTS 生成方法**

在 `PanelService.java` 新增两个方法：

```java
/**
 * 为单个 panel 生成 TTS 旁白
 * @return { ttsAudioUrl, ttsStatus, ttsCredits }
 */
public Map<String, Object> generateTts(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) throw new BusinessException("Panel 不存在");

    Episode episode = episodeRepository.selectById(panel.getEpisodeId());
    Project project = projectRepository.findByProjectId(episode.getProjectId());
    Map<String, Object> projectInfo = project.getProjectInfo();

    // 获取音色 ID
    String narrationPerspective = (String) projectInfo.get(ProjectInfoKeys.NARRATION_PERSPECTIVE);
    String voiceId;
    if ("first_person".equals(narrationPerspective)) {
        voiceId = (String) projectInfo.get(ProjectInfoKeys.PROTAGONIST_VOICE_ID);
    } else {
        voiceId = (String) projectInfo.get(ProjectInfoKeys.NARRATION_VOICE_ID);
    }
    if (voiceId == null || voiceId.isEmpty()) {
        throw new BusinessException("未配置旁白音色，请先在项目设置中选择");
    }

    // 更新状态为 generating
    updatePanelTtsStatus(panel, "generating", null, null);

    try {
        List<Map<String, Object>> shots = (List<Map<String, Object>>) panel.getPanelInfo().get("shots");
        ViduTtsService ttsService = applicationContext.getBean(ViduTtsService.class);
        String ttsText = ttsService.buildTtsText(shots);

        if (ttsText == null || ttsText.isEmpty()) {
            // 所有 shot 都有台词，无需 TTS
            updatePanelTtsStatus(panel, "completed", null, 0);
            Map<String, Object> result = new HashMap<>();
            result.put("ttsAudioUrl", null);
            result.put("ttsStatus", "completed");
            result.put("ttsCredits", 0);
            result.put("skipped", true);
            return result;
        }

        String ossUrl = ttsService.generate(ttsText, voiceId);
        updatePanelTtsStatus(panel, "completed", ossUrl, null);

        // 发布 SSE 事件
        stateChangeEventPublisher.publishPanelTtsDone(
            episode.getProjectId(), panel.getEpisodeId(), panelId, ossUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("ttsAudioUrl", ossUrl);
        result.put("ttsStatus", "completed");
        result.put("ttsCredits", null);  // TTS service 已记录
        return result;

    } catch (Exception e) {
        log.error("TTS 生成失败: panelId={}", panelId, e);
        updatePanelTtsStatus(panel, "failed", null, null);
        stateChangeEventPublisher.publishPanelTtsFailed(
            episode.getProjectId(), panel.getEpisodeId(), panelId, e.getMessage());
        throw new RuntimeException("TTS 生成失败: " + e.getMessage(), e);
    }
}

/**
 * 批量为某集所有 panel 生成 TTS
 * @return { generated, skipped }
 */
public Map<String, Object> batchGenerateTts(Long episodeId) {
    List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
    int generated = 0, skipped = 0;
    for (Panel panel : panels) {
        try {
            Map<String, Object> result = generateTts(panel.getId());
            if (Boolean.TRUE.equals(result.get("skipped"))) {
                skipped++;
            } else {
                generated++;
            }
        } catch (Exception e) {
            log.warn("Panel {} TTS 跳过或失败: {}", panel.getId(), e.getMessage());
            skipped++;
        }
    }
    Map<String, Object> result = new HashMap<>();
    result.put("generated", generated);
    result.put("skipped", skipped);
    return result;
}

/**
 * 更新 panel 的 TTS 状态到 panelInfo
 */
private void updatePanelTtsStatus(Panel panel, String status, String audioUrl, Integer credits) {
    Map<String, Object> panelInfo = panel.getPanelInfo();
    if (panelInfo == null) {
        panelInfo = new HashMap<>();
    }
    panelInfo.put("ttsStatus", status);
    if (audioUrl != null) panelInfo.put("ttsAudioUrl", audioUrl);
    if (credits != null) panelInfo.put("ttsCredits", credits);
    panel.setPanelInfo(panelInfo);
    panelRepository.updateById(panel);
}
```

依赖注入需要添加：`@Autowired private ApplicationContext applicationContext;` 和 `private final StateChangeEventPublisher stateChangeEventPublisher;`。

- [ ] **Step 3: PanelController 新增 TTS 端点**

在 `PanelController.java` 末尾（`}` 前）新增两个端点：

```java
// ===== TTS 旁白生成 =====

@PostMapping("/{panelId}/tts")
public Result<Map<String, Object>> generatePanelTts(@PathVariable Long panelId) {
    Map<String, Object> result = panelService.generateTts(panelId);
    return Result.success(result);
}

@PostMapping("/tts/batch")
public Result<Map<String, Object>> batchGenerateTts(@RequestParam Long episodeId) {
    Map<String, Object> result = panelService.batchGenerateTts(episodeId);
    return Result.success(result);
}
```

注意：批量端点路径为 `/panels/tts/batch`（在 `PanelController` 中已自然对应 `/panels/tts/batch`），无需额外路由前缀。

- [ ] **Step 4: PanelProductionService.getProductionStatus 新增 TTS 字段**

在 `PanelProductionService.java:229`（`videoCredits` 行之后）新增：

```java
status.put("ttsStatus", panelInfo.getOrDefault("ttsStatus", "pending"));
status.put("ttsAudioUrl", panelInfo.get("ttsAudioUrl"));
status.put("ttsCredits", panelInfo.get("ttsCredits"));
```

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/statemachine/service/StateChangeEventPublisher.java backend/com/comic/src/main/java/com/comic/service/panel/PanelService.java backend/com/comic/src/main/java/com/comic/controller/PanelController.java backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: add TTS generation endpoints and state change events"
```

---

## Task 5: 后端 — 移除视频 Prompt 中的 narration 文本 + 注入音色描述

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java:185-210`
- Modify: `backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java`（prompt 中台词密度约束）

- [ ] **Step 1: ComicCommentaryPanelPromptBuilder 移除视频 prompt 中的 narration 文本**

找到 `ComicCommentaryPanelPromptBuilder.buildMultiShotPrompt()` 方法中类似这样的行：
```java
String narLine = effectiveNarrationText(shot);
if (narLine != null) {
    sb.append("解说旁白: ").append(narLine).append("\n");
}
```
**删除这些行**（仅删除 narration 输出行，保留其他解说模式约束）。

同样检查九宫格 prompt（`buildGridPrompt()` 中的 `解说旁白(口播)` 行）并删除。

- [ ] **Step 2: ComicCommentaryPanelPromptBuilder 注入音色描述（第一人称模式）**

在 `buildMultiShotPrompt()` 的对白行，当 `narrationPerspective === "first_person"` 时，注入音色描述。

找到对白构建逻辑，修改 `sb.append("角色对白");` 附近，在 `narrationPerspective == "first_person"` 时追加音色描述：

```java
// 音色特征映射
Map<String, String> voiceDesc = new HashMap<>();
voiceDesc.put("male-qn-jingying", "年轻精英男性，声线沉稳自信、清晰有力");
voiceDesc.put("male-qn-badao", "霸道青年男性，声线低沉威严、气场强大");
// ... 其他映射

// 在 dialogue 构建后追加
if ("first_person".equals(narrationPerspective) && voiceId != null && voiceDesc.containsKey(voiceId)) {
    sb.append("，音色特征：").append(voiceDesc.get(voiceId));
}
```

需要通过 projectId 查询 projectInfo 获取 `narrationPerspective` 和 `protagonistVoiceId`。在 `ComicCommentaryPanelPromptBuilder` 中注入 `ProjectService` 依赖，从 `projectInfo` 中读取这两个字段。

- [ ] **Step 3: DeepSeekTextService 在解说模式 prompt 中约束台词密度**

在 `generatePanelAwareStoryboard()` 对应的 system prompt 中，新增约束：
```
（漫剧解说模式限定）
- 每集 9 个分镜中仅 1-2 个可以有台词（dialogue 不为"无"）
- 其余分镜必须无台词（dialogue 为"无"），通过 narration 旁白推动剧情
- 第一人称模式：narration 以主角口吻叙述（"我..."）
- 第三人称模式：narration 以旁观者口吻叙述
```

找到 DeepSeekTextService 中构建 comic_commentary storyboard prompt 的位置（搜索 `comic_commentary` 或 `解说模式` 关键字）添加此约束。

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java
git commit -m "feat: remove narration from video prompts and add voice description for first-person mode"
```

---

## Task 6: 前端 — Step1 旁白配置 UI

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/project.types.ts`
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts`

- [ ] **Step 1: project.types.ts 新增 CreateProjectRequest 字段**

在 `CreateProjectRequest` 接口新增：
```typescript
narrationPerspective?: 'first_person' | 'third_person';
narrationVoiceId?: string;
protagonistVoiceId?: string;
```

- [ ] **Step 2: Step1Content.tsx 新增旁白配置区域**

在 Step1Content.tsx 中找到 productionMode 选择区域，当 `productionMode === 'comic_commentary'` 时，在下方渲染旁白配置：

```tsx
{productionMode === 'comic_commentary' && (
  <div className={styles.narrationConfig}>
    <div className={styles.configLabel}>旁白配置</div>
    {/* 旁白视角选择 */}
    <Radio.Group
      value={narrationPerspective}
      onChange={e => {
        setNarrationPerspective(e.target.value);
        // 切换视角时清空音色选择
        setNarrationVoiceId(undefined);
        setProtagonistVoiceId(undefined);
      }}
    >
      <Radio value="first_person">第一人称（主角旁白）</Radio>
      <Radio value="third_person">第三人称（画外音旁白）</Radio>
    </Radio.Group>

    {/* 音色选择 */}
    <Select
      placeholder="选择音色"
      value={narrationPerspective === 'first_person' ? protagonistVoiceId : narrationVoiceId}
      onChange={val => {
        if (narrationPerspective === 'first_person') {
          setProtagonistVoiceId(val);
        } else {
          setNarrationVoiceId(val);
        }
      }}
      style={{ width: 200 }}
    >
      {VOICE_OPTIONS.map(opt => (
        <Select.Option key={opt.voiceId} value={opt.voiceId}>
          {opt.name}
        </Select.Option>
      ))}
    </Select>
  </div>
)}
```

`VOICE_OPTIONS` 使用设计文档中的预置音色列表（中国预制 + Vidu 音色精选）。

- [ ] **Step 3: episodeService.ts 新增 TTS API**

```typescript
export const generatePanelTts = (projectId: string, episodeId: number, panelId: number) =>
  request.post(`/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/tts`);

export const batchGenerateTts = (projectId: string, episodeId: number) =>
  request.post(`/projects/${projectId}/episodes/${episodeId}/panels/tts/batch`);
```

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx frontend/wiset_aivideo_generator/src/project.types.ts frontend/wiset_aivideo_generator/src/services/episodeService.ts
git commit -m "feat: add narration perspective and voice config in Step1"
```

---

## Task 7: 前端 — 4c Tab 左右两栏 + TTS 状态管理

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less`
- Modify: `frontend/wiset_aivideo_generator/src/types.ts`（SegmentState + PanelGridStatusResponse）

- [ ] **Step 1: types.ts SegmentState 新增 TTS 字段**

```typescript
export interface SegmentState {
  // ... 现有字段
  ttsAudioUrl?: string;
  ttsStatus?: 'pending' | 'generating' | 'completed' | 'failed';
  ttsCredits?: number;
}
```

在 `PanelGridStatusResponse` 接口也新增 `ttsAudioUrl`、`ttsStatus`、`ttsCredits`。

- [ ] **Step 2: Step4Production.module.less 新增两栏布局样式**

```less
.panelExpandContent {
  display: flex;
  gap: 16px;

  .leftColumn {
    flex: 0 0 50%;
    // 视频播放器等现有样式保持
  }

  .rightColumn {
    flex: 0 0 50%;
    .narrationHeader {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }
    .shotList {
      .shotItem {
        display: flex;
        align-items: flex-start;
        gap: 8px;
        padding: 6px 0;
        border-bottom: 1px solid #f0f0f0;
        .shotLabel {
          flex-shrink: 0;
          width: 70px;
          font-size: 12px;
          color: #888;
        }
        .shotText {
          flex: 1;
          font-size: 13px;
        }
        .hasDialogue {
          color: #52c41a;
        }
        .hasNarration {
          color: #1890ff;
        }
      }
    }
    .ttsPlayer {
      margin-top: 12px;
    }
  }
}
```

- [ ] **Step 3: Step4Production.tsx Panel 展开区域改为两栏**

在 `renderPanelDetail()` 或对应渲染逻辑中，将原有 panel 展开区域拆分为左右两栏：

**左栏（视频）：** 现有视频播放器 + 分镜描述 + 提示词按钮，保持不变。

**右栏（旁白）：**
```tsx
<div className={styles.rightColumn}>
  <div className={styles.narrationHeader}>
    <span>旁白语音</span>
    <Button
      size="small"
      loading={panel.ttsStatus === 'generating'}
      onClick={() => handleGenerateTts(panel.panelId)}
      disabled={!panel.shots || panel.shots.length === 0}
    >
      {panel.ttsStatus === 'completed' ? '重新生成' : '生成旁白'}
    </Button>
  </div>
  <div className={styles.shotList}>
    {panel.shots?.map((shot, idx) => {
      const hasDialogue = !!(shot.dialogue && shot.dialogue !== '无' && shot.dialogue !== '');
      return (
        <div key={idx} className={styles.shotItem}>
          <span className={styles.shotLabel}>分镜{idx + 1}</span>
          <span className={hasDialogue ? styles.hasDialogue : styles.hasNarration}>
            {hasDialogue ? `[有台词] ${shot.dialogue}` : `[旁白] ${shot.narration || '—'}`}
          </span>
        </div>
      );
    })}
  </div>
  {panel.ttsStatus === 'completed' && panel.ttsAudioUrl && (
    <div className={styles.ttsPlayer}>
      <AudioPlayer src={panel.ttsAudioUrl} />
    </div>
  )}
</div>
```

**集级别批量 TTS 按钮：** 在集 header 区域（Panel 列表 header）新增「批量生成旁白」按钮：

```tsx
{hasComicCommentary && (
  <Button onClick={handleBatchGenerateTts}>
    批量生成旁白 ({completedCount}/{totalCount} 已生成)
  </Button>
)}
```

- [ ] **Step 4: SSE 事件处理新增 tts_done / tts_failed**

在前端 SSE hook 中（`useProjectSSE` 或类似 hook），新增对 `panel:tts_done` 和 `panel:tts_failed` 事件类型的处理：

```typescript
if (event.type === 'panel:tts_done') {
  updateSegmentTts(data.panelId, {
    ttsAudioUrl: data.ttsAudioUrl,
    ttsStatus: 'completed',
  });
}
if (event.type === 'panel:tts_failed') {
  updateSegmentTts(data.panelId, {
    ttsStatus: 'failed',
  });
  message.error(`旁白生成失败: ${data.error}`);
}
```

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less frontend/wiset_aivideo_generator/src/types.ts
git commit -m "feat: add two-column layout and TTS control in 4c tab"
```

---

## Task 8: 编译验证

- [ ] **Step 1: 编译后端**

```bash
cd backend/com/comic && mvn compile -q
```
Expected: 编译成功，无错误

- [ ] **Step 2: 编译前端**

```bash
cd frontend/wiset_aivideo_generator && npm run build 2>&1 | head -30
```
Expected: 无 TypeScript 编译错误

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "chore: verify build passes after TTS feature implementation"
```

---

## 执行顺序

1. Task 1（ProjectInfoKeys + ProjectCreateRequest）— 基础字段
2. Task 3（OssService uploadAudioFromUrl）— TTS 依赖
3. Task 2（ViduTtsService）— 核心服务
4. Task 4（PanelController + PanelService + StateChangeEventPublisher）— API 层
5. Task 5（视频 Prompt 修改）— 配合改动
6. Task 6（Step1 旁白配置 UI）— 前端配置
7. Task 7（4c Tab TTS 集成）— 前端功能
8. Task 8（编译验证）

Task 2 和 Task 3 可并行。Task 6 和 Task 7 可并行。
