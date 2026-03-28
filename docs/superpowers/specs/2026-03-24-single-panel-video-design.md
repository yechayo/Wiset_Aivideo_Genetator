# 单分镜视频独立生产功能设计

> 日期：2026-03-24
> 状态：设计完成，待实现

## 1. 概述

### 1.1 目标

在 Step5 分镜审核页面实现单分镜视频独立生产能力，每个分镜格子可单独进入视频生产流程，同时保留一键生成所有分镜视频的功能。

### 1.2 核心设计变更

**废弃旧设计：**
- 九宫格融合模式
- 整页9格同时融合

**新设计：**
- 单分镜独立生产
- 一个背景图 + 角色 → 一张融合图
- 过渡融合图保证首尾帧连贯

---

## 2. 整体流程

### 2.1 生产流水线

```
┌─────────┐    ┌─────────┐    ┌─────────────────┐    ┌─────────┐    ┌─────────┐
│ 背景图   │ → │ 融合图   │ → │ 过渡融合图      │ → │ 视频    │ → │ 尾帧    │
│生成     │    │(用户确认)│    │(尾帧+融合图)    │    │(1-16s) │    │(截取)   │
└─────────┘    └─────────┘    └─────────────────┘    └─────────┘    └─────────┘
                                        ↑
                                   首帧参考
```

### 2.2 首尾帧连续性保证

```
融合图A + 尾帧(空) → 过渡融合图A' → 视频A → 尾帧A
融合图B + 尾帧A   → 过渡融合图B' → 视频B → 尾帧B
```

- **过渡融合图**：由后端将"融合图"和"上一个尾帧"进行AI处理生成
- 兼顾内容准确性（来自融合图）和风格连贯性（来自尾帧）

### 2.3 分镜视频合成

```
视频1 + 视频2 + ... + 视频N → ffmpeg拼接 → 最终剧集视频
```

---

## 3. 页面设计

### 3.1 路由设计

```
/project/:projectId/episode/:episodeId/panel/:panelIndex
```

示例：`/project/123/episode/1/panel/3` 表示 EP1 的第3个分镜

### 3.2 Page5 分镜审核页

**分镜格子展示：**

```
┌────────────────────────────────────────────────┐
│ EP1 - 第1集                                      │
├────────────────────────────────────────────────┤
│ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐   │
│ │ #1     │ │ #2     │ │ #3     │ │ #4     │   │
│ │ [已完成]│ │ [待生成]│ │ [待生成]│ │ [待生成]│   │
│ └────────┘ └────────┘ └────────┘ └────────┘   │
├────────────────────────────────────────────────┤
│                      [一键生成所有视频]          │
└────────────────────────────────────────────────┘
```

**状态标签类型：**
- `待生成` — 未开始
- `生成中` — 进行中
- `已完成` — 已完成
- `失败` — 失败

**交互：**
- 点击格子 → 跳转单分镜生产页面
- 一键生成 → 自动按顺序生成所有分镜视频

### 3.3 单分镜生产页面

**页面布局：**

```
┌─────────────────────────────────────────────────────────┐
│  ← 返回 EP1 - 分镜 #3 视频生产                           │
├─────────────────────────────────────────────────────────┤
│  [分镜信息卡]                                            │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 镜头：中景 / 视角：平视                          │   │
│  │ 场景：明亮的办公室，落地窗透光...                 │   │
│  │ 角色：小明 / 小红                                │   │
│  │ 对话："好久不见"                                 │   │
│  └─────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│  [生产流水线]                                           │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐      │
│  │ 背景图  │→ │ 融合图  │→ │过渡融合│→ │ 视频   │      │
│  │   ✓   │  │   ✓   │  │   ✓   │  │   ⏳   │      │
│  └────────┘  └────────┘  └────────┘  └────────┘      │
├─────────────────────────────────────────────────────────┤
│  [当前步骤内容区]                                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │                                                  │   │
│  │         融合图预览                                │   │
│  │                                                  │   │
│  │  [重新生成]                    [确认并生成视频]   │   │
│  └─────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│  [上一步]                              [下一步]        │
└─────────────────────────────────────────────────────────┘
```

### 3.4 单分镜生产页面 — 各阶段说明

#### 阶段1：背景图

**界面：**
- 显示"正在生成背景图..."或加载中
- 背景图生成完成后显示预览
- 提供 [重新生成] [确认使用] 按钮

**状态：**
- `pending` — 待生成
- `generating` — 生成中
- `completed` — 已完成
- `failed` — 失败

