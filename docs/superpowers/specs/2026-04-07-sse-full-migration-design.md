# SSE 全面迁移：消除前端所有轮询

**日期**: 2026-04-07
**状态**: Draft

## 问题

项目当前采用 SSE + 轮询混合模式推送异步操作状态。前端在 4 个文件中共存在 7 个轮询模式：

1. `createStore.ts` — `startPolling()` 递归 setTimeout（3s/5s）
2. `Step2page.tsx` — `fetchScriptWithPolling()` 递归 setTimeout（2s，最多 45 次）
3. `Step3Merged.tsx` — `setInterval` 角色数据轮询（3s）
4. `Step4Production.tsx` — `startScriptPolling()` 脚本 setInterval（5s）
5. `Step4Production.tsx` — `pollGrid()` 九宫格 for 循环（5s，最多 60 次）
6. `Step4Production.tsx` — `poll()` 视频生成 while 循环（3s，最多 720 次）
7. `Step2page.tsx` — `handleSaveOutlineWithAI()` 触发同上的 `fetchScriptWithPolling()`

这导致：

- 冗余网络请求（每 2-5 秒一次轮询）
- 轮询间隔内的数据滞后
- SSE 事件与轮询响应之间的状态竞争
- 复杂的状态同步逻辑

## 决策

**彻底消除所有轮询。** SSE 成为异步状态的唯一数据源。前端 zustand store 直接消费 SSE 事件。

## 方案选择

**方案 A（选定）：zustand store 直连 SSE。**

新建 `useSseStatusStore`，替代：
- `createStore.ts` 中的轮询逻辑
- `useSseProgress.ts` hook
- 页面组件中所有内联的 `setInterval` / `setTimeout` 轮询

淘汰方案：
- **方案 B**：保留 `createStore`，SSE hook 驱动更新 — 同步状态与 UI 状态混在一个 store 中，职责不清
- **方案 C**：SWR/React Query subscription — 引入新依赖，与现有 zustand 架构不一致

## 设计

### 第 1 部分：后端新增 SSE 事件

#### 新增事件类型

> **重要**：所有事件均使用 SSE event name `status-change`，通过 JSON payload 中的 `eventType` 字段区分。`snapshot` 事件同样如此：`emitter.send(SseEmitter.event().name("status-change").data(snapshotJson))`。

| eventType | 触发时机 | payload |
|-----------|---------|---------|
| `snapshot` | SSE 连接建立时 | 完整 `ProjectStatusResponse` JSON（见下方） |
| `panel:merge_done` | `PanelService.mergeAudio()` 完成 | `{ episodeId, panelId, mergedVideoUrl }` |
| `panel:merge_failed` | `PanelService.mergeAudio()` 失败 | `{ episodeId, panelId, error }` |
| `episode:compose_done` | `PanelService.composeEpisode()` 完成 | `{ episodeId, composedVideoUrl }` |
| `episode:compose_failed` | `PanelService.composeEpisode()` 失败 | `{ episodeId, error }` |
| `panel:grid_status` | `GridImageService.generateGridsForPanel()` 状态变化 | `{ episodeId, panelId, gridStatus }` |
| `character:generation_progress` | `CharacterImageGenerationService` 单角色进度 | `{ charId, stage, status }`（见下方详述） |

> **注意**：`panel:grid_status` 是单 Panel 九宫格事件，区别于已有的 `episode:grid_status`（集级别九宫格）。

#### snapshot 事件

SSE 连接建立时，`ProjectSseController` 注入 `ProjectService` 并调用 `projectService.getProjectStatusDetail(projectId)` 同步构建 `ProjectStatusResponse`，立即发送 `snapshot` 事件：

