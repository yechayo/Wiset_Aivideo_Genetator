# Step4 分镜生产重构 - Phase 1-3 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Step4Production 从 2000+ 行单文件重构为 Zustand store 驱动的分层架构，实现细粒度状态更新、乐观更新+SSE 确认、Segment 级完整状态机。

**Architecture:** 渐进式重构 - Phase 1 建基础设施(Store+适配器)、Phase 2 状态迁移(组件接 store)、Phase 3 细粒度更新(精准更新+乐观更新)。每个 Phase 可独立验证。

**Tech Stack:** React 18 + Zustand + TypeScript + SSE (现有)

---

## 文件结构

```
frontend/wiset_aivideo_generator/src/pages/create/steps/
├── Step4Production.tsx               # 主入口（Phase 2 重构后 < 200行）
│
├── store/
│   ├── step4Store.ts               # Zustand store（核心，Phase 1）
│   └── step4Types.ts               # 统一类型定义（Phase 1）
│
├── services/
│   └── step4Adapter.ts             # 后端 → 前端类型转换（Phase 1）
│
├── components/                      # Phase 2-3 逐步改造
│   ├── ScriptEpisodeCard.tsx
│   ├── GridEpisodeCard.tsx
│   ├── VideoSegmentRow.tsx
│   └── DoneEpisodeCard.tsx         # 抽取自 Step4Production
│
└── tabs/                           # Phase 5 拆分
    ├── ScriptTab.tsx
    ├── GridTab.tsx
    └── VideoTab.tsx
```

**关键依赖**：
- 现有 `episodeService.ts` - API 调用（不变）
- 现有 `useSseProgress.ts` - SSE 连接（Phase 1 改造为 store dispatcher）
- 现有 `stores/index.ts` - 需导出新 store

---

## Phase 1: 基础设施

> **Task 执行顺序注意**: Task 4 (SSE handler) 依赖 Task 5 (useStep4Data) 中定义的 `refreshStatuses` 和 `loadPanelsForEpisode`。必须先完成 Task 5 再完成 Task 4，或者将 Task 4 的实现推迟到 Task 5 之后。推荐顺序：Task 1 → Task 2 → Task 3 → Task 5 → Task 4 → Task 6。

### Task 1: 定义 step4Types.ts 统一类型

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Types.ts`

- [ ] **Step 1: 创建类型定义文件**

```typescript
// frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Types.ts

/** Episode 流水线阶段（派生自 panelApproved + gridStatus）*/
export type EpisodeStage = 'script_review' | 'grid_review' | 'video_production' | 'completed';

/** Episode 级九宫格状态 */
export type EpisodeGridStatus = 'pending' | 'text_ready' | 'generating' | 'generated' | 'approved' | 'rejected' | 'failed';

/** Segment 子任务状态 */
export type TaskStatus = 'pending' | 'generating' | 'completed' | 'failed';

/** 脚本/分镜生成状态 */
export type ScriptGenStatus = 'pending' | 'generating' | 'done';

/** 分镜数据 */
export interface ShotData {
  shotNumber: number;
  shotSize?: string;
  cameraAngle?: string;
  cameraMovement?: string;
  scene?: string;
  visualDescription?: string;
  dialogue?: string;
  speaker?: string;
  narration?: string;
  duration?: number;
  characters?: string[];
  visualEffects?: string;
  audioEffects?: string;
  [key: string]: any;
}

/** Panel 分镜详细信息 */
export interface PanelData {
  panelId: string;
  planPanelId: string;
  composition: string;
  shotType: string;
  cameraAngle: string;
  cameraMovement: string;
  pacing: string;
  dialogue: string;
  scene: string;
  characters: any[];
  background: any;
  imagePromptHint: string;
  sfx: string[];
  duration?: number;
  totalShots?: number;
  totalDuration?: number;
  visualStyle?: string;
  fusionImageUrl?: string | null;
}

/** Segment 分镜状态记录 */
export interface SegmentRecord {
  segmentKey: string;       // "episodeId-panelId"
  episodeId: number;
  segmentIndex: number;
  title: string;
  synopsis: string;
  sceneThumbnail: string | null;

  // 每个子任务独立状态机
  videoTask: {
    status: TaskStatus;
    url?: string | null;
    progress?: number | null;  // 0-100
    error?: string;
    taskId?: string | null;
    model?: string | null;
    offPeak?: boolean | null;
    credits?: number | null;
  };
  ttsTask: {
    status: TaskStatus;
    url?: string | null;
    error?: string;
    credits?: number | null;
  };
  mergeTask: {
    status: TaskStatus;
    url?: string | null;
    error?: string;
  };

  // 分镜详情（从 panels API 获取）
  panelData?: PanelData;
  shots?: ShotData[];
  feedback?: string;
  // grid 关联数据
  gridImages?: string[];
  gridStatus?: string;
  fusionImageUrl?: string | null;
}

/** Episode 剧集状态记录 */
export interface EpisodeRecord {
  episodeId: number;
  episodeIndex: number;
  title: string;
  chapterTitle: string;
  chapterIndex: number;

  /** 当前流水线阶段 */
  stage: EpisodeStage;

  /** 脚本数据 */
  scriptData: {
    shots: ShotData[];
    scriptStatus: ScriptGenStatus;
    storyboardStatus: ScriptGenStatus;
  };

  /** 九宫格数据 */
  gridData: {
    status: EpisodeGridStatus;
    prompt?: string;
    promptHint?: string;
    images: string[];
    feedback?: string | null;
  };

  /** 分镜 ID 列表 */
  segmentKeys: string[];

  /** 原始 episodeInfo（透传后端原始数据） */
  _raw?: Record<string, any>;
}

/** 生成任务追踪 */
export interface GenerationTask {
  taskKey: string;  // "script-ep-1", "grid-ep-1", "video-ep1-panel1", "tts-ep1-panel1"
  type: 'script' | 'grid' | 'video' | 'tts' | 'merge' | 'enhance';
  episodeId: number;
  segmentKey?: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  startedAt?: number;
  error?: string;
}

/** UI 状态 */
export interface Step4UIState {
  activeTab: 'script' | 'grid' | 'video';
  expandedEpisodeIds: Set<number>;
  expandedSegmentKeys: Set<string>;
  collapsedChapterIds: Set<number>;
  promptModal: {
    segmentKey: string | null;
    text: string;
    mode: 'view' | 'edit';
    loading: boolean;
    enhancing: boolean;
  };
  lightboxUrl: string | null;
  advancing: boolean;
}

/** 加载状态 */
export interface Step4LoadingState {
  episodes: boolean;
  segments: Map<number, boolean>;   // per-episode panels loading
  statuses: Map<number, boolean>;   // per-episode status polling
}

/** 用户偏好 */
export interface Step4Preferences {
  offPeak: boolean;
  videoModel: 'pro' | 'turbo';
  scriptTabCollapsedChapters: Map<number, boolean>;
  gridTabCollapsedChapters: Map<number, boolean>;
  videoTabCollapsedChapters: Map<number, boolean>;
}

/** Step4 Store 完整状态 */
export interface Step4State {
  projectId: string | null;

  // 实体数据
  episodeMap: Map<number, EpisodeRecord>;
  segmentMap: Map<string, SegmentRecord>;

  // UI 状态
  ui: Step4UIState;

  // 任务追踪
  tasks: Map<string, GenerationTask>;

  // 加载状态
  loading: Step4LoadingState;

  // 乐观更新快照
  snapshots: Map<string, Partial<SegmentRecord>>;

  // 偏好
  preferences: Step4Preferences;

