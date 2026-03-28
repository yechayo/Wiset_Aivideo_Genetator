# Step 5 全手动生产流程 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Step 5 的生产流程从后端自动串联改为全手动触发，前端每个生产步骤（背景图→四宫格漫画→视频）都由用户点击按钮触发，并实时显示从后端获取的真实生产状态。

**Architecture:** 后端 `PanelProductionService` 移除自动编排器（`startOrResume`/`drivePanelStep`）和所有 `self().startOrResume()` 回调调用。Controller 保留所有独立的手动触发接口。前端 `episodeService.ts` 补齐缺失的 API 调用，`Step5page.tsx` 对接真实生产状态，替换空回调函数。

**Tech Stack:** Java Spring Boot (后端), React + TypeScript (前端), REST API

---

## 文件清单

### 后端修改
| 文件 | 操作 | 职责 |
|------|------|------|
| `backend/.../service/production/PanelProductionService.java` | 修改 | 移除自动编排逻辑 |
| `backend/.../controller/PanelController.java` | 修改 | 移除 Controller 中对 startOrResume 的调用 |

### 前端修改
| 文件 | 操作 | 职责 |
|------|------|------|
| `frontend/.../services/types/episode.types.ts` | 修改 | 添加生产状态 DTO 类型 |
| `frontend/.../services/episodeService.ts` | 修改 | 补齐 8 个缺失 API + 清理废弃 API |
| `frontend/.../pages/create/steps/Step5page.tsx` | 修改 | 对接真实生产状态 + 实现回调 |

---

## Task 1: 后端 — 移除 PanelProductionService 自动编排逻辑

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: 删除 `startOrResume` 方法**

删除整个 `startOrResume(String projectId)` 方法（约第 98-120 行）。该方法包含 Redis 分布式锁、查找未完成 Panel、调用 `drivePanelStep` 的逻辑。

- [ ] **Step 2: 删除 `findNextIncompletePanel` 方法**

删除整个 `findNextIncompletePanel(String projectId)` 方法（约第 122-135 行）。此方法仅被 `startOrResume` 调用。

- [ ] **Step 3: 删除 `drivePanelStep` 方法**

删除整个 `drivePanelStep(String projectId, Panel panel)` 方法（约第 145-200 行）。此方法是自动编排的核心决策逻辑。

- [ ] **Step 4: 删除 `isPanelVideoCompleted` 方法**

删除整个 `isPanelVideoCompleted(Panel panel)` 方法（约第 202-207 行）。仅被 `startOrResume` 调用。

- [ ] **Step 5: 移除 `doGenerateBackgroundByPanelId` 末尾的自动串联调用**

在 `doGenerateBackgroundByPanelId` 方法末尾，删除以下代码块：
```java
// Resume orchestrator after background completes
String projId = getProjectIdByPanelId(panelId);
if (projId != null) {
    broadcaster.broadcast(projId, "PRODUCING", "PRODUCING");
    self().startOrResume(projId);
}
```
保留 `broadcaster.broadcast` 调用（用于 SSE 通知前端），仅删除 `self().startOrResume(projId)` 这一行。

- [ ] **Step 6: 移除 `pollNewVideoTask` 中视频完成后的自动串联调用**

在 `pollNewVideoTask` 方法的 `"completed"` case 中，删除以下代码块：
```java
// Resume orchestrator after video completes
String projId = getProjectIdByPanelId(panelId);
if (projId != null) {
    broadcaster.broadcast(projId, "PRODUCING", "PRODUCING");
    self().startOrResume(projId);
}
```
同样保留 `broadcaster.broadcast` 调用，仅删除 `self().startOrResume(projId)` 这一行。

- [ ] **Step 7: 清理不再需要的 import**

如果删除上述方法后，以下 import 不再被使用，则删除：
- `com.comic.service.pipeline.PipelineService`（`pipelineService` 仅在 `startOrResume` 中使用）
- `java.util.concurrent.TimeUnit`（仅在 `startOrResume` 的 Redis 锁中使用）

注意：`self()` 方法本身仍被 `doGenerateBackgroundByPanelId` 和 `doGenerateVideoByPanelId` 用于 `@Async` 代理，所以 `ApplicationContext` import 保留。

