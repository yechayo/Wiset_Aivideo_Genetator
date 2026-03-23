# Step5 原子化卡片工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Step5 从"分镜审查页"升级为"原子化卡片工作台"，每个分镜格子独立管理场景图生成→融合→视频生成的完整流程。

**Architecture:** 前端完全重写 Step5 页面，引入阶段状态机驱动卡片演化。Step6 简化为最终视频展示页。新增后端接口支持单格场景图重生成。

**Tech Stack:** React + TypeScript + LESS modules, Java Spring Boot backend

---

## 文件结构

### 前端新增/修改

| 文件 | 职责 | 改动 |
|------|------|------|
| `Step5page.tsx` | 阶段状态机 + 卡片渲染 + 融合操作 + 底部操作栏 | 重写 |
| `Step5page.module.less` | 进度条、卡片三态、底部操作栏样式 | 重写 |
| `PanelVideoCard.tsx` | 扩展支持阶段2场景图态 + 阶段4完整态 | 小改 |
| `PanelVideoCard.module.less` | 扩展样式支持场景图 + 融合图 + 视频三图布局 | 小改 |
| `GridFusionEditor.tsx` | 支持模态模式（`mode="modal"`） | 小改 |
| `episodeService.ts` | 新增 `regenerateSceneImage` API | 新增 |
| `episode.types.ts` | 新增 `WorkflowPhase` 类型 | 新增 |

### 后端新增

| 文件 | 职责 | 改动 |
|------|------|------|
| `EpisodeController.java` | 新增 `POST /api/episodes/{episodeId}/panels/{panelIndex}/regenerate-scene` | 新增端点 |
| `EpisodeProductionService.java` | 实现单格场景图重生成逻辑 | 新增方法 |

---

## 阶段计划

### Task 1: 后端新增单格场景图重生成接口

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/EpisodeProductionService.java`

- [ ] **Step 1: 在 EpisodeController 中新增端点**

在 `EpisodeController.java` 中找到现有端点附近（第230行 autoContinue 附近），新增：

```java
/**
 * 单格场景图重生成
 * POST /api/episodes/{episodeId}/panels/{panelIndex}/regenerate-scene
 */
