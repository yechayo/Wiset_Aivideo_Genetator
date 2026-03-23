# Step5 剧集卡片工作台重写

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Step5 从"分镜审查页"重写为"剧集卡片工作台"，每张卡片 = 一个剧集，卡片内部完成分镜审查 → 场景生成 → 融合 → 视频生成的全流程。

**Architecture:** Step5 通过 `getEpisodes(projectId)` 加载所有剧集，渲染 `EpisodeCard` 列表。每张卡片根据剧集状态内部切换模式：审核模式（展示分镜文字 + 确认/修订）和生产模式（面板网格展示场景图/融合图/视频）。轮询复用 `createStore` 的项目级轮询 + 额外的 `getPanelStates` 按剧集轮询。

**Tech Stack:** React 18, TypeScript, Less Modules, Zustand

---

## 文件结构

| 文件 | 职责 | 操作 |
|------|------|------|
| `steps/Step5page.tsx` | 主页面：加载剧集列表、轮询、渲染卡片 | 重写 |
| `steps/Step5page.module.less` | 页面布局样式 | 重写 |
| `components/EpisodeCard.tsx` | 剧集卡片：审核模式 + 生产模式 | 新建 |
| `components/EpisodeCard.module.less` | 卡片样式 | 新建 |
| `components/PanelCell.tsx` | 面板格子：场景图/融合图/视频状态 | 新建 |
| `components/PanelCell.module.less` | 格子样式 | 新建 |
| `components/Step5Card.tsx` | 旧实现（错误粒度） | 删除 |
| `components/Step5Card.module.less` | 旧实现样式 | 删除 |
| `types/episode.types.ts` | 类型定义 | 修改 |
| `services/episodeService.ts` | API 函数 | 修改 |

---

## 数据流

```
getEpisodes(projectId) → List<Episode>
  │
  ├── Episode 1 (storyboard confirmed, production IN_PROGRESS)
  │     └── getPanelStates(episode1Id) → PanelState[]
  │     └── getProductionPipeline(projectId) → sceneGridUrls[]
  │
  ├── Episode 2 (storyboard GENERATING) ← 当前审核
  │     └── getStoryboard(episode2Id) → StoryboardData
  │
  ├── Episode 3 (not yet started)
  │
  └── Episode N ...
```

**关键 API：**

| API | 用途 | 返回 |
|-----|------|------|
| `getEpisodes(projectId)` | 获取项目所有剧集 | `Episode[]` (含 id, episodeNum, title, status, productionStatus) |
| `getStoryboard(episodeId)` | 获取分镜文字数据 | `{ storyboardJson, status }` |
| `confirmStoryboard(episodeId)` | 确认分镜 | void |
| `reviseStoryboard(episodeId, feedback)` | 修订分镜 | void |
| `getPanelStates(episodeId)` | 获取面板状态 | `PanelState[]` (含 fusionStatus, videoStatus, fusionUrl, videoUrl) |
| `getProductionPipeline(projectId)` | 获取管线状态（含 sceneGridUrls） | `ProductionPipelineResponse` |
| `getGridInfo(episodeId)` | 获取网格详情（含 gridPages, fusedPanels） | `GridInfoResponse` |
| `generateSinglePanelVideo(episodeId, panelIndex)` | 单格生成视频 | void |
| `autoContinue(episodeId)` | 一键自动化 | void |
| `splitGridPage(episodeId, pageIndex)` | 后端切分九宫格 | `SplitGridPageResponse` |

**现有类型（不需修改）：**

```typescript
// PanelState - 来自 episode.types.ts
interface PanelState {
  panelIndex: number;
  fusionStatus: 'pending' | 'completed';
  fusionUrl: string | null;
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoUrl: string | null;
  videoTaskId: string | null;
  sceneDescription: string | null;
  shotType: string | null;
  dialogue: string | null;
  panelId: string | null;
  promptText: string | null;
}

// ProductionPipelineResponse
interface ProductionPipelineResponse {
  episodeId: string | null;
  episodeTitle: string | null;
  episodeStatus: string;
  productionStatus: string;
  stages: PipelineStage[];
  errorMessage: string | null;
  finalVideoUrl: string | null;
  sceneGridUrls: string[];
}
```

---

## Task 1: 清理旧实现

**Files:**
- Delete: `src/pages/create/steps/components/Step5Card.tsx`
- Delete: `src/pages/create/steps/components/Step5Card.module.less`

- [ ] **Step 1: 删除错误的 Step5Card 组件和样式**

```bash
rm frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.tsx
rm frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.module.less
```

- [ ] **Step 2: 清理 episode.types.ts 中不需要的类型**

从 `episode.types.ts` 删除 `WorkflowPhase` 和 `SceneImageState` 类型定义（这些是为错误实现添加的）。

- [ ] **Step 3: 从 episodeService.ts 删除不需要的 API 函数**

删除 `regenerateSceneImage` 函数（后端接口保留，但前端暂时不用——后续可重新添加）。

- [ ] **Step 4: 验证前端构建通过**

Run: `cd frontend/wiset_aivideo_generator && npm run build`
Expected: 构建成功（Step5page.tsx 可能报错因为它引用了已删除的 Step5Card，这是预期的，下一步修复）

---

## Task 2: 新增类型定义

**Files:**
- Modify: `src/services/types/episode.types.ts`

- [ ] **Step 1: 添加剧集卡片所需类型**

在 `episode.types.ts` 末尾添加：

