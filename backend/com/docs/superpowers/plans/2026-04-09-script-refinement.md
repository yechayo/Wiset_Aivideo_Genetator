# 4A 脚本精修 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 4A 页面实现分镜级别的编辑、锁定和精修重新生成功能。

**Architecture:** 后端在 EpisodeController 新增 2 个 shot 级 CRUD 端点，改造现有 `/script` 端点的 service 层支持精修模式（跳过 Stage 1，保留 locked shots）。前端 ScriptEpisodeCard 改为可编辑卡片，新增锁定 toggle 和原位编辑。改造 DeepSeekTextService 的 prompt 构建以支持 locked 标注。

**Tech Stack:** Java Spring Boot (后端), React + TypeScript + Less (前端), DeepSeek API (AI)

---

### Task 1: 后端 — 新增 updateShot 和 toggleShotLock 端点

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java` (line ~117, after `getEpisodeScript`)
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` (append two new methods)

- [ ] **Step 1: 在 EpisodeController 新增两个端点**

在 `// ================= 分镜文本审核 =================` 注释之前（约 line 119），新增：

```java
// ================= 单分镜编辑 =================

@PutMapping("/{episodeId}/shots/{shotIndex}")
@Operation(summary = "更新单个分镜文本")
public Result<Void> updateShot(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable int shotIndex,
        @RequestBody Map<String, Object> updates) {
    panelProductionService.updateShot(projectId, episodeId, shotIndex, updates);
    return Result.ok();
}

@PutMapping("/{episodeId}/shots/{shotIndex}/lock")
@Operation(summary = "锁定/解锁分镜")
public Result<Void> toggleShotLock(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable int shotIndex,
        @RequestBody Map<String, Boolean> body) {
    panelProductionService.toggleShotLock(projectId, episodeId, shotIndex, body.get("locked"));
    return Result.ok();
}
```

- [ ] **Step 2: 在 PanelProductionService 新增 updateShot 和 toggleShotLock 方法**

在类末尾（约 line 1128 之后）新增：

```java
private static final Set<String> SHOT_EDITABLE_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "visualDescription", "narration", "dialogue", "speaker",
    "narrationTone", "dialogueTone", "shotSize", "cameraAngle",
    "cameraMovement", "scene", "visualEffects", "audioEffects", "transitionHint"
));

public void updateShot(String projectId, Long episodeId, int shotIndex, Map<String, Object> updates) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo();
    if (info == null) throw new BusinessException("剧集数据为空");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
    if (shots == null || shotIndex < 0 || shotIndex >= shots.size()) {
        throw new BusinessException("分镜索引无效: " + shotIndex);
    }

    Map<String, Object> shot = shots.get(shotIndex);
    for (Map.Entry<String, Object> entry : updates.entrySet()) {
        if (SHOT_EDITABLE_FIELDS.contains(entry.getKey())) {
            shot.put(entry.getKey(), entry.getValue());
        }
    }

    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);
    log.info("[Shot] 分镜已更新: episodeId={}, shotIndex={}", episodeId, shotIndex);
}

public void toggleShotLock(String projectId, Long episodeId, int shotIndex, Boolean locked) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo();
    if (info == null) throw new BusinessException("剧集数据为空");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
    if (shots == null || shotIndex < 0 || shotIndex >= shots.size()) {
        throw new BusinessException("分镜索引无效: " + shotIndex);
    }

    shots.get(shotIndex).put("locked", locked != null && locked);
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);
    log.info("[Shot] 分镜锁定状态: episodeId={}, shotIndex={}, locked={}", episodeId, shotIndex, locked);
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: 新增单分镜编辑和锁定 API 端点"
```

---

### Task 2: 后端 — 改造 generateSingleEpisodeScript 支持精修模式

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` (lines 710-823, `generateSingleEpisodeScript` method)

- [ ] **Step 1: 在 generateSingleEpisodeScript 方法中添加精修判断和 Stage 1 跳过**

在 line 731 `boolean comicMode = ProjectProductionMode.isComicCommentary(project);` 之后，新增精修检测逻辑。

在 line 767 `// 生成目标章节的剧本` 之前，插入判断：