  // 全局错误
  error: string | null;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Types.ts
git commit -m "feat(step4): 定义 step4Store 统一类型系统"
```

---

### Task 2: 创建 step4Store.ts

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts`

- [ ] **Step 1: 创建基础 Store 结构**

```typescript
// frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts

import { create } from 'zustand';
import type {
  Step4State,
  EpisodeRecord,
  SegmentRecord,
  EpisodeStage,
  TaskStatus,
  GenerationTask,
} from './step4Types';

interface Step4Actions {
  // ===== 初始化 =====
  init: (projectId: string) => void;
  reset: () => void;

  // ===== Episode 操作 =====
  setEpisodes: (episodes: EpisodeRecord[]) => void;
  updateEpisode: (episodeId: number, patch: Partial<EpisodeRecord>) => void;

  // ===== Segment 操作 =====
  setSegments: (episodeId: number, segments: SegmentRecord[]) => void;
  updateSegment: (segmentKey: string, patch: Partial<SegmentRecord>) => void;

  // ===== 任务追踪 =====
  startTask: (task: Omit<GenerationTask, 'status' | 'startedAt'>) => void;
  completeTask: (taskKey: string) => void;
  failTask: (taskKey: string, error: string) => void;
  isTaskRunning: (taskKey: string) => boolean;

  // ===== 乐观更新 =====
  optimisticUpdate: (segmentKey: string, patch: Partial<SegmentRecord>) => void;
  rollback: (segmentKey: string) => void;

  // ===== UI 状态 =====
  setActiveTab: (tab: 'script' | 'grid' | 'video') => void;
  toggleEpisodeExpanded: (episodeId: number) => void;
  toggleSegmentExpanded: (segmentKey: string) => void;
  toggleChapterCollapsed: (chapterIndex: number) => void;

  // ===== Prompt Modal =====
  openPromptModal: (segmentKey: string) => void;
  closePromptModal: () => void;
  setPromptModalText: (text: string) => void;
  setPromptModalMode: (mode: 'view' | 'edit') => void;
  setPromptModalLoading: (loading: boolean) => void;
  setPromptModalEnhancing: (enhancing: boolean) => void;

  // ===== Lightbox =====
  setLightboxUrl: (url: string | null) => void;

  // ===== 偏好 =====
  setOffPeak: (offPeak: boolean) => void;
  setVideoModel: (model: 'pro' | 'turbo') => void;

  // ===== 加载状态 =====
  setEpisodesLoading: (loading: boolean) => void;
  setSegmentsLoading: (episodeId: number, loading: boolean) => void;
  setStatusesLoading: (episodeId: number, loading: boolean) => void;

  // ===== 错误 =====
  setError: (error: string | null) => void;

  // ===== 派生数据 =====
  getChapterGroups: () => ChapterGroup[];
  getScriptEpisodes: () => EpisodeRecord[];
  getGridEpisodes: () => EpisodeRecord[];
  getVideoEpisodes: () => EpisodeRecord[];
  getEpisode: (episodeId: number) => EpisodeRecord | undefined;
  getSegment: (segmentKey: string) => SegmentRecord | undefined;
}

export interface ChapterGroup {
  chapterIndex: number;
  title: string;
  episodes: EpisodeRecord[];
}

const initialUI = {
  activeTab: 'script' as const,
  expandedEpisodeIds: new Set<number>(),
  expandedSegmentKeys: new Set<string>(),
  collapsedChapterIds: new Set<number>(),
  promptModal: {
    segmentKey: null as string | null,
    text: '',
    mode: 'view' as const,
    loading: false,
    enhancing: false,
  },
  lightboxUrl: null as string | null,
  advancing: false,
};

const initialLoading = {
  episodes: false,
  segments: new Map<number, boolean>(),
  statuses: new Map<number, boolean>(),
};

const initialPreferences = {
  offPeak: false,
  videoModel: 'turbo' as const,
  scriptTabCollapsedChapters: new Map<number, boolean>(),
  gridTabCollapsedChapters: new Map<number, boolean>(),
  videoTabCollapsedChapters: new Map<number, boolean>(),
};

export const useStep4Store = create<Step4State & Step4Actions>()((set, get) => ({
  // ===== State =====
  projectId: null,
  episodeMap: new Map(),
  segmentMap: new Map(),
  tasks: new Map(),
  snapshots: new Map(),
  loading: initialLoading,
  ui: initialUI,
  preferences: initialPreferences,
  error: null,

  // ===== 初始化 =====
  init: (projectId: string) => {
    set({
      projectId,
      episodeMap: new Map(),
      segmentMap: new Map(),
      tasks: new Map(),
      snapshots: new Map(),
      loading: { episodes: false, segments: new Map(), statuses: new Map() },
      error: null,
    });
  },

  reset: () => {
    set({
      projectId: null,
      episodeMap: new Map(),
      segmentMap: new Map(),
      tasks: new Map(),
      snapshots: new Map(),
      loading: { episodes: false, segments: new Map(), statuses: new Map() },
      ui: initialUI,
      error: null,
    });
  },

  // ===== Episode 操作 =====
  setEpisodes: (episodes: EpisodeRecord[]) => {
    const map = new Map<number, EpisodeRecord>();
    episodes.forEach(ep => map.set(ep.episodeId, ep));
    set({ episodeMap: map });
  },

  updateEpisode: (episodeId: number, patch: Partial<EpisodeRecord>) => {
    set(state => {
      const existing = state.episodeMap.get(episodeId);
      if (!existing) return state;
      const next = new Map(state.episodeMap);
      next.set(episodeId, { ...existing, ...patch });
      return { episodeMap: next };
    });
  },

  // ===== Segment 操作 =====
  setSegments: (episodeId: number, segments: SegmentRecord[]) => {
    set(state => {
      const next = new Map(state.segmentMap);
      segments.forEach(seg => next.set(seg.segmentKey, seg));
      // 同时更新 episode 的 segmentKeys
      const ep = state.episodeMap.get(episodeId);
      if (ep) {
        const epMap = new Map(state.episodeMap);
        epMap.set(episodeId, {
          ...ep,
          segmentKeys: segments.map(s => s.segmentKey),
        });
        return { segmentMap: next, episodeMap: epMap };
      }
      return { segmentMap: next };
    });
  },

  updateSegment: (segmentKey: string, patch: Partial<SegmentRecord>) => {
    set(state => {
      const existing = state.segmentMap.get(segmentKey);
      if (!existing) return state;
      const next = new Map(state.segmentMap);
      next.set(segmentKey, { ...existing, ...patch });
      return { segmentMap: next };
    });
  },

  // ===== 任务追踪 =====
  startTask: (task: Omit<GenerationTask, 'status' | 'startedAt'>) => {
    const taskKey = task.taskKey;
    set(state => {
      const next = new Map(state.tasks);
      next.set(taskKey, { ...task, status: 'running', startedAt: Date.now() });
      return { tasks: next };
    });
  },

  completeTask: (taskKey: string) => {
    set(state => {
      const existing = state.tasks.get(taskKey);
      if (!existing) return state;
      const next = new Map(state.tasks);
      next.set(taskKey, { ...existing, status: 'completed' });
      return { tasks: next };
    });
  },

  failTask: (taskKey: string, error: string) => {
    set(state => {
      const existing = state.tasks.get(taskKey);
      if (!existing) return state;
      const next = new Map(state.tasks);
      next.set(taskKey, { ...existing, status: 'failed', error });
      return { tasks: next };
    });
  },

  isTaskRunning: (taskKey: string) => {
    const task = get().tasks.get(taskKey);
    return task?.status === 'running';
  },

  // ===== 乐观更新 =====
  optimisticUpdate: (segmentKey: string, patch: Partial<SegmentRecord>) => {
    set(state => {
      const existing = state.segmentMap.get(segmentKey);
      if (!existing) return state;
      // 保存快照
      const snapshots = new Map(state.snapshots);
      snapshots.set(segmentKey, existing);
      // 应用乐观更新
      const segmentMap = new Map(state.segmentMap);
      segmentMap.set(segmentKey, { ...existing, ...patch });
      return { segmentMap, snapshots };
    });
  },

  rollback: (segmentKey: string) => {
    set(state => {
      const snapshot = state.snapshots.get(segmentKey);
      if (!snapshot) return state;
      const segmentMap = new Map(state.segmentMap);
      segmentMap.set(segmentKey, snapshot as SegmentRecord);
      const snapshots = new Map(state.snapshots);
      snapshots.delete(segmentKey);
      return { segmentMap, snapshots };
    });
  },

  // ===== UI 状态 =====
  setActiveTab: (tab: 'script' | 'grid' | 'video') => {
    set(state => ({
      ui: { ...state.ui, activeTab: tab },
    }));
  },

  toggleEpisodeExpanded: (episodeId: number) => {
    set(state => {
      const next = new Set(state.ui.expandedEpisodeIds);
      if (next.has(episodeId)) next.delete(episodeId); else next.add(episodeId);
      return { ui: { ...state.ui, expandedEpisodeIds: next } };
    });
  },

  toggleSegmentExpanded: (segmentKey: string) => {
    set(state => {
      const next = new Set(state.ui.expandedSegmentKeys);
      if (next.has(segmentKey)) next.delete(segmentKey); else next.add(segmentKey);
      return { ui: { ...state.ui, expandedSegmentKeys: next } };
    });
  },

  toggleChapterCollapsed: (chapterIndex: number) => {
    set(state => {
      const next = new Set(state.ui.collapsedChapterIds);
      if (next.has(chapterIndex)) next.delete(chapterIndex); else next.add(chapterIndex);
      return { ui: { ...state.ui, collapsedChapterIds: next } };
    });
  },

  // ===== Prompt Modal =====
  openPromptModal: (segmentKey: string) => {
    set(state => ({
      ui: {
        ...state.ui,
        promptModal: {
          segmentKey,
          text: '',
          mode: 'view',
          loading: true,
          enhancing: false,
        },
      },
    }));
  },

  closePromptModal: () => {
    set(state => ({
      ui: {
        ...state.ui,
        promptModal: {
          segmentKey: null,
          text: '',
          mode: 'view',
          loading: false,
          enhancing: false,
        },
      },
    }));
  },

  setPromptModalText: (text: string) => {
    set(state => ({
      ui: {
        ...state.ui,
        promptModal: { ...state.ui.promptModal, text },
      },
    }));
  },

  setPromptModalMode: (mode: 'view' | 'edit') => {
    set(state => ({
      ui: {
        ...state.ui,
        promptModal: { ...state.ui.promptModal, mode },
      },
    }));
  },

  setPromptModalLoading: (loading: boolean) => {
    set(state => ({
      ui: {
        ...state.ui,
        promptModal: { ...state.ui.promptModal, loading },
      },
    }));
  },

  setPromptModalEnhancing: (enhancing: boolean) => {
    set(state => ({
      ui: {
        ...state.ui,
        promptModal: { ...state.ui.promptModal, enhancing },
      },
    }));
  },

  // ===== Lightbox =====
  setLightboxUrl: (url: string | null) => {
    set(state => ({
      ui: { ...state.ui, lightboxUrl: url },
    }));
  },

  // ===== 偏好 =====
  setOffPeak: (offPeak: boolean) => {
    set(state => ({
      preferences: { ...state.preferences, offPeak },
    }));
  },

  setVideoModel: (model: 'pro' | 'turbo') => {
    set(state => ({
      preferences: { ...state.preferences, videoModel: model },
    }));
  },

  // ===== 加载状态 =====
  setEpisodesLoading: (loading: boolean) => {
    set(state => ({
      loading: { ...state.loading, episodes: loading },
    }));
  },

  setSegmentsLoading: (episodeId: number, loading: boolean) => {
    set(state => {
      const next = new Map(state.loading.segments);
      if (loading) next.set(episodeId, true); else next.delete(episodeId);
      return { loading: { ...state.loading, segments: next } };
    });
  },

  setStatusesLoading: (episodeId: number, loading: boolean) => {
    set(state => {
      const next = new Map(state.loading.statuses);
      if (loading) next.set(episodeId, true); else next.delete(episodeId);
      return { loading: { ...state.loading, statuses: next } };
    });
  },

  // ===== 错误 =====
  setError: (error: string | null) => {
    set({ error });
  },

  // ===== 派生数据 =====
  getChapterGroups: () => {
    const { episodeMap } = get();
    const groups = new Map<number, ChapterGroup>();
    episodeMap.forEach(ep => {
      if (!groups.has(ep.chapterIndex)) {
        groups.set(ep.chapterIndex, { chapterIndex: ep.chapterIndex, title: ep.chapterTitle, episodes: [] });
      }
      groups.get(ep.chapterIndex)!.episodes.push(ep);
    });
    return Array.from(groups.values()).sort((a, b) => a.chapterIndex - b.chapterIndex);
  },

  getScriptEpisodes: () => {
    const { episodeMap } = get();
    return Array.from(episodeMap.values()).filter(ep => ep.stage === 'script_review');
  },

  getGridEpisodes: () => {
    const { episodeMap } = get();
    return Array.from(episodeMap.values()).filter(ep => ep.stage === 'grid_review');
  },

  getVideoEpisodes: () => {
    const { episodeMap } = get();
    return Array.from(episodeMap.values()).filter(ep => ep.stage === 'video_production');
  },

  getEpisode: (episodeId: number) => get().episodeMap.get(episodeId),

  getSegment: (segmentKey: string) => get().segmentMap.get(segmentKey),
}));
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts
git commit -m "feat(step4): 创建 step4Store Zustand store 基础结构"
```

---

### Task 3: 创建 step4Adapter.ts（后端类型转换）

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/services/step4Adapter.ts`

- [ ] **Step 1: 创建后端 → 前端类型转换器**

```typescript
// frontend/wiset_aivideo_generator/src/pages/create/steps/services/step4Adapter.ts

import type { EpisodeRecord, SegmentRecord, EpisodeStage, EpisodeGridStatus, TaskStatus } from '../store/step4Types';

/** 从 episodeNum + gridStatus 推导 EpisodeStage */
function deriveStage(panelApproved: boolean | undefined, gridStatus: string | undefined): EpisodeStage {
  if (!panelApproved) return 'script_review';
  if (gridStatus === 'approved') return 'video_production';
  return 'grid_review';
}

/** 从 gridStatus + videoStatus 推导 TaskStatus */
function deriveTaskStatus(status: string | undefined): TaskStatus {
  switch (status) {
    case 'generating': return 'generating';
    case 'completed': return 'completed';
    case 'failed': return 'failed';
    default: return 'pending';
  }
}

/** 后端 Episode 数据 → 前端 EpisodeRecord */
export function backendToEpisode(raw: {
  id: number;
  episodeInfo?: Record<string, any>;
  episodeIndex?: number;
}): EpisodeRecord {
  const info = raw.episodeInfo || {};
  const gridStatus = (info.gridStatus || 'pending') as EpisodeGridStatus;

  // 解析 scene_summary 映射（用于提示词构建）
  let sceneSummaryMap: Record<string, string> = {};
  try {
    const planStr = info.panelPlan;
    if (planStr) {
      const plan = JSON.parse(planStr);
      if (Array.isArray(plan?.panels)) {
        plan.panels.forEach((p: any) => {
          if (p.panel_id && p.scene_summary) {
            sceneSummaryMap[p.panel_id] = p.scene_summary;
          }
        });
      }
    }
  } catch { /* ignore */ }

  return {
    episodeId: raw.id,
    episodeIndex: info.episodeNum || raw.episodeIndex || 0,
    title: info.title || '',
    chapterTitle: info.chapterTitle?.trim() || '未分章',
    chapterIndex: info.chapterIndex || 0,
    stage: deriveStage(info.panelApproved, gridStatus),
    scriptData: {
      shots: info.shots || [],
      scriptStatus: info.scriptStatus || 'pending',
      storyboardStatus: info.storyboardStatus || 'pending',
    },
    gridData: {
      status: gridStatus,
      prompt: info.gridPrompt || '',
      promptHint: info.gridPromptHint || '',
      images: info.gridImages || [],
      feedback: info.gridRejectionFeedback || null,
    },
    segmentKeys: [], // 由 loadPanels 时填充
    _raw: info,
  };
}

/** 后端 Panel 数据 → 前端 SegmentRecord */
export function backendToSegment(
  raw: {
    id: number;
    panelInfo?: Record<string, any>;
    episodeId?: number;
  },
  episodeId: number,
  segmentIndex: number,
): SegmentRecord {
  const info = raw.panelInfo || {};
  const shots = info.shots || [];
  const isGroupedPanel = shots.length > 1;
  const synopsis = isGroupedPanel
    ? shots.map((s: any) => {
        const speaker = s.speaker && s.speaker !== '无' ? `【${s.speaker}】` : '';
        const desc = s.visualDescription || s.scene || '';
        return speaker ? `${speaker} ${desc}` : desc;
      }).filter(Boolean).join('\n')
    : (info.scene_summary || shots[0]?.visualDescription || '');

  const thumbnail = info.fusionImageUrl || shots[0]?.splitImageUrl || (info.gridImages?.[0] || null);
  const videoStatus = info.videoStatus || 'pending';
  const ttsStatus = info.ttsStatus || 'pending';
  const mergeStatus = info.mergeStatus || 'pending';
  const gridStatus = info.gridStatus || 'pending';

  return {
    segmentKey: `${episodeId}-${raw.id}`,
    episodeId,
    segmentIndex,
    title: isGroupedPanel ? `分组 ${segmentIndex + 1}` : `分镜 ${segmentIndex + 1}`,
    synopsis,
    sceneThumbnail: thumbnail,

    videoTask: {
      status: deriveTaskStatus(videoStatus),
      url: info.videoUrl || null,
      progress: info.videoProgress != null ? info.videoProgress : null,
      error: videoStatus === 'failed' ? (info.revisionFeedback || '视频生成失败') : undefined,
      taskId: info.videoTaskId || null,
      model: info.videoModel || null,
      offPeak: info.offPeak ?? null,
      credits: info.videoCredits ?? null,
    },

    ttsTask: {
      status: deriveTaskStatus(ttsStatus),
      url: info.ttsAudioUrl || null,
      error: ttsStatus === 'failed' ? (info.revisionFeedback || 'TTS 生成失败') : undefined,
      credits: info.ttsCredits ?? null,
    },

    mergeTask: {
      status: deriveTaskStatus(mergeStatus),
      url: info.videoWithNarrationUrl || null,
      error: mergeStatus === 'failed' ? (info.revisionFeedback || '合并失败') : undefined,
    },

    panelData: {
      panelId: String(raw.id),
      planPanelId: info.panel_id || '',
      composition: info.composition || '',
      shotType: info.shot_type || '',
      cameraAngle: info.camera_angle || '',
      cameraMovement: info.camera_movement || '',
      pacing: info.pacing || '',
      dialogue: Array.isArray(info.dialogue)
        ? info.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n')
        : '',
      characters: info.characters || [],
      background: info.background || {},
      imagePromptHint: info.image_prompt_hint || '',
      sfx: info.sfx || [],
      duration: info.duration || info.totalDuration,
      totalShots: info.totalShots,
      totalDuration: info.totalDuration,
      visualStyle: info.visualStyle,
      fusionImageUrl: info.fusionImageUrl || null,
    },

    shots,
    feedback: info.revisionFeedback || '',
    gridImages: info.gridImages || [],
    gridStatus,
    fusionImageUrl: info.fusionImageUrl || null,
  };
}

/** 后端 production-status 数组 → SegmentRecord patch */
export function backendStatusToSegmentPatch(
  raw: {
    panelId: number;
    gridStatus?: string;
    videoStatus?: string;
    videoUrl?: string;
    videoProgress?: number;
    videoTaskId?: string;
    ttsStatus?: string;
    ttsAudioUrl?: string;
    mergeStatus?: string;
    videoWithNarrationUrl?: string;
    gridImages?: string[];
    fusionImageUrl?: string;
    shots?: any[];
  },
  segmentKey: string,
): Partial<SegmentRecord> {
  return {
    gridStatus: raw.gridStatus,
    gridImages: raw.gridImages?.length ? raw.gridImages : undefined,
    fusionImageUrl: raw.fusionImageUrl ?? undefined,
    shots: raw.shots?.length ? raw.shots : undefined,

    videoTask: {
      status: deriveTaskStatus(raw.videoStatus),
      url: raw.videoUrl || null,
      progress: raw.videoProgress != null ? raw.videoProgress : null,
      error: raw.videoStatus === 'failed' ? '视频生成失败' : undefined,
      taskId: raw.videoTaskId || null,
    },

    ttsTask: {
      status: deriveTaskStatus(raw.ttsStatus),
      url: raw.ttsAudioUrl || null,
      error: raw.ttsStatus === 'failed' ? 'TTS 生成失败' : undefined,
    },

    mergeTask: {
      status: deriveTaskStatus(raw.mergeStatus),
      url: raw.videoWithNarrationUrl || null,
      error: raw.mergeStatus === 'failed' ? '合并失败' : undefined,
    },
  };
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/services/step4Adapter.ts
git commit -m "feat(step4): 创建后端类型转换适配器"
```

---

### Task 4: 将 SSE handler 改造为 store dispatcher

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useSseProgress.ts`（保持原接口不变，内部改为调用 store actions）
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/services/step4SseHandler.ts`

- [ ] **Step 1: 创建 SSE → store dispatcher 桥接层**

```typescript
// frontend/wiset_aivideo_generator/src/pages/create/steps/services/step4SseHandler.ts

import type { SseProgressCallbacks } from '../hooks/useSseProgress';
import type { EpisodeRecord, SegmentRecord } from '../store/step4Types';
import { useStep4Store } from '../store/step4Store';
import { backendToEpisode, backendStatusToSegmentPatch } from './step4Adapter';

/**
 * 创建 SSE 事件 → step4Store actions 的桥接函数
 * 传入 projectId 和可选的额外回调（如 loadPanels API）
 */
export function createStep4SseHandler(projectId: string, deps: {
  loadPanelsForEpisode: (episodeId: number) => Promise<void>;
  refreshStatuses: (episodeId: number) => Promise<void>;
  loadEpisodes: () => Promise<void>;
}): SseProgressCallbacks {
  return {
    onEpisodeScriptDone: (data) => {
      const store = useStep4Store.getState();
      const taskKey = `script-ep-${data.episodeNum}`;
      store.completeTask(taskKey);

      // 更新 episode 的 script/storyboard status
      store.setEpisodesLoading(true);
      deps.loadEpisodes().finally(() => store.setEpisodesLoading(false));
    },

    onEpisodeStoryboardDone: (data) => {
      const store = useStep4Store.getState();
      const taskKey = `script-ep-${data.episodeId}`;
      store.completeTask(taskKey);

      if (data.episodeId) {
        store.setSegmentsLoading(data.episodeId, true);
        deps.loadPanelsForEpisode(data.episodeId).finally(() => {
          store.setSegmentsLoading(data.episodeId, false);
        });
        deps.refreshStatuses(data.episodeId);
      }
      deps.loadEpisodes();
    },

    onEpisodePanelDone: (data) => {
      if (data.episodeId) {
        deps.loadPanelsForEpisode(data.episodeId);
      }
    },

    onEpisodeGridStatus: (data) => {
      const store = useStep4Store.getState();
      const episodeId = data.episodeId || 0;

      // 更新 episode 的 grid 状态
      store.updateEpisode(episodeId, {
        gridData: {
          ...(store.getEpisode(episodeId)?.gridData || { status: 'pending', images: [] }),
          status: (data.gridStatus || 'pending') as any,
        },
        stage: data.gridStatus === 'approved' ? 'video_production'
          : data.gridStatus ? 'grid_review'
          : store.getEpisode(episodeId)?.stage || 'script_review',
      });

      // grid 达到终态时，刷新 panels 和 statuses
      if (data.gridStatus === 'generated' || data.gridStatus === 'approved' || data.gridStatus === 'failed') {
        const taskKey = `grid-ep-${episodeId}`;
        if (data.gridStatus === 'generated' || data.gridStatus === 'approved') {
          store.completeTask(taskKey);
        } else if (data.gridStatus === 'failed') {
          store.failTask(taskKey, '九宫格生成失败');
        }
        deps.loadEpisodes();
      }
    },

    onPanelVideoDone: (data) => {
      const store = useStep4Store.getState();
      const segmentKey = `${data.episodeId}-${data.panelId}`;
      const taskKey = `video-${segmentKey}`;

      store.updateSegment(segmentKey, {
        videoTask: { status: 'completed', url: data.videoUrl },
      });
      store.completeTask(taskKey);

      if (data.episodeId) deps.refreshStatuses(data.episodeId);
    },

    onPanelVideoFailed: (data) => {
      const store = useStep4Store.getState();
      const segmentKey = `${data.episodeId}-${data.panelId}`;
      const taskKey = `video-${segmentKey}`;

      store.updateSegment(segmentKey, {
        videoTask: { status: 'failed', error: data.error || '视频生成失败' },
      });
      store.failTask(taskKey, data.error || '视频生成失败');

      if (data.episodeId) deps.refreshStatuses(data.episodeId);
    },

    onPanelTtsDone: (data) => {
      const store = useStep4Store.getState();
      const segmentKey = `${data.episodeId}-${data.panelId}`;
      const taskKey = `tts-${segmentKey}`;

      store.updateSegment(segmentKey, {
        ttsTask: { status: 'completed', url: data.ttsAudioUrl },
      });
      store.completeTask(taskKey);

      if (data.episodeId) deps.refreshStatuses(data.episodeId);
    },

    onPanelTtsFailed: (data) => {
      const store = useStep4Store.getState();
      const segmentKey = `${data.episodeId}-${data.panelId}`;
      const taskKey = `tts-${segmentKey}`;

      store.updateSegment(segmentKey, {
        ttsTask: { status: 'failed', error: data.error || 'TTS 生成失败' },
      });
      store.failTask(taskKey, data.error || 'TTS 生成失败');

      if (data.episodeId) deps.refreshStatuses(data.episodeId);
    },

    onPanelMergeDone: (data) => {
      const store = useStep4Store.getState();
      const segmentKey = `${data.episodeId}-${data.panelId}`;
      const taskKey = `merge-${segmentKey}`;

      store.updateSegment(segmentKey, {
        mergeTask: { status: 'completed', url: data.videoWithNarrationUrl },
      });
      store.completeTask(taskKey);

      if (data.episodeId) deps.refreshStatuses(data.episodeId);
    },

    onPanelMergeFailed: (data) => {
      const store = useStep4Store.getState();
      const segmentKey = `${data.episodeId}-${data.panelId}`;
      const taskKey = `merge-${segmentKey}`;

      store.updateSegment(segmentKey, {
        mergeTask: { status: 'failed', error: data.error || '合并失败' },
      });
      store.failTask(taskKey, data.error || '合并失败');

      if (data.episodeId) deps.refreshStatuses(data.episodeId);
    },

    onStatusChange: (data) => {
      const store = useStep4Store.getState();
      // task-complete 或 completed 时，停止所有运行中的任务
      if ((data as any).eventType === 'task-complete' || data.to === 'completed') {
        store.tasks.forEach((task, key) => {
          if (task.status === 'running') {
            store.completeTask(key);
          }
        });
        deps.loadEpisodes();
      }
    },

    onReconnect: () => {
      deps.loadEpisodes();
    },
  };
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/services/step4SseHandler.ts
git commit -m "feat(step4): 创建 SSE → store dispatcher 桥接层"
```

---

### Task 5: 创建数据加载 hooks（useStep4Data）

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useStep4Data.ts`

- [ ] **Step 1: 创建数据加载 hook**

```typescript
// frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useStep4Data.ts

import { useCallback, useEffect, useRef } from 'react';
import { useStep4Store } from '../store/step4Store';
import { backendToEpisode, backendToSegment } from '../services/step4Adapter';
import {
  getEpisodes,
  getPanels,
  getBatchProductionStatuses,
} from '../../../services/episodeService';

export function useStep4Data(projectId: string) {
  const store = useStep4Store();
  const abortRef = useRef<AbortController | null>(null);
  const refreshInflightRef = useRef<Set<number>>(new Set());

  // 加载所有 episodes
  const loadEpisodes = useCallback(async (options?: { silent?: boolean }) => {
    if (!projectId) return;
    const silent = options?.silent ?? false;
    if (!silent) store.setEpisodesLoading(true);
    store.setError(null);

    if (abortRef.current) abortRef.current.abort();
    abortRef.current = new AbortController();

    try {
      const res = await getEpisodes(projectId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) {
        throw new Error(res.message || '加载失败');
      }
      const items = res.data.items || [];

      // 按章分组
      const episodeMap = new Map<string, any[]>();
      items.forEach((ep: any) => {
        const chapterTitle = ep.episodeInfo?.chapterTitle?.trim() || '未分章';
        if (!episodeMap.has(chapterTitle)) episodeMap.set(chapterTitle, []);
        episodeMap.get(chapterTitle)!.push(ep);
      });

      // 按章顺序分配 chapterIndex
      let chapterIndex = 0;
      const chapterIndexMap = new Map<string, number>();
      episodeMap.forEach((_, title) => {
        chapterIndex++;
        chapterIndexMap.set(title, chapterIndex);
      });

      // 转换为 EpisodeRecord
      const episodes = items.map((ep: any, idx: number) => {
        const chapterTitle = ep.episodeInfo?.chapterTitle?.trim() || '未分章';
        return backendToEpisode({
          id: ep.id,
          episodeInfo: {
            ...ep.episodeInfo,
            chapterIndex: chapterIndexMap.get(chapterTitle) || 0,
          },
          episodeIndex: idx + 1,
        });
      });

      store.setEpisodes(episodes);

      // 加载每个 episode 的 panels
      episodes.forEach(ep => {
        loadPanelsForEpisode(ep.episodeId);
        refreshStatuses(ep.episodeId);
      });
    } catch (err: any) {
      store.setError(err?.message || '加载失败');
    } finally {
      store.setEpisodesLoading(false);
    }
  }, [projectId]);

  // 加载单个 episode 的 panels
  const loadPanelsForEpisode = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    store.setSegmentsLoading(episodeId, true);
    try {
      const res = await getPanels(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const panels = res.data || [];
      if (panels.length === 0) return;

      const segments = panels.map((panel: any, idx: number) =>
        backendToSegment(panel, episodeId, idx)
      );
      store.setSegments(episodeId, segments);
    } catch (err) {
      console.error(`加载集 ${episodeId} 分镜失败:`, err);
    } finally {
      store.setSegmentsLoading(episodeId, false);
    }
  }, [projectId]);

  // 刷新 production statuses
  const refreshStatuses = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    if (refreshInflightRef.current.has(episodeId)) return;
    refreshInflightRef.current.add(episodeId);

    try {
      const res = await getBatchProductionStatuses(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const statusMap = new Map<number, any>();
      res.data.forEach((s: any) => statusMap.set(s.panelId, s));

      // 更新每个 segment
      store.segmentMap.forEach((seg, key) => {
        if (seg.episodeId !== episodeId) return;
        const panelId = Number(seg.panelData?.panelId);
        const raw = statusMap.get(panelId);
        if (!raw) return;

        const patch = {
          ...seg,
          gridStatus: raw.gridStatus || seg.gridStatus,
          gridImages: raw.gridImages?.length ? raw.gridImages : seg.gridImages,
          fusionImageUrl: raw.fusionImageUrl ?? seg.fusionImageUrl,
          shots: raw.shots?.length ? raw.shots : seg.shots,
          videoTask: {
            status: raw.videoStatus === 'generating' ? 'generating'
              : raw.videoStatus === 'completed' ? 'completed'
              : raw.videoStatus === 'failed' ? 'failed'
              : 'pending',
            url: raw.videoUrl || seg.videoTask.url,
            progress: raw.videoProgress ?? seg.videoTask.progress,
          },
          ttsTask: {
            status: raw.ttsStatus === 'generating' ? 'generating'
              : raw.ttsStatus === 'completed' ? 'completed'
              : raw.ttsStatus === 'failed' ? 'failed'
              : 'pending',
            url: raw.ttsAudioUrl || seg.ttsTask.url,
          },
          mergeTask: {
            status: raw.mergeStatus === 'generating' ? 'generating'
              : raw.mergeStatus === 'completed' ? 'completed'
              : raw.mergeStatus === 'failed' ? 'failed'
              : 'pending',
            url: raw.videoWithNarrationUrl || seg.mergeTask.url,
          },
        };
        store.updateSegment(key, patch);
      });
    } catch (err) {
      console.error('刷新生产状态失败:', err);
    } finally {
      refreshInflightRef.current.delete(episodeId);
    }
  }, [projectId]);

  // 初始化
  useEffect(() => {
    store.init(projectId);
    loadEpisodes();
    return () => {
      abortRef.current?.abort();
      store.reset();
    };
  }, [projectId]);

  return {
    loadEpisodes,
    loadPanelsForEpisode,
    refreshStatuses,
  };
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useStep4Data.ts
git commit -m "feat(step4): 创建 useStep4Data 数据加载 hook"
```

---

### Task 6: 导出 step4Store 到 stores/index.ts

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/stores/index.ts`

- [ ] **Step 1: 添加导出**

在 `export { useCreateStore }` 之后添加:

```typescript
export { useStep4Store } from '../pages/create/steps/store/step4Store';
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/stores/index.ts
git commit -m "feat(step4): 导出 step4Store"
```

---

## Phase 1 验证

- [ ] **验证: TypeScript 编译通过**

按推荐顺序执行 Task 1-6（Task 5 在 Task 4 之前），确认无编译错误:
- `step4Types.ts` 类型定义正确
- `step4Store.ts` store 完整
- `step4Adapter.ts` 转换函数正确
- `useStep4Data.ts` 数据加载逻辑完整
- `step4SseHandler.ts` 无循环依赖
- `stores/index.ts` 导出正确

- [ ] **验证: Store 可以正常初始化**

在浏览器中打开 Step4，确认:
- 无 console.error
- episodes 正常加载
- SSE 连接正常

- [ ] **验证: SSE 事件正确更新 store**

触发一个视频生成，检查:
- store 中的 segment videoTask.status 从 'pending' → 'generating'
- SSE 推送后 → 'completed'

---

## Phase 2: 状态迁移（Step4Production 接入 Store）

### Task 7: Step4Production 改用 Store 而非本地 useState

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

目标：保留现有 UI 渲染逻辑，逐步将 `useState` 替换为 `useStep4Store`。

- [ ] **Step 1: 添加 store 和 hooks 导入，替换基础状态**

在 Step4Production.tsx 顶部导入后添加:

```typescript
import { useStep4Store } from './store/step4Store';
import { useStep4Data } from './hooks/useStep4Data';
import { createStep4SseHandler } from './services/step4SseHandler';
import { useSseProgress } from './hooks/useSseProgress';
```

- [ ] **Step 2: 替换数据加载（useEffect + loadEpisodes → useStep4Data）**

删除组件内的 `loadEpisodes` useCallback 和相关 useEffect，替换为:

```typescript
// 在组件内替换数据加载逻辑
const store = useStep4Store();
const { loadEpisodes, loadPanelsForEpisode, refreshStatuses } = useStep4Data(projectId);
```

- [ ] **Step 3: 替换 chapters → chapterGroups 派生数据**

```typescript
// 替换
const chapters = useMemo(() => /* 旧的 chapters 构建逻辑 */, [...]);

// 为
const chapterGroups = useStep4Store(s => s.getChapterGroups());
```

- [ ] **Step 4: 替换 UI 状态**

```typescript
// 替换
const [activeTab, setActiveTab] = useState<SubPhase>(...);
const [expandedEpisodeId, setExpandedEpisodeId] = useState<number | null>(null);
const [collapsedChapters, setCollapsedChapters] = useState<Set<number>>(new Set());

// 为
const activeTab = useStep4Store(s => s.ui.activeTab);
const setActiveTab = useStep4Store(s => s.setActiveTab);
const expandedEpisodeId = useStep4Store(s => {
  const ids = Array.from(s.ui.expandedEpisodeIds);
  return ids[0] ?? null;
});
const toggleEpisodeExpanded = useStep4Store(s => s.toggleEpisodeExpanded);
const collapsedChapters = useStep4Store(s => s.ui.collapsedChapterIds);
const toggleChapterCollapsed = useStep4Store(s => s.toggleChapterCollapsed);
```

- [ ] **Step 5: 替换生成状态追踪**

```typescript
// 替换
const [generatingScript, setGeneratingScript] = useState<number | null>(...);
const [generatingGrid, setGeneratingGrid] = useState<number | null>(null);
const [generatingVideoKeys, setGeneratingVideoKeys] = useState<Set<string>>(new Set());
const [approvingEpisodeId, setApprovingEpisodeId] = useState<number | null>(null);
const [rejectingEpisodeId, setRejectingEpisodeId] = useState<number | null>(null);

// 为
const generatingScript = useStep4Store(s => {
  let found: number | null = null;
  s.tasks.forEach((task, key) => {
    if (task.type === 'script' && task.status === 'running') {
      // task.episodeId 实际存储的是 episodeId
      found = Number(key.split('-').pop());
    }
  });
  return found;
});
```

- [ ] **Step 6: SSE callbacks 改为 store dispatcher**

替换 SSE callbacks 为:

```typescript
const sseCallbacks = createStep4SseHandler(projectId, {
  loadPanelsForEpisode,
  refreshStatuses,
  loadEpisodes,
});

useSseProgress(projectId, {
  ...sseCallbacks,
  // 保留原有的纯 UI 回调（onEpisodeComposed 等）
  onEpisodeComposed: (data) => { /* 保持原样 */ },
  onStatusChange: (data) => {
    sseCallbacks.onStatusChange(data);
    // 额外触发 Step5 刷新等
  },
});
```

- [ ] **Step 7: 替换 handle* actions**

每个 handle* 函数改为调用 store actions + API:

```typescript
// 替换 handleGenerateScript
const handleGenerateScript = useCallback(async (episodeId: number) => {
  const taskKey = `script-ep-${episodeId}`;
  if (store.isTaskRunning(taskKey)) return;

  store.startTask({ taskKey, type: 'script', episodeId });

  try {
    await generateEpisodeScripts(projectId, episodeId);
  } catch (err: any) {
    store.failTask(taskKey, err?.message || '生成失败');
    alert(err?.message || '生成脚本失败');
  }
}, [projectId]);

// 替换 handleGenerateVideo（加入乐观更新）
const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
  const segmentKey = `${episodeId}-${panelId}`;
  const taskKey = `video-${segmentKey}`;

  // 乐观更新
  store.optimisticUpdate(segmentKey, {
    videoTask: { status: 'generating', url: null, progress: 0 },
  });
  store.startTask({ taskKey, type: 'video', episodeId, segmentKey });

  try {
    await generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt, isVidu ? videoModel : undefined);
    // 成功，任务进入 running 状态，等待 SSE/polling 确认
  } catch (err: any) {
    store.rollback(segmentKey);
    store.failTask(taskKey, err?.message || '生成失败');
    alert(err?.message || '生成视频失败');
  }
}, [projectId, offPeak, videoModel, isVidu]);
```

- [ ] **Step 8: 替换渲染数据来源**

在 JSX 渲染部分，将所有 `chapters` 引用替换为从 store 读取:

```typescript
// 替换 chapters → store 读取
const allEpisodes = useStep4Store(s => Array.from(s.episodeMap.values()));
const scriptCount = useStep4Store(s => Array.from(s.episodeMap.values()).filter(ep => ep.stage === 'script_review').length);
const gridCount = useStep4Store(s => Array.from(s.episodeMap.values()).filter(ep => ep.stage === 'grid_review').length);
const videoCount = useStep4Store(s => Array.from(s.episodeMap.values()).filter(ep => ep.stage === 'video_production').length);
```

- [ ] **Step 9: 验证功能完整性**

确认以下功能仍然正常:
- 三个 Tab 切换正常
- 脚本生成/审核流程正常
- 九宫格生成/审核流程正常
- 视频生成/提示词编辑/TTS/合并流程正常
- SSE 推送正常更新 UI
- 页面刷新后状态正确恢复

- [ ] **Step 10: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "refactor(step4): Step4Production 接入 step4Store，替换本地状态为 store 驱动"
```