- [ ] **Step 8: 编译验证**

Run: `cd d:/wiset/Wiset_Aivideo_Genetator/backend && mvn compile -q`
Expected: BUILD SUCCESS

---

## Task 2: 后端 — 移除 Controller 中的 startOrResume 调用

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/PanelController.java`

- [ ] **Step 1: 移除 `approveComic` 接口中的自动串联**

在 `approveComic` 方法中，删除 `panelProductionService.startOrResume(projectId);` 这一行。

修改前：
```java
@PostMapping("/{panelId}/comic/approve")
public Result<Void> approveComic(...) {
    comicGenerationService.approveComic(panelId);
    panelProductionService.startOrResume(projectId);
    return Result.ok();
}
```

修改后：
```java
@PostMapping("/{panelId}/comic/approve")
public Result<Void> approveComic(...) {
    comicGenerationService.approveComic(panelId);
    return Result.ok();
}
```

- [ ] **Step 2: 移除 `retryVideo` 接口中的自动串联**

在 `retryVideo` 方法中，删除 `panelProductionService.startOrResume(projectId);` 这一行。

修改前：
```java
@PostMapping("/{panelId}/video/retry")
public Result<Void> retryVideo(...) {
    panelProductionService.retryVideoByPanelId(panelId);
    panelProductionService.startOrResume(projectId);
    return Result.ok();
}
```

修改后：
```java
@PostMapping("/{panelId}/video/retry")
public Result<Void> retryVideo(...) {
    panelProductionService.retryVideoByPanelId(panelId);
    return Result.ok();
}
```

- [ ] **Step 3: 编译验证**

Run: `cd d:/wiset/Wiset_Aivideo_Genetator/backend && mvn compile -q`
Expected: BUILD SUCCESS

---

## Task 3: 前端 — 添加生产状态 TypeScript 类型

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/episode.types.ts`

- [ ] **Step 1: 添加 Panel 生产状态类型**

在文件末尾添加以下类型定义（与后端 DTO 字段一一对应）：

```typescript
/** 单 Panel 完整生产状态（对应后端 PanelProductionStatusResponse） */
export interface PanelProductionStatusResponse {
  panelId: number;
  overallStatus: string;
  currentStage: string;
  backgroundStatus: string;
  backgroundUrl: string | null;
  comicStatus: string;
  comicUrl: string | null;
  videoStatus: string;
  videoUrl: string | null;
  videoDuration: number | null;
  errorMessage: string | null;
}

/** 四宫格漫画状态（对应后端 ComicStatusResponse） */
export interface ComicStatusResponse {
  panelId: number;
  status: string;
  comicUrl: string | null;
  backgroundUrl: string | null;
  errorMessage: string | null;
}

/** 视频状态（对应后端 VideoStatusResponse） */
export interface VideoStatusResponse {
  panelId: number;
  status: string;
  videoUrl: string | null;
  taskId: string | null;
  errorMessage: string | null;
  duration: number | null;
}

/** 背景图状态（对应后端 PanelBackgroundResponse） */
export interface PanelBackgroundResponse {
  panelId: number;
  panelIndex: number;
  backgroundUrl: string | null;
  status: string;
  prompt: string | null;
}
```

---

## Task 4: 前端 — 补齐缺失 API 调用 + 清理废弃 API

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts`

- [ ] **Step 1: 添加缺失的 API 函数**

在文件末尾（`revisePanel` 之后）添加以下函数：

```typescript
// ================= Panel 生产状态 API =================

/** 获取单 Panel 完整生产状态 */
export async function getPanelProductionStatus(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<PanelProductionStatusResponse>> {
  return get<ApiResponse<PanelProductionStatusResponse>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/production-status`,
  );
}

/** 批量获取所有 Panel 生产状态 */
export async function getBatchProductionStatuses(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<PanelProductionStatusResponse[]>> {
  return get<ApiResponse<PanelProductionStatusResponse[]>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/production-statuses`,
  );
}

/** 获取四宫格漫画状态 */
export async function getComicStatus(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<ComicStatusResponse>> {
  return get<ApiResponse<ComicStatusResponse>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic`,
  );
}

/** 生成四宫格漫画 */
export async function generateComic(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic`,
  );
}