```java
// 判断是否为精修（episode 已有 shots）
@SuppressWarnings("unchecked")
List<Map<String, Object>> existingShots = episode.getEpisodeInfo() != null
    ? (List<Map<String, Object>>) episode.getEpisodeInfo().get("shots")
    : null;
boolean isRefinement = existingShots != null && !existingShots.isEmpty();

// 提取锁定分镜
List<Map<String, Object>> lockedShots = new ArrayList<>();
if (isRefinement) {
    for (Map<String, Object> shot : existingShots) {
        if (Boolean.TRUE.equals(shot.get("locked"))) {
            lockedShots.add(shot);
        }
    }
}
```

替换 line 767-792 的 Stage 1 逻辑为：

```java
Map<String, Object> targetScript;
if (isRefinement) {
    // 精修模式：跳过 Stage 1，使用现有剧本
    targetScript = new HashMap<>(episode.getEpisodeInfo());
    log.info("[Pipeline-Text] 精修模式: 跳过 Stage 1, episodeId={}, lockedShots={}", episodeId, lockedShots.size());
    eventPublisher.publishEpisodeScriptDone(projectId, targetEpisodeNum,
        (String) targetScript.getOrDefault("title", ""), totalEpisodes, 1, "stage1");
} else {
    // 首次生成：执行 Stage 1
    String previousSummary = buildPreviousEpisodesSummaryFor(projectId, episodeCountBeforeChapter);
    log.info("[Pipeline-Text] 单集生成: projectId={}, chapter={}, episodeNum={}, targetEpisodeNum={}",
            projectId, targetChapter.title, targetEpisodeNum, targetEpisodeNum);

    List<Map<String, Object>> chapterScripts = deepSeekTextService.generateEpisodeScript(
        targetChapter.text, charactersDesc, targetDuration, visualStyle,
        targetChapter.episodeCount, comicMode, previousSummary);

    int episodeNumInChapter = targetEpisodeNum - episodeCountBeforeChapter;
    targetScript = (episodeNumInChapter >= 1 && episodeNumInChapter <= chapterScripts.size())
        ? chapterScripts.get(episodeNumInChapter - 1) : chapterScripts.get(0);

    eventPublisher.publishEpisodeScriptDone(projectId, targetEpisodeNum,
        (String) targetScript.getOrDefault("title", ""), totalEpisodes, 1, "stage1");
}
```

- [ ] **Step 2: 修改 Stage 2 调用，传入 lockedShots**

将 line 807-809：
```java
generateStoryboardForEpisode(projectId, projectInfo, targetScript, targetEpisodeNum,
        comicMode, rejectionReasons, visualStyle, targetDuration);
```

改为：
```java
generateStoryboardForEpisode(projectId, projectInfo, targetScript, targetEpisodeNum,
        comicMode, rejectionReasons, visualStyle, targetDuration, lockedShots, isRefinement);
```

- [ ] **Step 3: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS（此处会报错因为 generateStoryboardForEpisode 签名还未改，这是预期的）

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: generateSingleEpisodeScript 支持精修模式跳过 Stage 1"
```

---

### Task 3: 后端 — 改造 generateStoryboardForEpisode 支持锁定分镜合并

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` (lines 1044-1128, `generateStoryboardForEpisode` method)

- [ ] **Step 1: 修改方法签名，新增 lockedShots 和 isRefinement 参数**

```java
private void generateStoryboardForEpisode(String projectId, Map<String, Object> projectInfo,
                                           Map<String, Object> script, int episodeNum,
                                           boolean comicMode, Map<Integer, String> rejectionReasons,
                                           String visualStyle, int targetDuration,
                                           List<Map<String, Object>> lockedShots,
                                           boolean isRefinement) {
```

- [ ] **Step 2: 在生成 shots 之后、注入角色 ID 之前，插入锁定分镜合并逻辑**

在 line 1088（`log.info("[Pipeline-Text] 生成 {} 个分镜"`）之后、line 1090（`injectCharacterIds`）之前，插入：