```typescript
/** 剧集卡片数据（组合 Episode 实体 + 面板状态） */
export interface EpisodeCardData {
  /** 剧集实体信息 */
  id: number;
  episodeNum: number;
  title: string;
  status: string;           // DRAFT, GENERATING, DONE, FAILED
  productionStatus: string;  // null, NOT_STARTED, IN_PROGRESS, COMPLETED, FAILED
  finalVideoUrl: string | null;
  errorMsg: string | null;

  /** 分镜数据（审核模式用） */
  storyboardJson: string | null;
  storyboardStatus: string | null;

  /** 面板状态（生产模式用） */
  panelStates: PanelState[];

  /** 场景网格图 URL 列表（每页一张完整九宫格图） */
  sceneGridUrls: string[];

  /** 是否正在加载 */
  loading: boolean;
  /** 加载错误 */
  loadError: string | null;
}
```

- [ ] **Step 2: 验证构建通过**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 编译通过

---

## Task 3: Step5page.tsx 主页面重写

**Files:**
- Modify: `src/pages/create/steps/Step5page.tsx`
- Modify: `src/pages/create/steps/Step5page.module.less`

### 3.1 Step5page.tsx 完整代码

```typescript
import { useEffect, useState, useCallback, useRef } from 'react';
import styles from './Step5page.module.less';
import type { StepContentProps } from '../types';
import type { Project } from '../../../services';
import { useCreateStore } from '../../../stores/createStore';
import {
  getEpisodes,
  getStoryboard,
  startStoryboard,
  confirmStoryboard,
  reviseStoryboard,
  retryStoryboard,
  getPanelStates,
  getProductionPipeline,
  autoContinue,
  generateSinglePanelVideo,
} from '../../../services/episodeService';
import { isApiSuccess } from '../../../services/apiClient';
import type { EpisodeCardData } from '../../../services/types/episode.types';
import EpisodeCard from './components/EpisodeCard';

const POLL_INTERVAL = 5000;
const PANEL_POLL_INTERVAL = 5000;

interface Step5pageProps extends StepContentProps {
  project: Project;
}

interface RawStoryboardPanel {
  scene?: string;
  shot_size?: string;
  shot_type?: string;
  camera_angle?: string;
  dialogue?: string | { speaker?: string; text?: string }[];
  effects?: string;
  characters?: string | { name?: string; expression?: string }[];
  background?: { scene_desc?: string };
}

interface NormalizedPanel {
  scene: string;
  characters: string;
  shot_size: string;
  camera_angle: string;
  dialogue: string;
  effects: string;
}

function normalizePanels(raw: RawStoryboardPanel[]): NormalizedPanel[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((p) => ({
    scene: p.scene || p.background?.scene_desc || '',
    characters: typeof p.characters === 'string' ? p.characters
      : Array.isArray(p.characters) ? p.characters.map(c => c.name || '').filter(Boolean).join(' / ')
      : '',
    shot_size: p.shot_size || p.shot_type || '',
    camera_angle: p.camera_angle || '',
    dialogue: typeof p.dialogue === 'string' ? p.dialogue
      : Array.isArray(p.dialogue) ? p.dialogue.map(d => d.speaker ? `${d.speaker}: ${d.text || ''}` : (d.text || '')).filter(Boolean).join(' / ')
      : '',
    effects: p.effects || '',
  }));
}

const Step5page = ({ project }: Step5pageProps) => {
  const { statusInfo, syncStatus } = useCreateStore();
  const projectId = project.projectId;

  const [episodes, setEpisodes] = useState<EpisodeCardData[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);

  const panelPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const initializedRef = useRef(false);

  // 加载所有剧集的基础信息
  const loadEpisodes = useCallback(async () => {
    if (!projectId) return;
    try {
      const res = await getEpisodes(projectId);
      if (res.code === 200 && Array.isArray(res.data)) {
        setEpisodes(res.data.map((ep: any) => ({
          id: ep.id,
          episodeNum: ep.episodeNum || 1,
          title: ep.title || `Episode ${ep.episodeNum || 1}`,
          status: ep.status || 'DRAFT',
          productionStatus: ep.productionStatus || null,
          finalVideoUrl: ep.finalVideoUrl || null,
          errorMsg: ep.errorMsg || null,
          storyboardJson: ep.storyboardJson || null,
          storyboardStatus: null,
          panelStates: [],
          sceneGridUrls: [],
          loading: false,
          loadError: null,
        })));
      }
    } catch (e: any) {
      console.error('加载剧集列表失败:', e);
    }
  }, [projectId]);

  // 加载单个剧集的面板状态（生产模式用）
  const loadPanelStates = useCallback(async (epId: number): Promise<EpisodeCardData['panelStates']> => {
    try {
      const res = await getPanelStates(String(epId));
      if (res.code === 200 && res.data) return res.data;
    } catch (e) {
      console.error(`加载面板状态失败 episodeId=${epId}:`, e);
    }
    return [];
  }, []);

  // 加载管线状态（获取 sceneGridUrls）
  const loadPipelineForSceneGrids = useCallback(async (): Promise<string[]> => {
    if (!projectId) return [];
    try {
      const res = await getProductionPipeline(projectId);
      if (res.code === 200 && res.data) return res.data.sceneGridUrls || [];
    } catch (e) {
      console.error('加载管线状态失败:', e);
    }
    return [];
  }, [projectId]);

  // 轮询所有进行中剧集的面板状态
  const pollPanelStates = useCallback(async () => {
    setEpisodes(prev => {
      // 找出需要轮询的剧集（已确认分镜且在生产中的）
      const episodesToPoll = prev.filter(
        ep => (ep.status === 'DONE' || ep.productionStatus === 'IN_PROGRESS') && !ep.loading
      );
      if (episodesToPoll.length === 0) return prev;

      // 标记加载中
      const updated = prev.map(ep => ({
        ...ep,
        loading: episodesToPoll.some(p => p.id === ep.id) ? true : ep.loading,
      }));
      return updated;
    });

    // 并行加载所有剧集的面板状态
    const currentEpisodes = episodes; // capture
    const promises = currentEpisodes
      .filter(ep => (ep.status === 'DONE' || ep.productionStatus === 'IN_PROGRESS'))
      .map(async (ep) => {
        const [panelStates] = await Promise.all([
          loadPanelStates(ep.id),
        ]);
        return { id: ep.id, panelStates };
      });

    const results = await Promise.all(promises);
    const resultMap = new Map(results.map(r => [r.id, r.panelStates]));

    // 同时获取 sceneGridUrls
    const sceneGridUrls = await loadPipelineForSceneGrids();

    setEpisodes(prev => prev.map(ep => ({
      ...ep,
      panelStates: resultMap.get(ep.id) || ep.panelStates,
      sceneGridUrls: sceneGridUrls,
      loading: false,
    })));
  }, [episodes, loadPanelStates, loadPipelineForSceneGrids]);

  // 首次加载
  useEffect(() => {
    if (!projectId || initializedRef.current) return;
    initializedRef.current = true;
    loadEpisodes();
  }, [projectId, loadEpisodes]);

  // 项目状态变化时重新加载剧集列表
  useEffect(() => {
    if (!statusInfo) return;
    loadEpisodes();
  }, [statusInfo?.storyboardCurrentEpisode, statusInfo?.storyboardAllConfirmed, statusInfo?.statusCode, loadEpisodes]);

  // 面板状态轮询
  useEffect(() => {
    const hasActiveProduction = episodes.some(
      ep => ep.productionStatus === 'IN_PROGRESS' || ep.status === 'DONE'
    );
    if (!hasActiveProduction) {
      if (panelPollRef.current) {
        clearInterval(panelPollRef.current);
        panelPollRef.current = null;
      }
      return;
    }

    if (panelPollRef.current) clearInterval(panelPollRef.current);
    panelPollRef.current = setInterval(pollPanelStates, PANEL_POLL_INTERVAL);
    return () => {
      if (panelPollRef.current) {
        clearInterval(panelPollRef.current);
        panelPollRef.current = null;
      }
    };
  }, [episodes, pollPanelStates]);

  // 错误提示自动消失
  useEffect(() => {
    if (!apiError) return;
    const timer = setTimeout(() => setApiError(null), 5000);
    return () => clearTimeout(timer);
  }, [apiError]);

  // 确认分镜
  const handleConfirm = useCallback(async (episodeId: string) => {
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await confirmStoryboard(episodeId);
      if (isApiSuccess(res)) {
        if (projectId) await syncStatus(projectId);
        return;
      }
      setApiError(res.message || '确认失败');
    } catch (e: any) {
      setApiError(e.message || '确认失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 修订分镜
  const handleRevise = useCallback(async (episodeId: string, feedback: string) => {
    if (!feedback.trim()) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await reviseStoryboard(episodeId, feedback.trim());
      if (isApiSuccess(res)) {
        if (projectId) await syncStatus(projectId);
        return;
      }
      setApiError(res.message || '修订失败');
    } catch (e: any) {
      setApiError(e.message || '修订失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 重试分镜生成
  const handleRetry = useCallback(async (episodeId: string) => {
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await retryStoryboard(episodeId);
      if (isApiSuccess(res)) {
        if (projectId) await syncStatus(projectId);
        return;
      }
      setApiError(res.message || '重试失败');
    } catch (e: any) {
      setApiError(e.message || '重试失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 启动分镜生成
  const handleStartStoryboard = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startStoryboard(projectId);
      if (!isApiSuccess(res)) {
        setApiError(res.message || '启动失败');
      }
      if (projectId) await syncStatus(projectId);
    } catch (e: any) {
      setApiError(e.message || '启动失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 单格生成视频
  const handleGenerateVideo = useCallback(async (episodeId: string, panelIndex: number) => {
    try {
      await generateSinglePanelVideo(episodeId, panelIndex);
      await pollPanelStates();
    } catch (e: any) {
      setApiError(e.message || '生成视频失败');
    }
  }, [pollPanelStates]);

  // 一键自动化
  const handleAutoContinue = useCallback(async (episodeId: string) => {
    try {
      await autoContinue(episodeId);
      await pollPanelStates();
    } catch (e: any) {
      setApiError(e.message || '自动化执行失败');
    }
  }, [pollPanelStates]);

  // 加载单个剧集的分镜数据
  const handleLoadStoryboard = useCallback(async (episodeId: string) => {
    try {
      const res = await getStoryboard(episodeId);
      if (res.code === 200 && res.data) {
        setEpisodes(prev => prev.map(ep =>
          String(ep.id) === episodeId
            ? { ...ep, storyboardJson: res.data.storyboardJson || null, storyboardStatus: res.data.status }
            : ep
        ));
      }
    } catch (e) {
      console.error('加载分镜失败:', e);
    }
  }, []);

  const currentReviewId = statusInfo?.storyboardReviewEpisodeId ?? null;
  const totalEpisodes = episodes.length;

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>剧集工作台</h1>
        <p className={styles.subtitle}>
          管理所有剧集的分镜审核与视频生成
          {totalEpisodes > 0 && ` · 共 ${totalEpisodes} 集`}
        </p>
      </div>

      {episodes.length === 0 && (
        <div className={styles.emptyState}>
          <p className={styles.emptyHint}>
            剧集数据为空，请先完成前置步骤。
          </p>
          <button
            className={styles.primaryButton}
            onClick={handleStartStoryboard}
            disabled={isSubmitting}
          >
            {isSubmitting ? '处理中...' : '开始生成分镜'}
          </button>
        </div>
      )}

      <div className={styles.cardList}>
        {episodes.map((ep) => (
          <EpisodeCard
            key={ep.id}
            episode={ep}
            isCurrentReview={String(ep.id) === currentReviewId}
            isLoadingStoryboard={statusInfo?.isGenerating ?? false}
            storyboardStatusDesc={currentReviewId === String(ep.id) ? (statusInfo?.statusDescription || '') : ''}
            storyboardFailed={currentReviewId === String(ep.id) ? (statusInfo?.isFailed ?? false) : false}
            sceneGridUrls={ep.sceneGridUrls}
            onConfirm={() => handleConfirm(String(ep.id))}
            onRevise={(feedback) => handleRevise(String(ep.id), feedback)}
            onRetry={() => handleRetry(String(ep.id))}
            onLoadStoryboard={() => handleLoadStoryboard(String(ep.id))}
            onGenerateVideo={(panelIndex) => handleGenerateVideo(String(ep.id), panelIndex)}
            onAutoContinue={() => handleAutoContinue(String(ep.id))}
            isGlobalSubmitting={isSubmitting}
          />
        ))}
      </div>

      {apiError && (
        <div className={styles.apiError}>
          <span>{apiError}</span>
          <button className={styles.errorDismiss} onClick={() => setApiError(null)}>x</button>
        </div>
      )}
    </div>
  );
};

export default Step5page;
```