#### 阶段2：融合图

**界面：**
- 显示融合图预览
- 提供 [重新生成] [确认使用] 按钮

**注意：** 这是最终的融合图（直接给视频生成用）

#### 阶段3：过渡融合图

**说明：** 系统自动处理（融合图 + 尾帧 → 过渡融合图），用户无需操作

**界面：**
- 显示处理中状态
- 显示生成的过渡融合图预览

#### 阶段4：视频

**界面：**
- 显示视频生成进度
- 视频生成完成后显示播放器和时长
- 提供 [重新生成] [完成并返回] 按钮

---

## 4. 数据模型

### 4.1 分镜状态

```typescript
interface PanelProductionState {
  panelIndex: number;
  episodeId: number;
  status: 'pending' | 'in_progress' | 'completed' | 'failed';

  // 背景图
  backgroundUrl: string | null;
  backgroundStatus: 'pending' | 'generating' | 'completed' | 'failed';

  // 融合图
  fusionUrl: string | null;
  fusionStatus: 'pending' | 'generating' | 'completed' | 'failed';

  // 过渡融合图（系统自动生成）
 过渡FusionUrl: string | null;
  transitionStatus: 'pending' | 'generating' | 'completed' | 'failed';

  // 视频
  videoUrl: string | null;
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoDuration: number | null; // 1-16秒

  // 尾帧（供下一个分镜使用）
  tailFrameUrl: string | null;
}
```

### 4.2 Episode 状态

```typescript
interface EpisodeProductionState {
  episodeId: number;
  panels: PanelProductionState[];
  currentPanelIndex: number; // 当前正在处理的分镜
  finalVideoUrl: string | null; // 合成后的最终视频
  synthesisStatus: 'pending' | 'pending_user_trigger' | 'synthesizing' | 'completed' | 'failed';
}
```

---

## 5. API 设计

### 5.1 现有可复用 API

| API | 用途 |
|-----|------|
| `GET /api/episodes/{id}/panel-states` | 获取所有分镜状态 |
| `POST /api/episodes/{id}/panels/{panelIndex}/generate-video` | 视频生成 |
| `GET /api/episodes/{id}/video-segments` | 获取视频片段列表 |
| `VideoProductionTask.lastFrameUrl` | 实体已有字段，用于存储尾帧URL |

### 5.2 需要新增的后端接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/episodes/{id}/panels/{panelIndex}/background` | GET | 获取单分镜背景图状态 |
| `/api/episodes/{id}/panels/{panelIndex}/background` | POST | 生成单分镜背景图 |
| `/api/episodes/{id}/panels/{panelIndex}/fusion` | GET | 获取单分镜融合图状态 |
| `/api/episodes/{id}/panels/{panelIndex}/fusion` | POST | 生成单分镜融合图 |
| `/api/episodes/{id}/panels/{panelIndex}/transition` | GET | 获取单分镜过渡融合图状态 |
| `/api/episodes/{id}/panels/{panelIndex}/transition` | POST | 生成过渡融合图（融合图+尾帧） |
| `/api/episodes/{id}/panels/{panelIndex}/tail-frame` | GET | 获取该分镜的尾帧图 |
| `/api/episodes/{id}/panels/{panelIndex}/video-task` | GET | 获取视频生成任务状态 |
| `/api/episodes/{id}/panels/{panelIndex}/produce` | POST | 单分镜一键生产（背景→融合→过渡→视频） |
| `/api/episodes/{id}/synthesize` | POST | 合成所有分镜视频 |
| `/api/episodes/{id}/auto-produce-all` | POST | 一键生成所有分镜视频 |

### 5.3 接口详细设计

#### 5.3.1 获取/生成背景图

**GET** `/api/episodes/{episodeId}/panels/{panelIndex}/background`

获取单分镜背景图状态和URL。

```
Response:
{
  "code": 200,
  "data": {
    "panelIndex": 0,
    "backgroundUrl": "https://...",  // null表示未生成
    "status": "completed",          // pending | generating | completed | failed
    "prompt": "明亮的办公室场景..."  // 使用的prompt
  }
}
```

**POST** `/api/episodes/{episodeId}/panels/{panelIndex}/background`

触发单分镜背景图生成（异步）。

```
Response:
{
  "code": 200,
  "data": {
    "status": "generating"
  }
}
```

#### 5.3.2 获取/生成融合图

**GET** `/api/episodes/{episodeId}/panels/{panelIndex}/fusion`

获取单分镜融合图状态和URL。