---

## Phase 3: 细粒度更新（精准更新 + 乐观更新完善）

### Task 8: 实现细粒度 polling（消除全量刷新）

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useStep4Data.ts`

- [ ] **Step 1: 将全量 loadEpisodes 轮询替换为按需 polling**

在 useStep4Data.ts 中添加:

```typescript
// 每个 episode 独立的 polling（用于视频生成等长时间任务）
const videoPollIntervalsRef = useRef<Map<number, ReturnType<typeof setInterval>>>(new Map());
const scriptPollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

const pollVideoStatus = useCallback((episodeId: number) => {
  const existing = videoPollIntervalsRef.current.get(episodeId);
  if (existing) return;
  const interval = setInterval(() => {
    refreshStatuses(episodeId);
  }, 3000);
  videoPollIntervalsRef.current.set(episodeId, interval);
}, [refreshStatuses]);

const stopPollVideoStatus = useCallback((episodeId: number) => {
  const interval = videoPollIntervalsRef.current.get(episodeId);
  if (interval) {
    clearInterval(interval);
    videoPollIntervalsRef.current.delete(episodeId);
  }
}, []);

const stopAllPolling = useCallback(() => {
  videoPollIntervalsRef.current.forEach((interval) => clearInterval(interval));
  videoPollIntervalsRef.current.clear();
  if (scriptPollIntervalRef.current) {
    clearInterval(scriptPollIntervalRef.current);
    scriptPollIntervalRef.current = null;
  }
}, []);
```

- [ ] **Step 2: 暴露 polling 控制**

在 return 中添加:

```typescript
return {
  loadEpisodes,
  loadPanelsForEpisode,
  refreshStatuses,
  pollVideoStatus,
  stopPollVideoStatus,
  stopAllPolling,
};
```

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useStep4Data.ts
git commit -m "refactor(step4): 实现按 episode 粒度的独立 polling，消除全量刷新"
```

