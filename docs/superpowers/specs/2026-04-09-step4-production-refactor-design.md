# Step4 分镜生产重构设计方案

**日期**: 2026-04-09
**状态**: 已批准
**版本**: v1.0

---

## 1. 背景与目标

### 1.1 问题陈述

当前 Step4Production（2000+ 行单文件）存在以下核心问题：

- **状态机混乱**: `PipelineStage` 仅 3 值（script/grid/video），但实际业务每个阶段都有多个子状态。前端靠派生函数 `getPipelineStage()` 拼凑真实状态，逻辑分散在 SSE 回调、轮询、事件处理中。
- **数据流混乱**: SSE 事件、轮询（script 5s / grid 5s / video 3s）、手动刷新都在竞争更新同一个 `chapters` 数组状态，导致前端与后端经常显示不同步。
- **颗粒度不对齐**: 后端 Episode 级别有 `scriptStatus`、`storyboardStatus`、`panelApproved`、`gridStatus` 等多个字段，Panel 级别又有独立的 `gridStatus`、`videoStatus`、`ttsStatus`、`mergeStatus`。前端不得不写大量映射逻辑。
- **前端自由度低**: 所有数据都是后端生成后只读展示，审核员无法就地修改剧本内容后提交。

### 1.2 重构目标

- **状态管理**: 从组件内 useState 迁移到 Zustand store，支持细粒度单条更新
- **颗粒度**: Segment（分镜）级完整状态机，每个分镜独立追踪视频/TTS/合并状态
- **编辑能力**: 支持就地编辑剧本内容，编辑后通过 API 保存
- **同步策略**: 乐观更新 + SSE 确认，解决显示不同步问题
- **架构**: Store 驱动单向数据流，文件分层拆解
- **后端兼容**: 前端通过适配器层兼容现有后端 API，不依赖后端改动

---

## 2. 核心设计决策

### 2.1 状态同步策略

**乐观更新 + SSE 确认**

```
用户操作 → 乐观更新本地 store（立即反映）→ API 调用 → SSE 推送确认 → 最终状态
                ↓
           失败 → 回滚到快照
```

- 用户操作后立即更新本地 UI，不等待后端响应
- SSE 作为最终确认，覆盖本地状态
- 乐观更新失败时回滚到操作前的快照

### 2.2 状态分片设计

不再用单一 `chapters` 数组作为唯一数据源，按实体分片：

| 存储结构 | 用途 |
|---------|------|
| `episodeMap: Map<id, EpisodeRecord>` | 原始数据，ID 索引，O(1) 单条更新 |
| `segmentMap: Map<key, SegmentRecord>` | 分镜数据，key = "episodeId-panelId" |
| `chapterGroups: ChapterGroup[]` | 纯派生视图，从 episodeMap 计算 |
| `tasks: Map<taskKey, TaskState>` | 生成任务追踪（哪些正在操作中） |
| `ui: UIState` | Tab、展开态、弹窗等 UI 状态 |
| `loading: LoadingState` | 细粒度加载状态（per-entity） |
| `preferences: {}` | offPeak、videoModel 等用户偏好 |

### 2.3 统一类型模型

```typescript
// Episode 级别状态（派生自后端多字段）
type EpisodeStage = 'script_review' | 'grid_review' | 'video_production' | 'completed';

interface EpisodeRecord {
  episodeId: number;
  episodeIndex: number;
  title: string;
  stage: EpisodeStage;

  // 脚本内容（4a 完成前可编辑）
  scriptData: {
    shots: ShotData[];
    scriptStatus: 'pending' | 'generating' | 'done';
    storyboardStatus: 'pending' | 'generating' | 'done';
  };

  // 九宫格（4b 完成前可见）
  gridData: {
    status: EpisodeGridStatus; // pending | text_ready | generating | generated | approved | rejected | failed
    prompt: string;
    images: string[];
    feedback?: string;
  };

  // 分镜列表
  segments: string[]; // segmentId 列表
}

// Segment 状态（独立完整状态机）
interface SegmentRecord {
  segmentId: string;  // "episodeId-panelId"
  episodeId: number;
  title: string;
  synopsis: string;

  // 每个子任务独立状态
  videoTask:    { status: SegmentSubStatus; url?: string; progress?: number; error?: string };
  ttsTask:      { status: SegmentSubStatus; url?: string };
  mergeTask:    { status: SegmentSubStatus; url?: string };

  // 元数据
  panelData?: PanelData;
  shots?: any[];
  feedback?: string;
}

type SegmentSubStatus = 'pending' | 'generating' | 'completed' | 'failed';
```

### 2.4 后端状态兼容层

后端暂不改动的过渡方案。在 API 层做类型转换：

```typescript
// step4Adapter.ts
function backendToEpisodeRecord(raw: BackendEpisode): EpisodeRecord {
  return {
    episodeId: raw.id,
    stage: deriveStage(raw),
    scriptData: {
      shots: raw.episodeInfo.shots || [],
      scriptStatus: raw.episodeInfo.scriptStatus || 'pending',
      storyboardStatus: raw.episodeInfo.storyboardStatus || 'pending',
    },
    gridData: {
      status: raw.episodeInfo.gridStatus || 'pending',
      prompt: raw.episodeInfo.gridPrompt || '',
      images: raw.episodeInfo.gridImages || [],
      feedback: raw.episodeInfo.gridRejectionFeedback,
    },
    // ...
  };
}

function deriveStage(raw: BackendEpisode): EpisodeStage {
  if (!raw.episodeInfo.panelApproved) return 'script_review';
  if (raw.episodeInfo.gridStatus === 'approved') return 'video_production';
  return 'grid_review';
}
```