```
Response:
{
  "code": 200,
  "data": {
    "panelIndex": 0,
    "fusionUrl": "https://...",  // null表示未生成
    "status": "pending",         // pending | generating | completed | failed
    "referenceBackground": "https://...",  // 背景图URL
    "characterRefs": ["url1", "url2"]       // 角色参考图列表
  }
}
```

**POST** `/api/episodes/{episodeId}/panels/{panelIndex}/fusion`

触发单分镜融合图生成（异步）。

```
Request:
{
  "backgroundUrl": "https://...",          // 必填，背景图URL
  "characterRefs": ["url1", "url2"]       // 必填，角色参考图URL列表
}

Response:
{
  "code": 200,
  "data": {
    "status": "generating"
  }
}
```

#### 5.3.3 获取/生成过渡融合图

**GET** `/api/episodes/{episodeId}/panels/{panelIndex}/transition`

获取单分镜过渡融合图状态和URL。

```
Response:
{
  "code": 200,
  "data": {
    "panelIndex": 0,
    "transitionUrl": "https://...",  // null表示未生成
    "status": "pending",              // pending | generating | completed | failed
    "sourceFusionUrl": "https://...", // 源融合图
    "sourceTailFrameUrl": "https://..." // 尾帧图（第一个分镜时为null）
  }
}
```

**POST** `/api/episodes/{episodeId}/panels/{panelIndex}/transition`

触发过渡融合图生成（异步）。自动获取上一个分镜的尾帧。

```
Request:
{
  "fusionUrl": "https://..."  // 必填，融合图URL
}

Response:
{
  "code": 200,
  "data": {
    "status": "generating"
  }
}
```

**内部逻辑：**
1. 根据 `panelIndex` 获取上一个分镜 (`panelIndex - 1`) 的尾帧URL
2. 如果 `panelIndex == 0` 或无尾帧，则 `tailFrameUrl = null`
3. 调用图像生成服务，生成过渡融合图
4. 返回过渡融合图URL

#### 5.3.4 获取尾帧图

**GET** `/api/episodes/{episodeId}/panels/{panelIndex}/tail-frame`

获取该分镜的尾帧图URL（视频生成后由后端自动截取）。

```
Response:
{
  "code": 200,
  "data": {
    "panelIndex": 0,
    "tailFrameUrl": "https://...",  // null表示未生成
    "sourceVideoUrl": "https://...", // 来源视频URL
    "status": "completed"            // pending | completed
  }
}
```

**内部逻辑：**
1. 视频生成完成后，自动调用ffmpeg截取最后一帧
2. 上传到OSS，存储尾帧URL

#### 5.3.5 获取视频任务状态

**GET** `/api/episodes/{episodeId}/panels/{panelIndex}/video-task`

获取单分镜视频生成任务状态。

```
Response:
{
  "code": 200,
  "data": {
    "panelIndex": 0,
    "videoUrl": "https://...",      // null表示未完成
    "status": "generating",         // pending | generating | completed | failed
    "taskId": "task-xxx",          // 视频服务任务ID
    "duration": null,                // 时长（完成后返回）
    "errorMessage": null            // 失败时的错误信息
  }
}
```

#### 5.3.6 单分镜一键生产

**POST** `/api/episodes/{episodeId}/panels/{panelIndex}/produce`

触发单分镜的完整生产流程：背景图 → 融合图 → 过渡融合图 → 视频。

```
Request:
{
  "backgroundUrl": "https://...",          // 可选，不提供则自动生成
  "characterRefs": ["url1", "url2"]       // 可选，不提供则使用分镜中的角色
}

Response:
{
  "code": 200,
  "data": {
    "message": "单分镜生产已启动",
    "currentStage": "background"          // background | fusion | transition | video
  }
}
```

**内部逻辑：**
1. 检查上一个分镜是否完成（如果 `panelIndex > 0`）
2. 按顺序执行各阶段，每个阶段完成后自动触发下一个
3. 视频生成完成后，截取尾帧存储

#### 5.3.7 一键生成所有分镜视频

**POST** `/api/episodes/{episodeId}/auto-produce-all`

按顺序自动生成所有分镜的视频。

```
Request:
{
  "startFrom": 0  // 可选，从第几个分镜开始（用于断点续传）
}

Response:
{
  "code": 200,
  "data": {
    "message": "已开始自动生成",
    "totalPanels": 9,
    "startedFrom": 0
  }
}
```