---

### Task 9: 完善乐观更新（视频生成进度追踪）

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

- [ ] **Step 1: 在 step4Store 中添加批量任务操作**

在 step4Store.ts 的 actions 中添加:

```typescript
// 批量操作：开始/完成/失败多个任务
batchStartTasks: (tasks: Omit<GenerationTask, 'status' | 'startedAt'>[]) => {
  set(state => {
    const next = new Map(state.tasks);
    const now = Date.now();
    tasks.forEach(task => {
      next.set(task.taskKey, { ...task, status: 'running', startedAt: now });
    });
    return { tasks: next };
  });
},

// 获取正在运行的视频任务数
getRunningVideoTasksCount: () => {
  const { tasks } = get();
  let count = 0;
  tasks.forEach(t => { if (t.type === 'video' && t.status === 'running') count++; });
  return count;
},

// 获取指定 episode 下所有 segment 的完成进度
getEpisodeVideoProgress: (episodeId: number) => {
  const { segmentMap } = get();
  const segments = Array.from(segmentMap.values()).filter(s => s.episodeId === episodeId);
  const total = segments.length;
  const completed = segments.filter(s => s.videoTask.status === 'completed').length;
  return { completed, total, percent: total > 0 ? Math.round((completed / total) * 100) : 0 };
},

// 获取指定 episode 下所有 segment 的 TTS 完成进度
getEpisodeTtsProgress: (episodeId: number) => {
  const { segmentMap } = get();
  const segments = Array.from(segmentMap.values()).filter(s => s.episodeId === episodeId);
  const total = segments.length;
  const completed = segments.filter(s => s.ttsTask.status === 'completed').length;
  return { completed, total, percent: total > 0 ? Math.round((completed / total) * 100) : 0 };
},
```

