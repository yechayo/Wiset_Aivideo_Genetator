# Episode 级九宫格流水线重设计 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将九宫格生成和审核从 Panel 层提升到 Episode 层，实现：生成分镜 → 整集九宫格 → 切割 → 审核 → 分组创建 Panel → 视频。

**Architecture:** Episode 的 episodeInfo JSON 扩展存储整集九宫格数据。九宫格审核通过后，按 16s 贪心分组 splitShots → 创建 Panel → 融合图 + 多镜头视频提示词 → 独立生成视频。不改数据库表结构。

**Tech Stack:** Spring Boot 2.7.18 / Java 8 / MyBatis-Plus / React 19 + TypeScript + Less CSS Modules

**Spec:** `docs/superpowers/specs/2026-03-30-episode-grid-pipeline-redesign.md`

**Prerequisites:**
- `@EnableAsync` 必须在 Spring Boot 应用主类或配置类上启用（检查 `ComicApplication.java`，如未启用需添加 `@EnableAsync` 注解）
- `generateGridsForEpisode` 使用 `@Async` 异步执行，`storyboard_generated` 事件在九宫格实际生成完成前就发出，前端通过轮询 `GET /episodes/{id}/grid` 获取实际生成状态

---

## 文件结构总览

### 后端修改文件

| 文件 | 职责 | 改动类型 |
|------|------|----------|
| `backend/.../ai/PanelPromptBuilder.java:438-474` | 多镜头视频提示词构建 | 修改 `buildMultiShotPrompt` |
| `backend/.../service/storyboard/StoryboardService.java` | 分镜生成主流程 | 修改：shots 存 episodeInfo，调用 episode 级九宫格 |
| `backend/.../service/panel/GridImageService.java` | 九宫格生成服务 | 新增 `generateGridsForEpisode()` + `createFusionImageForPanel()` |
| `backend/.../controller/EpisodeController.java` | 剧集 API | 新增 4 个九宫格审核端点 |
| `backend/.../service/pipeline/PipelineService.java` | 流水线状态机 | 修改 `all_grids_approved` 检查逻辑 |

### 前端修改文件

| 文件 | 职责 | 改动类型 |
|------|------|----------|
| `frontend/.../services/episodeService.ts` | API 调用层 | 新增 4 个 episode grid API |
| `frontend/.../pages/create/steps/types.ts` | 类型定义 | 扩展 EpisodeState |
| `frontend/.../pages/create/steps/Step5page.tsx` | 主页面 | 修改数据流：episode 级九宫格 → 审核 → panels |
| `frontend/.../pages/create/steps/components/EpisodeCard.tsx` | 剧集卡片 | 双模式：九宫格审核 vs Panel 列表 |
| `frontend/.../pages/create/steps/components/BatchReviewBar.tsx` | 批量审核栏 | 适配 episode 级统计 |

---

## Task 1: PanelPromptBuilder — 【分镜N】格式

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:438-474`

- [ ] **Step 1: 修改 `buildMultiShotPrompt` 方法**

在 `PanelPromptBuilder.java` 中，将 `buildMultiShotPrompt` 方法的 `Shot N:` 格式改为 `【分镜N】` 格式。

**当前代码 (line 451):**
```java
sb.append("Shot ").append(shotNum).append(":\n");
```

**改为:**
```java
sb.append("【分镜").append(shotNum).append("】\n");
```

**当前代码 (line 452-456):**
```java
sb.append("duration: ").append(shot.get("duration")).append("s\n");
sb.append("Scene: ").append(shot.getOrDefault("shotSize", ""))
  .append(", ").append(shot.getOrDefault("cameraAngle", ""))
  .append(", ").append(shot.getOrDefault("cameraMovement", ""))
  .append(", ").append(shot.getOrDefault("visualDescription", "")).append("\n");
```

**改为:**
```java
sb.append("duration: ").append(shot.get("duration")).append("s\n");
sb.append("Scene: ").append(shot.getOrDefault("shotSize", ""))
  .append("，").append(shot.getOrDefault("cameraAngle", ""))
  .append("，").append(shot.getOrDefault("cameraMovement", ""))
  .append("，").append(shot.getOrDefault("visualDescription", "")).append("\n");
```

**当前代码 (line 472):**
```java
sb.append("参考图中编号①②③对应 Shot 1/2/3 的画面内容。");
```

**改为:**
```java
sb.append("参考图中编号①②③对应【分镜1】【分镜2】【分镜3】的画面内容。");
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "refactor: 多镜头提示词格式改为中文【分镜N】标记"
```

---

## Task 2: StoryboardService — shots 存入 episodeInfo，调用 episode 级九宫格

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java`

- [ ] **Step 1: 注入 GridImageService**

在类顶部添加注入（line 42 附近，其他 @Resource 之后）:

```java
@Resource
private GridImageService gridImageService;
```

添加 import:
```java
import com.comic.service.panel.GridImageService;
```

- [ ] **Step 2: 修改 `generateEpisodeScriptAndStoryboard` 主流程**

**当前代码 (lines 65-77):**
```java
for (Map<String, Object> script : scripts) {
    String content = (String) script.get("content");
    String characters = (String) script.getOrDefault("characters", "");

    List<Map<String, Object>> shots = deepSeekTextService.generateStoryboard(
        content, characters, targetDuration, visualStyle);

    List<List<Map<String, Object>>> groups = greedyGroup(shots, MAX_PANEL_DURATION);
    if (groups.isEmpty()) continue;

    Long episodeId = findOrCreateEpisode(projectId, script);
    deleteExistingPanels(episodeId);
    createPanels(episodeId, groups, visualStyle);
}
```

**改为:**
```java
for (Map<String, Object> script : scripts) {
    String content = (String) script.get("content");
    String characters = (String) script.getOrDefault("characters", "");

    List<Map<String, Object>> shots = deepSeekTextService.generateStoryboard(
        content, characters, targetDuration, visualStyle);

    Long episodeId = findOrCreateEpisode(projectId, script, shots, visualStyle);
    deleteExistingPanels(episodeId);

    // 设置 episodeInfo.gridStatus = "generating"，异步生成整集九宫格
    gridImageService.updateEpisodeGridStatus(episodeId, "generating");
    gridImageService.generateGridsForEpisode(episodeId, shots, visualStyle);
}
```

- [ ] **Step 3: 修改 `findOrCreateEpisode` 方法，增加 shots 和 visualStyle 参数**