```java
// 精修模式：合并锁定分镜与新生成分镜
if (isRefinement && lockedShots != null && !lockedShots.isEmpty()) {
    log.info("[Pipeline-Text] 精修合并: lockedShots={}, newShots={}", lockedShots.size(), shots.size());

    // 以 shotNumber 为 key 合并，locked 优先
    Map<Integer, Map<String, Object>> merged = new LinkedHashMap<>();
    for (Map<String, Object> shot : lockedShots) {
        int num = ((Number) shot.get("shotNumber")).intValue();
        shot.put("locked", true); // 确保锁定状态保留
        merged.put(num, shot);
    }
    for (Map<String, Object> shot : shots) {
        int num = ((Number) shot.get("shotNumber")).intValue();
        if (!merged.containsKey(num)) {
            merged.put(num, shot);
        }
    }

    // 按 shotNumber 排序
    List<Integer> sortedKeys = new ArrayList<>(merged.keySet());
    java.util.Collections.sort(sortedKeys);
    shots = new ArrayList<>();
    for (Integer key : sortedKeys) {
        shots.add(merged.get(key));
    }

    log.info("[Pipeline-Text] 合并后总 shots={}", shots.size());
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: generateStoryboardForEpisode 支持锁定分镜合并"
```

---

### Task 4: 后端 — 改造 DeepSeekTextService 支持精修 prompt

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java`
  - `generateStoryboard` 方法 (line 418)
  - `generatePanelAwareStoryboard` 方法 (line 539)

- [ ] **Step 1: 为 generateStoryboard 新增重载方法，支持 lockedShots 参数**

在现有 `generateStoryboard` 6 参数版本（line 418）之后，新增 7 参数重载：

```java
/**
 * @param lockedShots 精修模式下的锁定分镜，非空时进入精修模式
 */
