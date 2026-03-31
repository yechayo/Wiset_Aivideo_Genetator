# Episode 级九宫格流水线重设计

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将九宫格生成和审核从 Panel 层提升到 Episode 层，对齐参考流程：先生成整集分镜 → 整集九宫格 → 切割 → 审核 → 分组创建 Panel → 视频。

**Architecture:** Episode 的 episodeInfo JSON 扩展存储整集九宫格数据（gridImages、splitShots、gridStatus）。九宫格审核通过后，按 16 秒贪心分组 splitShots → 创建 Panel → 每个 Panel 拥有融合图 + 多镜头视频提示词 → 独立生成视频。Panel 表结构不变。

**Tech Stack:** Spring Boot 2.7.18 / Java 8 / MyBatis-Plus / DeepSeek（文本）+ Seedream（图片）+ Vidu（视频）/ React 19 + TypeScript

---

## 1. 设计约束

- 不改数据库表结构 — 新数据存入 episodeInfo JSON 字段
- Panel 表结构不变 — Panel 在审核通过后才创建
- 融合图 = 该 Panel 所属分镜的切割小图拼接 + 可选角色参考图
- 视频提示词使用中文 `【分镜N】` 标记分镜切换
- 每个 splitShot（切割后的格子）携带完整元数据：shot 描述、出场角色、时长等
- 九宫格 3×3 分页（每页 9 格，多页并行生成）

## 2. 当前流程 vs 新流程

### 当前流程
```
生成分镜 → 按16s分组创建Panel → 每Panel独立九宫格 → 审核每个Panel → 每Panel独立视频
```

### 新流程
```
生成分镜(整集) → 整集分页九宫格 → 切割 → 审核整集 → 按16s分组创建Panel(含fusion图) → 每Panel视频
```

## 3. Episode episodeInfo JSON 扩展

在 episodeInfo 中新增以下字段：

```json
{
  "shots": [
    {
      "shotNumber": 1,
      "duration": 4,
      "scene": "教室 - 白天",
      "characters": ["李逍遥", "赵灵儿"],
      "shotSize": "全景",
      "cameraAngle": "视平",
      "cameraMovement": "轨道推拉",
      "visualDescription": "男主推门走入房间...",
      "dialogue": "无",
      "visualEffects": "浅景深",
      "audioEffects": "门轴吱呀声"
    }
  ],
  "gridImages": ["https://oss.../grid-page1.png", "https://oss.../grid-page2.png"],
  "gridStatus": "pending",
  "gridRejectionFeedback": null,
  "splitShots": [
    {
      "shotNumber": 1,
      "splitImageUrl": "https://oss.../shot-1.png",
      "duration": 4,
      "scene": "教室 - 白天",
      "characters": ["李逍遥"],
      "shotSize": "全景",
      "cameraAngle": "视平",
      "cameraMovement": "轨道推拉",
      "visualDescription": "男主推门走入房间...",
      "dialogue": "无",
      "visualEffects": "浅景深",
      "audioEffects": "门轴吱呀声"
    }
  ],
  "visualStyle": "ANIME"
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `shots` | Array | 整集所有分镜（已存在，不变） |
| `gridImages` | String[] | 分页九宫格图 URL 列表 |
| `gridStatus` | String | `pending` → `generating` → `generated` → `approved` / `rejected` |
| `gridRejectionFeedback` | String? | 拒绝原因 |
| `splitShots` | Array | 切割后的分镜，每个带 splitImageUrl + 完整元数据 |
| `visualStyle` | String | 视觉风格（已存在，不变） |

**状态流转：**
```
pending → generating → generated → approved → (创建 Panels)
                  ↘ failed      ↘ rejected → (可重新生成)
