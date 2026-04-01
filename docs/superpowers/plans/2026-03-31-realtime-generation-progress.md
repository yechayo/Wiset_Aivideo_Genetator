# Step5 实时生成进度可视化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Step5 的分镜/九宫格生成过程中，通过 SSE 实时推送集级进度，移除阻塞性 spinner，在剧集列表中嵌入骨架屏加载态。

**Architecture:** 后端在 StoryboardService 和 GridImageService 的关键节点发布 Redis 事件（通过 ProjectStatusBroadcaster），前端通过 EventSource 订阅 SSE 事件，根据 eventType 字段分发更新 EpisodeState，渲染对应的加载态。

**Tech Stack:** Java 8 + Spring Boot (后端), React 19 + TypeScript + Less (前端), Redis Pub/Sub (消息通道)

---

## File Structure

### Backend (修改)
| 文件 | 职责 |
|------|------|
| `backend/.../service/pipeline/ProjectStatusBroadcaster.java` | 新增 `broadcastEpisodeProgress` 方法 |
| `backend/.../service/storyboard/StoryboardService.java` | 在生成循环中发布集级进度事件（直接发布，不用事务回调） |
| `backend/.../service/panel/GridImageService.java` | 注入 broadcaster，发布九宫格状态事件 |

### Frontend (修改/新建)
| 文件 | 职责 |
|------|------|
| `frontend/.../steps/types.ts` | EpisodeState 新增 scriptStatus/storyboardStatus |
| `frontend/.../steps/hooks/useSseProgress.ts` | **新建** SSE 订阅 hook |
| `frontend/.../steps/Step5page.tsx` | 移除阻塞性 spinner，集成 SSE hook |
| `frontend/.../steps/components/EpisodeCard.tsx` | 增加集级加载态渲染 |
| `frontend/.../steps/components/EpisodeCard.module.less` | 增加骨架屏和脉冲动画样式 |

---

## Task 1: 后端 — 扩展 ProjectStatusBroadcaster

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic\src\main\java\com\comic\service\pipeline\ProjectStatusBroadcaster.java`

- [ ] **Step 1: 添加 broadcastEpisodeProgress 方法**

在 `ProjectStatusBroadcaster.java` 的 `broadcast` 方法之后添加新方法：

```java
/**
 * 广播集级生成进度事件
 * @param projectId 项目ID
 * @param eventType 事件类型 (episode:script_done / episode:storyboard_done / episode:grid_status)
 * @param data 事件数据（方法内会自动添加 eventType、projectId、timestamp）
 */