**当前代码 (lines 140-159):**
```java
private Long findOrCreateEpisode(String projectId, Map<String, Object> script) {
    String title = (String) script.get("title");
    List<Episode> episodes = episodeRepository.findByProjectId(projectId);
    for (Episode ep : episodes) {
        Map<String, Object> info = ep.getEpisodeInfo();
        if (info != null && title.equals(info.get("title"))) {
            info.putAll(script);
            ep.setEpisodeInfo(info);
            episodeRepository.updateById(ep);
            return ep.getId();
        }
    }
    Episode episode = new Episode();
    episode.setProjectId(projectId);
    episode.setStatus("pending");
    episode.setDeleted(false);
    episode.setEpisodeInfo(new HashMap<>(script));
    episodeRepository.insert(episode);
    return episode.getId();
}
```

**改为:**
```java
private Long findOrCreateEpisode(String projectId, Map<String, Object> script,
                                   List<Map<String, Object>> shots, String visualStyle) {
    String title = (String) script.get("title");
    List<Episode> episodes = episodeRepository.findByProjectId(projectId);
    for (Episode ep : episodes) {
        Map<String, Object> info = ep.getEpisodeInfo();
        if (info != null && title.equals(info.get("title"))) {
            info.putAll(script);
            // 新流程：shots 存入 episodeInfo
            info.put("shots", shots);
            info.put("visualStyle", visualStyle);
            info.put("gridStatus", "pending");
            ep.setEpisodeInfo(info);
            episodeRepository.updateById(ep);
            return ep.getId();
        }
    }
    Episode episode = new Episode();
    episode.setProjectId(projectId);
    episode.setStatus("pending");
    episode.setDeleted(false);
    Map<String, Object> episodeInfo = new HashMap<>(script);
    episodeInfo.put("shots", shots);
    episodeInfo.put("visualStyle", visualStyle);
    episodeInfo.put("gridStatus", "pending");
    episode.setEpisodeInfo(episodeInfo);
    episodeRepository.insert(episode);
    return episode.getId();
}
```

- [ ] **Step 4: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: 编译失败（因为 `GridImageService.generateGridsForEpisode` 和 `updateEpisodeGridStatus` 尚不存在），这是预期行为。

- [ ] **Step 5: Commit (Task 2 编译会在 Task 3 完成后通过)**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java
git commit -m "refactor: StoryboardService shots 存入 episodeInfo，调用 episode 级九宫格"
```

---

## Task 3: GridImageService — 新增 episode 级九宫格生成

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`

- [ ] **Step 1: 注入 EpisodeRepository + 补充 imports**

在类顶部添加（line 36 附近）:

```java
@Resource
private EpisodeRepository episodeRepository;
```

添加 import（GridImageService 当前缺少 `HashMap` 和 `Map`）:
```java
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import java.util.HashMap;
```

- [ ] **Step 2: 新增 `updateEpisodeGridStatus` 方法**

在 `generateGridsForPanel` 方法之后添加:

```java
/**
 * 更新 Episode 的九宫格状态
 */
public void updateEpisodeGridStatus(Long episodeId, String status) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("Episode 不存在: " + episodeId);
    Map<String, Object> info = episode.getEpisodeInfo() != null ? episode.getEpisodeInfo() : new HashMap<>();
    info.put("gridStatus", status);
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);
}
```

- [ ] **Step 3: 新增 `generateGridsForEpisode` 方法**

```java
/**
 * 为整个 Episode 生成九宫格图 → 切割 → 构建 splitShots
 * 与 generateGridsForPanel 逻辑类似，但操作的是 episodeInfo 而非 panelInfo
 */
@Async
public void generateGridsForEpisode(Long episodeId, List<Map<String, Object>> shots, String visualStyle) {
    try {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("Episode 不存在: " + episodeId);

        Map<String, Object> episodeInfo = episode.getEpisodeInfo();
        List<String> characterRefUrls = getCharacterReferenceUrls(episodeId);
        int pageCount = calculatePageCount(shots.size(), SHOTS_PER_PAGE);
        List<String> gridImageUrls = new ArrayList<>();

        // 逐页生成九宫格
        for (int page = 0; page < pageCount; page++) {
            int fromIdx = page * SHOTS_PER_PAGE;
            int toIdx = Math.min(fromIdx + SHOTS_PER_PAGE, shots.size());
            List<Map<String, Object>> pageShots = shots.subList(fromIdx, toIdx);

            String prompt = panelPromptBuilder.buildGridPrompt(visualStyle, pageShots, characterRefUrls);
            String imageUrl;
            if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
                imageUrl = seedreamImageService.generateWithMultipleReferences(
                    prompt, characterRefUrls, 1920, 1080);
            } else {
                imageUrl = seedreamImageService.generate(prompt, 1920, 1080, visualStyle);
            }
            gridImageUrls.add(imageUrl);
        }

        // 切割九宫格 → 构建 splitShots
        List<Map<String, Object>> splitShots = new ArrayList<>();
        for (int page = 0; page < gridImageUrls.size(); page++) {
            BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
            List<BufferedImage> subImages = splitGridImage(gridImage, GRID_COLS, GRID_ROWS);
            int fromIdx = page * SHOTS_PER_PAGE;
            for (int i = 0; i < subImages.size() && (fromIdx + i) < shots.size(); i++) {
                Map<String, Object> shot = shots.get(fromIdx + i);
                String ossUrl = uploadToOssEpisode(subImages.get(i), episodeId, fromIdx + i);
                // 构建带完整元数据的 splitShot
                Map<String, Object> splitShot = new HashMap<>(shot);
                splitShot.put("splitImageUrl", ossUrl);
                splitShots.add(splitShot);
            }
        }

        // 更新 episodeInfo
        episodeInfo.put("gridImages", gridImageUrls);
        episodeInfo.put("splitShots", splitShots);
        episodeInfo.put("gridStatus", "generated");
        episodeInfo.put("gridPageCount", pageCount);
        episode.setEpisodeInfo(episodeInfo);
        episodeRepository.updateById(episode);

        log.info("Episode {} 整集九宫格完成, {} 页, {} 分镜", episodeId, pageCount, shots.size());

    } catch (Exception e) {
        log.error("Episode {} 整集九宫格失败", episodeId, e);
        try {
            updateEpisodeGridStatus(episodeId, "failed");
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode != null) {
                Map<String, Object> info = episode.getEpisodeInfo();
                info.put("errorMessage", e.getMessage());
                episode.setEpisodeInfo(info);
                episodeRepository.updateById(episode);
            }
        } catch (Exception ex) {
            log.error("更新失败状态异常: episodeId={}", episodeId, ex);
        }
    }
}
```

- [ ] **Step 4: 新增 `uploadToOssEpisode` 方法（复用 uploadToOss 逻辑，但用 episode 前缀）**