**内部逻辑：**
```
分镜0: 背景图 → 融合图 → 过渡融合图 → 视频 → 尾帧0
分镜1: 背景图 → 融合图 → (尾帧0)过渡融合图 → 视频 → 尾帧1
分镜2: ...
```

- 每个分镜完成后才执行下一个
- 失败时停止，返回失败的分镜索引
- 支持断点续传

#### 5.3.8 合成最终视频

**POST** `/api/episodes/{episodeId}/synthesize`

将所有分镜视频合成为最终剧集视频。

```
Response:
{
  "code": 200,
  "data": {
    "finalVideoUrl": "https://...",
    "duration": 45.6,
    "totalSegments": 9,
    "status": "completed"
  }
}
```

**内部逻辑：**
1. 检查所有分镜视频是否已完成
2. 按顺序获取所有视频片段
3. 调用 `VideoCompositionService.composeVideo()`
4. 上传最终视频到OSS
5. 更新 `EpisodeProduction.finalVideoUrl`

---

## 6. 数据库设计

### 6.1 现有表结构变更

#### episode_production 表

新增字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `background_urls` | JSON/VARCHAR | 各分镜背景图URL列表 |
| `fusion_urls` | JSON/VARCHAR | 各分镜融合图URL列表 |
| `transition_urls` | JSON/VARCHAR | 各分镜过渡融合图URL列表 |
| `tail_frame_urls` | JSON/VARCHAR | 各分镜尾帧图URL列表 |

#### video_production_task 表

已有字段复用：

| 字段名 | 说明 |
|--------|------|
| `reference_image_url` | 用于存储过渡融合图URL作为视频生成的首帧参考 |
| `last_frame_url` | 存储该视频的尾帧URL，供下一个分镜使用 |

### 6.2 新增表

#### panel_production_detail 表（可选，用于更细粒度的状态跟踪）

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | BIGINT | 主键 |
| `episode_id` | BIGINT | 剧集ID |
| `panel_index` | INT | 分镜索引 |
| `background_url` | VARCHAR | 背景图URL |
| `background_status` | VARCHAR | 背景图状态 |
| `fusion_url` | VARCHAR | 融合图URL |
| `fusion_status` | VARCHAR | 融合图状态 |
| `transition_url` | VARCHAR | 过渡融合图URL |
| `transition_status` | VARCHAR | 过渡融合图状态 |
| `tail_frame_url` | VARCHAR | 尾帧图URL |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间 |

---

## 7. Service 层设计

### 7.1 新增 Service

#### PanelProductionService.java（新建）

职责：
- 单分镜的各阶段生产逻辑
- 背景图生成
- 融合图生成
- 过渡融合图生成
- 尾帧截取
- 单分镜一键生产编排

关键方法：

```java
public class PanelProductionService {

    /**
     * 生成单分镜背景图
     */
    public BackgroundResult generateBackground(Long episodeId, Integer panelIndex);

    /**
     * 生成单分镜融合图
     */
    public FusionResult generateFusion(Long episodeId, Integer panelIndex, String backgroundUrl, List<String> characterRefs);

    /**
     * 生成过渡融合图
     */
    public TransitionResult generateTransition(Long episodeId, Integer panelIndex, String fusionUrl);

    /**
     * 截取视频尾帧
     */
    public String extractTailFrame(Long episodeId, Integer panelIndex, String videoUrl);

    /**
     * 单分镜一键生产
     */
    public void produceSinglePanel(Long episodeId, Integer panelIndex, ProduceRequest request);

    /**
     * 一键生成所有分镜视频
     */
    public void produceAllPanels(Long episodeId, Integer startFrom);

    /**
     * 获取分镜尾帧（供下一个分镜使用）
     */
    public String getTailFrameUrl(Long episodeId, Integer panelIndex);
}
```

### 7.2 现有 Service 变更

#### VideoCompositionService.java（扩展）

新增方法：

```java
public class VideoCompositionService {

    /**
     * 合成所有分镜视频
     */
    public CompositionResult composeAllPanels(Long episodeId);

    /**
     * 截取视频尾帧
     */
    public String extractFrame(String videoUrl, float timePosition);
}
```

#### EpisodeProductionService.java（扩展）

新增方法：

```java
public class EpisodeProductionService {

    /**
     * 一键生成所有分镜视频
     */
    public void autoProduceAllPanels(Long episodeId, Integer startFrom);

    /**
     * 获取单分镜完整状态
     */
    public PanelDetailState getPanelDetailState(Long episodeId, Integer panelIndex);
}
```

---