@PostMapping("/{episodeId}/panels/{panelIndex}/regenerate-scene")
@Operation(summary = "单格场景图重生成", description = "按分镜的scene_description重新生成单格场景图")
public Result<Void> regeneratePanelScene(
        @PathVariable Long episodeId,
        @PathVariable Integer panelIndex) {
    productionService.regeneratePanelScene(episodeId, panelIndex);
    return Result.success();
}
```

- [ ] **Step 2: 在 EpisodeProductionService 中实现 regeneratePanelScene 方法**

在 `EpisodeProductionService.java` 中新增方法。参考现有的 `submitFusionPage` 和 `getPanelStates` 实现：

```java
public void regeneratePanelScene(Long episodeId, Integer panelIndex) {
    Episode episode = episodeRepository.findById(episodeId)
        .orElseThrow(() -> new BusinessException("Episode not found"));

    // 解析 storyboardJson
    String storyboardJson = episode.getStoryboardJson();
    if (storyboardJson == null || storyboardJson.isEmpty()) {
        throw new BusinessException("Storyboard JSON is empty");
    }

    // 解析获取该 panelIndex 的 scene_description
    // 格式: { "panels": [{ "background": { "scene_desc": "..." } }, ...] }
    JSONObject root = JSONObject.parseObject(storyboardJson);
    JSONArray panels = root.getJSONArray("panels");
    if (panels == null || panelIndex >= panels.size()) {
        throw new BusinessException("Panel index out of bounds");
    }

    JSONObject panel = panels.getJSONObject(panelIndex);
    JSONObject background = panel.getJSONObject("background");
    String sceneDesc = background != null ? background.getString("scene_desc") : null;

    if (sceneDesc == null || sceneDesc.isEmpty()) {
        throw new BusinessException("Scene description not found for panel " + panelIndex);
    }

    // 调用图像生成服务重新生成该格子的场景图
    // 场景图是整页九宫格，需要重新生成整页然后替换对应格子的 URL
    SceneGridGenService gridService; // 注入
    String newGridUrl = gridService.regenerateSceneGrid(episodeId, sceneDesc);

    // 更新 sceneGridUrls[panelIndex]
    List<String> sceneGridUrls = episode.getSceneGridUrls();
    if (sceneGridUrls == null) {
        sceneGridUrls = new ArrayList<>();
    }
    // 确保列表有足够长度
    while (sceneGridUrls.size() <= panelIndex) {
        sceneGridUrls.add(null);
    }
    sceneGridUrls.set(panelIndex, newGridUrl);
    episode.setSceneGridUrls(sceneGridUrls);

    // 清除该格子的融合状态（让用户重新融合）
    // 融合状态存储在 VideoProductionTask 表中，按 panelIndex 查找并清除
    List<VideoProductionTask> tasks = videoProductionTaskRepository.findByEpisodeIdAndPanelIndex(episodeId, panelIndex);
    for (VideoProductionTask task : tasks) {
        task.setFusionUrl(null);
        task.setFusionStatus("pending");
        videoProductionTaskRepository.save(task);
    }

    episodeRepository.save(episode);
}
```

**注意**:
- `SceneGridGenService.regenerateSceneGrid(episodeId, sceneDesc)` 需要在 `SceneGridGenService` 中新增，支持单格场景图重生成
- `VideoProductionTaskRepository.findByEpisodeIdAndPanelIndex()` 如果不存在，需要在 `VideoProductionTaskRepository` 中新增

- [ ] **Step 3: 在 VideoProductionTaskRepository 中新增查询方法**

```java
// VideoProductionTaskRepository.java
List<VideoProductionTask> findByEpisodeIdAndPanelIndex(Long episodeId, Integer panelIndex);
```

- [ ] **Step 4: 验证后端编译**

Run: `cd backend && mvn compile -q`
Expected: BUILD SUCCESS（如果 Maven 在 PATH 中）

---

### Task 2: 前端新增类型和 API

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/episode.types.ts`
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts`

- [ ] **Step 1: 在 episode.types.ts 新增 WorkflowPhase 类型**

在文件末尾（第133行之后）新增：

```typescript
/** Step5 工作流阶段 */
export type WorkflowPhase = 'review' | 'scene-generating' | 'fusion' | 'video' | 'completed';