public void broadcastEpisodeProgress(String projectId, String eventType, Map<String, Object> data) {
    try {
        data.put("eventType", eventType);
        data.put("projectId", projectId);
        data.put("timestamp", String.valueOf(System.currentTimeMillis()));

        String json = objectMapper.writeValueAsString(data);
        redisTemplate.convertAndSend(CHANNEL_PREFIX + projectId, json);
        log.info("Episode progress broadcast: projectId={}, eventType={}", projectId, eventType);
    } catch (Exception e) {
        log.warn("Failed to broadcast episode progress: projectId={}, eventType={}, error={}",
            projectId, eventType, e.getMessage());
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/pipeline/ProjectStatusBroadcaster.java
git commit -m "feat: add broadcastEpisodeProgress method to ProjectStatusBroadcaster"
```

---

## Task 2: 后端 — StoryboardService 添加进度广播

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic\src\main\java\com\comic\service\storyboard\StoryboardService.java`

**设计决策：** `generateEpisodeScriptAndStoryboard` 整体是 `@Transactional` 的。在事务内直接发布 SSE 事件——前端收到 `storyboard_done` 事件后调用 `loadPanelsForEpisode` 时可能遇到事务尚未提交的问题。解决方案是前端在 `loadPanelsForEpisode` 失败时自动重试一次（1秒延迟），因为此时事务即将提交或刚刚提交。这比拆分事务简单得多，且现有代码已在同一事务内调用 `@Async` 的 `gridImageService.generateGridsForEpisode`，说明这种模式在生产中可行。

- [ ] **Step 1: 在 DeepSeek 批量返回分集剧本后，发布 episode:script_done 事件**

在 `generateEpisodeScriptAndStoryboard` 方法中，`List<Map<String, Object>> scripts = ...` 之后、`for` 循环之前，插入：

```java
// 广播每集剧本完成事件（分集剧本是一次性批量生成的，此时 episode 尚未创建，只有 episodeNum）
int totalEpisodes = scripts.size();
for (int i = 0; i < scripts.size(); i++) {
    Map<String, Object> script = scripts.get(i);
    Map<String, Object> eventData = new HashMap<>();
    eventData.put("episodeNum", i + 1);
    eventData.put("title", script.get("title"));
    eventData.put("totalEpisodes", totalEpisodes);
    eventData.put("completedEpisodes", i + 1);
    broadcaster.broadcastEpisodeProgress(projectId, "episode:script_done", eventData);
}
```

注意：此处没有 `episodeId`，因为 episodes 尚未创建。前端通过 `episodeNum` 匹配。

- [ ] **Step 2: 在每集分镜生成完成后，直接发布 episode:storyboard_done 事件**

在 `for` 循环内，`gridImageService.updateEpisodeGridStatus(episodeId, "generating");` 之后，直接广播（不使用 TransactionSynchronization，因为外层事务使得所有回调会在方法结束时统一触发，达不到逐集推送的效果）：

```java
// 直接广播分镜完成事件
Map<String, Object> storyboardDoneData = new HashMap<>();
storyboardDoneData.put("episodeId", episodeId);
storyboardDoneData.put("episodeNum", scripts.indexOf(script) + 1);
storyboardDoneData.put("title", title);
storyboardDoneData.put("shotsCount", shots.size());
broadcaster.broadcastEpisodeProgress(projectId, "episode:storyboard_done", storyboardDoneData);
```

- [ ] **Step 3: 编译验证**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java
git commit -m "feat: broadcast episode script and storyboard progress events in StoryboardService"
```

---

## Task 3: 后端 — GridImageService 添加九宫格状态广播

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic\src\main\java\com\comic\service\panel\GridImageService.java`

- [ ] **Step 1: 注入 ProjectStatusBroadcaster 依赖**

在 GridImageService 的依赖注入区域添加：

```java
@Resource
private ProjectStatusBroadcaster broadcaster;
```

- [ ] **Step 2: 在 generateGridsForEpisode 方法中发布九宫格状态事件**

**重要：** 将 `gridProjectId` 声明移到 try 块之前，确保 catch 块可以访问。

在方法开始处（获取 episode 之后），**try 块内第一行之后**，获取 projectId 并发布 generating 事件。使用 for 循环的 index 作为 episodeNum（从 episodeInfo 中取不到可靠值）：

```java
// 获取 projectId（在 try 外声明，catch 中也需要用）
String gridProjectId = episode.getProjectId();
```

在 try 块内，生成开始时：

```java
// 发布九宫格生成开始事件
if (gridProjectId != null) {
    Map<String, Object> startData = new HashMap<>();
    startData.put("episodeId", episodeId);
    startData.put("gridStatus", "generating");
    broadcaster.broadcastEpisodeProgress(gridProjectId, "episode:grid_status", startData);
}
```

在方法成功结束处（`episodeRepository.updateById(episode)` 之后）：

```java
// 发布九宫格生成完成事件
if (gridProjectId != null) {
    Map<String, Object> doneData = new HashMap<>();
    doneData.put("episodeId", episodeId);
    doneData.put("gridStatus", "generated");
    broadcaster.broadcastEpisodeProgress(gridProjectId, "episode:grid_status", doneData);
}
```

在 catch 块中（设置 `gridStatus = "failed"` 之后）：

```java
// 发布九宫格生成失败事件
if (gridProjectId != null) {
    Map<String, Object> failData = new HashMap<>();
    failData.put("episodeId", episodeId);
    failData.put("gridStatus", "failed");
    broadcaster.broadcastEpisodeProgress(gridProjectId, "episode:grid_status", failData);
}
```

注意：`episode:grid_status` 事件不包含 `episodeNum`（GridImageService 中没有可靠的来源）。前端通过 `episodeId` 匹配。

- [ ] **Step 3: 编译验证**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: broadcast episode grid status events in GridImageService"
```

---

## Task 4: 前端 — 更新类型定义

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator\src\pages\create\steps\types.ts`

- [ ] **Step 1: 在 EpisodeState 中新增 scriptStatus 和 storyboardStatus 字段**

在 `EpisodeState` 接口中，在 `isNewFlow?: boolean;` 之后添加：

```typescript
  /** 分集剧本生成状态（SSE 实时更新） */
  scriptStatus?: 'pending' | 'generating' | 'done';
  /** 分镜脚本生成状态（SSE 实时更新） */
  storyboardStatus?: 'pending' | 'generating' | 'done';
```

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit --pretty 2>&1 | head -20`
Expected: 无新增错误（可能存在已有的无关错误）

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
git commit -m "feat: add scriptStatus and storyboardStatus to EpisodeState"
```

---

## Task 5: 前端 — 创建 useSseProgress Hook

**Files:**
- Create: `D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator\src\pages\create\steps\hooks\useSseProgress.ts`

- [ ] **Step 1: 创建 hooks 目录和 useSseProgress hook**

```typescript
import { useEffect, useRef, useCallback } from 'react';
import { getEpisodes, getBatchProductionStatuses } from '../../../../services/episodeService';

interface SseProgressCallbacks {
  onEpisodeScriptDone: (data: { episodeNum: number; title: string; totalEpisodes: number; completedEpisodes: number }) => void;
  onEpisodeStoryboardDone: (data: { episodeId: number; episodeNum: number; shotsCount: number }) => void;
  onEpisodeGridStatus: (data: { episodeId: number; gridStatus: string }) => void;
  onStatusChange: (data: { from?: string; to?: string }) => void;
}

/**
 * SSE 实时进度订阅 Hook
 * 连接 /api/projects/{projectId}/status/stream，根据 eventType 分发事件
 *
 * 注意：前端当前没有任何 SSE/EventSource 代码，这是全新实现。
 * 与现有的轮询机制并存：SSE 做增量推送，轮询在关键时刻做全量同步。
 */
export function useSseProgress(
  projectId: string | undefined,
  callbacks: SseProgressCallbacks,
) {
  const esRef = useRef<EventSource | null>(null);
  // 用 ref 持有最新回调，避免闭包引用过期的 state
  const cbRef = useRef(callbacks);
  cbRef.current = callbacks;

  const handleReconnect = useCallback(async () => {
    if (!projectId) return;
    try {
      // 全量同步：重新加载 episodes 和 production statuses
      const res = await getEpisodes(projectId);
      if (res.code === 0 || res.code === 200) {
        const items = res.data?.items || [];
        for (const ep of items) {
          if (ep.id) {
            getBatchProductionStatuses(projectId, ep.id).catch(() => {});
          }
        }
      }
    } catch {
      // 静默失败
    }
  }, [projectId]);

  useEffect(() => {
    if (!projectId) return;

    const url = `/api/projects/${projectId}/status/stream`;
    const es = new EventSource(url);
    esRef.current = es;

    es.addEventListener('status-change', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        const eventType = data.eventType;

        if (eventType === 'episode:script_done') {
          cbRef.current.onEpisodeScriptDone(data);
        } else if (eventType === 'episode:storyboard_done') {
          cbRef.current.onEpisodeStoryboardDone(data);
        } else if (eventType === 'episode:grid_status') {
          cbRef.current.onEpisodeGridStatus(data);
        } else {
          // 兼容原有的项目级状态变更（无 eventType 或其他）
          cbRef.current.onStatusChange(data);
        }
      } catch {
        // 解析失败忽略
      }
    });

    // 首次连接不触发全量同步（页面加载时 loadEpisodes 已处理）
    let isFirstOpen = true;
    es.onopen = () => {
      if (isFirstOpen) {
        isFirstOpen = false;
        return;
      }
      // 重连后全量同步
      handleReconnect();
    };

    es.onerror = () => {
      // EventSource 会自动重连，不需要手动处理
    };

    return () => {
      es.close();
      esRef.current = null;
    };
  }, [projectId, handleReconnect]);

  return { reconnect: handleReconnect };
}
```

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit --pretty 2>&1 | head -20`
Expected: 无新增错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useSseProgress.ts
git commit -m "feat: add useSseProgress hook for SSE real-time progress subscription"
```

---

## Task 6: 前端 — EpisodeCard 增加骨架屏样式

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator\src\pages\create\steps\components\EpisodeCard.module.less`

- [ ] **Step 1: 添加骨架屏和脉冲动画样式**

在 `EpisodeCard.module.less` 末尾添加：

```less
// ===== 集级生成进度加载态 =====

.episodeGenerating {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.skeletonBar {
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: skeletonShimmer 1.5s ease-in-out infinite;
  border-radius: 4px;
  height: 14px;
}

.skeletonBarTitle {
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: skeletonShimmer 1.5s ease-in-out infinite;
  border-radius: 4px;
  width: 55%;
  height: 16px;
  margin-bottom: 6px;
}

.skeletonBarDesc {
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: skeletonShimmer 1.5s ease-in-out infinite;
  border-radius: 4px;
  width: 40%;
  height: 14px;
}

.skeletonSegment {
  padding: 10px 12px;
  margin-bottom: 6px;
  border-radius: var(--radius-sm);
  background: rgba(255,255,255,0.03);
  border: 1px solid var(--color-border-subtle);
}

.skeletonGrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  width: 120px;
  margin-top: 8px;
}

.skeletonGridCell {
  aspect-ratio: 1;
  background: linear-gradient(90deg, rgba(255,255,255,0.04) 25%, rgba(255,255,255,0.08) 50%, rgba(255,255,255,0.04) 75%);
  background-size: 200% 100%;
  animation: skeletonShimmer 1.5s ease-in-out infinite;
  border-radius: 3px;
}

@keyframes skeletonShimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
```

注意：使用完整的 CSS 属性而非 `composes`，以避免 Less + CSS Modules 的兼容性问题。

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less
git commit -m "feat: add skeleton and shimmer animation styles for episode loading states"
```

---

## Task 7: 前端 — EpisodeCard 增加加载态渲染

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator\src\pages\create\steps\components\EpisodeCard.tsx`

- [ ] **Step 1: 添加加载态判断和骨架屏渲染逻辑**

在 EpisodeCard 组件内部，`isGridFailed` 变量（约第88行）之后，添加生成状态判断：

```typescript
  const isScriptGenerating = episode.scriptStatus === 'generating';
  const isStoryboardGenerating = episode.storyboardStatus === 'generating';
```

在同一位置之后，添加骨架屏渲染函数：

```typescript
  /** 渲染骨架屏分镜条目 */
  const renderSkeletonSegments = (count: number = 3) => (
    Array.from({ length: count }).map((_, i) => (
      <div key={`skeleton-${i}`} className={styles.skeletonSegment}>
        <div className={styles.skeletonBarTitle} />
        <div className={styles.skeletonBarDesc} />
      </div>
    ))
  );

  /** 渲染生成进度提示 */
  const renderGeneratingHint = () => {
    if (isScriptGenerating) {
      return (
        <div className={styles.episodeGenerating}>
          <span className={styles.miniSpinner} />
          <span>分集剧本生成中...</span>
        </div>
      );
    }
    if (isStoryboardGenerating) {
      return (
        <div className={styles.episodeGenerating}>
          <span className={styles.miniSpinner} />
          <span>分镜脚本生成中...</span>
        </div>
      );
    }
    if (episode.gridStatus === 'generating') {
      return (
        <div className={styles.episodeGenerating}>
          <span className={styles.miniSpinner} />
          <span>九宫格生成中...</span>
        </div>
      );
    }
    return null;
  };
```

- [ ] **Step 2: 修改展开内容区域的渲染**

找到 EpisodeCard 中现有的展开区域渲染。当前结构大致为：

```
{isExpanded && (
  <div className={styles.expandedContent}>
    {/* 九宫格审核区域（!isGridApproved 时） */}
    {/* segmentCards 列表（isGridApproved 时） */}
  </div>
)}
```

将其改为以下结构。**保留所有现有逻辑，只在外层增加条件分支：**

```typescript
{isExpanded && (
  <div className={styles.expandedContent}>
    {renderGeneratingHint()}

    {isScriptGenerating ? (
      renderSkeletonSegments(3)
    ) : isStoryboardGenerating ? (
      renderSkeletonSegments(5)
    ) : (
      <>
        {/* ====== 以下为原有的展开内容，保持不变 ====== */}

        {!isGridApproved && (
          <div className={styles.episodeGridReview}>
            {/* ... 原有的九宫格审核区域 JSX 保持不变 ... */}
          </div>
        )}

        {isGridApproved && (
          <div className={styles.segmentList}>
            {segmentCards}
          </div>
        )}

        {/* ====== 原有展开内容结束 ====== */}
      </>
    )}
  </div>
)}
```

关键点：
- 原有的 `!isGridApproved` 九宫格审核区域和 `isGridApproved` 的 segmentCards 区域完整保留在 else 分支中
- 只在 `isScriptGenerating` 或 `isStoryboardGenerating` 时显示骨架屏
- `gridStatus === 'generating'` 不触发骨架屏（此时有 segments 数据，九宫格区域自然显示加载态）

- [ ] **Step 3: 验证 TypeScript 编译**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit --pretty 2>&1 | head -20`
Expected: 无新增错误

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx
git commit -m "feat: add script/storyboard/grid generating states to EpisodeCard"
```

---

## Task 8: 前端 — Step5page 集成 SSE 并移除阻塞性 Spinner

**Files:**
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator\src\pages\create\steps\Step5page.tsx`
- Modify: `D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator\src\pages\create\steps\Step5page.module.less`

这是最关键的任务，改动较大。SSE hook 调用放在组件靠后位置（在 `loadPanelsForEpisode` 定义之后），确保回调引用的函数已定义。

- [ ] **Step 1: 导入 useSseProgress hook**

在 Step5page.tsx 顶部 import 区域添加：

```typescript
import { useSseProgress } from './hooks/useSseProgress';
```

- [ ] **Step 2: 在 `loadPanelsForEpisode` 和 `refreshEpisodeGridStatus` 定义之后添加 SSE hook 调用**

在 `refreshEpisodeGridStatus` 的 `useCallback` 定义之后（约第776行之后），添加：

```typescript
  // SSE 实时进度订阅（放在 loadPanelsForEpisode 定义之后，确保回调可引用）
  useSseProgress(projectId, {
    onEpisodeScriptDone: (data) => {
      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeIndex === data.episodeNum
              ? { ...ep, scriptStatus: 'done' as const, title: data.title || ep.title }
              : ep
          ),
        }))
      );
    },
    onEpisodeStoryboardDone: (data) => {
      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeId === data.episodeId
              ? { ...ep, storyboardStatus: 'done' as const }
              : ep
          ),
        }))
      );
      // 加载该集的分镜（带重试：事务可能尚未提交）
      const tryLoad = async (retries = 0) => {
        panelsLoadedRef.current.delete(data.episodeId);
        try {
          await loadPanelsForEpisode(data.episodeId);
        } catch {
          if (retries < 1) {
            await new Promise(r => setTimeout(r, 1000));
            tryLoad(retries + 1);
          }
        }
      };
      tryLoad();
    },
    onEpisodeGridStatus: (data) => {
      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeId === data.episodeId
              ? { ...ep, gridStatus: data.gridStatus as any }
              : ep
          ),
        }))
      );
    },
    onStatusChange: (data) => {
      if (projectId && data.to) {
        syncStatus(projectId);
        if (data.to === 'STORYBOARD_REVIEW') {
          loadEpisodes();
        }
      }
    },
  });
```

- [ ] **Step 3: 移除阻塞性 spinner**

删除 Step5page.tsx 中第 912-926 行的 `isGeneratingScript` 阻塞性 return 分支。即删除以下代码：

```typescript
  // 流水线生成中状态（分集剧本/分镜正在生成）
  const isGeneratingScript = statusInfo?.statusCode === 'EPISODE_SCRIPT_GENERATING'
    || statusInfo?.statusCode === 'STORYBOARD_GENERATING';
  if (isGeneratingScript && !isPipelineFailed) {
    const label = statusInfo?.statusCode === 'EPISODE_SCRIPT_GENERATING'
      ? '正在生成分集剧本...' : '正在生成分镜脚本...';
    return (
      <div className={styles.pageContainer}>
        <div className={styles.loadingState}>
          <div className={styles.spinner} />
          <p>{label}</p>
          <p style={{ color: '#888', fontSize: 13, marginTop: 4 }}>AI 正在创作中，通常需要 1-3 分钟</p>
        </div>
      </div>
    );
  }
```

注意：`failedStatusCode` 和 `isPipelineFailed` 变量定义在第 70-73 行（组件顶部），不要删除它们，只删除上面的 `isGeneratingScript` 变量和 `if` 分支。

- [ ] **Step 4: 添加生成进度提示条**

在页面的 `pageHeader` div 之后、`BatchReviewBar` 之前，添加进度提示条：

```typescript
      {/* 生成进度提示条（替代原有的阻塞性 spinner） */}
      {(statusInfo?.statusCode === 'EPISODE_SCRIPT_GENERATING' || statusInfo?.statusCode === 'STORYBOARD_GENERATING') && (
        <div className={styles.generationProgress}>
          <span className={styles.progressSpinner} />
          <span>
            {statusInfo?.statusCode === 'EPISODE_SCRIPT_GENERATING'
              ? '正在生成分集剧本...'
              : '正在生成分镜脚本...'}
          </span>
          <span className={styles.generationCount}>
            {chapters.reduce((sum, ch) => sum + ch.episodes.filter(ep =>
              ep.storyboardStatus === 'done' || ep.gridStatus
            ).length, 0)}
            {' / '}
            {chapters.reduce((sum, ch) => sum + ch.episodes.length, 0)} 集已完成
          </span>
        </div>
      )}
```

- [ ] **Step 5: 添加进度提示条样式**

在 `Step5page.module.less` 末尾添加：

```less
// ===== 生成进度提示条 =====

.generationProgress {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.15);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.progressSpinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(99, 102, 241, 0.3);
  border-top-color: var(--color-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.generationCount {
  margin-left: auto;
  color: var(--color-accent);
  font-weight: var(--font-weight-semibold);
}
```

- [ ] **Step 6: 验证 TypeScript 编译**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit --pretty 2>&1 | head -30`
Expected: 无新增错误

- [ ] **Step 7: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.module.less
git commit -m "feat: integrate SSE progress, remove blocking spinner, add generation progress bar"
```

---

## Task 9: 集成验证

- [ ] **Step 1: 编译后端**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 编译前端**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit --pretty 2>&1 | head -30`
Expected: 无新增错误

- [ ] **Step 3: 功能验证清单**

启动后端和前端后，手动测试以下场景：

1. **分集剧本生成中**：进入 Step5，应看到进度提示条"正在生成分集剧本..."，已创建的集显示骨架屏
2. **SSE 事件到达**：浏览器 DevTools Network > EventStream，确认收到 `status-change` 事件且包含 `eventType` 字段
3. **分镜逐步显示**：每集分镜完成后，骨架屏应替换为实际的分镜内容
4. **九宫格加载态**：分镜已显示、九宫格生成中的集，九宫格区域应有脉冲动画
5. **生成完成**：项目状态推进到 STORYBOARD_REVIEW 后，进度提示条消失，进入正常审核流程
6. **页面刷新**：生成中刷新页面，应恢复当前进度（getEpisodes + SSE 重连）
7. **SSE 断连恢复**：断开网络后恢复，EventSource 自动重连，UI 更新

- [ ] **Step 4: Final commit (如有修复)**

```bash
git add -A
git commit -m "fix: address integration issues from testing"
```