## 8. 关键流程时序图

### 8.1 单分镜一键生产流程

```
前端                    Controller              PanelProductionService
 |                           |                          |
 |---POST /produce---------->|                          |
 |                           |---generateBackground---->|
 |                           |                          |---ImageService
 |                           |<--backgroundUrl----------|
 |                           |                          |
 |                           |---generateFusion-------->|
 |                           |                          |---ImageService
 |                           |<--fusionUrl-------------|
 |                           |                          |
 |                           |---getTailFrame(prev)--->|
 |                           |<--tailFrameUrl----------|
 |                           |                          |
 |                           |---generateTransition---->|
 |                           |                          |---ImageService
 |                           |<--transitionUrl---------|
 |                           |                          |
 |                           |---generateVideo-------->|
 |                           |                          |---VideoService
 |                           |<--videoTaskId----------|
 |                           |                          |
 |<--200 OK------------------|                          |
 |                           |                          |
 |---GET /video-task-------->|                          |
 |<--status: generating-----|                          |
 |                           |                          |
 |---GET /video-task-------->|                          |
 |<--status: completed------|                          |
 |                           |                          |
 |                           |---extractTailFrame------>|
 |                           |                          |---FFmpeg
 |                           |<--tailFrameUrl----------|
```

### 8.2 一键生成所有分镜流程

```
前端                    Controller              PanelProductionService
 |                           |                          |
 |---POST /auto-produce-all->|                          |
 |                           |---for panel 0 to N:------|
 |                           |    produceSinglePanel--->|
 |                           |    (await each complete) |
 |                           |                          |
 |<--200 OK------------------|                          |
 |                           |                          |
 |---GET /panel-states------>|                          |
 |<--[completed, completed..]-|
```

### 8.3 合成流程

```
前端                    Controller              VideoCompositionService
 |                           |                          |
 |---POST /synthesize------->|                          |
 |                           |---getAllVideoSegments--->|
 |                           |<--videoSegments----------|
 |                           |                          |
 |                           |---composeVideo---------->|
 |                           |                          |---FFmpeg
 |                           |<--finalVideoUrl----------|
 |                           |                          |
 |<--200 {finalVideoUrl}----|                          |
```

---

## 9. 前端组件设计

### 9.1 组件结构

```
src/pages/create/steps/
├── Step5page.tsx                    # 分镜审核页（修改）
├── Step5page.module.less
│
├── PanelProductionPage.tsx          # 新建：单分镜生产页面
├── PanelProductionPage.module.less  # 新建
│
└── components/
    ├── PanelCell.tsx                # 修改：简化为状态标签
    ├── PanelCell.module.less
    ├── PanelInfoCard.tsx           # 新建：分镜信息卡
    ├── PanelInfoCard.module.less
    ├── ProductionPipeline.tsx      # 新建：流水线指示器
    ├── ProductionPipeline.module.less
    ├── BackgroundPanel.tsx         # 新建：背景图步骤
    ├── BackgroundPanel.module.less
    ├── FusionPanel.tsx             # 新建：融合图步骤
    ├── FusionPanel.module.less
    ├── TransitionPanel.tsx          # 新建：过渡融合图步骤
    ├── TransitionPanel.module.less
    ├── VideoPanel.tsx              # 新建：视频步骤
    ├── VideoPanel.module.less
    └── SynthesisPanel.tsx          # 新建：合成控制面板
    └── SynthesisPanel.module.less
```

### 9.2 组件职责

| 组件 | 职责 |
|------|------|
| `PanelProductionPage` | 单分镜生产页面主容器，路由参数解析，阶段状态管理 |
| `PanelInfoCard` | 显示分镜基本信息（镜头、场景、角色、对话） |
| `ProductionPipeline` | 显示流水线进度（背景图→融合→过渡→视频） |
| `BackgroundPanel` | 背景图生成、预览、重新生成、确认 |
| `FusionPanel` | 融合图预览、重新生成、确认 |
| `TransitionPanel` | 过渡融合图自动处理、显示 |
| `VideoPanel` | 视频预览、进度、播放控制 |
| `SynthesisPanel` | 合成按钮、进度、结果展示 |

### 9.3 路由配置

在 React Router 中添加新路由：

```typescript
// App.tsx 或路由配置文件
<Route
  path="/project/:projectId/episode/:episodeId/panel/:panelIndex"
  element={<PanelProductionPage />}
/>
```

### 9.4 前端 API 服务

新增 `panelProductionService.ts`：