```java
private String uploadToOssEpisode(BufferedImage img, Long episodeId, Object suffix) {
    try {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();
        String fileName = "episode_" + episodeId + "_grid_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        String objectKey = "comic/grids/" + fileName;
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
        return ossService.uploadFromInputStream(bais, objectKey, "image/png", bytes.length);
    } catch (Exception e) {
        throw new RuntimeException("OSS上传失败: episodeId=" + episodeId + ", suffix=" + suffix, e);
    }
}
```

- [ ] **Step 5: 新增公开辅助方法（供 EpisodeController 审核通过时调用）**

```java
/**
 * 为指定 Panel 的 splitShots 创建融合参考图
 * 审核通过后创建 Panel 时调用
 */
public BufferedImage createFusionImageForPanel(List<Map<String, Object>> panelShots, List<String> charRefUrls) {
    return createFusionImage(panelShots, charRefUrls);
}

/**
 * 获取 Episode 的角色参考图（公开版本，供外部调用）
 */
public List<String> getCharacterReferenceUrlsForEpisode(Long episodeId) {
    return getCharacterReferenceUrls(episodeId);
}

/**
 * 上传 Panel 融合图到 OSS
 */
public String uploadFusionImageForPanel(BufferedImage img, Long episodeId, List<Map<String, Object>> shots) {
    try {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();
        int firstShotNum = shots.isEmpty() ? 0 : ((Number) shots.get(0).get("shotNumber")).intValue();
        String fileName = "episode_" + episodeId + "_fusion_" + firstShotNum + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        String objectKey = "comic/grids/" + fileName;
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
        return ossService.uploadFromInputStream(bais, objectKey, "image/png", bytes.length);
    } catch (Exception e) {
        throw new RuntimeException("融合图上传失败: episodeId=" + episodeId, e);
    }
}
```

- [ ] **Step 6: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS（Task 2 的编译错误应该已解决）

- [ ] **Step 7: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: GridImageService 新增 episode 级九宫格生成方法"
```

---

## Task 4: EpisodeController — 新增九宫格审核端点

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java`

- [ ] **Step 1: 注入依赖**

在 EpisodeController 中添加注入和 import:

```java
import com.comic.service.panel.GridImageService;
import com.comic.service.production.PanelProductionService;
import com.comic.service.pipeline.PipelineService;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.transaction.annotation.Transactional;
```

注意：`StoryboardService` 已经注入（类中已有 `private final StoryboardService storyboardService`），直接使用其 `greedyGroup` 静态方法。

在类中添加字段（episodeService 和 storyboardService 之后）:

```java
private final GridImageService gridImageService;
private final PipelineService pipelineService;
private final EpisodeRepository episodeRepository;
private final PanelRepository panelRepository;
```

- [ ] **Step 2: 新增 GET 获取九宫格状态端点**

在 `getEpisodeScript` 方法之后添加:

```java
// ================= 整集九宫格审核 =================

@GetMapping("/{episodeId}/grid")
@Operation(summary = "获取整集九宫格状态")
public Result<Map<String, Object>> getEpisodeGrid(
        @PathVariable String projectId,
        @PathVariable Long episodeId) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo() != null ? episode.getEpisodeInfo() : new HashMap<>();
    Map<String, Object> result = new HashMap<>();
    result.put("gridStatus", info.getOrDefault("gridStatus", "pending"));
    result.put("gridImages", info.getOrDefault("gridImages", new ArrayList<>()));
    result.put("splitShots", info.getOrDefault("splitShots", new ArrayList<>()));
    result.put("gridRejectionFeedback", info.get("gridRejectionFeedback"));
    result.put("shots", info.getOrDefault("shots", new ArrayList<>()));
    result.put("gridPageCount", info.getOrDefault("gridPageCount", 0));
    return Result.ok(result);
}
```

- [ ] **Step 3: 新增 PUT 审核通过端点（带 @Transactional 保证原子性）**

```java
@Transactional
@PutMapping("/{episodeId}/grid/approve")
@Operation(summary = "审核通过整集九宫格，触发分组创建Panel")
public Result<Void> approveEpisodeGrid(
        @PathVariable String projectId,
        @PathVariable Long episodeId) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo();
    String gridStatus = (String) info.getOrDefault("gridStatus", "pending");
    if (!"generated".equals(gridStatus) && !"rejected".equals(gridStatus)) {
        throw new BusinessException("当前状态不可审核: " + gridStatus);
    }

    // 设置 gridStatus = approved
    info.put("gridStatus", "approved");
    info.put("gridRejectionFeedback", null);
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);

    // 读取 splitShots
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> splitShots = (List<Map<String, Object>>) info.get("splitShots");
    String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");

    if (splitShots == null || splitShots.isEmpty()) {
        throw new BusinessException("切割分镜数据为空，无法创建 Panel");
    }

    // 删除已有 Panel（重新分组）
    List<Panel> existingPanels = panelRepository.findByEpisodeId(episodeId);
    for (Panel p : existingPanels) {
        p.setDeleted(true);
        panelRepository.updateById(p);
    }

    // 贪心分组 splitShots（16s 一组）— 使用已注入的 storyboardService
    List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(splitShots, 16);
    // 注意：greedyGroup 是 public static 方法，通过类名调用即可（无需实例）

    // 每组创建 Panel
    List<String> charRefUrls = gridImageService.getCharacterReferenceUrlsForEpisode(episodeId);
    for (List<Map<String, Object>> group : groups) {
        Panel panel = new Panel();
        panel.setEpisodeId(episodeId);
        panel.setStatus("pending");
        panel.setDeleted(false);
        Map<String, Object> panelInfo = new HashMap<>();
        panelInfo.put("shots", group);
        panelInfo.put("totalShots", group.size());
        panelInfo.put("totalDuration",
            group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum());
        panelInfo.put("gridStatus", "approved"); // 跳过 Panel 级审核
        panelInfo.put("videoStatus", "pending");
        panelInfo.put("visualStyle", visualStyle);
        panelInfo.put("gridImages", new ArrayList<String>());

        // 生成融合图
        try {
            BufferedImage fusionImage = gridImageService.createFusionImageForPanel(group, charRefUrls);
            String fusionUrl = gridImageService.uploadFusionImageForPanel(fusionImage, episodeId, group);
            panelInfo.put("fusionImageUrl", fusionUrl);
        } catch (Exception e) {
            // 融合图生成失败不阻断流程
        }

        panel.setPanelInfo(panelInfo);
        panelRepository.insert(panel);
    }

    // 检查是否所有 Episode 的九宫格都已审核通过
    checkAndAdvanceAllGridsApproved(projectId);

    return Result.ok();
}
```

- [ ] **Step 4: 新增 PUT 拒绝端点**