### 3.2 Step5page.module.less 完整代码

```less
.page {
  width: 100%;
  box-sizing: border-box;
  padding: 32px 0;
}

.header {
  margin-bottom: 32px;
}

.title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0 0 8px 0;
  letter-spacing: -0.02em;
}

.subtitle {
  font-size: var(--font-size-md);
  color: var(--color-text-secondary);
  margin: 0;
}

.cardList {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ---- 空状态 ---- */

.emptyState {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  gap: 20px;
}

.emptyHint {
  font-size: var(--font-size-md);
  color: var(--color-text-secondary);
  margin: 0;
}

/* ---- 按钮 ---- */

.primaryButton {
  padding: 12px 28px;
  background: var(--color-accent-strong);
  color: var(--color-text-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 15px;
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: var(--color-accent);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

/* ---- API 错误 ---- */

.apiError {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid rgba(239, 68, 68, 0.25);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: rgba(255, 200, 200, 0.95);
  z-index: 1000;
  animation: slideUp 0.3s ease;
}

.errorDismiss {
  flex-shrink: 0;
  padding: 2px 8px;
  background: rgba(247, 249, 252, 0.1);
  border: 1px solid rgba(247, 249, 252, 0.15);
  border-radius: 4px;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 12px;

  &:hover {
    background: rgba(247, 249, 252, 0.2);
  }
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateX(-50%) translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateX(-50%) translateY(0);
  }
}
```