/** 场景图状态 */
export interface SceneImageState {
  url: string | null;
  generating: boolean;
  failed: boolean;
  prompt: string | null;  // 来自分镜 JSON 的 background.scene_desc
}
```

- [ ] **Step 2: 在 episodeService.ts 新增 regenerateSceneImage API**

在文件末尾（第114行之后）新增：

```typescript
/** 单格场景图重生成 */
export async function regenerateSceneImage(
  episodeId: string,
  panelIndex: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/episodes/${episodeId}/panels/${panelIndex}/regenerate-scene`,
  );
}
```

- [ ] **Step 3: 验证前端构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -20`
Expected: build success 或仅原有警告

---

### Task 3: Step5page 完全重写 — 阶段状态机

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

- [ ] **Step 1: 引入新的状态和类型**

在文件顶部 import 区新增：
```typescript
import type { WorkflowPhase, SceneImageState } from '../../../services/types/episode.types';
import { regenerateSceneImage } from '../../../services/episodeService';
```

替换原有的 `Step5page` 组件中的状态定义，新增：
```typescript
// 工作流阶段状态
const [workflowPhase, setWorkflowPhase] = useState<WorkflowPhase>('review');

// 管线状态（用于判断场景图是否生成）
const [pipeline, setPipeline] = useState<ProductionPipelineResponse | null>(null);

// 格子级别场景图状态 Map<panelIndex, SceneImageState>
const [sceneImageStates, setSceneImageStates] = useState<Map<number, SceneImageState>>(new Map());

// 底部操作栏可见性（阶段2-4始终可见）
const showBottomBar = ['scene-generating', 'fusion', 'video'].includes(workflowPhase);
```

- [ ] **Step 2: 实现阶段检测逻辑 + helper 函数**

在 `useEffect` 轮询逻辑中，新增阶段判断：

```typescript
// 判断工作流阶段
useEffect(() => {
  if (!pipeline) return;

  const stages = pipeline.stages;

  // 检查是否已确认分镜（allConfirmed + startProduction 已调用）
  if (statusInfo?.storyboardAllConfirmed) {
    // 检查场景图是否全部生成
    const sceneGridUrls = pipeline.sceneGridUrls ?? [];
    const allSceneGenerated = sceneGridUrls.length > 0 && sceneGridUrls.every(url => !!url);

    if (!allSceneGenerated) {
      setWorkflowPhase('scene-generating');
    } else {
      // 检查融合状态
      const fusionStage = stages.find(s => s.key === 'grid_fusion');
      if (fusionStage?.displayStatus === 'completed') {
        // 检查视频状态
        const allPanelsCompleted = panelStates.every(p => p.videoStatus === 'completed');
        if (allPanelsCompleted) {
          setWorkflowPhase('completed');
        } else {
          setWorkflowPhase('video');
        }
      } else {
        setWorkflowPhase('fusion');
      }
    }
  } else {
    setWorkflowPhase('review');
  }
}, [pipeline, statusInfo, panelStates]);

// Helper: 从 panelStates 获取指定格子的融合状态
const getFusionStatus = (panelIndex: number): 'pending' | 'completed' | 'failed' => {
  const panel = panelStates.find(p => p.panelIndex === panelIndex);
  return panel?.fusionStatus ?? 'pending';
};

// Helper: 从 panelStates 获取指定格子的视频状态
const getVideoStatus = (panelIndex: number): 'pending' | 'generating' | 'completed' | 'failed' => {
  const panel = panelStates.find(p => p.panelIndex === panelIndex);
  return panel?.videoStatus ?? 'pending';
};
```

- [ ] **Step 3: 实现顶部进度条**

替换现有的 `progressBar` 渲染逻辑：

```typescript
// 4阶段进度条
const phases = ['分镜审查', '场景生成', '图片融合', '视频生成', '完成'];
const phaseIndex = {
  'review': 0,
  'scene-generating': 1,
  'fusion': 2,
  'video': 3,
  'completed': 4,
}[workflowPhase] ?? 0;

return (
  <div className={styles.content}>
    {/* 顶部进度条 */}
    <div className={styles.phaseProgressBar}>
      {phases.map((label, idx) => (
        <div
          key={label}
          className={`${styles.phaseStep} ${idx <= phaseIndex ? styles.phaseActive : ''} ${idx < phaseIndex ? styles.phaseDone : ''}`}
        >
          <div className={styles.phaseDot}>{idx < phaseIndex ? '✓' : idx + 1}</div>
          <span className={styles.phaseLabel}>{label}</span>
        </div>
      ))}
    </div>

    {/* 卡片网格（阶段1-4） */}
    {workflowPhase !== 'completed' && (
      <div className={styles.cardGrid}>
        {storyboardData?.panels.map((panel, idx) => (
          <Step5Card
            key={idx}
            panel={panel}
            panelIndex={idx}
            workflowPhase={workflowPhase}
            sceneImageState={sceneImageStates.get(idx)}
            fusionStatus={getFusionStatus(idx)}
            fusionUrl={panelStates.find(p => p.panelIndex === idx)?.fusionUrl}
            videoStatus={getVideoStatus(idx)}
            videoUrl={panelStates.find(p => p.panelIndex === idx)?.videoUrl}
            onConfirm={handleConfirm}
            onRegenerateScene={() => handleRegenerateScene(idx)}
            onAutoFusion={() => handleAutoFusion(idx)}
            onManualFusion={() => handleOpenManualFusion(idx)}
            onGenerateVideo={() => handleGenerateVideo(idx)}
          />
        ))}
      </div>
    )}

    {/* 底部操作栏 */}
    {showBottomBar && (
      <div className={styles.bottomActionBar}>
        <button className={styles.autoButton} onClick={handleAutoContinue}>
          一键自动化执行
        </button>
        <button className={styles.composeButton} onClick={handleCompose}>
          统一合并生成最终视频
        </button>
      </div>
    )}
  </div>
);
```

- [ ] **Step 4: 实现卡片渲染逻辑**

将现有的面板列表渲染替换为 `Step5Card` 组件调用。根据 `workflowPhase` 渲染不同状态的卡片内容。

- [ ] **Step 5: 验证构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | grep -E "(error|Error|SUCCESS|failed)" | head -10`
Expected: 无新增 error

---

### Task 4: Step5Card 组件实现

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.module.less`

- [ ] **Step 1: 创建 Step5Card 组件**

```typescript
import styles from './Step5Card.module.less';
import type { StoryboardPanel } from '../../../services';
import type { WorkflowPhase, SceneImageState } from '../../../services/types/episode.types';

interface Step5CardProps {
  panel: StoryboardPanel;
  panelIndex: number;
  workflowPhase: WorkflowPhase;
  sceneImageState?: SceneImageState;
  fusionStatus: 'pending' | 'completed' | 'failed';
  fusionUrl?: string | null;  // 来自 PanelState
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoUrl?: string | null;  // 来自 PanelState
  onConfirm: () => void;
  onRegenerateScene: () => void;
  onAutoFusion: () => void;
  onManualFusion: () => void;
  onGenerateVideo: () => void;
}

const Step5Card = ({
  panel,
  panelIndex,
  workflowPhase,
  sceneImageState,
  fusionStatus,
  fusionUrl,
  videoStatus,
  videoUrl,
  onConfirm,
  onRegenerateScene,
  onAutoFusion,
  onManualFusion,
  onGenerateVideo,
}: Step5CardProps) => {
  // 阶段1：分镜审查态
  if (workflowPhase === 'review') {
    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size} / {panel.camera_angle}</span>
        </div>
        <div className={styles.cardBody}>
          <div className={styles.field}>
            <span className={styles.label}>场景</span>
            <span className={styles.value}>{panel.scene}</span>
          </div>
          <div className={styles.field}>
            <span className={styles.label}>角色</span>
            <span className={styles.value}>{panel.characters}</span>
          </div>
          <div className={styles.field}>
            <span className={styles.label}>对话</span>
            <span className={styles.value}>"{panel.dialogue}"</span>
          </div>
        </div>
        <div className={styles.cardActions}>
          <button className={styles.primaryBtn} onClick={onConfirm}>确认</button>
          <button className={styles.secondaryBtn}>修订</button>
        </div>
      </div>
    );
  }

  // 阶段2：场景生成态
  if (workflowPhase === 'scene-generating') {
    const generating = sceneImageState?.generating ?? false;
    const failed = sceneImageState?.failed ?? false;
    const hasImage = !!sceneImageState?.url;

    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size}</span>
        </div>
        <div className={styles.cardBody}>
          {generating && (
            <div className={styles.sceneGenerating}>
              <div className={styles.spinner} />
              <span>场景图生成中...</span>
            </div>
          )}
          {failed && (
            <div className={styles.sceneFailed}>
              <span>生成失败</span>
              <button onClick={onRegenerateScene}>重新生成</button>
            </div>
          )}
          {hasImage && !generating && (
            <>
              <img className={styles.sceneImage} src={sceneImageState!.url!} alt={`场景${panelIndex + 1}`} />
              <div className={styles.promptText}>
                <span className={styles.label}>Prompt</span>
                <span className={styles.value}>{sceneImageState!.prompt}</span>
              </div>
            </>
          )}
          {!hasImage && !generating && !failed && (
            <div className={styles.waitingScene}>
              <span>等待场景图生成</span>
            </div>
          )}
        </div>
        {/* 融合按钮（仅在有场景图时可用） */}
        <div className={styles.cardActions}>
          <button
            className={styles.primaryBtn}
            disabled={!hasImage || generating}
            onClick={onAutoFusion}
          >
            自动融合
          </button>
          <button
            className={styles.secondaryBtn}
            disabled={!hasImage || generating}
            onClick={onManualFusion}
          >
            手动融合
          </button>
        </div>
      </div>
    );
  }

  // 阶段3：融合态
  if (workflowPhase === 'fusion') {
    const hasImage = !!sceneImageState?.url;
    const hasFusion = fusionStatus === 'completed';

    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size}</span>
        </div>
        <div className={styles.cardBody}>
          <div className={styles.sceneImageRow}>
            {hasImage ? (
              <img className={styles.sceneImage} src={sceneImageState!.url!} alt={`场景${panelIndex + 1}`} />
            ) : (
              <div className={styles.placeholder}>等待场景图</div>
            )}
          </div>
          {sceneImageState?.prompt && (
            <div className={styles.promptText}>
              <span className={styles.label}>Prompt</span>
              <span className={styles.value}>{sceneImageState!.prompt}</span>
            </div>
          )}
          {hasFusion && fusionUrl ? (
            <div className={styles.fusionImageWrapper}>
              <img className={styles.fusionImage} src={fusionUrl} alt={`融合图${panelIndex + 1}`} />
              <span className={styles.fusionLabel}>✓ 已融合</span>
            </div>
          ) : hasFusion ? (
            <div className={styles.fusionStatus}>
              <span>✓ 已融合</span>
            </div>
          ) : null}
        </div>
        <div className={styles.cardActions}>
          <button
            className={styles.primaryBtn}
            disabled={!hasImage}
            onClick={onAutoFusion}
          >
            {hasFusion ? '重新自动融合' : '自动融合'}
          </button>
          <button
            className={styles.secondaryBtn}
            disabled={!hasImage}
            onClick={onManualFusion}
          >
            手动融合
          </button>
        </div>
      </div>
    );
  }

  // 阶段4：视频生成态（参考 spec 线框图）
  if (workflowPhase === 'video') {
    const hasImage = !!sceneImageState?.url;
    const hasFusion = fusionStatus === 'completed';

    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size}</span>
        </div>
        <div className={styles.cardBody}>
          {/* 左侧：场景图 + 融合图 */}
          <div className={styles.leftCol}>
            {hasImage ? (
              <>
                <div className={styles.imageWithButton}>
                  <img className={styles.sceneImage} src={sceneImageState!.url!} alt={`场景${panelIndex + 1}`} />
                  <button className={styles.imageActionBtn} onClick={onRegenerateScene}>重新生成</button>
                </div>
                {sceneImageState?.prompt && (
                  <div className={styles.promptText}>
                    <span className={styles.label}>Prompt</span>
                    <span className={styles.value}>{sceneImageState!.prompt}</span>
                  </div>
                )}
              </>
            ) : (
              <div className={styles.placeholder}>等待场景图</div>
            )}
          </div>

          {/* 右侧：视频 */}
          <div className={styles.rightCol}>
            {videoStatus === 'completed' && videoUrl && (
              <video className={styles.videoPlayer} src={videoUrl} controls />
            )}
            {videoStatus === 'generating' && (
              <div className={styles.placeholder}>
                <div className={styles.spinner} />
                <span>视频生成中...</span>
              </div>
            )}
            {videoStatus === 'failed' && (
              <div className={styles.placeholder}>
                <span>生成失败</span>
                <button onClick={onGenerateVideo}>重试</button>
              </div>
            )}
            {videoStatus === 'pending' && (
              <div className={styles.placeholder}>
                <button className={styles.generateBtn} onClick={onGenerateVideo}>
                  生成视频
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }

  return null;
};
```

- [ ] **Step 2: 创建样式文件**

参考 `PanelVideoCard.module.less` 和 spec 中的线框图，创建 `Step5Card.module.less`。

- [ ] **Step 3: 验证构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | grep -E "Step5Card|error" | head -5`
Expected: 无 Step5Card 相关的 error