---

## 3. 数据流设计

### 3.1 SSE 与 Store 的交互

```
SSE EventSource
    │
    ▼
step4SseHandler.ts（统一入口）
    │
    ├── episode:script_done
    │     → confirmTask + updateEpisode + loadPanels
    ├── episode:storyboard_done
    │     → confirmTask + updateEpisode + loadPanels + refreshStatuses
    ├── episode:grid_status
    │     → updateEpisode + (grid完成时) loadPanels
    ├── panel:video_done
    │     → updateSegment (精准单条) + confirmTask
    ├── panel:video_failed
    │     → updateSegment (精准单条) + failTask
    ├── panel:tts_done / panel:tts_failed
    │     → updateSegment + confirmTask/failTask
    └── panel:merge_done / panel:merge_failed
          → updateSegment + confirmTask/failTask
```

### 3.2 乐观更新与回滚

```typescript
// 乐观更新
optimisticUpdate(segmentId: string, patch: Partial<SegmentRecord>) {
  const snapshot = this.get(segmentId);
  set(state => ({
    segmentSnapshots: { ...state.segmentSnapshots, [segmentId]: snapshot },
    segmentMap: updateMap(state.segmentMap, segmentId, patch),
  }));
}

// 回滚
rollback(segmentId: string) {
  const snapshot = this.get().segmentSnapshots[segmentId];
  if (snapshot) {
    set(state => ({
      segmentMap: updateMap(state.segmentMap, segmentId, snapshot),
      segmentSnapshots: omit(state.segmentSnapshots, segmentId),
    }));
  }
}
```

---

## 4. 文件结构

```
frontend/wiset_aivideo_generator/src/pages/create/steps/
├── Step4Production.tsx           # 主入口，极简（< 200行）
│
├── store/                        # 状态管理层
│   ├── step4Store.ts            # Zustand store 定义
│   ├── types.ts                  # Store 类型定义
│   └── selectors/
│       ├── chapterGroups.ts     # episodeMap → chapterGroups
│       ├── scriptTabEpisodes.ts # 4a 过滤
│       ├── gridTabEpisodes.ts   # 4b 过滤
│       └── videoTabEpisodes.ts  # 4c 过滤
│
├── services/                     # API 层
│   ├── step4Adapter.ts         # 后端 → 前端类型转换
│   └── step4SseHandler.ts      # SSE → store actions 桥接
│
├── components/                   # UI 组件
│   ├── ScriptEpisodeCard.tsx   # 4a 剧集卡片
│   ├── GridEpisodeCard.tsx     # 4b 剧集卡片
│   ├── VideoSegmentRow.tsx     # 4c 分镜行
│   └── PromptModal.tsx        # 提示词编辑弹窗
│
└── tabs/
    ├── ScriptTab.tsx           # 4a 标签页容器
    ├── GridTab.tsx             # 4b 标签页容器
    └── VideoTab.tsx            # 4c 标签页容器
```

**拆分原则**: 每个文件职责单一，不超过 300-400 行。

---

## 5. 重构步骤（渐进式）

### Phase 1: 基础设施（不改变现有 UI 行为）
1. 新建 `store/step4Store.ts`，定义 store schema
2. 新建 `services/step4Adapter.ts`，实现后端类型转换
3. 实现基础的 `loadEpisodes` / `loadPanels` / `refreshStatuses` actions
4. 实现 SSE handler 到 store actions 的桥接

### Phase 2: 状态迁移（Store 接管数据）
1. `Step4Production` 改用 store 而非本地 useState
2. 子组件通过 `useStep4Store` 读取状态
3. 保持 SSE 和 polling 逻辑，验证数据一致性

### Phase 3: 细粒度更新（消除全量刷新）
1. 将 `loadEpisodes` 全量刷新改为按需单条加载
2. 实现 `updateSegment` 精准更新（不触发 episode/chapter 重创建）
3. 实现 `tasks` 任务追踪系统
4. 实现乐观更新与回滚

### Phase 4: 就地编辑（给前端自由度）
1. 在 `SegmentRecord` 中支持 `editingShots` 状态
2. 实现 `saveShotEdits` API 调用
3. 编辑态 UI：分镜内容原地可编辑，编辑后保存

### Phase 5: UI 拆分（文件结构）
1. 拆分 `Step4Production.tsx` → ScriptTab / GridTab / VideoTab
2. 子组件 `ScriptEpisodeCard` / `GridEpisodeCard` / `VideoSegmentRow` 独立
3. 抽取 `PromptModal.tsx`
4. 最终 `Step4Production.tsx` 只做布局编排（< 200 行）

---

## 6. 验收标准

- [ ] 所有状态变更通过 Zustand store 管理，不再有跨组件的 useState 共享
- [ ] 单个 Segment 状态更新不触发整个 `chapters` 数组重建
- [ ] SSE 推送更新不覆盖正在进行的乐观更新状态
- [ ] 轮询和 SSE 共存时无状态覆盖冲突
- [ ] 前端可对分镜内容（画面描述、对话、镜头类型）进行就地编辑并保存
- [ ] `Step4Production.tsx` 精简至 200 行以内
- [ ] 每个文件职责单一，不超过 400 行