- [ ] **Step 3: 写入 Step5page.tsx**

Write the complete code above to `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`.

- [ ] **Step 4: 写入 Step5page.module.less**

Write the complete styles above to `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.module.less`.

- [ ] **Step 5: 验证 TypeScript 编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 可能有 EpisodeCard 找不到的错误（预期的，下一步创建）

---

## Task 4: EpisodeCard 组件

**Files:**
- Create: `src/pages/create/steps/components/EpisodeCard.tsx`
- Create: `src/pages/create/steps/components/EpisodeCard.module.less`

### 4.1 EpisodeCard.tsx 完整代码

```typescript
import { useEffect, useState } from 'react';
import styles from './EpisodeCard.module.less';
import type { EpisodeCardData } from '../../../../services/types/episode.types';
import type { NormalizedPanel } from '../Step5page';
import PanelCell from './PanelCell';
import GridFusionEditor from './GridFusionEditor';

interface EpisodeCardProps {
  episode: EpisodeCardData;
  isCurrentReview: boolean;
  isLoadingStoryboard: boolean;
  storyboardStatusDesc: string;
  storyboardFailed: boolean;
  sceneGridUrls: string[];
  onConfirm: () => void;
  onRevise: (feedback: string) => void;
  onRetry: () => void;
  onLoadStoryboard: () => void;
  onGenerateVideo: (panelIndex: number) => void;
  onAutoContinue: () => void;
  isGlobalSubmitting: boolean;
}

function parseStoryboardPanels(json: string | null): NormalizedPanel[] {
  if (!json) return [];
  try {
    const data = JSON.parse(json);
    if (!data.panels || !Array.isArray(data.panels)) return [];
    // 复用 Step5page 的 normalizePanels 逻辑（内联简化版）
    return data.panels.map((p: any) => ({
      scene: p.scene || p.background?.scene_desc || '',
      characters: typeof p.characters === 'string' ? p.characters
        : Array.isArray(p.characters) ? p.characters.map((c: any) => c.name || '').filter(Boolean).join(' / ')
        : '',
      shot_size: p.shot_size || p.shot_type || '',
      camera_angle: p.camera_angle || '',
      dialogue: typeof p.dialogue === 'string' ? p.dialogue
        : Array.isArray(p.dialogue) ? p.dialogue.map((d: any) => d.speaker ? `${d.speaker}: ${d.text || ''}` : (d.text || '')).filter(Boolean).join(' / ')
        : '',
      effects: p.effects || '',
    }));
  } catch {
    return [];
  }
}

/** 判断剧集是否在生产模式（分镜已确认） */
function isProductionMode(ep: EpisodeCardData): boolean {
  return ep.status === 'DONE' || ep.productionStatus === 'IN_PROGRESS'
    || ep.productionStatus === 'COMPLETED' || ep.productionStatus === 'FAILED';
}

const EpisodeCard = ({
  episode,
  isCurrentReview,
  isLoadingStoryboard,
  storyboardStatusDesc,
  storyboardFailed,
  sceneGridUrls,
  onConfirm,
  onRevise,
  onRetry,
  onLoadStoryboard,
  onGenerateVideo,
  onAutoContinue,
  isGlobalSubmitting,
}: EpisodeCardProps) => {
  const [showRevision, setShowRevision] = useState(false);
  const [feedback, setFeedback] = useState('');
  const [manualFusionOpen, setManualFusionOpen] = useState(false);

  const panels = parseStoryboardPanels(episode.storyboardJson);
  const inProduction = isProductionMode(episode);
  const isCompleted = episode.productionStatus === 'COMPLETED';

  // 分镜加载
  useEffect(() => {
    if (isCurrentReview && !episode.storyboardJson && !isLoadingStoryboard) {
      onLoadStoryboard();
    }
  }, [isCurrentReview, episode.storyboardJson, isLoadingStoryboard, onLoadStoryboard]);

  // 统计
  const totalPanels = episode.panelStates.length || panels.length;
  const fusedCount = episode.panelStates.filter(p => p.fusionStatus === 'completed').length;
  const videoCount = episode.panelStates.filter(p => p.videoStatus === 'completed').length;

  return (
    <div className={`${styles.card} ${isCurrentReview ? styles.cardActive : ''}`}>
      {/* ---- 卡片头部 ---- */}
      <div className={styles.cardHeader}>
        <div className={styles.cardTitle}>
          <span className={styles.episodeNum}>EP{episode.episodeNum}</span>
          <span className={styles.episodeTitle}>{episode.title}</span>
        </div>
        <div className={styles.cardMeta}>
          {inProduction && (
            <span className={styles.progressBadge}>
              融合 {fusedCount}/{totalPanels} · 视频 {videoCount}/{totalPanels}
            </span>
          )}
          <span className={`${styles.statusBadge} ${styles[`status_${(episode.productionStatus || episode.status).toLowerCase()}`]}`}>
            {episode.productionStatus === 'COMPLETED' ? '已完成'
              : episode.productionStatus === 'IN_PROGRESS' ? '生产中'
              : episode.productionStatus === 'FAILED' ? '失败'
              : episode.status === 'DONE' ? '待生产'
              : episode.status === 'GENERATING' ? '生成中'
              : episode.status === 'FAILED' ? '生成失败'
              : '待审核'}
          </span>
        </div>
      </div>

      {/* ---- 审核模式 ---- */}
      {!inProduction && (
        <div className={styles.cardBody}>
          {storyboardFailed && (
            <div className={styles.failedState}>
              <span className={styles.failedIcon}>!</span>
              <span>{storyboardStatusDesc || '分镜生成失败'}</span>
              <button className={styles.actionBtn} onClick={onRetry} disabled={isGlobalSubmitting}>
                重试
              </button>
            </div>
          )}

          {isLoadingStoryboard && !storyboardFailed && (
            <div className={styles.loadingState}>
              <span className={styles.spinner} />
              <span>{storyboardStatusDesc || '生成分镜中...'}</span>
            </div>
          )}

          {!isLoadingStoryboard && !storyboardFailed && panels.length > 0 && (
            <div className={styles.panelList}>
              {panels.map((panel, idx) => (
                <div key={idx} className={styles.panelItem}>
                  <div className={styles.panelItemHeader}>
                    <span className={styles.panelItemIndex}>#{idx + 1}</span>
                    {(panel.shot_size || panel.camera_angle) && (
                      <span className={styles.panelItemMeta}>
                        {panel.shot_size}{panel.shot_size && panel.camera_angle ? ' / ' : ''}{panel.camera_angle}
                      </span>
                    )}
                  </div>
                  {panel.scene && (
                    <div className={styles.panelItemField}>
                      <span className={styles.fieldLabel}>场景</span>
                      <span className={styles.fieldValue}>{panel.scene}</span>
                    </div>
                  )}
                  {panel.characters && (
                    <div className={styles.panelItemField}>
                      <span className={styles.fieldLabel}>角色</span>
                      <span className={styles.fieldValue}>{panel.characters}</span>
                    </div>
                  )}
                  {panel.dialogue && (
                    <div className={styles.panelItemField}>
                      <span className={styles.fieldLabel}>对话</span>
                      <span className={styles.fieldValue}>"{panel.dialogue}"</span>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {!isLoadingStoryboard && !storyboardFailed && panels.length === 0 && (
            <div className={styles.emptyCard}>暂无分镜数据</div>
          )}

          {/* 审核操作 */}
          {!isLoadingStoryboard && !storyboardFailed && panels.length > 0 && (
            <div className={styles.cardActions}>
              <button className={styles.primaryBtn} onClick={onConfirm} disabled={isGlobalSubmitting}>
                {isGlobalSubmitting ? '处理中...' : '确认分镜'}
              </button>
              <button className={styles.secondaryBtn} onClick={() => setShowRevision(!showRevision)} disabled={isGlobalSubmitting}>
                {showRevision ? '取消修订' : '修订'}
              </button>
            </div>
          )}

          {/* 修订输入 */}
          {showRevision && (
            <div className={styles.revisionBox}>
              <textarea
                className={styles.revisionInput}
                placeholder="输入修订意见..."
                value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
                rows={3}
                disabled={isGlobalSubmitting}
              />
              <div className={styles.revisionActions}>
                <button className={styles.primaryBtn} onClick={() => { onRevise(feedback); setFeedback(''); setShowRevision(false); }} disabled={isGlobalSubmitting || !feedback.trim()}>
                  {isGlobalSubmitting ? '提交中...' : '提交修订'}
                </button>
                <button className={styles.secondaryBtn} onClick={() => { setFeedback(''); setShowRevision(false); }}>
                  取消
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ---- 生产模式 ---- */}
      {inProduction && (
        <div className={styles.cardBody}>
          {/* 场景网格参考图 */}
          {sceneGridUrls.length > 0 && (
            <div className={styles.sceneGridSection}>
              <div className={styles.sectionLabel}>场景网格图</div>
              <div className={styles.sceneGridList}>
                {sceneGridUrls.map((url, idx) => (
                  <div key={`${url}-${idx}`} className={styles.sceneGridItem}>
                    <img src={url} alt={`场景页${idx + 1}`} />
                    <span className={styles.sceneGridLabel}>第 {idx + 1} 页</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 面板网格 */}
          <div className={styles.sectionLabel}>面板状态</div>
          <div className={styles.panelGrid}>
            {episode.panelStates.length > 0 ? (
              episode.panelStates.map((panel) => (
                <PanelCell
                  key={panel.panelIndex}
                  panel={panel}
                  onGenerateVideo={() => onGenerateVideo(panel.panelIndex)}
                />
              ))
            ) : (
              <div className={styles.emptyGrid}>
                {episode.productionStatus === 'NOT_STARTED'
                  ? '等待开始生产...'
                  : '加载面板状态中...'}
              </div>
            )}
          </div>

          {/* 最终视频 */}
          {isCompleted && episode.finalVideoUrl && (
            <div className={styles.finalVideoSection}>
              <div className={styles.sectionLabel}>最终视频</div>
              <video className={styles.finalVideo} src={episode.finalVideoUrl} controls />
            </div>
          )}

          {/* 生产操作栏 */}
          <div className={styles.cardActions}>
            <button className={styles.primaryBtn} onClick={onAutoContinue}>
              一键自动化执行
            </button>
          </div>
        </div>
      )}

      {/* ---- 手动融合模态框 ---- */}
      {manualFusionOpen && (
        <div className={styles.modalOverlay} onClick={() => setManualFusionOpen(false)}>
          <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
            <GridFusionEditor
              episodeId={String(episode.id)}
              onFusionSubmitted={() => setManualFusionOpen(false)}
              mode="modal"
              onClose={() => setManualFusionOpen(false)}
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default EpisodeCard;
```