```java
@PutMapping("/{episodeId}/grid/reject")
@Operation(summary = "拒绝整集九宫格")
public Result<Void> rejectEpisodeGrid(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @RequestBody Map<String, String> body) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo();
    String gridStatus = (String) info.getOrDefault("gridStatus", "pending");
    if (!"generated".equals(gridStatus)) {
        throw new BusinessException("当前状态不可拒绝: " + gridStatus);
    }
    info.put("gridStatus", "rejected");
    info.put("gridRejectionFeedback", body.getOrDefault("reason", ""));
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);
    return Result.ok();
}
```

- [ ] **Step 5: 新增 POST 重新生成端点**

```java
@PostMapping("/{episodeId}/grid/regenerate")
@Operation(summary = "重新生成整集九宫格（不重新生成分镜脚本）")
public Result<Void> regenerateEpisodeGrid(
        @PathVariable String projectId,
        @PathVariable Long episodeId) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
    String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");

    if (shots == null || shots.isEmpty()) {
        throw new BusinessException("分镜数据为空，无法重新生成九宫格");
    }

    // 重置状态
    info.put("gridStatus", "generating");
    info.put("gridImages", new ArrayList<>());
    info.put("splitShots", new ArrayList<>());
    info.put("gridRejectionFeedback", null);
    info.put("errorMessage", null);
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);

    // 异步重新生成
    gridImageService.generateGridsForEpisode(episodeId, shots, visualStyle);

    return Result.ok();
}
```

- [ ] **Step 6: 新增 `checkAndAdvanceAllGridsApproved` 私有方法**

```java
private void checkAndAdvanceAllGridsApproved(String projectId) {
    try {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        boolean allApproved = episodes.stream().allMatch(ep -> {
            Map<String, Object> info = ep.getEpisodeInfo();
            return info != null && "approved".equals(info.get("gridStatus"));
        });
        if (allApproved && !episodes.isEmpty()) {
            pipelineService.advancePipeline(projectId, "all_grids_approved");
        }
    } catch (Exception e) {
        // 不阻断主流程
    }
}
```

- [ ] **Step 7: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS（所有 GridImageService 辅助方法已在 Task 3 中添加）

- [ ] **Step 8: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java
git commit -m "feat: EpisodeController 新增整集九宫格审核端点（approve/reject/regenerate/status）"
```

---

## Task 5: PipelineService — all_grids_approved 检查逻辑

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/pipeline/PipelineService.java`

- [ ] **Step 1: 修改 `advancePipeline` 中的 `all_grids_approved` 验证**

找到 PipelineService.java 中 `advancePipeline` 方法，在 `all_grids_approved` 或 `all_panels_confirmed` 事件验证处。

**当前逻辑** 检查的是所有 Panel 数量 > 0。需要改为同时兼容新旧流程：
- 新流程：检查所有 Episode 的 gridStatus === "approved"
- 旧流程：检查 Panel 数量 > 0（向后兼容）

在验证 gate 处，找到类似以下代码:

```java
if ("all_panels_confirmed".equals(event) || "all_grids_approved".equals(event)) {
    // 验证 panels 存在
    List<Episode> episodes = episodeRepository.findByProjectId(projectId);
    int totalPanels = 0;
    for (Episode ep : episodes) {
        totalPanels += panelRepository.findByEpisodeId(ep.getId()).size();
    }
    if (totalPanels == 0) {
        throw new BusinessException("没有可生产的分镜");
    }
}
```

**改为:**
```java
if ("all_panels_confirmed".equals(event) || "all_grids_approved".equals(event)) {
    List<Episode> episodes = episodeRepository.findByProjectId(projectId);

    // 新流程：检查所有 Episode 的 gridStatus === "approved"
    boolean hasNewFlow = episodes.stream()
        .anyMatch(ep -> {
            Map<String, Object> info = ep.getEpisodeInfo();
            return info != null && info.containsKey("gridStatus");
        });

    if (hasNewFlow) {
        // 新流程验证：所有 Episode 九宫格必须审核通过
        for (Episode ep : episodes) {
            Map<String, Object> info = ep.getEpisodeInfo();
            if (info == null || !"approved".equals(info.get("gridStatus"))) {
                throw new BusinessException("请先审核通过所有剧集的九宫格");
            }
        }
    } else {
        // 旧流程验证：Panel 数量 > 0
        int totalPanels = 0;
        for (Episode ep : episodes) {
            totalPanels += panelRepository.findByEpisodeId(ep.getId()).size();
        }
        if (totalPanels == 0) {
            throw new BusinessException("没有可生产的分镜");
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/pipeline/PipelineService.java
git commit -m "feat: PipelineService all_grids_approved 兼容新旧流程"
```

---

## Task 6: 前端 API — episodeService.ts 新增 episode grid API

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts`

- [ ] **Step 1: 在 `// ================= 九宫格审核 API =================` 区域之前添加新的 Episode 级 API**

```typescript
// ================= 整集九宫格审核 API（新流程） =================

/** 获取整集九宫格状态 */
export async function getEpisodeGridStatus(
  projectId: string, episodeId: number,
): Promise<ApiResponse<any>> {
  return get<ApiResponse<any>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid`,
  );
}

/** 审核通过整集九宫格 */
export async function approveEpisodeGrid(
  projectId: string, episodeId: number,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/approve`,
  );
}

/** 拒绝整集九宫格 */
export async function rejectEpisodeGrid(
  projectId: string, episodeId: number, reason: string,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/reject`,
    { reason },
  );
}