```typescript
// src/services/panelProductionService.ts

/**
 * 获取分镜背景图状态
 */
export async function getBackgroundStatus(episodeId: string, panelIndex: number) {
  return get(`/api/episodes/${episodeId}/panels/${panelIndex}/background`);
}

/**
 * 生成背景图
 */
export async function generateBackground(episodeId: string, panelIndex: number) {
  return post(`/api/episodes/${episodeId}/panels/${panelIndex}/background`);
}

/**
 * 获取融合图状态
 */
export async function getFusionStatus(episodeId: string, panelIndex: number) {
  return get(`/api/episodes/${episodeId}/panels/${panelIndex}/fusion`);
}

/**
 * 生成融合图
 */
export async function generateFusion(episodeId: string, panelIndex: number, backgroundUrl: string, characterRefs: string[]) {
  return post(`/api/episodes/${episodeId}/panels/${panelIndex}/fusion`, {
    backgroundUrl,
    characterRefs
  });
}

/**
 * 获取过渡融合图状态
 */
export async function getTransitionStatus(episodeId: string, panelIndex: number) {
  return get(`/api/episodes/${episodeId}/panels/${panelIndex}/transition`);
}

/**
 * 生成过渡融合图
 */
export async function generateTransition(episodeId: string, panelIndex: number, fusionUrl: string) {
  return post(`/api/episodes/${episodeId}/panels/${panelIndex}/transition`, { fusionUrl });
}

/**
 * 获取视频任务状态
 */
export async function getVideoTaskStatus(episodeId: string, panelIndex: number) {
  return get(`/api/episodes/${episodeId}/panels/${panelIndex}/video-task`);
}

/**
 * 单分镜一键生产
 */
export async function produceSinglePanel(episodeId: string, panelIndex: number, request?: {
  backgroundUrl?: string;
  characterRefs?: string[];
}) {
  return post(`/api/episodes/${episodeId}/panels/${panelIndex}/produce`, request || {});
}

/**
 * 一键生成所有分镜视频
 */
export async function autoProduceAll(episodeId: string, startFrom?: number) {
  return post(`/api/episodes/${episodeId}/auto-produce-all`, { startFrom: startFrom ?? 0 });
}

/**
 * 合成最终视频
 */
export async function synthesizeEpisode(episodeId: string) {
  return post(`/api/episodes/${episodeId}/synthesize`);
}
```

---

## 10. 状态管理（Zustand Store）

### 10.1 新增 Store

```typescript
// src/stores/panelProductionStore.ts

interface PanelProductionState {
  episodeId: number;
  panelIndex: number;
  // 背景图
  backgroundUrl: string | null;
  backgroundStatus: 'pending' | 'generating' | 'completed' | 'failed';
  // 融合图
  fusionUrl: string | null;
  fusionStatus: 'pending' | 'generating' | 'completed' | 'failed';
  // 过渡融合图
  transitionUrl: string | null;
  transitionStatus: 'pending' | 'generating' | 'completed' | 'failed';
  // 视频
  videoUrl: string | null;
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoDuration: number | null;
  // 尾帧
  tailFrameUrl: string | null;
  // 当前流水线阶段
  currentStage: 'background' | 'fusion' | 'transition' | 'video';
}

interface PanelProductionStore {
  // 各分镜状态
  panels: Map<number, PanelProductionState>;
  // 当前正在处理的分镜
  currentPanelIndex: number | null;
  // 操作锁（防止重复提交）
  isOperating: boolean;

  // Actions
  setPanelState: (panelIndex: number, state: Partial<PanelProductionState>) => void;
  setCurrentPanel: (panelIndex: number) => void;
  setOperating: (operating: boolean) => void;
  reset: () => void;
  loadFromServer: (episodeId: number, panelIndex: number) => Promise<void>;
}
```

### 10.2 轮询策略

| 阶段 | 轮询间隔 | API | 停止条件 |
|------|---------|-----|---------|
| 背景图生成中 | 3s | `GET /background` | status=completed/failed |
| 融合图生成中 | 3s | `GET /fusion` | status=completed/failed |
| 过渡融合图生成中 | 3s | `GET /transition` | status=completed/failed |
| 视频生成中 | 5s | `GET /video-task` | status=completed/failed |

---

## 7. 状态管理

### 7.1 Zustand Store