- [ ] **Step 2: 在 Step4Production 中完善乐观更新**

替换 `handleGenerateVideo` 加入 polling 追踪:

```typescript
const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
  const segmentKey = `${episodeId}-${panelId}`;
  const taskKey = `video-${segmentKey}`;
  const store = useStep4Store.getState();

  if (store.isTaskRunning(taskKey)) return;

  // 乐观更新：立即显示生成中
  store.optimisticUpdate(segmentKey, {
    videoTask: { status: 'generating', url: null, progress: 0, error: undefined },
  });
  store.startTask({ taskKey, type: 'video', episodeId, segmentKey });

  // 开始 polling
  const maxRetries = 720;
  let retries = 0;
  const pollInterval = setInterval(async () => {
    retries++;
    if (retries >= maxRetries) {
      clearInterval(pollInterval);
      store.failTask(taskKey, '视频生成超时');
      store.updateSegment(segmentKey, {
        videoTask: { status: 'failed', error: '视频生成超时' },
      });
      return;
    }
    await refreshStatuses(episodeId);
    const seg = store.getSegment(segmentKey);
    if (seg?.videoTask.status === 'completed' || seg?.videoTask.status === 'failed') {
      clearInterval(pollInterval);
      if (seg.videoTask.status === 'completed') {
        store.completeTask(taskKey);
      } else {
        store.failTask(taskKey, seg.videoTask.error || '生成失败');
      }
    }
  }, 3000);

  try {
    await generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt, isVidu ? videoModel : undefined);
    // API 调用成功，继续等待 polling 结果
  } catch (err: any) {
    clearInterval(pollInterval);
    store.rollback(segmentKey);
    store.failTask(taskKey, err?.message || '生成失败');
    alert(err?.message || '生成视频失败');
  }
}, [projectId, offPeak, videoModel, isVidu, refreshStatuses]);
```

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "refactor(step4): 完善乐观更新和细粒度 polling 追踪"
```

---

## Phase 4: 就地编辑（前端自由修改剧本）

### Task 10: 支持分镜内容就地编辑

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts`