```json
{
  "eventType": "snapshot",
  "projectId": "...",
  "timestamp": 1712476800000,
  "data": {
    "projectId": "...",
    "statusCode": "GENERATING_PANELS",
    "statusDescription": "...",
    "currentStep": 4,
    "isGenerating": true,
    "isFailed": false,
    "isReview": false,
    "completedSteps": [1, 2, 3],
    "availableActions": ["start_production"],
    "productionProgress": 45,
    "productionSubStage": "generating_videos",
    "panelCurrentEpisode": 1,
    "panelTotalEpisodes": 3,
    "panelReviewEpisodeId": null,
    "panelAllConfirmed": false,
    "finalVideoUrl": null,
    "mergeStatus": "idle",
    "generatingTaskType": "video",
    "errorMessage": null
  }
}
```

数据结构复用现有 `GET /api/projects/{projectId}/status` 响应的全部字段。以上为代表性示例，实际包含 `ProjectStatusResponse` 的所有字段。

#### 事件发布

所有新事件统一通过 `StateChangeEventPublisher` 发布到 Redis channel `project:status:{projectId}`。复用现有 `ProjectSseController` 的监听和转发逻辑，不创建新的发布通道。

#### 新增 Publish 方法（6 个）

`StateChangeEventPublisher` 中需要新增以下方法（现有方法保持不变）：

```java
void publishPanelMergeDone(String projectId, Long episodeId, Long panelId, String mergedVideoUrl);
void publishPanelMergeFailed(String projectId, Long episodeId, Long panelId, String error);
void publishEpisodeComposeDone(String projectId, Long episodeId, String composedVideoUrl);
void publishEpisodeComposeFailed(String projectId, Long episodeId, String error);
void publishPanelGridStatus(String projectId, Long episodeId, Long panelId, String gridStatus);
void publishCharacterGenerationProgress(String projectId, Long charId, String stage, String status);
```

#### character:generation_progress 事件定义

- `stage` 取值：`"threeView"` | `"expression"` | `"allDone"`
- `status` 取值：`"generating"` | `"completed"` | `"failed"`
- 该事件仅作为变更通知。前端收到后需调用 REST API `GET /characters/{charId}/status` 获取完整角色数据（包括图片 URL、错误信息等），用于更新 UI。

#### 后端改动

| 文件 | 改动 |
|------|------|
| `StateChangeEventPublisher.java` | 新增 6 个 publish 方法（见上方列表） |
| `ProjectSseController.java` | 注入 `ProjectService`，连接时发送 `snapshot` 事件（调用 `getProjectStatusDetail`） |
| `PanelService.java` | `mergeAudio()` 和 `composeEpisode()` 完成或失败时调用新增 publish 方法 |
| `GridImageService.java` | `generateGridsForPanel()` 状态变化时调用 `publishPanelGridStatus()` |
| `CharacterImageGenerationService.java` | 单角色图片生成进度变化时调用 `publishCharacterGenerationProgress()` |

> **注意**：`PanelProductionService.java` 已有 `publishPanelVideoDone` 和 `publishPanelVideoFailed` 调用，无需改动。前端视频轮询的消除完全由删除 `Step4Production.tsx` 中的 `poll()` while 循环实现。

### 第 2 部分：前端状态管理重构

#### 新建 `useSseStatusStore`

替代 `createStore` 轮询 + `useSseProgress` hook，合并为单一 zustand store：

```typescript
interface SseStatusState {
  // SSE 连接状态
  connected: boolean;

  // 项目状态快照（从 snapshot 事件初始化，类型为前端 ProjectStatusInfo）
  statusInfo: ProjectStatusInfo | null;

  // 增量更新的细粒度实体状态
  episodes: Record<number, EpisodeStatus>;
  panels: Record<number, PanelStatus>;
  characters: Record<number, CharacterStatus>;

  // 操作方法
  connect: (projectId: string, token: string) => void;
  disconnect: () => void;
}
```

#### 事件处理逻辑