### 4.2 EpisodeCard.module.less 完整代码

```less
.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: border-color 0.2s;

  &:hover {
    border-color: var(--color-accent);
  }
}

.cardActive {
  border-color: var(--color-accent);
  box-shadow: 0 0 0 1px var(--color-accent);
}

/* ---- 头部 ---- */

.cardHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
  background: rgba(0, 0, 0, 0.08);
}

.cardTitle {
  display: flex;
  align-items: center;
  gap: 12px;
}

.episodeNum {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-accent);
  background: rgba(114, 183, 255, 0.12);
  padding: 2px 10px;
  border-radius: var(--radius-sm);
}

.episodeTitle {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.cardMeta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.progressBadge {
  font-size: 12px;
  color: var(--color-text-muted);
}

.statusBadge {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: var(--radius-sm);
}

.status_completed,
.status_done {
  background: rgba(34, 197, 94, 0.12);
  color: #4ade80;
}

.status_in_progress {
  background: rgba(114, 183, 255, 0.12);
  color: var(--color-accent);
}

.status_generating {
  background: rgba(114, 183, 255, 0.12);
  color: var(--color-accent);
}

.status_failed {
  background: rgba(239, 68, 68, 0.12);
  color: rgba(255, 120, 120, 0.95);
}

.status_draft,
.status_not_started {
  background: rgba(247, 249, 252, 0.06);
  color: var(--color-text-muted);
}

/* ---- 内容区 ---- */

.cardBody {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---- 审核模式 ---- */

.panelList {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.panelItem {
  padding: 12px 16px;
  background: rgba(0, 0, 0, 0.15);
  border-radius: var(--radius-sm);
  border: 1px solid rgba(247, 249, 252, 0.06);
}

.panelItemHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.panelItemIndex {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-accent);
}

.panelItemMeta {
  font-size: 11px;
  color: var(--color-text-muted);
}

.panelItemField {
  display: flex;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 13px;
  line-height: 1.5;

  &:last-child {
    margin-bottom: 0;
  }
}

.fieldLabel {
  flex-shrink: 0;
  color: var(--color-text-muted);
  min-width: 32px;
  font-size: 12px;

  &::after {
    content: '：';
  }
}

.fieldValue {
  color: rgba(247, 249, 252, 0.8);
}

/* ---- 状态区 ---- */

.loadingState,
.failedState,
.emptyCard {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px;
  color: var(--color-text-secondary);
  font-size: 14px;
}

.failedState {
  color: rgba(255, 120, 120, 0.9);
}

.failedIcon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(239, 68, 68, 0.15);
  font-size: 16px;
  font-weight: 700;
}

.spinner {
  display: inline-block;
  width: 20px;
  height: 20px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---- 修订 ---- */

.revisionBox {
  padding: 16px;
  background: rgba(114, 183, 255, 0.06);
  border: 1px solid rgba(114, 183, 255, 0.18);
  border-radius: var(--radius-md);
}

.revisionInput {
  width: 100%;
  min-height: 72px;
  padding: 10px 12px;
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  color: var(--color-text-primary);
  font-size: 14px;
  line-height: 1.5;
  resize: vertical;
  font-family: inherit;

  &::placeholder {
    color: rgba(247, 249, 252, 0.25);
  }

  &:focus {
    outline: none;
    border-color: rgba(114, 183, 255, 0.4);
  }
}

.revisionActions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

/* ---- 生产模式 ---- */

.sectionLabel {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.sceneGridSection {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sceneGridList {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.sceneGridItem {
  position: relative;
  width: 160px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  border: 1px solid var(--color-border);

  img {
    width: 100%;
    aspect-ratio: 3 / 2;
    object-fit: cover;
    display: block;
  }
}

.sceneGridLabel {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 4px 8px;
  background: rgba(0, 0, 0, 0.7);
  font-size: 11px;
  color: var(--color-text-secondary);
  text-align: center;
}

/* ---- 面板网格 ---- */

.panelGrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.emptyGrid {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--color-text-muted);
  font-size: 14px;
}

/* ---- 最终视频 ---- */

.finalVideoSection {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.finalVideo {
  width: 100%;
  max-height: 360px;
  border-radius: var(--radius-md);
  background: #000;
}

/* ---- 操作栏 ---- */

.cardActions {
  display: flex;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid rgba(247, 249, 252, 0.06);
}

.primaryBtn {
  flex: 1;
  padding: 10px 16px;
  background: var(--color-accent);
  color: var(--color-text-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;

  &:hover:not(:disabled) {
    opacity: 0.85;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.secondaryBtn {
  flex: 1;
  padding: 10px 16px;
  background: transparent;
  color: var(--color-accent);
  border: 1px solid var(--color-accent);
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;

  &:hover:not(:disabled) {
    background: var(--color-accent);
    color: var(--color-text-primary);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.actionBtn {
  padding: 8px 20px;
  background: transparent;
  color: var(--color-accent);
  border: 1px solid var(--color-accent);
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: var(--color-accent);
    color: var(--color-text-primary);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

/* ---- 模态 ---- */

.modalOverlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modalContent {
  max-width: 90vw;
  max-height: 90vh;
  overflow: auto;
}
```