public List<Map<String, Object>> generateStoryboard(
        String episodeContent, String characters, int totalDuration, String visualStyle,
        boolean comicCommentary, String revisionNote,
        List<Map<String, Object>> lockedShots) {
    int recommendedShots = Math.max(1, totalDuration / 4);
    int minShots = Math.max(1, (int) Math.ceil((double) totalDuration / 6));
    int maxShots = Math.max(minShots, totalDuration / 3);

    String systemPrompt = buildStoryboardSystemPrompt(
            totalDuration, minShots, maxShots, recommendedShots, comicCommentary);

    StringBuilder promptBuilder = new StringBuilder();
    promptBuilder.append("剧本内容：\n").append(episodeContent).append("\n\n")
        .append("角色：").append(characters).append("\n")
        .append("视觉风格：").append(visualStyle).append("\n")
        .append("目标总时长：").append(totalDuration).append("秒\n\n");

    if (revisionNote != null && !revisionNote.trim().isEmpty()) {
        promptBuilder.append("**修改建议**：").append(revisionNote.trim()).append("\n\n");
    }

    // 精修模式：标注锁定分镜
    if (lockedShots != null && !lockedShots.isEmpty()) {
        promptBuilder.append("【精修模式】以下是当前全部分镜，标记 [LOCKED] 的分镜必须保持不变，请仅为其余分镜重新生成内容。\n");
        promptBuilder.append("保持与锁定分镜的叙事连贯性。\n\n");
        for (Map<String, Object> locked : lockedShots) {
            promptBuilder.append("[LOCKED] 第").append(locked.get("shotNumber")).append("镜\n");
        }
        promptBuilder.append("\n请为未锁定的分镜重新生成内容，输出完整的 JSON 数组（包含所有分镜，locked 的保持原样）。\n\n");
    }

    promptBuilder.append("请生成详细的分镜脚本 JSON 数组。");

    String response = generateStream(systemPrompt, promptBuilder.toString());
    List<Map<String, Object>> shots = parseJsonArray(response);

    if (shots == null || shots.isEmpty()) {
        throw new BusinessException("分镜生成结果为空，请重试");
    }

    if (comicCommentary) {
        normalizeComicNarrationFields(shots);
    }

    // 钳制时长到 2-4 秒
    int currentTime = 0;
    for (Map<String, Object> shot : shots) {
        int duration = ((Number) shot.get("duration")).intValue();
        duration = Math.max(2, Math.min(4, duration));
        shot.put("duration", duration);
        shot.put("startTime", currentTime);
        currentTime += duration;
        shot.put("endTime", currentTime);
    }

    while (currentTime > totalDuration && !shots.isEmpty()) {
        Map<String, Object> removed = shots.remove(shots.size() - 1);
        currentTime -= ((Number) removed.get("duration")).intValue();
    }
    if (!shots.isEmpty()) {
        shots.get(shots.size() - 1).put("endTime", currentTime);
    }

    return shots;
}
```

- [ ] **Step 2: 为 generatePanelAwareStoryboard 新增重载，支持 lockedShots**

在现有方法之后新增重载：

```java
public List<List<Map<String, Object>>> generatePanelAwareStoryboard(
        String episodeContent, String characters, int totalDuration, String visualStyle,
        String revisionNote, String narrationPerspective,
        List<Map<String, Object>> lockedShots) {

    String systemPrompt = buildPanelAwareSystemPrompt(totalDuration, true, narrationPerspective);

    StringBuilder promptBuilder = new StringBuilder();
    promptBuilder.append("剧本内容：\n").append(episodeContent).append("\n\n")
        .append("角色：").append(characters).append("\n")
        .append("视觉风格：").append(visualStyle).append("\n")
        .append("目标总时长：").append(totalDuration).append("秒\n\n");

    if (revisionNote != null && !revisionNote.trim().isEmpty()) {
        promptBuilder.append("**修改建议**：").append(revisionNote.trim()).append("\n\n");
    }

    // 精修模式标注
    if (lockedShots != null && !lockedShots.isEmpty()) {
        promptBuilder.append("【精修模式】以下是当前全部分镜，标记 [LOCKED] 的分镜必须保持不变，请仅为其余分镜重新生成内容。\n");
        promptBuilder.append("保持与锁定分镜的叙事连贯性。\n\n");
        for (Map<String, Object> locked : lockedShots) {
            promptBuilder.append("[LOCKED] 第").append(locked.get("shotNumber")).append("镜\n");
        }
        promptBuilder.append("\n请为未锁定的分镜重新生成内容，输出完整的 Panel-Aware JSON（包含所有分镜，locked 的保持原样）。\n\n");
    }

    if ("third_person".equals(narrationPerspective)) {
        promptBuilder.append("**重要：旁白必须使用第三人称叙述，用角色名字或他/她指代，严禁使用「我」**。\n\n");
    } else if ("first_person".equals(narrationPerspective)) {
        promptBuilder.append("**重要：旁白必须使用第一人称「我」叙述，以主角口吻讲述**。\n\n");
    }
    promptBuilder.append("请生成 Panel-Aware 分镜脚本 JSON。");

    // 以下复用现有后处理逻辑（parsePanelAwareJson + normalize + 裁剪）
    String response = generateStream(systemPrompt, promptBuilder.toString());
    List<List<Map<String, Object>>> panelShots = parsePanelAwareJson(response);

    if (panelShots == null || panelShots.isEmpty()) {
        throw new BusinessException("Panel-Aware 分镜生成结果为空，请重试");
    }

    int globalShotNumber = 0;
    for (List<Map<String, Object>> panelShotList : panelShots) {
        normalizeComicNarrationFields(panelShotList);

        int panelStartTime = 0;
        for (List<Map<String, Object>> prevPanel : panelShots) {
            if (prevPanel == panelShotList) break;
            for (Map<String, Object> ps : prevPanel) {
                panelStartTime += toSafeInt(ps.get("duration"), 3);
            }
        }

        int currentTime = panelStartTime;
        for (Map<String, Object> shot : panelShotList) {
            int duration = toSafeInt(shot.get("duration"), 3);
            duration = Math.max(2, Math.min(4, duration));
            shot.put("duration", duration);
            shot.put("startTime", currentTime);
            currentTime += duration;
            shot.put("endTime", currentTime);
            globalShotNumber++;
            shot.put("globalShotNumber", globalShotNumber);
        }

        int panelDuration = currentTime - panelStartTime;
        while (panelDuration > 10 && panelShotList.size() > 2) {
            Map<String, Object> removed = panelShotList.remove(panelShotList.size() - 1);
            int removedDur = toSafeInt(removed.get("duration"), 3);
            panelDuration -= removedDur;
            globalShotNumber--;
            currentTime -= removedDur;
        }
        if (!panelShotList.isEmpty()) {
            panelShotList.get(panelShotList.size() - 1).put("endTime", currentTime);
        }
    }

    int totalActual = 0;
    for (List<Map<String, Object>> ps : panelShots) {
        for (Map<String, Object> s : ps) {
            totalActual += toSafeInt(s.get("duration"), 3);
        }
    }
    while (totalActual > totalDuration && panelShots.size() > 1) {
        List<Map<String, Object>> removed = panelShots.remove(panelShots.size() - 1);
        for (Map<String, Object> s : removed) {
            totalActual -= toSafeInt(s.get("duration"), 3);
        }
    }

    for (List<Map<String, Object>> panelShotList : panelShots) {
        String prevNarration = "";
        for (Map<String, Object> shot : panelShotList) {
            String dialogue = str(shot.get("dialogue"));
            String narration = str(shot.get("narration"));
            if (!"无".equals(dialogue) && !dialogue.isEmpty()) {
                if (!"无".equals(narration) && !narration.isEmpty()) {
                    log.warn("Shot {} 违反互斥原则，清除 narration 保留 dialogue", shot.get("shotNumber"));
                    shot.put("narration", "无");
                }
            }
            narration = str(shot.get("narration"));
            if (!"无".equals(narration) && !narration.isEmpty() && narration.equals(prevNarration)) {
                log.warn("Shot {} narration 与上一镜完全重复，已清除", shot.get("shotNumber"));
                shot.put("narration", "无");
            }
            prevNarration = str(shot.get("narration"));
            shot.remove("narrationType");
        }
    }

    return panelShots;
}
```

- [ ] **Step 3: 修改 PanelProductionService 中调用 DeepSeek 的地方，精修时传入 lockedShots**

在 `generateStoryboardForEpisode` 中，修改两处调用：

line 1058-1061（comicMode 分支）：
```java
if (isRefinement && lockedShots != null && !lockedShots.isEmpty()) {
    panelGroups = deepSeekTextService.generatePanelAwareStoryboard(
        content, characters, targetDuration, visualStyle, revisionNote, narrationPerspective, lockedShots);
} else {
    panelGroups = deepSeekTextService.generatePanelAwareStoryboard(
        content, characters, targetDuration, visualStyle, revisionNote, narrationPerspective);
}
```

line 1080-1082（非 comicMode 分支）：
```java
if (isRefinement && lockedShots != null && !lockedShots.isEmpty()) {
    shots = deepSeekTextService.generateStoryboard(
        content, characters, targetDuration, visualStyle, false, revisionNote, lockedShots);
} else {
    shots = deepSeekTextService.generateStoryboard(
        content, characters, targetDuration, visualStyle, false, revisionNote);
}
```

- [ ] **Step 4: 改造 refineNarrationsSequentially 跳过全锁定 panel**

在 `DeepSeekTextService.refineNarrationsSequentially`（line 676）循环内，在筛选 `narrationShots` 之后，新增判断：

```java
// 检查该 panel 是否所有 shot 都被锁定，如果是则跳过
boolean allLocked = panel.stream().allMatch(s -> Boolean.TRUE.equals(s.get("locked")));
if (allLocked) {
    log.info("[Stage2] Panel {} all shots locked, skipping", panelIdx + 1);
    appendNarrationContext(narrationContext, panel);
    continue;
}
```

- [ ] **Step 5: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: DeepSeek 精修 prompt 支持锁定分镜标注"
```