- [ ] **Step 1: 在 SegmentRecord 中添加编辑态**

在 step4Types.ts 中添加:

```typescript
/** Segment 编辑状态 */
export interface SegmentEditState {
  isEditing: boolean;
  editingField: string | null;  // 'dialogue' | 'visualDescription' | etc.
  draft: Partial<ShotData>;
  isDirty: boolean;
  isSaving: boolean;
}
```

在 SegmentRecord 中添加:

```typescript
export interface SegmentRecord {
  // ... 现有字段
  editState: SegmentEditState;
}
```

在 step4Store.ts 中添加编辑 actions:

```typescript
startEditing: (segmentKey: string, field: string, currentValue: any) => void,
saveEditing: (segmentKey: string, field: string, newValue: any) => Promise<void>,
cancelEditing: (segmentKey: string) => void,
```

实现:

```typescript
startEditing: (segmentKey: string, field: string, currentValue: any) => {
  set(state => {
    const seg = state.segmentMap.get(segmentKey);
    if (!seg) return state;
    const next = new Map(state.segmentMap);
    next.set(segmentKey, {
      ...seg,
      editState: {
        isEditing: true,
        editingField: field,
        draft: { [field]: currentValue },
        isDirty: false,
        isSaving: false,
      },
    });
    return { segmentMap: next };
  });
},

saveEditing: async (segmentKey: string, field: string, newValue: any) => {
  const { projectId } = get();
  const seg = get().segmentMap.get(segmentKey);
  if (!seg || !projectId) return;

  set(state => {
    const next = new Map(state.segmentMap);
    next.set(segmentKey, {
      ...seg,
      editState: { ...seg.editState, isSaving: true, isDirty: true },
    });
    return { segmentMap: next };
  });

  try {
    // 调用后端 API 保存分镜编辑
    // shotIndex: 从 segmentIndex 取
    await updateShot(projectId, seg.episodeId, seg.segmentIndex, { [field]: newValue });

    set(state => {
      const seg = state.segmentMap.get(segmentKey);
      if (!seg) return state;
      const next = new Map(state.segmentMap);
      next.set(segmentKey, {
        ...seg,
        editState: {
          isEditing: false,
          editingField: null,
          draft: {},
          isDirty: false,
          isSaving: false,
        },
        shots: seg.shots?.map((s, i) => i === 0 ? { ...s, [field]: newValue } : s),
      });
      return { segmentMap: next };
    });
  } catch (err: any) {
    // 保存失败：回滚编辑状态但不丢失用户输入
    set(state => {
      const seg = state.segmentMap.get(segmentKey);
      if (!seg) return state;
      const next = new Map(state.segmentMap);
      next.set(segmentKey, {
        ...seg,
        editState: { ...seg.editState, isSaving: false, isDirty: true },
      });
      return { segmentMap: next };
    });
    alert(err?.response?.data?.message || err?.message || '保存失败');
  }
},

cancelEditing: (segmentKey: string) => {
  set(state => {
    const seg = state.segmentMap.get(segmentKey);
    if (!seg) return state;
    const next = new Map(state.segmentMap);
    next.set(segmentKey, {
      ...seg,
      editState: {
        isEditing: false,
        editingField: null,
        draft: {},
        isDirty: false,
        isSaving: false,
      },
    });
    return { segmentMap: next };
  });
},
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Types.ts
git add frontend/wiset_aivideo_generator/src/pages/create/steps/store/step4Store.ts
git commit -m "feat(step4): 添加 Segment 就地编辑状态支持"
```