- [ ] **Step 3: 写入 EpisodeCard.tsx**

Write the complete code above to `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx`.

- [ ] **Step 4: 写入 EpisodeCard.module.less**

Write the complete styles above to `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less`.

---

## Task 5: PanelCell 组件

**Files:**
- Create: `src/pages/create/steps/components/PanelCell.tsx`
- Create: `src/pages/create/steps/components/PanelCell.module.less`

### 5.1 PanelCell.tsx 完整代码

```typescript
import styles from './PanelCell.module.less';
import type { PanelState } from '../../../../services/types/episode.types';

interface PanelCellProps {
  panel: PanelState;
  onGenerateVideo: () => void;
}

const PanelCell = ({ panel, onGenerateVideo }: PanelCellProps) => {
  const {
    panelIndex,
    fusionStatus,
    fusionUrl,
    videoStatus,
    videoUrl,
    sceneDescription,
    shotType,
    dialogue,
    panelId,
  } = panel;

  const hasFusion = fusionStatus === 'completed' && !!fusionUrl;
  const hasVideo = videoStatus === 'completed' && !!videoUrl;
  const isVideoGenerating = videoStatus === 'generating';
  const isVideoFailed = videoStatus === 'failed';

  return (
    <div className={styles.cell}>
      {/* 格子头部 */}
      <div className={styles.cellHeader}>
        <span className={styles.cellIndex}>#{(panelIndex ?? 0) + 1}</span>
        {shotType && <span className={styles.cellShot}>{shotType}</span>}
      </div>

      {/* 描述 */}
      {sceneDescription && (
        <div className={styles.cellDesc}>{sceneDescription.slice(0, 40)}{sceneDescription.length > 40 ? '...' : ''}</div>
      )}

      {/* 视觉区域 */}
      <div className={styles.cellVisual}>
        {/* 融合图 */}
        {hasFusion && (
          <div className={styles.visualItem}>
            <img className={styles.visualImage} src={fusionUrl!} alt={`融合${panelIndex + 1}`} />
            <span className={styles.visualLabel}>融合图</span>
          </div>
        )}

        {/* 视频 */}
        {hasVideo && (
          <div className={styles.visualItem}>
            <video className={styles.visualVideo} src={videoUrl!} controls preload="metadata" />
            <span className={styles.visualLabel}>视频</span>
          </div>
        )}

        {/* 状态占位 */}
        {!hasFusion && !hasVideo && !isVideoGenerating && (
          <div className={styles.placeholder}>
            {fusionStatus === 'pending' ? '待融合' : ''}
          </div>
        )}

        {isVideoGenerating && (
          <div className={styles.placeholder}>
            <span className={styles.spinner} />
            <span>生成中</span>
          </div>
        )}

        {isVideoFailed && (
          <div className={styles.placeholder}>
            <span className={styles.failedText}>失败</span>
            <button className={styles.retryBtn} onClick={onGenerateVideo}>重试</button>
          </div>
        )}

        {/* 有融合图但无视频 */}
        {hasFusion && !hasVideo && !isVideoGenerating && !isVideoFailed && (
          <div className={styles.placeholder}>
            <button className={styles.generateBtn} onClick={onGenerateVideo}>生成视频</button>
          </div>
        )}
      </div>

      {/* 对话 */}
      {dialogue && (
        <div className={styles.cellDialogue}>"{dialogue.slice(0, 30)}{dialogue.length > 30 ? '...' : ''}"</div>
      )}
    </div>
  );
};

export default PanelCell;
```