```

## 4. 后端改动

### 4.1 StoryboardService 改动

**当前行为：**
1. 生成分镜 shots
2. `greedyGroup(shots, 16)` 分组
3. 每组创建 Panel（含 shots、gridStatus=pending）

**新行为：**
1. 生成分镜 shots → **写入 episodeInfo.shots**（当前 shots 只存在 Panel 中，新流程必须先存入 Episode）
2. 设置 episodeInfo.gridStatus = "generating"
3. 异步调用 `GridImageService.generateGridsForEpisode(episodeId)` 生成整集九宫格
4. 不再调用 `greedyGroup`，不再创建 Panel（Panel 在审核通过后才创建）

**关键改动：** `findOrCreateEpisode()` 中需要把生成的 shots 写入 episodeInfo.shots（当前只存 title/content/characters 等）

**改动文件：** `StoryboardService.java`

### 4.2 GridImageService 改动

**新增方法：** `generateGridsForEpisode(Long episodeId)`

**流程：**
1. 从 episodeInfo.shots 读取整集所有分镜
2. 按 9 个一页计算页数：`pageCount = ceil(shots.size / 9)`
3. 每页构建九宫格提示词 → 调用 Seedream 生成 1920×1080 图片
4. 切割每页九宫格 → 得到每个 shot 的 splitImageUrl → 上传 OSS
5. 构建 splitShots 数组（每个元素 = splitImageUrl + shot 完整元数据）
6. 更新 episodeInfo：gridImages、splitShots、gridStatus="generated"

**保留方法：** `generateGridsForPanel(Long panelId)` — 用于 Panel 级别重新生成（如果后续需要）

**改动文件：** `GridImageService.java`

### 4.3 新增审核 API

**新增方法在 EpisodeController 或独立 controller：**

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | `/api/projects/{projectId}/episodes/{episodeId}/grid/approve` | 审核通过整集九宫格，触发分组+创建Panel+视频生成 |
| PUT | `/api/projects/{projectId}/episodes/{episodeId}/grid/reject` | 拒绝整集九宫格，请求体：`{ "reason": "..." }` |
| POST | `/api/projects/{projectId}/episodes/{episodeId}/grid/regenerate` | 重新生成整集九宫格（不重新生成分镜脚本） |
| GET | `/api/projects/{projectId}/episodes/{episodeId}/grid` | 获取整集九宫格状态 |

**审核通过后的处理流程（`approveEpisodeGrid`）：**
1. 设置 episodeInfo.gridStatus = "approved"
2. 从 episodeInfo.splitShots 读取所有切割后的分镜
3. `greedyGroup(splitShots, 16)` 按 16 秒贪心分组
4. 每组：
   a. 创建 Panel 实体
   b. 将该组的 splitShots 写入 panelInfo.shots
   c. 生成融合图（该组分镜的 splitImage 拼接 + 可选角色参考图）→ panelInfo.fusionImageUrl
   d. 设置 panelInfo.gridStatus = "approved"（跳过 Panel 级审核）
   e. 设置 panelInfo.videoStatus = "pending"
5. 可选：自动触发每个 Panel 的视频生成

**改动文件：** 新增 `EpisodeGridController.java` 或在 `EpisodeController.java` 中新增端点

### 4.4 PanelPromptBuilder 改动

**`buildMultiShotPrompt`** 的提示词格式改为使用中文 `【分镜N】` 标记：

```
多镜头连续拍摄指令，以下 3 个镜头必须在同一视频中连续呈现：

【分镜1】
duration: 4s
Scene: 全景，视平，轨道推拉，男主推门走入房间，环顾四周
对白: 无
音效: [门轴吱呀声]

【分镜2】
duration: 3s
Scene: 中景，视平，固定，女主惊讶地回头，手中针线掉落

【分镜3】
duration: 5s
Scene: 特写，低位仰拍，固定，两人四目相对，空气凝固