---

### Task 5: GridFusionEditor 模态模式改造

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridFusionEditor.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridFusionEditor.module.less`

- [ ] **Step 1: 新增 mode 和 onClose props**

```typescript
interface GridFusionEditorProps {
  episodeId: string;
  onFusionSubmitted: () => void;
  mode?: 'full' | 'modal';  // 新增
  onClose?: () => void;      // 新增：模态关闭回调，不触发任何 API
}
```

- [ ] **Step 2: 模态模式下的布局调整**

在 `GridFusionEditor` 组件返回值外层包装条件：

```typescript
const isModal = mode === 'modal';

// 在组件 return 之前判断
if (isModal) {
  return (
    <div className={styles.modalOverlay} onClick={handleOverlayClick}>
      <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
        {/* 现有的 GridFusionEditor 内容 */}
        {renderContent()}
      </div>
    </div>
  );
}

return renderContent();
```

- [ ] **Step 3: 模态取消处理**

```typescript
const handleOverlayClick = useCallback(() => {
  // 模态取消：不触发任何后端调用，融合状态保持不变
  onClose?.(); // 只关闭模态，不调用 onFusionSubmitted
}, [onClose]);

// 在最后一页提交成功后调用 onFusionSubmitted
// 在模态取消时只调用 onClose
```

- [ ] **Step 4: 验证构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | grep -E "GridFusionEditor|error" | head -5`
Expected: 无 error