### 5.2 PanelCell.module.less 完整代码

```less
.cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  background: rgba(0, 0, 0, 0.15);
  border: 1px solid rgba(247, 249, 252, 0.06);
  border-radius: var(--radius-md);
  transition: border-color 0.2s;

  &:hover {
    border-color: rgba(247, 249, 252, 0.14);
  }
}

.cellHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cellIndex {
  font-size: 12px;
  font-weight: 700;
  color: var(--color-accent);
}

.cellShot {
  font-size: 11px;
  color: var(--color-text-muted);
}

.cellDesc {
  font-size: 11px;
  color: var(--color-text-secondary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cellDialogue {
  font-size: 11px;
  font-style: italic;
  color: var(--color-text-muted);
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 视觉区域 ---- */

.cellVisual {
  position: relative;
  aspect-ratio: 16 / 9;
  background: rgba(0, 0, 0, 0.2);
  border-radius: var(--radius-sm);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.visualItem {
  flex: 1;
  position: relative;
  min-height: 0;
}

.visualImage,
.visualVideo {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.visualLabel {
  position: absolute;
  bottom: 4px;
  right: 4px;
  padding: 1px 6px;
  background: rgba(0, 0, 0, 0.7);
  border-radius: 3px;
  font-size: 10px;
  color: var(--color-text-secondary);
}

/* ---- 占位符 ---- */

.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: 1;
  color: var(--color-text-muted);
  font-size: 11px;
}

.failedText {
  color: rgba(255, 120, 120, 0.9);
}

.spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---- 按钮 ---- */

.retryBtn {
  padding: 4px 12px;
  background: transparent;
  color: rgba(255, 120, 120, 0.9);
  border: 1px solid rgba(255, 120, 120, 0.3);
  border-radius: var(--radius-sm);
  font-size: 11px;
  cursor: pointer;

  &:hover {
    background: rgba(255, 120, 120, 0.15);
  }
}

.generateBtn {
  padding: 8px 16px;
  background: var(--color-accent);
  color: var(--color-text-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;

  &:hover {
    opacity: 0.85;
  }
}
```