---

### Task 5: 前端 — 新增 updateShot 和 toggleShotLock API

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts` (after line 55)

- [ ] **Step 1: 在 `// ================= 整集九宫格审核 API` 注释之前，新增分镜编辑 API**

```typescript
// ================= 单分镜编辑 API =================

/** 更新单个分镜的可编辑字段 */
export async function updateShot(
  projectId: string,
  episodeId: number,
  shotIndex: number,
  updates: Record<string, any>,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/shots/${shotIndex}`,
    updates,
  );
}

/** 锁定或解锁分镜 */
export async function toggleShotLock(
  projectId: string,
  episodeId: number,
  shotIndex: number,
  locked: boolean,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/shots/${shotIndex}/lock`,
    { locked },
  );
}
```

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | head -20`
Expected: 无新增错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/episodeService.ts
git commit -m "feat: 新增单分镜编辑和锁定前端 API"
```

---

### Task 6: 前端 — 改造 ScriptEpisodeCard 为可编辑分镜卡片

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ScriptEpisodeCard.tsx` (full rewrite)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less` (新增编辑相关样式)

- [ ] **Step 1: 重写 ScriptEpisodeCard 组件**

核心变更：
- 移除 `rejectingEpisodeId`, `onRejectScript` props
- 每个分镜卡片原位展开为可编辑表单（textarea + select）
- 锁定 toggle 按钮
- 保存按钮调用 `updateShot`
- 主按钮文案：有分镜时显示「重新生成」
- 移除退回按钮

```typescript
import React, { useCallback, useState } from 'react';
import type { EpisodeState } from '../types';
import { updateShot, toggleShotLock } from '../../../../services/episodeService';
import styles from '../Step4Production.module.less';

const SpinIcon = () => <span className={styles.btnSpinner} />;

// 可编辑字段白名单（与后端一致）
const EDITABLE_FIELDS = [
  'visualDescription', 'narration', 'dialogue', 'speaker',
  'narrationTone', 'dialogueTone', 'shotSize', 'cameraAngle',
  'cameraMovement', 'scene', 'visualEffects', 'audioEffects', 'transitionHint',
] as const;

const FIELD_LABELS: Record<string, string> = {
  visualDescription: '画面描述',
  narration: '旁白',
  dialogue: '对白',
  speaker: '说话人',
  narrationTone: '旁白语气',
  dialogueTone: '对白语气',
  shotSize: '景别',
  cameraAngle: '角度',
  cameraMovement: '运镜',
  scene: '场景',
  visualEffects: '视觉特效',
  audioEffects: '音效',
  transitionHint: '过渡提示',
};

interface ScriptEpisodeCardProps {
  episode: EpisodeState;
  projectId: string;
  generatingScript: number | null;
  approvingEpisodeId: number | null;
  expandedEpisodeId: number | null;
  onGenerateScript: (episodeId: number) => void;
  onApproveScript: (episodeId: number) => void;
  onToggleEpisode: (episodeId: number) => void;
}

const ScriptEpisodeCard = React.memo(function ScriptEpisodeCard({
  episode, projectId, generatingScript, approvingEpisodeId,
  expandedEpisodeId, onGenerateScript, onApproveScript, onToggleEpisode,
}: ScriptEpisodeCardProps) {
  const isGenerating = generatingScript === episode.episodeId || generatingScript === -1;
  const isExpanded = expandedEpisodeId === episode.episodeId;
  const hasShots = episode.segments.length > 0;
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [editData, setEditData] = useState<Record<string, string>>({});
  const [savingIdx, setSavingIdx] = useState<number | null>(null);

  const handleEdit = useCallback((idx: number) => {
    const shot = episode.episodeInfo?.shots?.[idx];
    if (!shot) return;
    const data: Record<string, string> = {};
    for (const field of EDITABLE_FIELDS) {
      const val = shot[field];
      if (val != null) data[field] = String(val);
    }
    setEditData(data);
    setEditingIdx(idx);
  }, [episode.episodeInfo?.shots]);

  const handleSave = useCallback(async (idx: number) => {
    if (!projectId) return;
    setSavingIdx(idx);
    try {
      await updateShot(projectId, episode.episodeId, idx, editData);
      setEditingIdx(null);
      // 通知父组件刷新
      onToggleEpisode(episode.episodeId); // trigger reload via parent
    } catch (err: any) {
      alert(err?.message || '保存失败');
    } finally {
      setSavingIdx(null);
    }
  }, [projectId, episode.episodeId, editData, onToggleEpisode]);

  const handleToggleLock = useCallback(async (idx: number, currentLocked: boolean) => {
    if (!projectId) return;
    try {
      await toggleShotLock(projectId, episode.episodeId, idx, !currentLocked);
      onToggleEpisode(episode.episodeId);
    } catch (err: any) {
      alert(err?.message || '锁定操作失败');
    }
  }, [projectId, episode.episodeId, onToggleEpisode]);

  const handleFieldChange = useCallback((field: string, value: string) => {
    setEditData(prev => ({ ...prev, [field]: value }));
  }, []);

  return (
    <div className={`${styles.episodeScriptCard} ${isGenerating ? styles.cardGenerating : ''}`}>
      <div className={styles.episodeScriptHeader}>
        <div>
          <h3 className={styles.episodeScriptTitle}>
            第{episode.episodeIndex}集 {episode.title}
          </h3>
          <span className={styles.episodeScriptCount}>
            {hasShots ? `${episode.segments.length} 个分镜` : '暂无分镜数据'}
          </span>
          <div className={styles.scriptStageList}>
            <span className={`${styles.scriptStageItem} ${episode.scriptStatus === 'done' ? styles.scriptStageItemDone : episode.scriptStatus === 'generating' ? styles.scriptStageItemActive : styles.scriptStageItemPending}`}>
              {episode.scriptStatus === 'generating' ? '⏳ Stage 1: 剧本生成中...' : episode.scriptStatus === 'done' ? '✓ Stage 1: 剧本完成' : '○ Stage 1: 待生成'}
            </span>
            <span className={`${styles.scriptStageItem} ${episode.storyboardStatus === 'done' ? styles.scriptStageItemDone : episode.storyboardStatus === 'generating' ? styles.scriptStageItemActive : styles.scriptStageItemPending}`}>
              {episode.storyboardStatus === 'generating' ? '⏳ Stage 2: 旁白精修中...' : episode.storyboardStatus === 'done' ? '✓ Stage 2: 旁白精修完成' : '○ Stage 2: 待精修'}
            </span>
          </div>
        </div>
        <div className={styles.episodeScriptActions}>
          <button
            className={styles.btnPrimary}
            onClick={() => onGenerateScript(episode.episodeId)}
            disabled={isGenerating}
          >
            {isGenerating ? <><SpinIcon /> {hasShots ? '精修中...' : '生成中...'}</> : (hasShots ? '重新生成' : '生成脚本')}
          </button>
          {hasShots && (
            <button
              className={styles.btnSuccess}
              onClick={() => onApproveScript(episode.episodeId)}
              disabled={approvingEpisodeId === episode.episodeId}
            >
              {approvingEpisodeId === episode.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
            </button>
          )}
        </div>
      </div>

      <button
        className={styles.expandToggle}
        onClick={() => onToggleEpisode(episode.episodeId)}
      >
        {isExpanded ? '收起' : '展开'}分镜文本 &#9660;
      </button>

      {isExpanded && hasShots && (
        <div className={styles.scriptSegmentList}>
          {episode.segments.map((seg, idx) => {
            const shot = episode.episodeInfo?.shots?.[idx];
            const isLocked = shot?.locked === true;
            const isEditing = editingIdx === idx;

            return (
              <div key={idx} className={`${styles.scriptSegmentItem} ${isLocked ? styles.shotLocked : ''}`}>
                <div className={styles.scriptSegmentHeader}>
                  <span className={styles.scriptSegmentTitle}>分镜 {idx + 1}</span>
                  {shot?.duration && (
                    <span className={styles.shotDuration}>{shot.duration}s</span>
                  )}
                  <button
                    className={`${styles.lockBtn} ${isLocked ? styles.lockBtnActive : ''}`}
                    onClick={() => handleToggleLock(idx, isLocked)}
                    title={isLocked ? '解锁此分镜' : '锁定此分镜（重新生成时保持不变）'}
                  >
                    {isLocked ? '🔒' : '🔓'}
                  </button>
                  {isEditing ? (
                    <div className={styles.shotEditActions}>
                      <button
                        className={styles.btnSave}
                        onClick={() => handleSave(idx)}
                        disabled={savingIdx === idx}
                      >
                        {savingIdx === idx ? '保存中...' : '保存'}
                      </button>
                      <button
                        className={styles.btnCancel}
                        onClick={() => setEditingIdx(null)}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <button className={styles.btnEdit} onClick={() => handleEdit(idx)}>
                      编辑
                    </button>
                  )}
                </div>

                {isEditing ? (
                  <div className={styles.shotEditForm}>
                    {EDITABLE_FIELDS.map(field => (
                      <div key={field} className={styles.shotFieldRow}>
                        <label className={styles.shotFieldLabel}>{FIELD_LABELS[field]}</label>
                        <textarea
                          className={styles.shotFieldInput}
                          value={editData[field] || ''}
                          onChange={e => handleFieldChange(field, e.target.value)}
                          rows={field === 'visualDescription' || field === 'narration' || field === 'dialogue' ? 2 : 1}
                        />
                      </div>
                    ))}
                  </div>
                ) : (
                  <>
                    {seg.synopsis && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>画面：</span>{seg.synopsis}
                      </div>
                    )}
                    {seg.panelData?.dialogue && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>对话：</span><span style={{ whiteSpace: 'pre-wrap' }}>{seg.panelData.dialogue}</span>
                      </div>
                    )}
                    {seg.characterAvatars.length > 0 && (
                      <div className={styles.scriptSegmentCharacters}>
                        <span>角色：</span>{seg.characterAvatars.map(a => a.name).join('、')}
                      </div>
                    )}
                    {seg.panelData?.composition && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>镜头：</span>{seg.panelData.composition}
                        {seg.panelData?.cameraAngle && ` / ${seg.panelData.cameraAngle}`}
                        {seg.panelData?.cameraMovement && ` / ${seg.panelData.cameraMovement}`}
                      </div>
                    )}
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
});

export default ScriptEpisodeCard;
```

- [ ] **Step 2: 在 Step4Production.module.less 中新增编辑相关样式**

在文件末尾追加：

```less
// 分镜编辑相关
.scriptSegmentHeader {
  display: flex;
  align-items: center;
  gap: 8px;
}

.shotDuration {
  font-size: 12px;
  color: #999;
  background: #f0f0f0;
  padding: 1px 6px;
  border-radius: 4px;
}

.lockBtn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 6px;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  transition: all 0.2s;
  &:hover { border-color: #4096ff; }
}

.lockBtnActive {
  border-color: #faad14;
  background: #fffbe6;
}

.shotLocked {
  border-left: 3px solid #faad14;
}

.shotEditActions {
  display: flex;
  gap: 4px;
  margin-left: auto;
}

.btnEdit {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
  font-size: 12px;
  color: #4096ff;
  &:hover { border-color: #4096ff; background: #e6f4ff; }
}

.btnSave {
  background: #4096ff;
  color: #fff;
  border: none;
  border-radius: 4px;
  padding: 2px 10px;
  cursor: pointer;
  font-size: 12px;
  &:disabled { opacity: 0.6; cursor: not-allowed; }
}

.btnCancel {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 10px;
  cursor: pointer;
  font-size: 12px;
  &:hover { border-color: #ff4d4f; color: #ff4d4f; }
}

.shotEditForm {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
  padding: 8px;
  background: #fafafa;
  border-radius: 4px;
}

.shotFieldRow {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.shotFieldLabel {
  min-width: 70px;
  font-size: 13px;
  color: #666;
  line-height: 28px;
  flex-shrink: 0;
}

.shotFieldInput {
  flex: 1;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 13px;
  resize: vertical;
  font-family: inherit;
  &:focus { border-color: #4096ff; outline: none; box-shadow: 0 0 0 2px rgba(64, 150, 255, 0.2); }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ScriptEpisodeCard.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less
git commit -m "feat: ScriptEpisodeCard 改为可编辑分镜卡片（锁定+原位编辑+保存）"
```

---

### Task 7: 前端 — 改造 Step4Production 移除退回逻辑、适配新 props

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`
  - line 264: 移除 `rejectingEpisodeId` state
  - lines 925-936: 移除 `handleRejectScript` handler
  - lines 1642-1653: 移除 `rejectingEpisodeId` 和 `onRejectScript` props

- [ ] **Step 1: 移除 rejectingEpisodeId state（line 264）**

删除：
```typescript
const [rejectingEpisodeId, setRejectingEpisodeId] = useState<number | null>(null);
```

- [ ] **Step 2: 移除 handleRejectScript handler（lines 925-936）**

删除整个 `handleRejectScript` callback。

- [ ] **Step 3: 修改 ScriptEpisodeCard 调用（lines 1642-1653）**

移除 `rejectingEpisodeId` 和 `onRejectScript` props，新增 `projectId` prop：

```tsx
<ScriptEpisodeCard
  key={ep.episodeId}
  episode={ep}
  projectId={projectId!}
  generatingScript={generatingScript}
  approvingEpisodeId={approvingEpisodeId}
  expandedEpisodeId={expandedEpisodeId}
  onGenerateScript={handleGenerateScript}
  onApproveScript={handleApproveScript}
  onToggleEpisode={toggleEpisode}
/>
```

- [ ] **Step 4: 验证编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | head -20`
Expected: 无新增错误

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat: 移除退回逻辑，适配 ScriptEpisodeCard 新 props"
```

---

## 实现顺序总结

Task 1-4 为后端（可顺序执行），Task 5-7 为前端。后端和前端可并行开发，因为 API 契约已确定。

**后端依赖链：** Task 1（CRUD 端点）→ Task 2（精修判断）→ Task 3（合并逻辑）→ Task 4（prompt 改造）

**前端依赖链：** Task 5（API 层）→ Task 6（组件改造）→ Task 7（父组件适配）