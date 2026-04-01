# Step5 实时生成进度可视化

日期：2026-03-31

## 问题

Step5 在分集剧本生成（`EPISODE_SCRIPT_GENERATING`）和分镜脚本生成（`STORYBOARD_GENERATING`）期间，页面显示一个阻塞性 spinner，用户无法看到任何中间进度。九宫格生成虽然已有轮询机制，但分镜生成阶段完全没有进度反馈。整个生成过程可能持续数分钟，用户体验差。

## 方案概述

采用 SSE 实时推送方案，在现有 Redis Pub/Sub + SSE 基础设施上扩展集级事件。移除阻塞性 spinner，改为在剧集列表中嵌入加载态，让用户看到内容"逐渐填充"的过程。

## 设计

### 1. 后端 SSE 事件扩展

#### SSE 事件路由机制

现有 `ProjectSseController.sendToEmitters()` 统一使用 `event().name("status-change")` 发送所有消息。为减少改动，**保持 `status-change` 统一事件名**，前端在 `onmessage` handler 中通过 data 的 `eventType` 字段分发处理。

所有集级事件的 data 中都包含 `eventType` 字段用于前端分发。

#### 新增 3 种集级事件

所有事件通过同一 Redis channel `"project:status:" + projectId` 发布，SSE event name 统一为 `"status-change"`。

| eventType | 触发时机 | 数据结构 |
|-----------|---------|---------|
| `episode:script_done` | DeepSeek 批量返回分集剧本后（for 循环之前），对每集循环发布 | `{eventType, projectId, episodeId, episodeNum, title, totalEpisodes, completedEpisodes}` |
| `episode:storyboard_done` | 某集分镜脚本生成完成、Panels 已创建并**事务已提交**后 | `{eventType, projectId, episodeId, episodeNum, shotsCount}` |
| `episode:grid_status` | 某集九宫格状态变化 | `{eventType, projectId, episodeId, episodeNum, gridStatus}` |

#### 改动文件

**ProjectStatusBroadcaster.java**
- 新增方法 `broadcastEpisodeProgress(String projectId, String eventType, Map<String, Object> data)`
- 发布到同一 Redis channel，data 中包含 `eventType` 字段

**StoryboardService.java**
- `generateEpisodeScript()` 一次性返回所有集的 scripts，**在 for 循环之前**对每集循环发布 `episode:script_done`
- 在 for 循环内，每集分镜生成完成、Panels 创建后，使用 `TransactionSynchronizationManager.registerSynchronization` 注册回调，**事务提交后**再发布 `episode:storyboard_done`。这确保前端收到事件时数据已可查询
- 注入 `ProjectStatusBroadcaster` 依赖

**GridImageService.java**
- 注入 `ProjectStatusBroadcaster` 依赖（当前没有）
- 在 `generateGridsForEpisode()` 中，开始生成时发布 `episode:grid_status` (generating)
- 完成或失败时发布 `episode:grid_status` (generated/failed)
- 注意：该方法不在事务中（@Async），可直接发布

**ProjectSseController.java**
- 无需改动。所有消息统一以 `status-change` 事件名发送，前端通过 `eventType` 字段分发

### 2. 前端改动

#### 2.1 移除阻塞性 spinner

**Step5page.tsx**
- 删除 `isGeneratingScript` 分支的独立 return（912-926行）
- 改为在正常的剧集列表渲染中处理生成态
- 生成期间 `getEpisodes` 可能返回部分集（已创建的），正常渲染这些集

#### 2.2 SSE 订阅（全新实现）

**注意：前端当前没有任何 SSE/EventSource 代码，这是全新实现。**

现有的状态轮询（`createStore.ts` 中的 `startPolling`/`syncStatus`）保持不变，SSE 作为补充——SSE 负责实时推送增量事件，轮询在关键时刻（如 SSE 断连重连后）做全量同步。

**新增 hook：useSseProgress**

```
useSseProgress(projectId, { onEpisodeScriptDone, onEpisodeStoryboardDone, onEpisodeGridStatus, onStatusChange })
```