## 画面衔接
视频应从参考图自然展开，多镜头间平滑过渡。
保持角色位置和动作的连贯性。
参考图中编号①②③对应 【分镜1】【分镜2】【分镜3】 的画面内容。
```

**改动文件：** `PanelPromptBuilder.java` 的 `buildMultiShotPrompt` 方法

### 4.5 融合图生成

**在创建 Panel 时生成融合图**（不是在九宫格阶段）：

融合图内容：
- 标题行：`分镜融合图 - 共 N 个镜头`
- 3 列网格布局，每个格子是该 Panel 包含的一个 splitImage
- 每个格子左上角标注编号（①②③...）
- 可选：底部追加涉及角色的三视图/表情图

编号与提示词中的 `【分镜N】` 一一对应。

**复用现有逻辑：** `GridImageService.createFusionImage()` 方法略作调整即可复用。

### 4.6 PanelProductionService 改动

**基本不变：**
- `generateVideoByPanelId` 逻辑不变
- `approveGrid` / `rejectGrid` 等方法保留（向后兼容）
- 新增：Panel 级别的 gridStatus 在创建时已设为 "approved"，无需再审核

## 5. 前端改动

### 5.1 Step5page 数据流变更

**当前：** 展开 Episode → 加载 Panels → 每个 Panel 展示九宫格和视频
**新：** 展开 Episode → 展示整集九宫格（从 episodeInfo 读取）→ 审核通过后 → 展示 Panel 列表

**数据加载逻辑：**
1. `loadEpisodes()` 获取剧集列表（含 episodeInfo）
2. 从 episodeInfo 读取：shots、gridImages、gridStatus、splitShots
3. 如果 gridStatus === "generated" → 显示审核按钮
4. 如果 gridStatus === "approved" → 加载该集的 Panels → 每个 Panel 展示融合图和视频

### 5.2 UI 变更

**Episode 卡片（折叠状态）：**
- 显示九宫格状态（pending / generating / generated / approved）
- 如果 generated → 点击展开直接进入九宫格审核视图

**Episode 卡片（展开状态）— 九宫格阶段（gridStatus !== approved）：**
- 分页九宫格图浏览（每页 9 格）
- 每个格子可点击查看 splitShot 详情（图片 + 元数据）
- 底部：审核按钮（通过 / 拒绝 + 原因）
- 底部：重新生成按钮

**Episode 卡片（展开状态）— 视频阶段（gridStatus === approved）：**
- 显示 Panel 列表
- 每个 Panel 展示：融合图 + 视频状态
- 复用现有 SegmentCard 组件（略作调整）

### 5.3 前端组件变更

| 组件 | 改动 |
|------|------|
| `EpisodeCard` | 增加九宫格审核视图（审核前显示整集九宫格，审核后显示 Panel 列表）|
| `GridReviewPanel` | 适配整集九宫格的分页展示 |
| `SegmentCard` | 不变（Panel 创建后的展示逻辑不变）|
| `ShotDetail` | 不变 |
| `BatchReviewBar` | 改为整集级别的一键通过 |

### 5.4 前端 API 调用变更

**新增调用：**
- `approveEpisodeGrid(projectId, episodeId)` — 审核通过整集九宫格
- `rejectEpisodeGrid(projectId, episodeId, reason)` — 拒绝整集九宫格
- `regenerateEpisodeGrid(projectId, episodeId)` — 重新生成整集九宫格
- `getEpisodeGridStatus(projectId, episodeId)` — 获取整集九宫格状态

**移除调用：**
- 单 Panel 的 grid approve/reject/regenerate 在新流程中不再需要（但保留 API 向后兼容）

## 6. 用户操作流程

1. **进入 Step5** — 看到剧集列表
2. **展开剧集** — 看到整集九宫格图（分页浏览）
3. **点击格子** — 查看该分镜的切割小图 + 元数据（景别、角度、角色、描述等）
4. **审核整集九宫格**：
   - 通过 → 系统自动分组创建 Panel + 生成融合图 + 触发视频
   - 拒绝 + 原因 → 可重新生成
5. **查看 Panel 视频进度** — 每个 Panel 独立显示视频生成状态
6. **播放视频** — 视频完成后在 Panel 中播放预览

## 7. 状态机改动

### 7.1 ProjectStatus 状态流转

当前 `STORYBOARD_GENERATING` 完成后直接进入 `STORYBOARD_REVIEW`，用户在此审核 Panel 级九宫格。

**新流程中 `STORYBOARD_REVIEW` 复用，但审核对象变为 Episode 级九宫格：**

```
EPISODE_SCRIPT_GENERATING → episode_script_generated → STORYBOARD_GENERATING
STORYBOARD_GENERATING → storyboard_generated → STORYBOARD_REVIEW（整集九宫格审核）
STORYBOARD_REVIEW → all_grids_approved → PRODUCING（所有集审核通过后）
STORYBOARD_REVIEW → regenerate_storyboard → EPISODE_SCRIPT_GENERATING（可重新生成）
```

**改动点：**
- `STORYBOARD_REVIEW` 状态复用，不改枚举值
- `storyboard_generated` 事件含义变化：从"分镜+Panel创建完成"变为"分镜+整集九宫格生成完成"
- `all_grids_approved` 事件含义变化：从"所有 Panel 九宫格审核通过"变为"所有 Episode 九宫格审核通过"
- PipelineService 中触发 `all_grids_approved` 的逻辑改为检查所有 Episode 的 gridStatus === "approved"

### 7.2 StoryboardService 状态推进

**当前：** `generateEpisodeScriptAndStoryboard()` 在生成分镜+创建 Panel 后，直接推进到 `STORYBOARD_REVIEW`

**新：** `generateEpisodeScriptAndStoryboard()` 流程变为：
1. 生成分镜 shots → 存入 episodeInfo.shots
2. 调用 GridImageService 生成整集九宫格 → 存入 episodeInfo.gridImages/splitShots
3. 推进到 `STORYBOARD_REVIEW`（整集九宫格已生成，等待审核）

**九宫格生成是异步的**：`generateGridsForEpisode` 使用 `@Async` 执行，前端通过轮询 `GET /episodes/{id}/grid` 获取 gridStatus。

## 8. 向后兼容

### 8.1 已有项目（已有 Panel 数据）

- 旧的 Panel 级 API 保留（`approveGrid`、`rejectGrid`、`regenerateGrid`）
- 前端通过检查 `episodeInfo.gridImages` 是否存在来判断使用新流程还是旧流程
- 如果 `episodeInfo.gridImages` 存在 → 新流程（Episode 级审核）
- 如果 `episodeInfo.gridImages` 不存在但有 Panels → 旧流程（Panel 级审核）

### 8.2 保留的 API

| API | 状态 | 说明 |
|-----|------|------|
| `PUT /panels/{id}/grid/approve` | 保留 | 旧流程向后兼容 |
| `PUT /panels/{id}/grid/reject` | 保留 | 旧流程向后兼容 |
| `POST /panels/{id}/grid/regenerate` | 保留 | 旧流程向后兼容 |
| `GridImageService.generateGridsForPanel()` | 保留 | 旧流程向后兼容 |

## 9. 不改动的部分

- Episode 表结构不变（只改 episodeInfo JSON 内容）
- Panel 表结构不变
- Panel 的 CRUD API 不变
- PanelProductionService 视频生成逻辑不变
- ViduVideoService 不变
- SeedreamImageService 不变
- 角色设计流程不变
- 剧本大纲生成流程不变
- ProjectStatus 枚举值不变（复用现有状态）