---

### Task 6: PanelVideoCard 扩展支持阶段2/阶段4

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelVideoCard.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelVideoCard.module.less`

- [ ] **Step 1: 扩展 PanelVideoCard Props**

```typescript
interface PanelVideoCardProps {
  panel: PanelState;
  onGenerateVideo: (panelIndex: number) => void;
  // 新增：场景图相关（用于阶段2-4）
  sceneImageUrl?: string | null;
  sceneImagePrompt?: string | null;
  sceneGenerating?: boolean;
  // 新增：融合图（阶段4需要显示融合图）
  fusionImageUrl?: string | null;
  // 新增：操作回调
  onRegenerateScene?: () => void;
  onAutoFusion?: () => void;
  onManualFusion?: () => void;
}
```

- [ ] **Step 2: 阶段4三图布局实现**

在 `PanelVideoCard` 中，根据传入的 `sceneImageUrl` + `fusionImageUrl` + `videoUrl` 渲染三图布局：

```typescript
<div className={styles.card}>
  <div className={styles.mediaRow}>
    {/* 左侧：场景图 */}
    <div className={styles.mediaThird}>
      <div className={styles.mediaBox}>
        {sceneImageUrl ? (
          <>
            <img src={sceneImageUrl} alt="场景图" />
            {onRegenerateScene && (
              <button className={styles.regenBtn} onClick={onRegenerateScene}>重新生成</button>
            )}
          </>
        ) : sceneGenerating ? (
          <div className={styles.placeholder}>
            <div className={styles.spinner} />
            <span>生成中...</span>
          </div>
        ) : (
          <div className={styles.placeholder}>等待场景图</div>
        )}
      </div>
      {sceneImagePrompt && (
        <div className={styles.promptLabel}>Prompt: {sceneImagePrompt}</div>
      )}
    </div>

    {/* 中间：融合图 */}
    <div className={styles.mediaThird}>
      <div className={styles.mediaBox}>
        {fusionImageUrl ? (
          <>
            <img src={fusionImageUrl} alt="融合图" />
            <button onClick={onManualFusion}>重新融合</button>
          </>
        ) : (
          <div className={styles.placeholder}>等待融合</div>
        )}
      </div>
    </div>

    {/* 右侧：视频 */}
    <div className={styles.mediaThird}>
      {/* 现有视频渲染逻辑 */}
    </div>
  </div>