- [ ] **Step 3: 写入 PanelCell.tsx**

Write the complete code above to `frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelCell.tsx`.

- [ ] **Step 4: 写入 PanelCell.module.less**

Write the complete styles above to `frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelCell.module.less`.

---

## Task 6: 修复导入和类型导出

**Files:**
- Modify: `src/pages/create/steps/Step5page.tsx` (export NormalizedPanel)
- Modify: `src/services/types/episode.types.ts` (ensure EpisodeCardData exported)
- Modify: `src/services/index.ts` (check if types are re-exported)

- [ ] **Step 1: 导出 NormalizedPanel 类型**

在 `Step5page.tsx` 中，`NormalizedPanel` 被定义为 `interface`，但 `EpisodeCard.tsx` 通过 `import type { NormalizedPanel } from '../Step5page'` 引用它。需要确认 TypeScript 允许这种跨文件类型导入。

如果不行，将 `NormalizedPanel` 移到 `types/episode.types.ts` 中。

- [ ] **Step 2: 确认所有类型导出正确**

检查 `episode.types.ts` 是否正确导出 `EpisodeCardData`。检查 `episodeService.ts` 是否导出所有被 `Step5page.tsx` 使用的 API 函数。

- [ ] **Step 3: 验证 TypeScript 编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 编译通过，无类型错误

---

## Task 7: 构建验证

- [ ] **Step 1: 运行完整构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build`
Expected: 构建成功（✓ built in Xs, 570 modules）

- [ ] **Step 2: 检查构建产物**

Run: `ls frontend/wiset_aivideo_generator/dist/`
Expected: `index.html` + `assets/` 目录

---

## Task 8: 后端编译验证

- [ ] **Step 1: 验证后端编译（确认之前新增的 regeneratePanelScene 仍然通过）**

Run: `"C:/Users/HP/apache-maven-3.9.9/bin/mvn.cmd" compile -q -f backend/com/comic/pom.xml`
Expected: 编译通过，EXIT_CODE: 0