```typescript
interface ProductionStore {
  // 当前剧集的所有分镜状态
  panelStates: Map<number, PanelProductionState>;

  // 当前正在处理的分镜索引
  currentPanelIndex: number | null;

  // 设置分镜状态
  setPanelState: (panelIndex: number, state: Partial<PanelProductionState>) => void;

  // 更新当前分镜索引
  setCurrentPanel: (panelIndex: number) => void;

  // 重置所有状态
  reset: () => void;
}
```

### 7.2 轮询策略

| 阶段 | 轮询间隔 | API |
|------|---------|-----|
| 背景图生成中 | 3s | `GET /panels/{index}/status` |
| 融合图生成中 | 3s | `GET /panels/{index}/status` |
| 过渡融合图生成中 | 3s | `GET /panels/{index}/status` |
| 视频生成中 | 5s | `GET /panels/{index}/video-task` |

---

## 8. Page5 一键生成流程

```
用户点击"一键生成所有视频"
  │
  ├→ 分镜1:
  │    背景图生成 → 融合图生成 → 过渡融合图 → 视频 → 尾帧
  │
  ├→ 分镜2:
  │    背景图生成 → 融合图生成 → (尾帧1)过渡融合图 → 视频 → 尾帧
  │
  ├→ 分镜3:
  │    ...
  │
  └→ 分镜N:
       背景图生成 → 融合图生成 → (尾帧N-1)过渡融合图 → 视频 → 尾帧N

全部完成后，用户手动触发合成 → 最终剧集视频
```

**注意：** 每个分镜的融合图由系统自动选择（第一个可用），无需用户确认。

---

## 9. 错误处理

### 9.1 失败恢复策略

| 阶段 | 失败处理 |
|------|---------|
| 背景图失败 | 显示错误，提供 [重试] 按钮 |
| 融合图失败 | 显示错误，提供 [重试] 按钮 |
| 过渡融合图失败 | 自动重试3次，仍失败则停止流程 |
| 视频生成失败 | 显示错误，提供 [重试] 按钮 |

### 9.2 失败时的后续分镜处理

当某个分镜失败时：
- 停止后续分镜的自动生成
- 提示用户该分镜失败，等待用户处理
- 用户重试成功后，可继续一键生成剩余分镜

---

## 11. 文件改动清单