连接 `EventSource('/api/projects/{projectId}/status/stream')`：
- 订阅 `status-change` 事件（统一事件名）
- 从 `data.eventType` 字段分发：
  - `episode:script_done` → 回调 `onEpisodeScriptDone(episodeId, data)`
  - `episode:storyboard_done` → 回调 `onEpisodeStoryboardDone(episodeId, data)` → 内部调用 `loadPanelsForEpisode(episodeId)`
  - `episode:grid_status` → 回调 `onEpisodeGridStatus(episodeId, gridStatus)`
  - 缺少 `eventType` 或 `eventType === 'status-change'` → 回调 `onStatusChange(data)`（兼容现有的项目级状态变更）

生命周期：
- 组件挂载时创建 EventSource 连接
- 组件卸载时关闭 EventSource
- EventSource `onerror` 时浏览器自动重连
- 重连成功后（`onopen` 事件），主动调 `getEpisodes` + `getBatchProductionStatuses` 全量同步一次

#### 2.3 类型系统整合

`EpisodeProgress` 的字段直接**合并到现有 `EpisodeState`** 中，不创建独立的接口或 Map：

```typescript
// 在 types.ts 的 EpisodeState 中新增字段
interface EpisodeState {
  // ... 现有字段保持不变 ...
  episodeId: number;
  title?: string;
  gridStatus?: string;
  // 新增：
  scriptStatus?: 'pending' | 'generating' | 'done';
  storyboardStatus?: 'pending' | 'generating' | 'done';
}
```

`EpisodeCard` 直接从 `episode` prop 读取 `scriptStatus`/`storyboardStatus`，无需第二个数据源。

#### 2.4 EpisodeCard 加载态

在现有 `EpisodeCard` 渲染逻辑中增加分支：
- `scriptStatus === 'generating'` 或 `scriptStatus === 'pending'`（且项目正在生成）：集标题占位 + 旋转图标 + "分集剧本生成中..."
- `storyboardStatus === 'generating'`：集标题显示，下方显示骨架屏分镜条目（3-5个灰色脉冲条）
- `gridStatus === 'generating'`：分镜文本已显示，九宫格位置显示脉冲动画占位符
- 全部完成或无生成态字段：正常渲染（现有逻辑不变）

#### 2.5 SegmentCard 骨架屏

当 segment 数据未就绪时（`pipelineStep === 'pending'` 且正在生成中）：
- 标题行：灰色脉冲条（宽度 60-80%）
- 描述行：灰色脉冲条（宽度 40-60%）
- 九宫格区域：3x3 网格占位符，每格有淡入淡出动画

使用 CSS `@keyframes pulse` 实现脉冲效果，不需要额外依赖。

### 3. 边界情况和错误处理

#### 3.1 页面刷新/重新进入

- 页面加载时先调 `getEpisodes` 获取当前已有的集数据
- 然后连接 SSE 接收后续变更
- `getEpisodes` 返回的每集 `gridStatus` 可用于恢复九宫格状态
- `scriptStatus`/`storyboardStatus` 不持久化，页面加载时根据项目状态推导：若项目处于 `EPISODE_SCRIPT_GENERATING` 则所有未出现的集为 `pending`

#### 3.2 生成中途进入

- 同 3.1，`getEpisodes` 返回已创建的集
- SSE 接管后续更新

#### 3.3 SSE 断连

- EventSource 自动重连（浏览器内置行为）
- 重连成功后（`onopen` 事件），主动调 `getEpisodes` + `getBatchProductionStatuses` 全量同步
- 不需要后端补偿机制
- 多标签页场景：`ProjectSseController` 的 `CopyOnWriteArraySet<SseEmitter>` 已支持多连接，无需额外处理

#### 3.4 生成完成

- 项目状态推进到 `STORYBOARD_REVIEW` 时，通过 `status-change` 事件（无 `eventType` 字段）触发
- 前端进入正常审核流程（现有逻辑）
- EventSource 保持连接，用于后续九宫格/视频状态更新

## 不做的事

- 不做页级九宫格进度（只做到集级）
- 不做预估剩余时间
- 不修改现有的视频生成轮询逻辑
- 不引入新的依赖库
- 不替换现有的轮询机制（SSE 和轮询并存）
