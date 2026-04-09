# 4A 脚本精修设计

## 概述

在 4A 页面，用户生成分镜脚本后，可以针对每个分镜进行直接修改或锁定。重新生成作为精修操作，只替换未锁定的分镜，保留锁定分镜（含用户手动修改的内容）。

## 需求要点

1. 每个分镜支持原位编辑（画面描述、旁白/对白文本、镜头参数等），duration/characters 只读
2. 用户可锁定分镜，重新生成时跳过锁定分镜
3. 有分镜文本后，「生成脚本」按钮变为「重新生成」
4. 保留每集「通过」按钮，移除「退回」按钮
5. 重新生成 = 精修操作，传入当前全部分镜（含用户修改后的版本）作为上下文
6. 仅集级别重新生成，不支持单分镜重新生成

## 约束

- 重新生成时必须从 DB 实时读取 shots，确保拿到用户最新编辑的版本
- locked 字段在重新生成后必须保留

## 前端交互变更

### ScriptEpisodeCard 改造

**按钮状态矩阵：**

| 状态 | 主按钮 | 通过 | 退回 |
|------|--------|------|------|
| 无分镜 | 生成脚本 | 隐藏 | 隐藏 |
| 有分镜 | 重新生成 | ✓ | 移除 |
| 生成中 | (disabled) | (disabled) | 隐藏 |

**分镜卡片原位编辑：**
- 锁定 toggle（pin icon），点击调用 lock API
- 可编辑字段（textarea/select）：visualDescription, narration, dialogue, speaker, narrationTone, dialogueTone, shotSize, cameraAngle, cameraMovement, scene, visualEffects, audioEffects, transitionHint
- 只读字段（标签展示）：duration, characters, characterRefs
- 每分镜独立保存按钮，调用 shot update API

### Step4Production 状态变更

- 移除 `handleRejectScript`、`rejectingEpisodeId`
- 新增 `editingShotKey` 状态追踪当前编辑中的分镜
- `handleGenerateScript` 不变（复用同一 API）

## 后端 API 变更

### 新增端点

**1. PUT /api/projects/{projectId}/episodes/{episodeId}/shots/{shotIndex}**

更新单个分镜的可编辑字段。

```java
@PutMapping("/{episodeId}/shots/{shotIndex}")
public Result<Void> updateShot(
    @PathVariable String projectId,
    @PathVariable Long episodeId,
    @PathVariable int shotIndex,
    @RequestBody Map<String, Object> updates)
```

Service 逻辑：
- 读取 `episodeInfo.shots[shotIndex]`
- 白名单字段过滤：`visualDescription, narration, dialogue, speaker, narrationTone, dialogueTone, shotSize, cameraAngle, cameraMovement, scene, visualEffects, audioEffects, transitionHint`
- duration/characters/characterRefs 不在白名单，直接忽略
- 保存 episode

**2. PUT /api/projects/{projectId}/episodes/{episodeId}/shots/{shotIndex}/lock**

锁定或解锁分镜。

```java
@PutMapping("/{episodeId}/shots/{shotIndex}/lock")
public Result<Void> toggleShotLock(
    @PathVariable String projectId,
    @PathVariable Long episodeId,
    @PathVariable int shotIndex,
    @RequestBody Map<String, Boolean> body) // { "locked": true/false }
```

Service 逻辑：
- 读取 `episodeInfo.shots[shotIndex]`
- 设置 `locked` 字段
- 保存 episode

### 现有端点改造

**POST /api/projects/{projectId}/episodes/{episodeId}/script**

改造 `PanelProductionService.generateSingleEpisodeScript`：

**判断是否为精修：**
```java
List<Map<String, Object>> existingShots = existingEpInfo != null
    ? (List<Map<String, Object>>) existingEpInfo.get("shots")
    : null;
boolean isRefinement = existingShots != null && !existingShots.isEmpty();
```

**精修时跳过 Stage 1：**
```java
if (isRefinement) {
    targetScript = existingEpInfo;
    eventPublisher.publishEpisodeScriptDone(projectId, targetEpisodeNum,
        (String) existingEpInfo.getOrDefault("title", ""), totalEpisodes, 1, "stage1");
} else {
    // 现有 Stage 1 逻辑不变
    List<Map<String, Object>> chapterScripts = deepSeekTextService.generateEpisodeScript(...);
    ...
}
```

**改造 generateStoryboardForEpisode：**

方法签名新增 `List<Map<String, Object>> lockedShots` 参数。

精修流程：
1. 从 existingShots 中筛选 `locked: true` 的分镜
2. 提取未锁定分镜的 shotNumber 列表
3. 构建精修 prompt：在现有 user prompt 基础上追加锁定分镜标注
4. AI 只输出未锁定分镜的新内容
5. 合并：lockedShots 原样保留（含 locked 字段），newShots 替换未锁定分镜
6. 后续流程不变（注入角色 ID → 保存 → 删除 panels → 更新状态 → SSE）

**精修 prompt 示例：**
```
【精修模式】以下是当前全部分镜，标记 [LOCKED] 的分镜保持不变，请仅为其余分镜重新生成内容。
保持与锁定分镜的叙事连贯性。

[LOCKED] 第1镜: {"shotNumber":1, "narration":"...", ...}
第2镜: {"shotNumber":2, ...}  ← 请重新生成
[LOCKED] 第3镜: {"shotNumber":3, ...}
```

**旁白精修（refineNarrationsSequentially）：**
- 精修模式下同样执行
- 跳过全部 shots 都被锁定的 panel

## Shot 数据结构扩展

```json
{
  "shotNumber": 1,
  "locked": false,
  "duration": 3,
  "visualDescription": "...",
  "narration": "...",
  "dialogue": "...",
  "speaker": "...",
  "narrationTone": "...",
  "dialogueTone": "...",
  "shotSize": "...",
  "cameraAngle": "...",
  "cameraMovement": "...",
  "scene": "...",
  "characters": [...],
  "characterRefs": [...],
  "visualEffects": "...",
  "audioEffects": "...",
  "transitionHint": "..."
}
```

## 变更文件清单

### 后端

| 文件 | 变更 |
|------|------|
| `EpisodeController.java` | 新增 updateShot、toggleShotLock 两个端点 |
| `PanelProductionService.java` | 改造 generateSingleEpisodeScript（精修跳过 Stage 1）、改造 generateStoryboardForEpisode（接收 lockedShots + 合并）、新增 updateShot/toggleShotLock |
| `DeepSeekTextService.java` | 新增精修 prompt 构建（locked 标注），改造 generateStoryboard/generatePanelAwareStoryboard 支持精修模式 |

### 前端

| 文件 | 变更 |
|------|------|
| `episodeService.ts` | 新增 updateShot、toggleShotLock API 调用 |
| `ScriptEpisodeCard.tsx` | 重构为可编辑分镜卡片（锁定 toggle、原位编辑、保存） |
| `Step4Production.tsx` | 移除退回逻辑，按钮文案切换，移除 rejectingEpisodeId 状态 |