### 前端

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/pages/create/steps/Step5page.tsx` | 修改 | 简化分镜格子为状态标签，添加路由跳转 |
| `src/pages/create/steps/Step5page.module.less` | 修改 | 调整分镜格子样式 |
| `src/pages/create/steps/PanelProductionPage.tsx` | 新建 | 单分镜生产页面主容器 |
| `src/pages/create/steps/PanelProductionPage.module.less` | 新建 | 页面样式 |
| `src/pages/create/steps/components/PanelCell.tsx` | 修改 | 简化为状态标签展示 |
| `src/pages/create/steps/components/PanelCell.module.less` | 修改 | 简化样式 |
| `src/pages/create/steps/components/PanelInfoCard.tsx` | 新建 | 分镜信息卡组件 |
| `src/pages/create/steps/components/PanelInfoCard.module.less` | 新建 | 信息卡样式 |
| `src/pages/create/steps/components/ProductionPipeline.tsx` | 新建 | 流水线指示器组件 |
| `src/pages/create/steps/components/ProductionPipeline.module.less` | 新建 | 流水线样式 |
| `src/pages/create/steps/components/BackgroundPanel.tsx` | 新建 | 背景图步骤组件 |
| `src/pages/create/steps/components/BackgroundPanel.module.less` | 新建 | 背景图步骤样式 |
| `src/pages/create/steps/components/FusionPanel.tsx` | 新建 | 融合图步骤组件 |
| `src/pages/create/steps/components/FusionPanel.module.less` | 新建 | 融合图步骤样式 |
| `src/pages/create/steps/components/TransitionPanel.tsx` | 新建 | 过渡融合图步骤组件 |
| `src/pages/create/steps/components/TransitionPanel.module.less` | 新建 | 过渡融合图步骤样式 |
| `src/pages/create/steps/components/VideoPanel.tsx` | 新建 | 视频步骤组件 |
| `src/pages/create/steps/components/VideoPanel.module.less` | 新建 | 视频步骤样式 |
| `src/pages/create/steps/components/SynthesisPanel.tsx` | 新建 | 合成控制面板组件 |
| `src/pages/create/steps/components/SynthesisPanel.module.less` | 新建 | 合成面板样式 |
| `src/services/panelProductionService.ts` | 新建 | 单分镜生产API服务 |
| `src/services/types/episode.types.ts` | 修改 | 新增单分镜相关类型定义 |
| `src/stores/panelProductionStore.ts` | 新建 | Zustand状态管理 |
| `App.tsx` 或路由配置 | 修改 | 添加新路由 `/project/:projectId/episode/:episodeId/panel/:panelIndex` |

### 后端

| 文件 | 操作 | 说明 |
|------|------|------|
| `controller/EpisodeController.java` | 修改 | 新增单分镜相关接口（11个新接口） |
| `service/production/PanelProductionService.java` | 新建 | 单分镜生产核心服务 |
| `service/production/VideoCompositionService.java` | 修改 | 扩展尾帧截取方法 |
| `service/production/EpisodeProductionService.java` | 修改 | 新增autoProduceAllPanels方法 |
| `entity/EpisodeProduction.java` | 修改 | 新增背景图/融合图/过渡融合图/尾帧URL字段 |
| `dto/response/PanelBackgroundResponse.java` | 新建 | 背景图状态响应DTO |
| `dto/response/PanelFusionResponse.java` | 新建 | 融合图状态响应DTO |
| `dto/response/PanelTransitionResponse.java` | 新建 | 过渡融合图状态响应DTO |
| `dto/response/PanelVideoTaskResponse.java` | 新建 | 视频任务状态响应DTO |
| `dto/response/PanelTailFrameResponse.java` | 新建 | 尾帧图响应DTO |
| `dto/response/CompositionResultResponse.java` | 新建 | 合成结果响应DTO |
| `dto/request/ProduceRequest.java` | 新建 | 单分镜生产请求DTO |
| `dto/request/AutoProduceRequest.java` | 新建 | 一键生产请求DTO |

### 数据库

| 改动 | 说明 |
|------|------|
| `episode_production` 表 | 新增 `background_urls`、`fusion_urls`、`transition_urls`、`tail_frame_urls` JSON字段 |
| `video_production_task` 表 | 已有 `last_frame_url` 字段可复用 |

---

## 12. 验收标准

### Page5 分镜审核页
- [ ] 分镜格子显示状态标签（待生成/生成中/已完成/失败）
- [ ] 点击分镜格子跳转到单分镜生产页面
- [ ] Page5 一键生成按钮触发所有分镜按顺序生产
- [ ] Page5 自动刷新分镜状态
- [ ] 可随时返回 Page5，不阻塞操作

### 单分镜生产页面
- [ ] 正确显示分镜基本信息（镜头、场景、角色、对话）
- [ ] 流水线正确展示各阶段状态（背景图→融合图→过渡融合图→视频）
- [ ] 各阶段可独立操作（重新生成、确认）
- [ ] 点击"返回"按钮正确返回 Page5

### 背景图生成
- [ ] 可触发生成背景图
- [ ] 生成中显示进度状态
- [ ] 可预览生成的背景图
- [ ] 可重新生成背景图
- [ ] 失败时显示错误并提供重试按钮

### 融合图生成
- [ ] 可触发生成融合图
- [ ] 生成中显示进度状态
- [ ] 可预览生成的融合图
- [ ] 可重新生成融合图
- [ ] 失败时显示错误并提供重试按钮

### 过渡融合图生成
- [ ] 自动获取上一个分镜的尾帧（如有）
- [ ] 融合图+尾帧自动生成过渡融合图
- [ ] 可预览过渡融合图
- [ ] 失败时自动重试3次

### 视频生成
- [ ] 视频时长符合1-16秒要求
- [ ] 生成中显示进度状态
- [ ] 视频生成完成后自动截取尾帧
- [ ] 可预览生成的视频
- [ ] 可重新生成视频
- [ ] 失败时显示错误并提供重试按钮

### 首尾帧连续性
- [ ] 分镜2使用分镜1的尾帧作为过渡融合图输入之一
- [ ] 分镜3使用分镜2的尾帧作为过渡融合图输入之一
- [ ] 以此类推，保证所有相邻分镜间的连续性

### 一键生成所有分镜
- [ ] 按分镜顺序执行（必须等待上一个完成才执行下一个）
- [ ] 失败时停止后续分镜，提示用户
- [ ] 支持断点续传（从失败的分镜重新开始）

### 最终合成
- [ ] 用户手动触发合成
- [ ] 所有分镜视频按顺序拼接
- [ ] 最终视频可预览和下载

### 后端接口
- [ ] 所有11个新增接口可正常调用
- [ ] 数据库字段正确新增
- [ ] 尾帧截取使用ffmpeg实现
- [ ] 视频拼接使用ffmpeg实现