/** 重新生成整集九宫格 */
export async function regenerateEpisodeGrid(
  projectId: string, episodeId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/regenerate`,
  );
}
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -5`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/episodeService.ts
git commit -m "feat: 新增整集九宫格审核 API 调用"
```

---

## Task 7: 前端类型 — types.ts 扩展 EpisodeState

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts`

- [ ] **Step 1: 新增 Episode 级九宫格类型和扩展 EpisodeState**

在 `EpisodeState` 接口中添加字段:

```typescript
/** Episode 级九宫格状态 */
export type EpisodeGridStatus = 'pending' | 'generating' | 'generated' | 'approved' | 'rejected' | 'failed';

/** 切割后的分镜（带完整元数据） */
export interface SplitShot {
  shotNumber: number;
  splitImageUrl: string;
  duration: number;
  scene: string;
  characters: string[];
  shotSize: string;
  cameraAngle: string;
  cameraMovement: string;
  visualDescription: string;
  dialogue: string;
  visualEffects: string;
  audioEffects: string;
}
```

修改 `EpisodeState` 接口，在 `episodeInfo` 之前添加:

```typescript
/** 剧集状态 */
export interface EpisodeState {
  episodeId: number;
  episodeIndex: number;
  title: string;
  /** panelPlan JSON 解析后的 scene_summary 映射：panel_id → scene_summary */
  sceneSummaryMap: Record<string, string>;
  segments: SegmentState[];

  // === 新流程：Episode 级九宫格 ===
  /** 整集九宫格状态 */
  gridStatus?: EpisodeGridStatus;
  /** 整集九宫格图 URL 列表（分页） */
  gridImages?: string[];
  /** 切割后的分镜列表（带完整元数据） */
  splitShots?: SplitShot[];
  /** 九宫格拒绝原因 */
  gridRejectionFeedback?: string | null;
  /** 是否使用新流程（episodeInfo 中有 gridStatus 字段） */
  isNewFlow?: boolean;

  /** Raw episodeInfo from backend Episode entity */
  episodeInfo?: Record<string, any>;
}
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -5`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
git commit -m "feat: types.ts 扩展 EpisodeState 支持整集九宫格"
```

---

## Task 8: Step5page.tsx — Episode 级九宫格数据流

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

- [ ] **Step 1: 添加新 API 导入**

在 import 中添加:

```typescript
import {
  getEpisodes,
  getPanels,
  generatePanels,
  getPanelGenerateStatus,
  revisePanel,
  getBatchProductionStatuses,
  approveGrid,
  rejectGrid,
  regenerateGrid,
  approveAllGrids,
  generateVideo,
  reviseSinglePanel,
  updatePanel,
  // 新增
  getEpisodeGridStatus,
  approveEpisodeGrid,
  rejectEpisodeGrid,
  regenerateEpisodeGrid,
} from '../../../services/episodeService';
```

- [ ] **Step 2: 修改 `loadEpisodes` — 解析 episodeInfo 中的九宫格字段**

在 `loadEpisodes` 的 episodeStates 构建处（line 139-146 附近），增加从 episodeInfo 提取九宫格字段:

```typescript
return {
  episodeId: ep.id,
  episodeIndex: ep.episodeInfo?.episodeNum || idx + 1,
  title: ep.episodeInfo?.title,
  sceneSummaryMap,
  segments: [],
  // 新流程：提取 Episode 级九宫格数据
  gridStatus: ep.episodeInfo?.gridStatus || undefined,
  gridImages: ep.episodeInfo?.gridImages || [],
  splitShots: ep.episodeInfo?.splitShots || [],
  gridRejectionFeedback: ep.episodeInfo?.gridRejectionFeedback || null,
  isNewFlow: !!ep.episodeInfo?.gridStatus,
};
```

- [ ] **Step 3: 添加 episode 级九宫格操作方法**

在 `handleApproveAllGrids` 方法之后添加:

```typescript
/**
 * 审核通过整集九宫格（新流程）
 */
const handleApproveEpisodeGrid = useCallback(async (episodeId: number) => {
  if (!projectId) return;
  try {
    await approveEpisodeGrid(projectId, episodeId);
    // 重新加载剧集和 panels
    panelsLoadedRef.current.delete(episodeId);
    await loadEpisodes();
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '审核失败');
  }
}, [projectId, loadEpisodes]);

/**
 * 拒绝整集九宫格（新流程）
 */
const handleRejectEpisodeGrid = useCallback(async (episodeId: number, reason: string) => {
  if (!projectId) return;
  try {
    await rejectEpisodeGrid(projectId, episodeId, reason);
    await loadEpisodes();
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '退回失败');
  }
}, [projectId, loadEpisodes]);

/**
 * 重新生成整集九宫格（新流程）
 */
const handleRegenerateEpisodeGrid = useCallback(async (episodeId: number) => {
  if (!projectId) return;
  try {
    await regenerateEpisodeGrid(projectId, episodeId);
    // 轮询等待生成完成
    const poll = async () => {
      while (true) {
        await new Promise(r => setTimeout(r, 5000));
        try {
          const res = await getEpisodeGridStatus(projectId, episodeId);
          const gridStatus = res.data?.gridStatus;
          if (gridStatus === 'generated' || gridStatus === 'approved') {
            await loadEpisodes();
            return;
          }
          if (gridStatus === 'failed') {
            alert('九宫格重新生成失败');
            await loadEpisodes();
            return;
          }
        } catch {
          // 继续轮询
        }
      }
    };
    poll();
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '重新生成失败');
  }
}, [projectId, loadEpisodes]);

/**
 * 刷新整集九宫格状态（轮询用）
 */
const refreshEpisodeGridStatus = useCallback(async (episodeId: number) => {
  if (!projectId) return;
  try {
    const res = await getEpisodeGridStatus(projectId, episodeId);
    if ((res.code !== 0 && res.code !== 200) || !res.data) return;
    const data = res.data;

    setChapters(prev =>
      prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                gridStatus: data.gridStatus,
                gridImages: data.gridImages || [],
                splitShots: data.splitShots || [],
                gridRejectionFeedback: data.gridRejectionFeedback,
              }
            : ep
        ),
      }))
    );
  } catch {
    // 静默失败
  }
}, [projectId]);
```

- [ ] **Step 4: 修改 `renderEpisodeCard` — 传递新的 props**

在 `renderEpisodeCard` 中，给 `EpisodeCard` 传递新的 props:

```typescript
return (
  <EpisodeCard
    key={episode.episodeId}
    chapterIndex={chapter.chapterIndex}
    episode={episode}
    isExpanded={isExpanded}
    onToggle={() => toggleEpisode(episode.episodeId)}
    expandedSegmentKey={expansion.expandedSegmentKey}
    onSegmentToggle={toggleSegment}
    onSegmentApproveGrid={(epId, segIdx) => {
      const panelId = episode.segments[segIdx]?.panelData?.panelId;
      if (panelId) handleApproveGrid(epId, panelId);
    }}
    onSegmentRejectGrid={(epId, segIdx, reason) => {
      const panelId = episode.segments[segIdx]?.panelData?.panelId;
      if (panelId) handleRejectGrid(epId, panelId, reason);
    }}
    onSegmentRegenerateGrid={(epId, segIdx) => {
      const panelId = episode.segments[segIdx]?.panelData?.panelId;
      if (panelId) handleRegenerateGrid(epId, panelId);
    }}
    onSegmentGenerateVideo={(epId, segIdx) => {
      const panelId = episode.segments[segIdx]?.panelData?.panelId;
      if (panelId) handleGenerateVideo(epId, panelId);
    }}
    onGeneratePanels={handleGeneratePanels}
    isGeneratingPanels={generatingEpisodeId === episode.episodeId}
    onRefreshPanels={handleRefreshPanels}
    generatingGridPanelId={generatingGridPanelId}
    generatingVideoPanelId={generatingVideoPanelId}
    onSegmentReviseSingle={(epId, segIdx, feedback) => {
      const panelId = episode.segments[segIdx]?.panelData?.panelId;
      if (panelId) handleReviseSinglePanel(epId, panelId, feedback);
    }}
    isRevisingSinglePanelId={revisingPanelId}
    onSegmentUpdatePanel={(epId, segIdx, fields) => {
      const panelId = episode.segments[segIdx]?.panelData?.panelId;
      if (panelId) handleUpdatePanel(epId, panelId, fields);
    }}
    isUpdatingSinglePanelId={updatingPanelId}
    onRevisePanel={handleRevisePanel}
    isRevisingPanel={revisingEpisodeId === episode.episodeId}
    onApproveAllGrids={() => handleApproveAllGrids(episode.episodeId)}
    // === 新流程 props ===
    onApproveEpisodeGrid={() => handleApproveEpisodeGrid(episode.episodeId)}
    onRejectEpisodeGrid={(reason) => handleRejectEpisodeGrid(episode.episodeId, reason)}
    onRegenerateEpisodeGrid={() => handleRegenerateEpisodeGrid(episode.episodeId)}
    onRefreshEpisodeGrid={() => refreshEpisodeGridStatus(episode.episodeId)}
  />
);
```

- [ ] **Step 5: 修改完成统计逻辑 — 适配新流程**

在统计计算处（lines 265-295），修改为兼容新旧流程:

```typescript
// 计算完成统计（兼容新旧流程）
const totalSegments = chapters.reduce(
  (sum, ch) => sum + ch.episodes.reduce((s, ep) => s + ep.segments.length, 0),
  0
);
const completedSegments = chapters.reduce(
  (sum, ch) =>
    sum +
    ch.episodes.reduce(
      (s, ep) => s + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length,
      0
    ),
  0
);