---

## Phase 5: UI 拆分（文件结构精简）

### Task 11: 拆分 Tab 容器

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/ScriptTab.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/GridTab.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/VideoTab.tsx`

- [ ] **Step 1: 创建 ScriptTab.tsx**

从 Step4Production.tsx 中抽取 4a Tab 的 JSX 渲染逻辑:

```typescript
// frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/ScriptTab.tsx

import React from 'react';
import { useStep4Store } from '../store/step4Store';
import ScriptEpisodeCard from '../components/ScriptEpisodeCard';
import DoneEpisodeCard from '../components/DoneEpisodeCard';

interface ScriptTabProps {
  project: any;
  onGenerateScript: (episodeId: number) => void;
  onApproveScript: (episodeId: number) => void;
  generatingScript: number | null;
  approvingEpisodeId: number | null;
  buildGridPromptText: Function;
  buildMultiShotPromptText: Function;
}

export default function ScriptTab({ project, ...props }: ScriptTabProps) {
  const chapterGroups = useStep4Store(s => s.getChapterGroups());
  const collapsedChapters = useStep4Store(s => s.ui.collapsedChapterIds);
  const toggleChapterCollapsed = useStep4Store(s => s.toggleChapterCollapsed);
  const expandedPassedEpisodeId = useStep4Store(s => {
    const ids = Array.from(s.ui.expandedEpisodeIds);
    return ids[0] ?? null;
  });
  const setExpandedPassedEpisodeId = useStep4Store(s => s.toggleEpisodeExpanded);

  return (
    <div className={styles.scriptReviewSection}>
      {/* 从 Step4Production.tsx 中抽取对应 JSX */}
    </div>
  );
}
```

- [ ] **Step 2: 类似抽取 GridTab 和 VideoTab**

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/ScriptTab.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/GridTab.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/tabs/VideoTab.tsx
git commit -m "refactor(step4): 拆分 ScriptTab/GridTab/VideoTab 容器组件"
```

---

### Task 12: 最终精简 Step4Production.tsx

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

- [ ] **Step 1: 精简主入口文件**

移除所有 JSX 渲染逻辑（已拆分到 Tab 组件），保留:
- Props 定义
- 常量定义（STEPS, STYLE_PREFIX_MAP 等）
- 工具函数（buildGridPromptText, buildMultiShotPromptText）
- API 导入
- 组件初始化
- 主 JSX 布局（Tab 切换 + 统计栏 + Tab 容器 + Modal）

目标行数: < 200 行

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "refactor(step4): 最终精简 Step4Production.tsx 至 300 行以内"
```

---

## 验收清单

- [ ] `step4Store.ts` 定义完整，TypeScript 类型正确
- [ ] SSE 事件正确更新 store（无状态丢失）
- [ ] 乐观更新在视频/TTS/合并操作中正常工作
- [ ] 回滚机制在 API 失败时正确恢复状态
- [ ] `Step4Production.tsx` < 300 行
- [ ] 所有子组件通过 store 读取状态，不再有 prop drilling
- [ ] 每个 Phase 完成后功能验证通过