</div>
```

- [ ] **Step 3: 验证构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -5`
Expected: build success

---

### Task 7: Step6page 简化

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step6page.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step6page.module.less`

- [ ] **Step 1: 移除中间状态视图**

根据 spec，Step6 只保留完成态。当 `workflowPhase === 'completed'` 时，Step5 会跳转到 Step6。Step6 简化为：

```typescript
// 移除 'loading' | 'pipeline' | 'fusion' | 'atomic' | 'failed' | 'no-episode' 等中间状态
// 只保留：
type Step6ViewMode = 'completed' | 'no-episode';

// 保留现有的 state 声明（来自原 Step6page.tsx）
const [videoSegments, setVideoSegments] = useState<VideoSegmentInfo[]>([]);
const [segmentsExpanded, setSegmentsExpanded] = useState(false);
const [segmentsLoading, setSegmentsLoading] = useState(false);

if (viewMode === 'completed' && pipeline?.finalVideoUrl) {
  return (
    <div className={styles.completedView}>
      <div className={styles.completedIcon}>✓</div>
      <h1>视频生成完成</h1>
      <div className={styles.videoContainer}>
        <video controls src={pipeline.finalVideoUrl} />
      </div>
      <a className={styles.downloadButton} href={pipeline.finalVideoUrl} download>
        下载视频
      </a>
      {/* 可展开的视频片段列表 */}
      {videoSegments.length > 0 && (
        <div className={styles.segmentsSection}>
          <button onClick={() => setSegmentsExpanded(!segmentsExpanded)}>
            {segmentsExpanded ? '收起' : '展开'}视频片段列表 ({videoSegments.length})
          </button>
          {segmentsExpanded && (
            <div className={styles.segmentsList}>
              {videoSegments.map((seg) => (
                <div key={seg.panelIndex} className={styles.segmentItem}>
                  <div className={styles.segmentVideo}>
                    <video controls src={seg.videoUrl} preload="metadata" />
                  </div>
                  <div className={styles.segmentInfo}>
                    <span>#{seg.panelIndex + 1}</span>
                    <span>{seg.targetDuration}s</span>
                    <a href={seg.videoUrl} download>下载</a>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 简化样式文件**

移除所有与 `atomic`、`pipeline`、`fusion` 相关的样式定义。

- [ ] **Step 3: 验证构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -5`
Expected: build success

---

### Task 8: 一键自动化和统一合并按钮

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

- [ ] **Step 1: 实现 handleAutoContinue**

```typescript
const handleAutoContinue = useCallback(async () => {
  if (!episodeId) return;
  try {
    setAutoContinuing(true);
    await autoContinue(episodeId);
    // 流水线将在后端自动执行，前端继续轮询状态
  } catch (e: any) {
    console.error('一键自动化失败:', e);
  } finally {
    setAutoContinuing(false);
  }
}, [episodeId]);
```

- [ ] **Step 2: 实现 handleCompose**

```typescript
const handleCompose = useCallback(async () => {
  if (!episodeId) return;
  // 复用 autoContinue 触发最终视频合成
  // 或调用独立的合成接口（当前 spec 说复用 autoContinue）
  await autoContinue(episodeId);
}, [episodeId]);
```

- [ ] **Step 3: 验证构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -3`
Expected: build success

---

### Task 9: 集成测试与验收

**Files:**
- 测试范围：所有改动的文件

- [ ] **Step 1: 前端完整构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -10`
Expected: build success

- [ ] **Step 2: 验收标准检查**

逐项对照 `spec` 中的验收标准：

- [ ] 分镜确认后自动进入卡片视图，不再有独立的生产管线页
- [ ] 每张卡片能独立展示分镜信息、场景图、融合图、视频
- [ ] 场景图下方显示生成 Prompt（来自分镜 JSON 的 scene_desc）
- [ ] 场景图生成中：融合按钮禁用，显示等待文案
- [ ] 场景图生成失败：显示错误标记，引导重新生成
- [ ] 自动融合：按分镜 JSON 角色绑定关系自动叠加
- [ ] 手动融合：打开九宫格模态框精细调整，取消不改变状态
- [ ] 每个格子可独立重新生成场景图（需后端接口）
- [ ] 重新生成场景图后自动触发重新融合
- [ ] 每个格子可独立重新融合
- [ ] 每个格子可独立生成/重新生成视频
- [ ] 一键自动化：自动完成所有格子的融合+视频生成
- [ ] 全部完成后跳转 Step6 展示最终视频
- [ ] 前端构建通过

---

## 依赖关系

```
Task 1 (Backend) ─────────────────┐
                                  ├─> Task 9 (验收)
Task 2 (Types+API) ───────────────┤
                                  │
Task 3 (Step5 重写) ──┬───────────┤
                      │           │
Task 4 (Step5Card) ──┘           │
                                  │
Task 5 (GridFusion modal) ───────┤
                                  │
Task 6 (PanelVideoCard 扩展) ─────┤
                                  │
Task 7 (Step6 简化) ──────────────┘

Task 8 (一键自动化) 依赖 Task 2 和 Task 3
```

---

## 错误处理

| 场景 | 处理方式 |
|------|---------|
| 后端 regenerate-scene 接口失败 | 格子显示错误标记，按钮变为"重新生成场景图" |
| 场景图生成中用户点击融合 | 按钮禁用，显示"等待场景图生成完成" |
| 模态取消 | 直接关闭，不调用任何 API，融合状态保持不变 |
| 视频生成失败 | 格子显示失败状态 + 重试按钮 |
| 后端 Maven 编译失败 | 停在 Task 1，修复后继续 |