/** 审核通过四宫格漫画 */
export async function approveComic(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic/approve`,
  );
}

/** 退回重生成四宫格漫画 */
export async function reviseComic(
  projectId: string,
  episodeId: number,
  panelId: number,
  feedback: string,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic/revise`,
    { feedback },
  );
}

/** 生成视频 */
export async function generateVideo(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video`,
  );
}

/** 重试失败的视频生成 */
export async function retryVideo(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video/retry`,
  );
}

/** 重新生成背景图 */
export async function regenerateBackground(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/background/regenerate`,
  );
}
```

- [ ] **Step 2: 更新 import 语句**

在文件顶部的 import 块中，添加新的类型 import：

```typescript
import type {
  ProductionStatusResponse,
  ProductionPipelineResponse,
  GridInfoResponse,
  SplitGridPageResponse,
  VideoSegmentInfo,
  PanelState,
  PanelProductionStatusResponse,
  ComicStatusResponse,
  VideoStatusResponse,
  PanelBackgroundResponse,
} from './types/episode.types';
```

- [ ] **Step 3: 删除废弃的 API 函数**

删除以下不再使用的旧 API 函数（它们指向已不存在的后端 Controller 端点）：

1. `getProductionStatus` — 指向 `/api/episodes/{id}/production-status`（旧路由）
2. `getProductionPipeline` — 指向 `/api/episodes/project/{id}/pipeline`（旧路由）
3. `getGridInfo` — 指向 `/api/episodes/{id}/grid-info`（旧路由）
4. `uploadFusionImage` — 指向 `/api/episodes/{id}/fusion-image`（旧路由）
5. `submitFusion` — 指向 `/api/episodes/{id}/submit-fusion`（旧路由）
6. `submitFusionPage` — 指向 `/api/episodes/{id}/submit-fusion-page`（旧路由）
7. `splitGridPage` — 指向 `/api/episodes/{id}/split-grid-page`（旧路由）
8. `getVideoSegments` — 指向 `/api/episodes/{id}/video-segments`（旧路由）
9. `retryProduction` — 指向 `/api/episodes/{id}/retry-production`（旧路由）
10. `getPanelStates` — 指向 `/api/episodes/{id}/panel-states`（旧路由）
11. `generateSinglePanelVideo` — 指向 `/api/episodes/{id}/panels/{index}/generate-video`（旧路由）
12. `autoContinue` — 指向 `/api/episodes/{id}/auto-continue`（旧路由）
13. `submitFusionPageWithAuto` — 指向 `/api/episodes/{id}/submit-fusion-page`（旧路由）
14. `regenerateSceneImage` — 指向 `/api/episodes/{id}/panels/{index}/regenerate-scene`（旧路由）

同时清理 import 中不再使用的类型：`ProductionStatusResponse`, `ProductionPipelineResponse`, `GridInfoResponse`, `SplitGridPageResponse`, `VideoSegmentInfo`, `PanelState`。

- [ ] **Step 4: 验证 TypeScript 编译**

Run: `cd d:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | head -30`
Expected: 无 episodeService 相关的类型错误

---

## Task 5: 前端 — Step5page 对接真实生产状态

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

### 5.1: 更新 import

- [ ] **Step 1: 添加新的 API import**

在文件顶部 import 语句中，添加：

```typescript
import {
  getEpisodes,
  getPanels,
  generatePanels,
  getPanelGenerateStatus,
  generateBackground,
  revisePanel,
  getBatchProductionStatuses,
  getComicStatus,
  generateComic,
  approveComic,
  reviseComic,
  generateVideo,
  retryVideo,
  regenerateBackground,
} from '../../../services/episodeService';
```

替换原有的 `import { getEpisodes, getPanels, generatePanels, getPanelGenerateStatus, generateBackground, revisePanel } from '../../../services/episodeService';`。

### 5.2: 添加生产状态映射函数

- [ ] **Step 2: 添加 `mapProductionToPipelineStep` 辅助函数**

在 `Step5page` 组件定义之前添加：

```typescript
/**
 * 将后端 PanelProductionStatusResponse 映射为前端 SegmentPipelineStep
 */
function mapProductionToPipelineStep(status: {
  backgroundStatus: string;
  backgroundUrl: string | null;
  comicStatus: string;
  videoStatus: string;
}): SegmentPipelineStep {
  // 视频完成
  if (status.videoStatus === 'completed') return 'video_completed';
  // 视频失败
  if (status.videoStatus === 'failed') return 'video_failed';
  // 视频生成中
  if (status.videoStatus === 'generating') return 'video_generating';
  // 四宫格已审核 → 可以生成视频
  if (status.comicStatus === 'approved') return 'comic_approved';
  // 四宫格待审核
  if (status.comicStatus === 'pending_review') return 'comic_review';
  // 四宫格生成中
  if (status.comicStatus === 'generating') return 'comic_review';
  // 四宫格失败
  if (status.comicStatus === 'failed') return 'comic_review';
  // 背景图完成
  if (status.backgroundUrl) return 'scene_ready';
  // 背景图生成中
  if (status.backgroundStatus === 'generating') return 'scene_ready';
  // 默认
  return 'pending';
}
```

需要在文件顶部 import `SegmentPipelineStep`：

```typescript
import type {
  ChapterState,
  EpisodeState,
  SegmentState,
  ExpansionState,
  SegmentPipelineStep,
} from './types';
```

### 5.3: 添加生产状态刷新逻辑

- [ ] **Step 3: 添加 `refreshProductionStatuses` 回调**

在 `Step5page` 组件内部，`handleRevisePanel` 之后添加：

```typescript
/**
 * 刷新某集所有 Panel 的生产状态
 */
const refreshProductionStatuses = useCallback(async (episodeId: number) => {
  if (!projectId) return;
  try {
    const res = await getBatchProductionStatuses(projectId, episodeId);
    if ((res.code !== 0 && res.code !== 200) || !res.data) return;

    const statusMap = new Map<number, PanelProductionStatusResponse>();
    res.data.forEach(s => statusMap.set(s.panelId, s));

    setChapters(prev =>
      prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                segments: ep.segments.map(seg => {
                  const panelId = Number(seg.panelData?.panelId);
                  const status = statusMap.get(panelId);
                  if (!status) return seg;

                  return {
                    ...seg,
                    pipelineStep: mapProductionToPipelineStep(status),
                    comicUrl: status.comicUrl ?? seg.comicUrl,
                    videoUrl: status.videoUrl ?? seg.videoUrl,
                    sceneThumbnail: status.backgroundUrl ?? seg.sceneThumbnail,
                  };
                }),
              }
            : ep
        ),
      }))
    );
  } catch (err) {
    console.error('刷新生产状态失败:', err);
  }
}, [projectId]);
```

需要在文件顶部添加 `PanelProductionStatusResponse` 的 import（从 episodeService 的类型文件或直接 import）：

```typescript
import type { PanelProductionStatusResponse } from '../../../services/types/episode.types';
```

### 5.4: 在展开集数时加载生产状态

- [ ] **Step 4: 修改展开集数的 useEffect，加载完分镜后刷新生产状态**

找到现有的：
```typescript
useEffect(() => {
  if (expansion.expandedEpisodeId !== null) {
    loadPanelsForEpisode(expansion.expandedEpisodeId);
  }
}, [expansion.expandedEpisodeId, loadPanelsForEpisode]);
```

替换为：
```typescript
useEffect(() => {
  if (expansion.expandedEpisodeId !== null) {
    loadPanelsForEpisode(expansion.expandedEpisodeId);
    refreshProductionStatuses(expansion.expandedEpisodeId);
  }
}, [expansion.expandedEpisodeId, loadPanelsForEpisode, refreshProductionStatuses]);
```

### 5.5: 实现核心回调函数

- [ ] **Step 5: 实现 `onSegmentApprove` 回调**

在 `Step5page` 组件内部添加：

```typescript
/**
 * 审核通过四宫格漫画
 */
const handleApproveComic = useCallback(async (episodeId: number, panelId: string) => {
  if (!projectId) return;
  try {
    await approveComic(projectId, episodeId, Number(panelId));
    await refreshProductionStatuses(episodeId);
  } catch (err: any) {
    alert(err?.message || '审核失败');
  }
}, [projectId, refreshProductionStatuses]);
```

- [ ] **Step 6: 实现 `onSegmentRegenerate` 回调**

```typescript
/**
 * 退回重生成四宫格漫画
 */
const handleRegenerateComic = useCallback(async (episodeId: number, panelId: string, feedback: string) => {
  if (!projectId) return;
  try {
    await reviseComic(projectId, episodeId, Number(panelId), feedback);
    // 轮询直到生成完成（变为 pending_review 或 failed）
    const poll = async () => {
      for (let i = 0; i < 60; i++) {
        await new Promise(r => setTimeout(r, 3000));
        await refreshProductionStatuses(episodeId);
        // 检查当前状态
        const chaps = await new Promise<ChapterState[]>(resolve => setChapters(prev => { resolve(prev); return prev; }));
        const ep = chaps.flatMap(c => c.episodes).find(e => e.episodeId === episodeId);
        const seg = ep?.segments.find(s => s.panelData?.panelId === panelId);
        if (seg?.pipelineStep === 'comic_review') return; // pending_review → comic_review
        if (seg?.pipelineStep === 'video_failed') return;  // failed
      }
    };
    poll();
  } catch (err: any) {
    alert(err?.message || '重新生成失败');
  }
}, [projectId, refreshProductionStatuses]);
```

- [ ] **Step 7: 实现 `onSegmentGenerateVideo` 回调**

```typescript
/**
 * 生成视频
 */
const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string) => {
  if (!projectId) return;
  try {
    await generateVideo(projectId, episodeId, Number(panelId));
    // 轮询直到视频完成
    const poll = async () => {
      for (let i = 0; i < 120; i++) {
        await new Promise(r => setTimeout(r, 5000));
        await refreshProductionStatuses(episodeId);
        const chaps = await new Promise<ChapterState[]>(resolve => setChapters(prev => { resolve(prev); return prev; }));
        const ep = chaps.flatMap(c => c.episodes).find(e => e.episodeId === episodeId);
        const seg = ep?.segments.find(s => s.panelData?.panelId === panelId);
        if (seg?.pipelineStep === 'video_completed' || seg?.pipelineStep === 'video_failed') return;
      }
    };
    poll();
  } catch (err: any) {
    alert(err?.message || '生成视频失败');
  }
}, [projectId, refreshProductionStatuses]);
```

### 5.6: 修改 `renderEpisodeCard` 传递真实回调

- [ ] **Step 8: 修改 EpisodeCard 的 props 传递**

找到 `renderEpisodeCard` 方法中的：
```typescript
onSegmentApprove={() => {}}
onSegmentRegenerate={() => {}}
onSegmentGenerateVideo={() => {}}
```

替换为（注意需要从 EpisodeCard 内部传出 episodeId 和 panelId）：

这里需要检查 `EpisodeCard` 组件的 props 接口，确认回调的参数签名。根据 `SegmentCard` 组件，回调签名是：
- `onApprove: () => void`
- `onRegenerate: (feedback: string) => void`
- `onGenerateVideo: () => void`

这些回调是在 `EpisodeCard` 内部传递给 `SegmentCard` 的，不包含 episodeId/panelId 参数。因此需要修改回调，让 `Step5page` 在渲染时绑定具体参数：

```typescript
onSegmentApprove={(segmentKey) => {
  const [epId, segIdx] = segmentKey.split('-').map(Number);
  const panelId = episode.segments[segIdx]?.panelData?.panelId;
  if (panelId) handleApproveComic(epId, panelId);
}}
onSegmentRegenerate={(segmentKey, feedback) => {
  const [epId, segIdx] = segmentKey.split('-').map(Number);
  const panelId = episode.segments[segIdx]?.panelData?.panelId;
  if (panelId) handleRegenerateComic(epId, panelId, feedback);
}}
onSegmentGenerateVideo={(segmentKey) => {
  const [epId, segIdx] = segmentKey.split('-').map(Number);
  const panelId = episode.segments[segIdx]?.panelData?.panelId;
  if (panelId) handleGenerateVideo(epId, panelId);
}}
```

**注意：** 这需要同步修改 `EpisodeCard` 和 `SegmentCard` 的 props 接口，将回调签名从 `() => void` 改为 `(segmentKey: string) => void` 等。详见 Task 6。

**如果回调签名改动太大**，可以使用闭包方式：在 `EpisodeCard` 的 `segments.map` 内部直接绑定。需要查看 `EpisodeCard` 源码确认当前结构。

### 5.7: 修改背景图生成后刷新状态

- [ ] **Step 9: 修改 `handleGenerateBackground`，生成后刷新生产状态**

找到现有的 `handleGenerateBackground` 方法，在 `finally` 块之前添加状态刷新：

```typescript
const handleGenerateBackground = useCallback(async (episodeId: number, panelId: string) => {
  if (!projectId || !panelId) return;
  setGeneratingBackgroundPanelId(panelId);
  try {
    const res = await generateBackground(projectId, episodeId, Number(panelId));
    if (res.code !== 0 && res.code !== 200) {
      alert(res.message || '生成背景图失败');
    }
    // 轮询背景图生成状态
    const poll = async () => {
      for (let i = 0; i < 40; i++) {
        await new Promise(r => setTimeout(r, 3000));
        await refreshProductionStatuses(episodeId);
        const chaps = await new Promise<ChapterState[]>(resolve => setChapters(prev => { resolve(prev); return prev; }));
        const ep = chaps.flatMap(c => c.episodes).find(e => e.episodeId === episodeId);
        const seg = ep?.segments.find(s => s.panelData?.panelId === panelId);
        if (seg?.pipelineStep === 'scene_ready' || seg?.pipelineStep === 'comic_review') return;
        if (seg?.pipelineStep === 'video_failed') return;
      }
    };
    poll();
  } catch (err: any) {
    alert(err?.message || '生成背景图失败');
  } finally {
    setGeneratingBackgroundPanelId(null);
  }
}, [projectId, refreshProductionStatuses]);
```

### 5.8: 修改 `loadPanelsForEpisode` 中的硬编码状态

- [ ] **Step 10: 移除 `pipelineStep: 'pending'` 硬编码**

找到 `loadPanelsForEpisode` 中的 segments 映射：

```typescript
return {
  segmentIndex: idx,
  title: `分镜 ${idx + 1}`,
  synopsis: info.composition || '',
  sceneThumbnail: info.backgroundUrl || null,
  characterAvatars,
  pipelineStep: 'pending' as const,  // ← 这里硬编码
  comicUrl: null,                    // ← 这里硬编码
  videoUrl: null,                    // ← 这里硬编码
  feedback: '',
  panelData: { ... },
};
```

改为从 panelInfo 中读取真实状态：

```typescript
// 从 panelInfo 读取已有状态
const bgUrl = info.backgroundUrl || null;
const comicUrl = info.comicUrl || null;
const comicStatus = info.comicStatus || null;
const videoUrl = info.videoUrl || null;
const videoStatus = info.videoStatus || null;

return {
  segmentIndex: idx,
  title: `分镜 ${idx + 1}`,
  synopsis: info.composition || '',
  sceneThumbnail: bgUrl,
  characterAvatars,
  pipelineStep: mapProductionToPipelineStep({
    backgroundStatus: bgUrl ? 'completed' : (info.backgroundStatus || 'pending'),
    backgroundUrl: bgUrl,
    comicStatus: comicStatus || 'pending',
    videoStatus: videoStatus || 'pending',
  }),
  comicUrl,
  videoUrl,
  feedback: info.revisionFeedback || '',
  panelData: { ... },
};
```

---

## Task 6: 前端 — 修改 EpisodeCard 回调签名以传递 segmentKey

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx`

**注意：** 需要先读取 `EpisodeCard.tsx` 确认当前 props 接口。

- [ ] **Step 1: 读取 EpisodeCard.tsx 确认当前结构**

Run: `cat d:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx`

- [ ] **Step 2: 修改 SegmentCard 的 props 回调签名**

将 `SegmentCardProps` 中的回调签名从无参数改为带 segmentKey：

```typescript
export interface SegmentCardProps {
  episodeId: number;
  segment: SegmentState;
  segmentKey: string;          // 新增
  isExpanded: boolean;
  onToggle: () => void;
  onApprove: (segmentKey: string) => void;           // 修改
  onRegenerate: (segmentKey: string, feedback: string) => void;  // 修改
  onGenerateVideo: (segmentKey: string) => void;     // 修改
  onGenerateBackground?: (panelId: string) => void;
  isGeneratingBackground?: boolean;
}
```

修改 `ComicPanel` 和 `VideoPanel` 的调用处：

```typescript
<ComicPanel
  comicUrl={segment.comicUrl}
  pipelineStep={segment.pipelineStep}
  onApprove={() => onApprove(segmentKey)}
  onRegenerate={(feedback) => onRegenerate(segmentKey, feedback)}
/>
...
<VideoPanel
  videoUrl={segment.videoUrl}
  pipelineStep={segment.pipelineStep}
  onGenerateVideo={() => onGenerateVideo(segmentKey)}
/>
```

- [ ] **Step 3: 修改 EpisodeCard 的 props 回调签名**

将 `EpisodeCardProps` 中的回调签名改为带 segmentKey：

```typescript
// 在 EpisodeCard 的 props 接口中修改：
onSegmentApprove: (segmentKey: string) => void;
onSegmentRegenerate: (segmentKey: string, feedback: string) => void;
onSegmentGenerateVideo: (segmentKey: string) => void;
```

在 `EpisodeCard` 内部渲染 `SegmentCard` 时，传递 `segmentKey`：

```typescript
const segmentKey = `${episode.episodeId}-${segment.segmentIndex}`;
<SegmentCard
  episodeId={episode.episodeId}
  segment={segment}
  segmentKey={segmentKey}
  isExpanded={expandedSegmentKey === segmentKey}
  onToggle={() => onSegmentToggle(segmentKey)}
  onApprove={onSegmentApprove}
  onRegenerate={onSegmentRegenerate}
  onGenerateVideo={onSegmentGenerateVideo}
  onGenerateBackground={onGenerateBackground}
  isGeneratingBackground={generatingBackgroundPanelId === segment.panelData?.panelId}
/>
```

- [ ] **Step 4: 修改 Step5page 的 renderEpisodeCard 传递真实回调**

回到 `Step5page.tsx`，在 `renderEpisodeCard` 中：

```typescript
onSegmentApprove={(segmentKey) => {
  const [epId, segIdx] = segmentKey.split('-').map(Number);
  const panelId = episode.segments[segIdx]?.panelData?.panelId;
  if (panelId) handleApproveComic(epId, panelId);
}}
onSegmentRegenerate={(segmentKey, feedback) => {
  const [epId, segIdx] = segmentKey.split('-').map(Number);
  const panelId = episode.segments[segIdx]?.panelData?.panelId;
  if (panelId) handleRegenerateComic(epId, panelId, feedback);
}}
onSegmentGenerateVideo={(segmentKey) => {
  const [epId, segIdx] = segmentKey.split('-').map(Number);
  const panelId = episode.segments[segIdx]?.panelData?.panelId;
  if (panelId) handleGenerateVideo(epId, panelId);
}}
```

---

## Task 7: 端到端验证

- [ ] **Step 1: 后端编译验证**

Run: `cd d:/wiset/Wiset_Aivideo_Genetator/backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 前端编译验证**

Run: `cd d:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 前端构建验证**

Run: `cd d:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npm run build`
Expected: BUILD SUCCESS

---

## 变更摘要

### 后端变更（2 个文件）
1. **PanelProductionService.java** — 删除 4 个方法（`startOrResume`, `findNextIncompletePanel`, `drivePanelStep`, `isPanelVideoCompleted`），移除 2 处 `self().startOrResume()` 调用
2. **PanelController.java** — 移除 2 处 `startOrResume` 调用（`approveComic` 和 `retryVideo`）

### 前端变更（5 个文件）
1. **episode.types.ts** — 新增 4 个 DTO 类型
2. **episodeService.ts** — 新增 9 个 API 函数，删除 14 个废弃 API
3. **Step5page.tsx** — 对接真实生产状态，实现 3 个核心回调，修改状态映射
4. **EpisodeCard.tsx** — 修改回调签名传递 segmentKey
5. **SegmentCard.tsx** — 修改回调签名传递 segmentKey