// 新流程：统计 episode 级九宫格
const totalEpisodes = chapters.reduce((s, ch) => s + ch.episodes.length, 0);
const newFlowEpisodes = chapters.flatMap(ch => ch.episodes).filter(ep => ep.isNewFlow);
const approvedGridEpisodes = newFlowEpisodes.filter(ep => ep.gridStatus === 'approved').length;
const pendingGridEpisodes = newFlowEpisodes.filter(ep => ep.gridStatus === 'generated').length;

// 旧流程统计（如果还有旧流程的 episode）
const oldFlowApprovedSegments = chapters.reduce(
  (sum, ch) =>
    sum +
    ch.episodes.reduce(
      (s, ep) => s + ep.segments.filter(seg => seg.pipelineStep === 'grid_approved').length,
      0
    ),
  0
);
const oldFlowPendingSegments = chapters.reduce(
  (sum, ch) =>
    sum +
    ch.episodes.reduce(
      (s, ep) => s + ep.segments.filter(seg => seg.pipelineStep === 'grid_review').length,
      0
    ),
  0
);

// 合并统计
const approvedSegments = approvedGridEpisodes + oldFlowApprovedSegments;
const pendingReviewSegments = pendingGridEpisodes + oldFlowPendingSegments;
```

- [ ] **Step 6: 修改 BatchReviewBar 调用 — 适配新流程**

在 BatchReviewBar 渲染处:

```typescript
{totalSegments > 0 && (
  <BatchReviewBar
    totalPanels={totalSegments}
    approvedCount={approvedSegments}
    pendingReviewCount={pendingReviewSegments}
    onApproveAll={() => {
      // 新流程：审核所有 pending/generated 的 episode 九宫格
      const newFlowPending = newFlowEpisodes.filter(
        ep => ep.gridStatus === 'generated' || ep.gridStatus === 'rejected'
      );
      if (newFlowPending.length > 0) {
        newFlowPending.forEach(ep => handleApproveEpisodeGrid(ep.episodeId));
        return;
      }
      // 旧流程：审核所有 panel 九宫格
      const allEpisodeIds = [...new Set(chapters.flatMap(ch => ch.episodes.map(ep => ep.episodeId)))];
      allEpisodeIds.forEach(eid => handleApproveAllGrids(eid));
    }}
  />
)}
```

- [ ] **Step 7: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -5`

注意：编译可能因为 EpisodeCard 的 props 类型不匹配而失败（Task 9 会解决）。

- [ ] **Step 8: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
git commit -m "feat: Step5page 支持整集九宫格数据流和操作"
```

---

## Task 9: EpisodeCard.tsx — 双模式显示

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less`

- [ ] **Step 1: 扩展 EpisodeCardProps**

```typescript
interface EpisodeCardProps {
  chapterIndex: number;
  episode: EpisodeState;
  isExpanded: boolean;
  onToggle: () => void;
  expandedSegmentKey: string | null;
  onSegmentToggle: (key: string | null) => void;
  onSegmentApproveGrid: (episodeId: number, segmentIndex: number) => void;
  onSegmentRejectGrid: (episodeId: number, segmentIndex: number, reason: string) => void;
  onSegmentRegenerateGrid: (episodeId: number, segmentIndex: number) => void;
  onSegmentGenerateVideo: (episodeId: number, segmentIndex: number) => void;
  generatingGridPanelId?: string | null;
  generatingVideoPanelId?: string | null;
  onSegmentReviseSingle?: (episodeId: number, segmentIndex: number, feedback: string) => void;
  isRevisingSinglePanelId?: string | null;
  onSegmentUpdatePanel?: (episodeId: number, segmentIndex: number, fields: Record<string, any>) => void;
  isUpdatingSinglePanelId?: string | null;
  onGeneratePanels?: (episodeId: number) => void;
  isGeneratingPanels?: boolean;
  onRefreshPanels?: (episodeId: number) => void;
  onRevisePanel?: (episodeId: number) => void;
  isRevisingPanel?: boolean;
  onApproveAllGrids?: () => void;
  // === 新流程 props ===
  onApproveEpisodeGrid?: () => void;
  onRejectEpisodeGrid?: (reason: string) => void;
  onRegenerateEpisodeGrid?: () => void;
  onRefreshEpisodeGrid?: () => void;
}
```

- [ ] **Step 2: 在组件中添加新流程判断和新 props 解构**

在组件函数参数中解构新的 props:

```typescript
const EpisodeCard = ({
  // ... 现有 props ...
  onApproveEpisodeGrid,
  onRejectEpisodeGrid,
  onRegenerateEpisodeGrid,
  onRefreshEpisodeGrid,
}: EpisodeCardProps) => {
```

在组件内部，添加判断:

