# 逐集流水线阶段 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个 episode 独立走 4a→4b→4c 流水线，不同 episode 可同时处于不同阶段。

**Architecture:** 前端推导 `pipelineStage`（从 `panelApproved` + `gridStatus` 计算），移除全局 Tab 解锁逻辑。每个 Tab 按阶段筛选 episode，底部折叠显示已进入下一阶段的 episode。

**Tech Stack:** React, TypeScript, Less, Spring Boot (可选后端改动)

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `types.ts` | 新增 `PipelineStage` 类型 |
| `Step4Production.tsx` | 核心改动：移除全局解锁、按阶段筛选、折叠区、Tab 指示器 |
| `Step4Production.module.less` | 新增折叠区 + 已通过徽章样式 |
| `EpisodeController.java`（可选） | 新增"退回脚本阶段"接口 |

---

### Task 1: 添加 PipelineStage 类型和推导函数

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts:57-58`

- [ ] **Step 1: 在 types.ts 添加类型和推导函数**

在 `EpisodeGridStatus` 类型定义之后，添加：

```typescript
/** Episode 流水线阶段（前端推导，不存储到后端） */
export type PipelineStage = 'script' | 'grid' | 'video';

/** 从 panelApproved + gridStatus 推导当前流水线阶段 */
export function getPipelineStage(ep: {
  panelApproved?: boolean;
  gridStatus?: EpisodeGridStatus;
}): PipelineStage {
  if (!ep.panelApproved) return 'script';
  if (ep.gridStatus === 'approved') return 'video';
  return 'grid';
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
git commit -m "feat: add PipelineStage type and derivation function"
```

---

### Task 2: 移除全局 Tab 解锁逻辑 + 添加按阶段筛选

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

- [ ] **Step 1: 添加 import**

在文件顶部的 types import 中添加 `getPipelineStage`：

```typescript
import { ChapterState, EpisodeState, ExpansionState, getPipelineStage, PipelineStage } from './types';
```

- [ ] **Step 2: 移除 tab4bUnlocked / tab4cUnlocked**

找到约第 780-788 行，**删除**以下代码：

```typescript
// Tab 4b unlocked when ALL episodes have script approved (panelApproved)
// Spec: "4a → 4b：所有集脚本确认后自动解锁"
const tab4bUnlocked = allEpisodes.length > 0 && allEpisodes.every(ep => ep.panelApproved);

// Tab 4c unlocked when ALL episodes have grid approved
// Spec: "4b → 4c：所有集九宫格审核通过后自动解锁"
const tab4cUnlocked = tab4bUnlocked && allEpisodes.every(ep => ep.gridStatus === 'approved');
```

替换为 per-tab 计数统计（用于 Tab 指示器）：

```typescript
// Per-tab episode counts for progress indicator
const scriptCount = allEpisodes.filter(ep => getPipelineStage(ep) === 'script').length;
const gridCount = allEpisodes.filter(ep => getPipelineStage(ep) === 'grid').length;
const videoCount = allEpisodes.filter(ep => getPipelineStage(ep) === 'video').length;
```

- [ ] **Step 3: 添加 episode 阶段筛选 helper**

在 `allEpisodes` 定义之后添加：

```typescript
// Filter chapters' episodes by pipeline stage
const filterChaptersByStage = useCallback((stage: PipelineStage, chapters: ChapterState[]): ChapterState[] => {
  return chapters.map(ch => ({
    ...ch,
    episodes: ch.episodes.filter(ep => getPipelineStage(ep) === stage),
  })).filter(ch => ch.episodes.length > 0);
}, []);

// Get episodes that have passed beyond a given stage (for collapsed footer)
const getPassedEpisodes = useCallback((stage: PipelineStage, chapters: ChapterState[]): EpisodeState[] => {
  const stageOrder: PipelineStage[] = ['script', 'grid', 'video'];
  const currentIdx = stageOrder.indexOf(stage);
  return chapters.flatMap(ch => ch.episodes).filter(ep => {
    const epIdx = stageOrder.indexOf(getPipelineStage(ep));
    return epIdx > currentIdx;
  });
}, []);
```

- [ ] **Step 4: 修改 Tab 进度条（移除锁定）**

找到约第 1238-1273 行的 `STEPS.map` 部分，替换整个 map 回调内容：

```typescript
{STEPS.map((step, idx) => {
  const isActive = activeTab === step.key;
  const count = step.key === 'script' ? scriptCount
    : step.key === 'grid' ? gridCount
    : videoCount;
  // Tab is "completed" when no episodes remain at this stage
  const completed = count === 0 && allEpisodes.length > 0;
  const stepClasses = [
    styles.stepItem,
    isActive && styles.stepItemActive,
    completed && !isActive && styles.stepItemCompleted,
  ].filter(Boolean).join(' ');

  return (
    <div key={step.key} style={{ display: 'contents' }}>
      <button
        className={stepClasses}
        onClick={() => switchTab(step.key)}
      >
        <span className={styles.stepNumber}>
          {completed && !isActive ? <CheckIcon /> : `4${step.number}`}
        </span>
        <span className={styles.stepLabel}>
          {step.label}
          {count > 0 && <span className={styles.stepCount}>{count}</span>}
        </span>
      </button>
      {idx < STEPS.length - 1 && (
        <div className={`${styles.stepConnector} ${completed ? styles.stepConnectorCompleted : ''}`} />
      )}
    </div>
  );
})}
```

关键变化：
- 移除 `locked` 状态判断和 `disabled` 属性
- 移除 `<LockIcon />` 渲染
- `completed` 改为：该阶段 episode 数为 0（全部已流转到下一阶段）
- Tab 标签旁显示剩余 episode 数量

- [ ] **Step 5: 移除 handleApproveScript 中的自动切换 Tab**

找到 `handleApproveScript`（约第 808-825 行），将函数体替换为：

```typescript
const handleApproveScript = useCallback(async (episodeId: number) => {
  if (!projectId || approvingEpisodeId) return;
  setApprovingEpisodeId(episodeId);
  try {
    await approvePanel(projectId, episodeId);
    await loadEpisodes();
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '审核失败');
  } finally {
    setApprovingEpisodeId(null);
  }
}, [projectId, loadEpisodes, approvingEpisodeId]);
```

移除了 `getEpisodes` 二次调用和 `switchTab('grid')` 自动跳转。

- [ ] **Step 6: 移除 handleApproveGrid 中的自动切换 Tab**

找到 `handleApproveGrid`（约第 875-893 行），将函数体替换为：

```typescript
const handleApproveGrid = useCallback(async (episodeId: number) => {
  if (!projectId || approvingEpisodeId) return;
  setApprovingEpisodeId(episodeId);
  try {
    await approveEpisodeGrid(projectId, episodeId);
    panelsLoadedRef.current.delete(episodeId);
    await loadEpisodes();
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '审核失败');
  } finally {
    setApprovingEpisodeId(null);
  }
}, [projectId, loadEpisodes, approvingEpisodeId]);
```

- [ ] **Step 7: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat: remove global tab unlock, add per-stage episode filtering"
```