| 事件类型 | Store 操作 |
|---------|-----------|
| `snapshot` | `set({ statusInfo, episodes, panels, characters })` — 全量覆盖 |
| `milestone-change` / `task-complete` / `failure` / `progress` | Patch `statusInfo` 对应字段 |
| `panel:video_done` / `video_failed` / `tts_done` / `tts_failed` / `merge_done` / `merge_failed` / `grid_status` | Patch `panels[panelId]` |
| `episode:script_done` / `panel_done` / `grid_status` / `compose_done` / `compose_failed` | Patch `episodes[episodeId]` |
| `character:generation_progress` | Patch `characters[charId]` |

#### 需要删除的代码

| 文件 | 删除内容 |
|------|---------|
| `createStore.ts` | `startPolling()`、`stopPolling()`、`pollingTimerId` 及相关状态（**保留** `syncStatus()` 等一次性 REST 调用方法） |
| `Step2page.tsx` | `fetchScriptWithPolling()` 递归 setTimeout、`pollingRef` |
| `Step3Merged.tsx` | 角色数据 `setInterval` 轮询、`pollingRef`、`startPolling()`、`stopPolling()` |
| `Step4Production.tsx` | 视频生成 while 循环轮询、九宫格 for 循环轮询、脚本 `setInterval` |
| `useSseProgress.ts` | 整个文件（逻辑合并进 store） |

#### UI 组件接入

| 组件 | 订阅内容 |
|------|---------|
| `CreateLayout` | `statusInfo.isGenerating`、`statusInfo.currentStep`、`statusInfo.completedSteps` |
| `Step2page` | `statusInfo`（重连逻辑移入 store） |
| `Step3Merged` | `characters` map |
| `Step4Production` | `panels` map、`episodes` map |

### 第 3 部分：错误处理与边界情况

#### SSE 连接失败

- EventSource 浏览器自动重连，`onerror` 不做特殊处理
- 重连成功后（非首次 `onopen`）→ 后端推送 snapshot → 前端覆盖 state

#### 后端 Emitter 生命周期

- 现有 5 分钟超时保持不变
- 浏览器重连创建新 emitter，旧 emitter 在 onCompletion/onTimeout 中自动清理
- 不会出现"重连后旧 emitter 还在发"的问题

#### snapshot 时序

- 后端在连接建立时同步构建 `ProjectStatusResponse`
- snapshot 反映连接瞬间的状态。如果此时有异步写操作正在进行（如 `@Async` 方法正在更新 `episodeInfo`），snapshot 可能包含部分陈旧数据，但后续增量事件会在毫秒内将前端更新到正确状态
- 不需要等待 — 前端拿到 snapshot 后，后续增量事件持续更新

#### 多 Tab 场景

- 每个 Tab 建立独立的 SSE 连接和 emitter
- 后端通过 Redis Pub/Sub 广播，所有连接都会收到事件
- 现有行为，不需要改变

#### 向后兼容

- REST API 端点（`getProjectStatus`、`getBatchProductionStatuses`、`getEpisodes`、角色状态等）**保留不删**
- 仍可用于手动刷新、调试或未来其他场景
- 前端不再定时调用它们

#### 保留的一次性 REST 调用

以下 REST 调用由用户操作触发（非轮询），**不应删除**：

| 调用 | 位置 | 触发时机 |
|------|------|---------|
| `syncStatus(projectId)` | `Step2page.tsx:393` | 确认剧本后 |
| `refreshScript()` | `Step2page.tsx:303` | 确认剧本后 |
| `syncStatus(projectId)` | `Step3Merged.tsx:160` | 页面初始加载 |
| `syncStatus(projectId)` | `Step4Production.tsx:765` | 状态变更时 |

`createStore.ts` 中的 `syncStatus()` 方法保留，仅删除 `startPolling()`/`stopPolling()` 及定时器逻辑。

#### SSE 认证

由于 `EventSource` API 不支持自定义 header，token 通过 query 参数传递（与现有实现一致）：`?token=xxx`。后端需有对应的 filter 从 query 参数中读取 token 进行认证。