```typescript
// 新流程 vs 旧流程
const isNewFlow = episode.isNewFlow;
const isGridApproved = isNewFlow && episode.gridStatus === 'approved';
const isGridPending = isNewFlow && (episode.gridStatus === 'pending' || episode.gridStatus === 'generating');
const isGridReview = isNewFlow && episode.gridStatus === 'generated';
const isGridRejected = isNewFlow && episode.gridStatus === 'rejected';
```

- [ ] **Step 3: 修改 `getEpisodeStatus` 函数 — 适配新流程**

```typescript
const getEpisodeStatus = (episode: EpisodeState): 'completed' | 'in-progress' | 'not-started' => {
  // 新流程：基于 gridStatus 判断
  if (episode.isNewFlow) {
    if (episode.gridStatus === 'approved' && episode.segments.length > 0) {
      const allCompleted = episode.segments.every(s => s.pipelineStep === 'video_completed');
      if (allCompleted) return 'completed';
      const hasInProgress = episode.segments.some(s =>
        s.pipelineStep === 'video_generating' || s.pipelineStep === 'grid_approved'
      );
      return hasInProgress ? 'in-progress' : 'not-started';
    }
    if (episode.gridStatus === 'generating' || episode.gridStatus === 'generated') return 'in-progress';
    return 'not-started';
  }
  // 旧流程
  if (episode.segments.length === 0) return 'not-started';
  const allCompleted = episode.segments.every(s => s.pipelineStep === 'video_completed');
  if (allCompleted) return 'completed';
  const hasInProgress = episode.segments.some(s =>
    s.pipelineStep === 'grid_generating' ||
    s.pipelineStep === 'grid_review' ||
    s.pipelineStep === 'grid_approved' ||
    s.pipelineStep === 'video_generating'
  );
  if (hasInProgress) return 'in-progress';
  return 'not-started';
};
```

- [ ] **Step 4: 修改卡片头部 — 显示九宫格状态标签**

在头部标题区域，添加九宫格状态标签:

```typescript
<span className={styles.segmentCount}>
  {isNewFlow ? (
    <>
      {episode.gridStatus === 'approved' ? '九宫格已通过' :
       episode.gridStatus === 'generating' ? '九宫格生成中...' :
       episode.gridStatus === 'generated' ? '待审核九宫格' :
       episode.gridStatus === 'rejected' ? '九宫格已退回' :
       episode.gridStatus === 'failed' ? '九宫格生成失败' :
       '未生成九宫格'}
      {episode.gridStatus === 'approved' && episode.segments.length > 0 &&
        ` · ${episode.segments.length} 个片段`}
    </>
  ) : (
    `${episode.segments.length} 个片段`
  )}
</span>
```

- [ ] **Step 5: 修改操作按钮 — 新流程显示审核/重新生成按钮**

替换现有的 `onApproveAllGrids` 按钮区域，改为根据流程类型显示不同按钮:

```typescript
{/* 新流程：九宫格审核按钮 */}
{isNewFlow && isExpanded && isGridReview && (
  <>
    <button
      className={styles.generatePanelsBtn}
      onClick={(e) => { e.stopPropagation(); onApproveEpisodeGrid?.(); }}
    >
      通过九宫格
    </button>
    <button
      className={styles.generatePanelsBtn}
      onClick={(e) => {
        e.stopPropagation();
        const reason = prompt('请输入拒绝原因：');
        if (reason?.trim()) onRejectEpisodeGrid?.(reason.trim());
      }}
    >
      退回
    </button>
    <button
      className={styles.generatePanelsBtn}
      onClick={(e) => { e.stopPropagation(); onRegenerateEpisodeGrid?.(); }}
    >
      重新生成
    </button>
  </>
)}

{/* 新流程：九宫格被拒绝或生成失败时显示重新生成 */}
{isNewFlow && isExpanded && (isGridRejected || episode.gridStatus === 'failed') && (
  <button
    className={styles.generatePanelsBtn}
    onClick={(e) => { e.stopPropagation(); onRegenerateEpisodeGrid?.(); }}
  >
    重新生成九宫格
  </button>
)}

{/* 旧流程：一键审核通过按钮 */}
{!isNewFlow && onApproveAllGrids && isExpanded && (
  <button
    className={styles.generatePanelsBtn}
    onClick={(e) => { e.stopPropagation(); onApproveAllGrids(); }}
    disabled={!episode.segments.some(s => s.pipelineStep === 'grid_review')}
    title="一键审核通过所有九宫格"
  >
    一键通过
  </button>
)}
```

- [ ] **Step 6: 修改展开内容 — 新流程显示九宫格预览或 Panel 列表**

替换现有的展开内容区域:

```typescript
{isExpanded && (
  <div className={styles.cardContent}>
    {/* 新流程：九宫格审核阶段 */}
    {isNewFlow && !isGridApproved && (
      <div className={styles.episodeGridReview}>
        {episode.gridImages && episode.gridImages.length > 0 ? (
          <div className={styles.gridImageList}>
            {episode.gridImages.map((url, idx) => (
              <div key={idx} className={styles.gridImagePage}>
                <div className={styles.gridPageLabel}>第 {idx + 1} 页</div>
                <img src={url} alt={`九宫格第${idx + 1}页`} className={styles.gridImage} />
              </div>
            ))}
          </div>
        ) : (
          <div className={styles.emptyState}>
            <p>{isGridPending ? '九宫格正在生成中，请稍候...' : '暂无九宫格'}</p>
            {isGridPending && onRefreshEpisodeGrid && (
              <button className={styles.refreshPanelsBtn} onClick={onRefreshEpisodeGrid}>
                刷新
              </button>
            )}
          </div>
        )}

        {/* 分割后的分镜列表（可选展示） */}
        {episode.splitShots && episode.splitShots.length > 0 && (
          <div className={styles.splitShotList}>
            <h5 className={styles.splitShotTitle}>分镜列表（共 {episode.splitShots.length} 个）</h5>
            <div className={styles.splitShotGrid}>
              {episode.splitShots.map((shot, idx) => (
                <div key={idx} className={styles.splitShotItem}>
                  <span className={styles.splitShotNumber}>#{shot.shotNumber}</span>
                  <span className={styles.splitShotDesc}>{shot.visualDescription}</span>
                  <span className={styles.splitShotDuration}>{shot.duration}s</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {episode.gridRejectionFeedback && (
          <div className={styles.rejectionFeedback}>
            退回原因：{episode.gridRejectionFeedback}
          </div>
        )}
      </div>
    )}

    {/* 新流程已审核通过 → 或旧流程 → 显示 Panel 列表 */}
    {(isNewFlow && isGridApproved) || !isNewFlow ? (
      episode.segments.length === 0 ? (
        <div className={styles.emptyState}>
          <p>暂无片段</p>
        </div>
      ) : (
        <div className={styles.segmentList}>
          {segmentCards}
        </div>
      )
    ) : null}
  </div>
)}
```