---

### Task 3: Tab 4a 按阶段筛选 + 折叠已完成区

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` (~line 1310-1462)

- [ ] **Step 1: 修改 Tab 4a 内容区域**

找到 `{activeTab === 'script' && (` 开始的 Tab 4a 区块（约第 1310 行）。将区块内 `chapters.map(...)` 的渲染部分替换为按阶段筛选的版本。

在 `{/* Tab 4a: Script */}` 之后，将整个 tab content 替换为：

```tsx
{activeTab === 'script' && (
  <div className={styles.scriptReviewSection}>
    {chapters.length === 0 ? (
      <div className={styles.emptyState}><p>暂无章节数据</p></div>
    ) : (
      <>
        {/* 当前阶段的 episode */}
        {filterChaptersByStage('script', chapters).map(chapter => {
          const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
          return (
          <div key={chapter.chapterIndex} className={styles.chapterGroup}>
            <button className={styles.chapterHeader} onClick={() => toggleChapter(chapter.chapterIndex)}>
              <ChevronIcon open={chapterOpen} />
              <BookIcon />
              <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
            </button>
            {chapterOpen && (
            <div className={styles.episodeList}>
              {chapter.episodes.map(ep => (
                <div key={ep.episodeId} className={`${styles.episodeScriptCard} ${(generatingScript === ep.episodeId || generatingScript === -1) ? styles.cardGenerating : ''}`}>
                  <div className={styles.episodeScriptHeader}>
                    <div>
                      <h3 className={styles.episodeScriptTitle}>
                        第{ep.episodeIndex}集 {ep.title}
                      </h3>
                      <span className={styles.episodeScriptCount}>
                        {ep.segments.length > 0 ? `${ep.segments.length} 个分镜` : '暂无分镜数据'}
                      </span>
                    </div>
                    <div className={styles.episodeScriptActions}>
                      <button
                        className={styles.btnPrimary}
                        onClick={() => handleGenerateScript(ep.episodeId)}
                        disabled={generatingScript === ep.episodeId || generatingScript === -1}
                      >
                        {(generatingScript === ep.episodeId || generatingScript === -1) ? <><SpinIcon /> 生成中...</> : '生成脚本'}
                      </button>
                      {ep.segments.length > 0 && (
                        <>
                          <button
                            className={styles.btnSuccess}
                            onClick={() => handleApproveScript(ep.episodeId)}
                            disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                          >
                            {approvingEpisodeId === ep.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
                          </button>
                          <button
                            className={styles.btnDanger}
                            onClick={() => {
                              const reason = prompt('请给出你的优化建议:');
                              if (reason) handleRejectScript(ep.episodeId, reason!);
                            }}
                            disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                          >
                            {rejectingEpisodeId === ep.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
                          </button>
                        </>
                      )}
                    </div>
                  </div>

                  {/* Expand to show script text */}
                  <button
                    className={styles.expandToggle}
                    onClick={() => toggleEpisode(ep.episodeId)}
                  >
                    {expandedEpisodeId === ep.episodeId ? '收起' : '展开'}分镜文本 &#9660;
                  </button>

                  {expandedEpisodeId === ep.episodeId && ep.segments.length > 0 && (
                    <div className={styles.scriptSegmentList}>
                      {ep.segments.map((seg, idx) => (
                        <div key={idx} className={styles.scriptSegmentItem}>
                          <div className={styles.scriptSegmentTitle}>分镜 {idx + 1}</div>
                          {seg.synopsis && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>画面：</span>{seg.synopsis}
                            </div>
                          )}
                          {seg.panelData?.dialogue && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>对话：</span><span style={{ whiteSpace: 'pre-wrap' }}>{seg.panelData.dialogue}</span>
                            </div>
                          )}
                          {seg.characterAvatars.length > 0 && (
                            <div className={styles.scriptSegmentCharacters}>
                              <span>角色：</span>{seg.characterAvatars.map(a => a.name).join('、')}
                            </div>
                          )}
                          {seg.panelData?.composition && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>景别：</span>{seg.panelData.composition}
                            </div>
                          )}
                          {seg.panelData?.cameraAngle && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>镜头：</span>{seg.panelData.cameraAngle}
                            </div>
                          )}
                          {seg.panelData?.cameraMovement && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>运镜：</span>{seg.panelData.cameraMovement}
                            </div>
                          )}
                          {seg.panelData?.pacing && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>节奏：</span>{seg.panelData.pacing}
                            </div>
                          )}
                          {seg.panelData?.sfx && seg.panelData.sfx.length > 0 && (
                            <div className={styles.scriptSegmentDetail}>
                              <span>音效：</span>{seg.panelData.sfx.join('、')}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
            )}
          </div>
          );
        })}

        {/* 折叠：已进入下一阶段的 episode */}
        {getPassedEpisodes('script', chapters).length > 0 && (
          <details className={styles.passedEpisodesSection}>
            <summary className={styles.passedEpisodesSummary}>
              已完成脚本审核（{getPassedEpisodes('script', chapters).length} 集）
            </summary>
            <div className={styles.passedEpisodesList}>
              {getPassedEpisodes('script', chapters).map(ep => {
                const stage = getPipelineStage(ep);
                const stageLabel = stage === 'grid' ? '→ 九宫格' : '→ 视频';
                return (
                  <div key={ep.episodeId} className={styles.passedEpisodeItem}>
                    <span className={styles.passedEpisodeTitle}>第{ep.episodeIndex}集 {ep.title}</span>
                    <span className={styles.passedEpisodeStage}>{stageLabel}</span>
                  </div>
                );
              })}
            </div>
          </details>
        )}
      </>
    )}
  </div>
)}
```

关键变化：
- `chapters.map(...)` → `filterChaptersByStage('script', chapters).map(...)`
- 底部新增 `<details>` 折叠区，显示已流转到 grid/video 阶段的 episode
- episode 卡片内容保持不变（保持原有的展开/收起交互）

- [ ] **Step 2: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat: Tab 4a filters by script stage, add collapsed passed section"
```

---

### Task 4: Tab 4b 按阶段筛选 + 折叠已完成区

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` (~line 1464-1563)

- [ ] **Step 1: 修改 Tab 4b 内容区域**

找到 `{/* Tab 4b: Grid */}` 区块（约第 1464 行），将 `chapters.map(...)` 替换为：

```tsx
{activeTab === 'grid' && (
  <div className={styles.gridReviewSection}>
    {chapters.length === 0 ? (
      <div className={styles.emptyState}><p>暂无章节数据</p></div>
    ) : (
      <>
        {filterChaptersByStage('grid', chapters).map(chapter => {
          const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
          return (
          <div key={chapter.chapterIndex} className={styles.chapterGroup}>
            <button className={styles.chapterHeader} onClick={() => toggleChapter(chapter.chapterIndex)}>
              <ChevronIcon open={chapterOpen} />
              <BookIcon />
              <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
            </button>
            {chapterOpen && (
            <div className={styles.episodeList}>
              {chapter.episodes.map(ep => {
                const badge = getGridStatusBadge(ep.gridStatus || 'pending');
                return (
                <div key={ep.episodeId} className={styles.episodeGridCard}>
                  <div className={styles.episodeGridHeader}>
                    <div>
                      <h3 className={styles.episodeGridTitle}>
                        第{ep.episodeIndex}集 {ep.title}
                      </h3>
                      <span className={`${styles.statusBadge} ${badge.className}`}>
                        <span className={`${styles.statusDot} ${badge.pulse ? styles.statusDotPulse : ''}`} />
                        {badge.text}
                      </span>
                      {ep.gridRejectionFeedback && (
                        <div className={styles.gridRejectionFeedback}>
                          退回原因：{ep.gridRejectionFeedback}
                        </div>
                      )}
                    </div>
                    <div className={styles.episodeGridActions}>
                      {ep.gridStatus !== 'approved' && ep.gridStatus !== 'generated' && (
                      <button
                        className={styles.btnPrimary}
                        onClick={() => handleGenerateGrid(ep.episodeId)}
                        disabled={generatingGrid === ep.episodeId}
                      >
                        {generatingGrid === ep.episodeId ? <><SpinIcon /> 排队中...</> : '生成九宫格'}
                      </button>
                      )}
                      {(ep.gridStatus === 'generated' || ep.gridStatus === 'rejected') && (
                        <>
                          <button
                            className={styles.btnSuccess}
                            onClick={() => handleApproveGrid(ep.episodeId)}
                            disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                          >
                            {approvingEpisodeId === ep.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
                          </button>
                          <button
                            className={styles.btnDanger}
                            onClick={() => {
                              const reason = prompt('请给出你的优化建议:');
                              if (reason) handleRejectGrid(ep.episodeId, reason!);
                            }}
                            disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                          >
                            {rejectingEpisodeId === ep.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
                          </button>
                        </>
                      )}
                    </div>
                  </div>

                  {/* Grid images */}
                  {ep.gridImages && ep.gridImages.length > 0 && (
                    <div className={styles.gridImagesContainer}>
                      {ep.gridImages.map((url, idx) => (
                        <img
                          key={idx}
                          src={url}
                          alt={`九宫格 ${idx + 1}`}
                          className={styles.gridImage}
                          onClick={() => setLightboxUrl(url)}
                        />
                      ))}
                    </div>
                  )}
                  {(!ep.gridImages || ep.gridImages.length === 0) && (
                    <div className={styles.gridEmptyState}>
                      暂无九宫格图片
                    </div>
                  )}
                </div>
                );
              })}
            </div>
            )}
          </div>
          );
        })}

        {/* 折叠：已进入视频阶段的 episode */}
        {getPassedEpisodes('grid', chapters).length > 0 && (
          <details className={styles.passedEpisodesSection}>
            <summary className={styles.passedEpisodesSummary}>
              已完成九宫格审核（{getPassedEpisodes('grid', chapters).length} 集）
            </summary>
            <div className={styles.passedEpisodesList}>
              {getPassedEpisodes('grid', chapters).map(ep => (
                <div key={ep.episodeId} className={styles.passedEpisodeItem}>
                  <span className={styles.passedEpisodeTitle}>第{ep.episodeIndex}集 {ep.title}</span>
                  <span className={styles.passedEpisodeStage}>→ 视频生成</span>
                </div>
              ))}
            </div>
          </details>
        )}
      </>
    )}
  </div>
)}
```

关键变化：
- `chapters.map(...)` → `filterChaptersByStage('grid', chapters).map(...)`
- episode 卡片内容保持不变
- 底部新增折叠区显示已进入 video 阶段的 episode

- [ ] **Step 2: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat: Tab 4b filters by grid stage, add collapsed passed section"
```

---

### Task 5: Tab 4c 按阶段筛选

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` (~line 1565-1845)

- [ ] **Step 1: 修改 Tab 4c 内容区域**

找到 `{/* Tab 4c: Video */}` 区块（约第 1565 行），将 `chapters.map(...)` 替换为：

```tsx
{activeTab === 'video' && (
  <div className={styles.videoReviewSection}>
    {chapters.length === 0 ? (
      <div className={styles.emptyState}><p>暂无章节数据</p></div>
    ) : (
      filterChaptersByStage('video', chapters).map(chapter => {
        const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
        return (
        <div key={chapter.chapterIndex} className={styles.chapterGroup}>
          <button className={styles.chapterHeader} onClick={() => toggleChapter(chapter.chapterIndex)}>
            <ChevronIcon open={chapterOpen} />
            <BookIcon />
            <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
          </button>
          {/* 保持原有的 episode video card 内容不变，此处仅改外层 chapters 来源 */}
          {chapterOpen && (
          <div className={styles.episodeList}>
            {chapter.episodes.map(ep => {
              /* ... 保持原有 episode video card 渲染逻辑 ... */
```

**注意**：Tab 4c 的 episode card 内部渲染逻辑**保持原样不变**，只改外层的 `chapters.map` → `filterChaptersByStage('video', chapters).map`。由于 4c 是最后阶段，不需要折叠区。

- [ ] **Step 2: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat: Tab 4c filters by video stage"
```

---

### Task 6: 添加样式

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less`

- [ ] **Step 1: 添加 Tab 计数徽章和折叠区样式**

在 `.stepLabel` 样式之后（约第 861 行）添加：

```less
// Tab episode count badge
.stepCount {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: rgba(114, 183, 255, 0.15);
  color: var(--color-accent);
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  margin-left: 4px;
}
```

在文件末尾添加折叠区样式：

```less
// ============================================================
// Passed Episodes (collapsed footer in 4a / 4b tabs)
// ============================================================

.passedEpisodesSection {
  margin-top: var(--space-6);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: rgba(247, 249, 252, 0.02);
}

.passedEpisodesSummary {
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-muted);
  cursor: pointer;
  user-select: none;
  list-style: none;

  &::marker {
    display: none;
    content: '';
  }

  &:hover {
    color: var(--color-text-secondary);
    background: rgba(247, 249, 252, 0.04);
  }
}

.passedEpisodesList {
  padding: 0 var(--space-4) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.passedEpisodeItem {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: rgba(247, 249, 252, 0.03);
  font-size: var(--font-size-sm);
}

.passedEpisodeTitle {
  color: var(--color-text-secondary);
}

.passedEpisodeStage {
  color: var(--color-success);
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-xs);
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less
git commit -m "feat: add tab count badge and passed episodes section styles"
```

---

### Task 7: 后端新增"退回脚本阶段"接口

**Files:**
- Modify: `comic/src/main/java/com/comic/controller/EpisodeController.java`

- [ ] **Step 1: 在 EpisodeController 添加新接口**

在 `rejectPanel` 方法之后添加：

```java
@PutMapping("/{episodeId}/panel/reject-to-script")
@Operation(summary = "退回脚本阶段", description = "将已通过脚本审核的剧集退回4a，清除九宫格相关数据")
public Result<Void> rejectToScript(
        @PathVariable String projectId,
        @PathVariable Long episodeId) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");
    Map<String, Object> info = episode.getEpisodeInfo();
    if (info == null) info = new HashMap<>();

    info.put("panelApproved", false);
    info.put("gridStatus", "pending");
    info.remove("gridImages");
    info.remove("splitShots");
    info.put("gridRejectionFeedback", null);
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);

    log.info("剧集退回脚本阶段: episodeId={}", episodeId);
    return Result.ok();
}
```

- [ ] **Step 2: 前端添加对应 API 调用**

在 `episodeService.ts` 添加：

```typescript
export async function rejectToScript(projectId: string, episodeId: number): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panel/reject-to-script`,
  );
}
```

- [ ] **Step 3: 提交**

```bash
git add comic/src/main/java/com/comic/controller/EpisodeController.java
git add frontend/wiset_aivideo_generator/src/services/episodeService.ts
git commit -m "feat: add reject-to-script endpoint for 4b→4a rollback"
```

---

### Task 8: 手动验证

- [ ] **Step 1: 启动前后端**

```bash
# 后端
cd backend/com && mvn spring-boot:run

# 前端
cd frontend/wiset_aivideo_generator && npm run dev
```

- [ ] **Step 2: 验证场景**

| 场景 | 预期 |
|---|---|
| 打开 Step 4，3 个 Tab 都可点击 | 无锁图标，Tab 全部解锁 |
| 4a Tab 只显示未审脚本的 episode | 已审的不在主列表，在折叠区 |
| 审核通过 1 集 | 该集从 4a 主列表消失，出现在 4a 折叠区和 4b 主列表 |
| 4b Tab 显示该集 | 可生成九宫格、审核 |
| 4a 折叠区显示"已完成脚本审核（1 集）" | 显示集名 + "→ 九宫格" |
| 全部审完后，4a Tab 显示 ✓ | 4b Tab 也显示 ✓（无 episode 在该阶段） |
| 在 4b 退回某集九宫格 | 该集留在 4b，状态变为 rejected |
| 在 4b 使用"退回脚本阶段"（如已实现） | 该集回到 4a |