- [ ] **Step 7: 添加 Less 样式**

在 `EpisodeCard.module.less` 中添加新样式:

```less
/* 整集九宫格审核视图 */
.episodeGridReview {
  padding: 16px 0;
}

.gridImageList {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.gridImagePage {
  flex: 1;
  min-width: 300px;
  max-width: 600px;
}

.gridPageLabel {
  font-size: 12px;
  color: #999;
  margin-bottom: 8px;
  text-align: center;
}

.gridImage {
  width: 100%;
  border-radius: 8px;
  border: 1px solid #2a2a2e;
}

/* 分割分镜列表 */
.splitShotList {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #2a2a2e;
}

.splitShotTitle {
  font-size: 13px;
  color: #999;
  margin-bottom: 12px;
}

.splitShotGrid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 8px;
}

.splitShotItem {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #1a1a1c;
  font-size: 13px;
}

.splitShotNumber {
  color: #6c6cff;
  font-weight: 600;
  min-width: 30px;
}

.splitShotDesc {
  color: #ccc;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.splitShotDuration {
  color: #888;
  font-size: 12px;
}

/* 退回原因 */
.rejectionFeedback {
  margin-top: 16px;
  padding: 12px;
  border-radius: 8px;
  background: rgba(255, 77, 79, 0.1);
  border: 1px solid rgba(255, 77, 79, 0.3);
  color: #ff8a8d;
  font-size: 13px;
}
```

- [ ] **Step 8: 修改片段完成指示器 — 新流程显示九宫格状态点**

在折叠状态的 `segmentIndicator` 区域，新流程改为显示九宫格状态而非片段状态:

```typescript
{!isExpanded && !isNewFlow && episode.segments.length > 0 && (
  <div className={styles.segmentIndicator}>
    {episode.segments.map((segment) => (
      <div
        key={segment.segmentIndex}
        className={styles.segmentDot}
        style={{ backgroundColor: getSegmentStatusColor(segment.pipelineStep) }}
        title={`片段 ${segment.segmentIndex + 1}: ${segment.pipelineStep}`}
      />
    ))}
  </div>
)}
{!isExpanded && isNewFlow && (
  <div className={styles.segmentIndicator}>
    <div
      className={styles.segmentDot}
      style={{
        backgroundColor:
          episode.gridStatus === 'approved' ? '#4ade80' :
          episode.gridStatus === 'generated' ? '#fbbf24' :
          episode.gridStatus === 'generating' ? '#fbbf24' :
          '#474747',
      }}
      title={`九宫格: ${episode.gridStatus || 'pending'}`}
    />
    {episode.gridStatus === 'approved' && episode.segments.map((segment) => (
      <div
        key={segment.segmentIndex}
        className={styles.segmentDot}
        style={{ backgroundColor: getSegmentStatusColor(segment.pipelineStep) }}
        title={`片段 ${segment.segmentIndex + 1}: ${segment.pipelineStep}`}
      />
    ))}
  </div>
)}
```

- [ ] **Step 9: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -5`
Expected: 无错误

- [ ] **Step 10: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less
git commit -m "feat: EpisodeCard 双模式显示（九宫格审核 vs Panel 列表）"
```

---

## Task 10: BatchReviewBar — 适配 episode 级统计

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/BatchReviewBar.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/BatchReviewBar.module.less`

- [ ] **Step 1: 修改标签文案适配新流程**

BatchReviewBar 的 props 已经足够通用（totalPanels / approvedCount / pendingReviewCount），不需要修改接口。Step5page.tsx 中传入的统计数据已在新旧流程间合并（Task 8 Step 5）。

但可以微调文案以更好表达含义。修改 `BatchReviewBar.tsx`:

```typescript
<div className={styles.statsText}>
  <span className={styles.approved}>已审核 {approvedCount}</span>
  <span className={styles.separator}>/</span>
  <span className={styles.total}>共 {totalPanels} 个</span>
  {pendingReviewCount > 0 && (
    <>
      <span className={styles.separator}>|</span>
      <span className={styles.pending}>{pendingReviewCount} 个待审核</span>
    </>
  )}
</div>
```

无需修改——当前文案已兼容新流程。

- [ ] **Step 2: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -5`
Expected: 无错误

- [ ] **Step 3: Commit (如无改动可跳过)**

如果 BatchReviewBar 确实无需改动，跳过此 commit。

---

## Task 11: 九宫格生成轮询恢复

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

- [ ] **Step 1: 修改页面加载恢复逻辑 — 检测 episode 级九宫格生成中状态**

在现有的恢复轮询 `useEffect`（lines 176-222 附近）中，增加对 episode 级九宫格 `generating` 状态的检测:

```typescript
// 检测新流程中正在生成九宫格的 episode
const generatingGridEp = allEpisodes.find(ep =>
  ep.isNewFlow && ep.gridStatus === 'generating'
);
if (generatingGridEp) {
  const epId = generatingGridEp.episodeId;
  console.info('检测到正在生成中的 episode 九宫格, 恢复轮询: epId=', epId);
  refreshEpisodeGridStatus(epId);
  const poll = async () => {
    while (true) {
      await new Promise(r => setTimeout(r, 5000));
      await refreshEpisodeGridStatus(epId);
      const currentEp = chaptersRef.current
        ?.flatMap(ch => ch.episodes)
        .find(e => e.episodeId === epId);
      if (currentEp && (currentEp.gridStatus === 'generated' || currentEp.gridStatus === 'approved' || currentEp.gridStatus === 'failed')) {
        return;
      }
    }
  };
  poll();
}
```

注意：需要添加一个 `chaptersRef` 来在异步闭包中获取最新状态:

```typescript
const chaptersRef = useRef<ChapterState[]>([]);
// 在 setChapters 调用处同步更新 ref
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -5`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
git commit -m "feat: 九宫格生成轮询恢复（页面刷新后自动恢复）"
```

---

## Task 12: 全量编译验证

- [ ] **Step 1: 后端全量编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 2: 前端全量编译**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -10`
Expected: 无错误

- [ ] **Step 3: 前端构建**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -10`
Expected: 构建成功

---

## 向后兼容清单

| 场景 | 行为 |
|------|------|
| 新项目（首次生成分镜） | 使用新流程：episodeInfo 含 gridStatus |
| 旧项目（已有 Panel 数据） | 旧流程：episodeInfo 无 gridStatus，前端走 Panel 级审核 |
| 旧 Panel API（approveGrid 等） | 保留，不受影响 |
| 旧 `generateGridsForPanel` 方法 | 保留，不受影响 |
| PipelineService 状态推进 | 自动检测流程类型 |
