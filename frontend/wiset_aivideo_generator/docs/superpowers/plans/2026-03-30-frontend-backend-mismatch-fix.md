# 前端适配新流程 Panel 数据结构

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复前端 Step5 视频生产页面与后端新流程 Panel 数据结构的不匹配，使分组后的多分镜单视频面板正确显示。

**Architecture:** 后端 `approveEpisodeGrid` 执行贪心分组后创建的 Panel 只有 `shots[]`、`totalDuration`、`fusionImageUrl`、`visualStyle`、`gridStatus=approved`、`videoStatus`，没有旧流程的 `composition`、`characters`、`background` 等字段。前端需要：1) 在 `loadPanelsForEpisode` 数据映射中适配新字段；2) SegmentCard 左侧将空的 GridReviewPanel 替换为融合图 + 分镜列表；3) 提示词详情区显示分组多镜头信息而非旧的空字段。

**Tech Stack:** React 19 / TypeScript / Less CSS Modules

---

## 问题清单

| # | 问题 | 影响 |
|---|------|------|
| 1 | 标题写死 `分镜 ${idx+1}`，实际是分组 | 语义错误 |
| 2 | synopsis 取 `scene_summary / composition`，新流程均不存在 | 摘要空白 |
| 3 | sceneThumbnail 取 `gridImages[0]`，新流程 Panel 级 gridImages 为空 | 无缩略图 |
| 4 | GridReviewPanel 传空 gridImages，显示"暂无九宫格图片" | 左侧面板空 |
| 5 | 提示词详情区显示旧的 composition/shotType 等空字段 | 全空白 |
| 6 | panelData.duration 不存在，新流程用 totalDuration | 时长显示 5s（默认值） |

## 后端新流程 Panel 的 panelInfo 实际结构

```json
{
  "shots": [
    {
      "shotNumber": 1,
      "duration": 4,
      "shotSize": "WIDE_SHOT",
      "cameraAngle": "LEVEL",
      "cameraMovement": "PAN_RIGHT",
      "visualDescription": "...",
      "scene": "...",
      "dialogue": "无",
      "audioEffects": "无",
      "visualEffects": "无",
      "characters": ["角色A", "角色B"],
      "splitImageUrl": "https://..."
    }
  ],
  "totalShots": 3,
  "totalDuration": 12,
  "gridStatus": "approved",
  "videoStatus": "pending",
  "visualStyle": "ANIME",
  "gridImages": [],
  "fusionImageUrl": "https://...",
  "videoUrl": null,
  "videoTaskId": null,
  "offPeak": null
}
```

---

### Task 1: 更新 types.ts — PanelData 适配新流程

**Files:**
- Modify: `src/pages/create/steps/types.ts`

- [ ] **Step 1: 更新 PanelData 接口，添加新流程字段**

```typescript
/** 分镜详细信息 */
export interface PanelData {
  panelId: string;
  /** panelInfo 中的 panel_id（如 "p1"、"p2"），用于匹配 panelPlan（旧流程） */
  planPanelId: string;
  composition: string;
  shotType: string;
  cameraAngle: string;
  pacing: string;
  dialogue: string;
  characters: any[];
  background: any;
  imagePromptHint: string;
  sfx: string[];
  duration?: number;
  // === 新流程字段 ===
  totalShots?: number;
  totalDuration?: number;
  visualStyle?: string;
  fusionImageUrl?: string | null;
}
```

- [ ] **Step 2: TypeScript 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

---

### Task 2: 修复 Step5page.tsx 数据映射 — loadPanelsForEpisode

**Files:**
- Modify: `src/pages/create/steps/Step5page.tsx` (约 327-386 行，`loadPanelsForEpisode` 中的 panel→segment 映射)

- [ ] **Step 1: 更新 segment 映射逻辑**

将 `loadPanelsForEpisode` 中的 panel→segment 映射改为：

```typescript
// 将分镜转为 segments
const segments: SegmentState[] = panels.map((panel: any, idx: number) => {
  const info = panel.panelInfo || {};
  const planPanelId = info.panel_id || info.planPanelId || '';
  const shots = info.shots || [];
  const isGroupedPanel = shots.length > 1;

  // 新流程：从 shots 中提取角色和对话
  const allCharacters = shots.flatMap((s: any) => s.characters || []);
  const uniqueCharNames = [...new Set(allCharacters)];

  // 新流程：用第一个 shot 的 visualDescription 作为摘要
  const synopsis = isGroupedPanel
    ? `${shots.length} 个分镜 · ${info.totalDuration || shots.reduce((s: number, sh: any) => s + (sh.duration || 0), 0)}s`
    : (info.scene_summary || (planPanelId ? sceneSummaryMap[planPanelId] : '') || info.composition || shots[0]?.visualDescription || '');

  // 新流程缩略图：用融合图或第一个 shot 的 splitImageUrl
  const thumbnail = info.fusionImageUrl
    || shots[0]?.splitImageUrl
    || (info.gridImages?.length > 0 ? info.gridImages[0] : null);

  const videoUrl = info.videoUrl || null;
  const videoStatus = info.videoStatus || null;
  const gridImages = info.gridImages || [];
  const gridStatus = info.gridStatus || 'pending';
  const fusionImageUrl = info.fusionImageUrl || null;

  return {
    segmentIndex: idx,
    title: isGroupedPanel ? `分组 ${idx + 1}` : `分镜 ${idx + 1}`,
    synopsis,
    sceneThumbnail: thumbnail,
    characterAvatars: uniqueCharNames.map(name => ({
      charId: charNameToIdMap[name] || '',
      name,
      avatarUrl: (charNameToIdMap[name] && charAvatarMap[charNameToIdMap[name]]) || '',
    })),
    pipelineStep: mapGridToPipelineStep({
      gridStatus: gridStatus,
      videoStatus: videoStatus || 'pending',
    }),
    gridImages,
    gridStatus,
    fusionImageUrl,
    shots,
    videoUrl,
    feedback: info.revisionFeedback || '',
    panelData: {
      panelId: String(panel.id),
      planPanelId,
      composition: info.composition || '',
      shotType: info.shot_type,
      cameraAngle: info.camera_angle,
      pacing: info.pacing,
      dialogue: Array.isArray(info.dialogue)
        ? info.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n')
        : '',
      characters: info.characters || [],
      background: info.background || {},
      imagePromptHint: info.image_prompt_hint,
      sfx: info.sfx || [],
      duration: info.duration || info.totalDuration,
      // 新流程字段
      totalShots: info.totalShots,
      totalDuration: info.totalDuration,
      visualStyle: info.visualStyle,
    },
  };
});
```

关键变化：
- 删除旧的 `characterAvatars` 解析（基于 `info.characters` + `char_id`）
- 新流程从 `shots[].characters` 提取角色名
- `title` 区分"分组"vs"分镜"
- `synopsis` 新流程显示 `N 个分镜 · Xs`
- `sceneThumbnail` 优先用 `fusionImageUrl` 或 `splitImageUrl`
- `duration` fallback 到 `totalDuration`

- [ ] **Step 2: TypeScript 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

---

### Task 3: 重构 SegmentCard 左侧面板 — GridReviewPanel 替换为 PanelGroupView

**Files:**
- Create: `src/pages/create/steps/components/PanelGroupView.tsx`
- Modify: `src/pages/create/steps/components/SegmentCard.tsx`

- [ ] **Step 1: 创建 PanelGroupView 组件**

新流程的 Panel 已通过九宫格审核（`gridStatus=approved`），无需再审核。左侧应显示：
- 融合参考图（如有）
- 该分组包含的镜头列表（shotNumber、duration、shotSize、visualDescription）
- 状态徽章"已通过"

```typescript
// src/pages/create/steps/components/PanelGroupView.tsx
import React from 'react';
import styles from './PanelGroupView.module.less';

interface PanelGroupViewProps {
  fusionImageUrl: string | null;
  shots: any[];
  gridStatus: string;
}

const shotSizeMap: Record<string, string> = {
  WIDE_SHOT: '远景',
  MID_SHOT: '中景',
  CLOSE_UP: '特写',
  OVER_SHOULDER: '过肩',
  Establishing: '建立镜头',
};

export const PanelGroupView: React.FC<PanelGroupViewProps> = ({
  fusionImageUrl,
  shots,
  gridStatus,
}) => {
  const isApproved = gridStatus === 'approved';
  const totalDuration = shots.reduce((s: number, sh: any) => s + (sh.duration || 0), 0);

  return (
    <div className={styles.panelGroupView}>
      <div className={styles.header}>
        <h3 className={styles.title}>
          {shots.length} 个分镜 · {totalDuration}s
        </h3>
        {isApproved && (
          <div className={styles.statusBadge} style={{ backgroundColor: '#4ade80' }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M20 6L9 17l-5-5" />
            </svg>
            <span>已通过</span>
          </div>
        )}
      </div>

      {/* 融合参考图 */}
      {fusionImageUrl && (
        <div className={styles.fusionImageContainer}>
          <img src={fusionImageUrl} alt="融合参考图" className={styles.fusionImage} />
          <span className={styles.fusionLabel}>融合参考图</span>
        </div>
      )}

      {/* 镜头列表 */}
      {shots.length > 0 && (
        <div className={styles.shotList}>
          {shots.map((shot: any, idx: number) => (
            <div key={idx} className={styles.shotItem}>
              <span className={styles.shotNumber}>#{shot.shotNumber || idx + 1}</span>
              <span className={styles.shotDuration}>{shot.duration}s</span>
              {shot.shotSize && (
                <span className={styles.shotSize}>
                  {shotSizeMap[shot.shotSize] || shot.shotSize}
                </span>
              )}
              <span className={styles.shotDesc}>
                {shot.visualDescription || shot.scene || ''}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
```

- [ ] **Step 2: 创建 PanelGroupView.module.less**

```less
.panelGroupView {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #e0e0e0;
}

.statusBadge {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  color: #fff;
}

.fusionImageContainer {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #333;
}

.fusionImage {
  width: 100%;
  display: block;
  border-radius: 8px;
}

.fusionLabel {
  position: absolute;
  bottom: 6px;
  right: 6px;
  background: rgba(0, 0, 0, 0.7);
  color: #ccc;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}

.shotList {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.shotItem {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: #1a1a2e;
  border-radius: 6px;
  font-size: 13px;
}

.shotNumber {
  color: #888;
  font-size: 12px;
  min-width: 28px;
}

.shotDuration {
  color: #fbbf24;
  font-size: 12px;
  min-width: 28px;
}

.shotSize {
  background: #2a2a4a;
  color: #aaa;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  min-width: 36px;
  text-align: center;
}

.shotDesc {
  color: #ccc;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

- [ ] **Step 3: 修改 SegmentCard.tsx — 根据是否为分组 Panel 切换左侧面板**

在 SegmentCard 中，判断 `segment.shots.length > 1` 或 `segment.gridImages.length === 0 && segment.gridStatus === 'approved'` 来决定显示 PanelGroupView 还是 GridReviewPanel。

修改 `SegmentCard.tsx` 展开内容区（约 241-267 行）：

```typescript
{/* 展开内容区 */}
{isExpanded && (
  <div className={styles.content}>
    {/* 左侧面板 */}
    <div className={styles.panel}>
      {segment.shots.length > 1 ? (
        // 新流程：多分镜分组 → 显示融合图 + 镜头列表
        <PanelGroupView
          fusionImageUrl={segment.fusionImageUrl}
          shots={segment.shots}
          gridStatus={segment.gridStatus}
        />
      ) : (
        // 单分镜或旧流程 → 显示九宫格审核面板
        <GridReviewPanel
          panelId={Number(segment.panelData?.panelId || 0)}
          gridImages={segment.gridImages}
          shots={segment.shots}
          gridStatus={segment.gridStatus}
          gridRejectionFeedback={segment.feedback || null}
          onApprove={onApproveGrid}
          onReject={onRejectGrid}
          onRegenerate={onRegenerateGrid}
          isRegenerating={isRegeneratingGrid}
        />
      )}
    </div>

    {/* 右侧：AI 视频面板 */}
    <div className={styles.panel}>
      <VideoPanel
        videoUrl={segment.videoUrl}
        pipelineStep={segment.pipelineStep}
        onGenerateVideo={onGenerateVideo}
        isGenerating={isGeneratingVideo}
        videoTaskId={segment.videoTaskId}
        videoOffPeak={segment.videoOffPeak}
      />
    </div>

    {/* 提示词详情 — 保持不变（已有多镜头提示词显示逻辑） */}
    ...
  </div>
)}
```

添加 import:
```typescript
import { PanelGroupView } from './PanelGroupView';
```

- [ ] **Step 4: TypeScript 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

---

### Task 4: 优化 SegmentCard 提示词详情区 — 适配新流程

**Files:**
- Modify: `src/pages/create/steps/components/SegmentCard.tsx` (约 279-347 行)

- [ ] **Step 1: 更新提示词详情区**

"分镜字段"区块中，新流程 Panel 的 `panelData.composition` 等字段为空，应改为显示 shots 列表和分组信息。同时视频提示词已有正确逻辑（遍历 shots），保持不变。

修改"分镜字段"区块（约 316-337 行）：

```typescript
{/* 分镜信息 */}
<div className={styles.promptGroup}>
  <div className={styles.promptGroupTitle} onClick={() => toggleGroup('fields')}>
    <span className={styles.promptGroupArrow}>{expandedGroups.has('fields') ? '▼' : '▶'}</span>
    {shots.length > 1 ? '分组分镜列表' : '分镜字段'}
  </div>
  {expandedGroups.has('fields') && (
  <div className={styles.promptFieldGrid}>
    {shots.length > 1 ? (
      // 新流程：多镜头分组 → 显示每个 shot 的摘要
      shots.map((shot: any, idx: number) => (
        <div key={idx} className={`${styles.promptFieldItem} ${styles.promptFieldItemFull}`}>
          <span className={styles.pfLabel}>分镜 {shot.shotNumber || idx + 1}</span>
          <span className={styles.pfValue}>
            {shot.duration}s · {shot.shotSize || ''} · {shot.cameraAngle || ''} · {shot.visualDescription || ''}
          </span>
        </div>
      ))
    ) : (
      // 单分镜或旧流程：显示原始字段
      <>
        {d.composition && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>构图描述</span><span className={styles.pfValue}>{d.composition}</span></div>}
        {(d.shotType || d.cameraAngle) && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>镜头</span><span className={styles.pfValue}>{d.shotType}{d.cameraAngle ? ` / ${d.cameraAngle}` : ''}</span></div>}
        {d.pacing && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>节奏</span><span className={styles.pfValue}>{d.pacing}</span></div>}
        <div className={styles.promptFieldItem}><span className={styles.pfLabel}>时长</span><span className={styles.pfValue}>{d.duration || 5}s</span></div>}
        {d.background?.scene_desc && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>场景描述</span><span className={styles.pfValue}>{d.background.scene_desc}</span></div>}
        {d.background?.atmosphere && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>氛围</span><span className={styles.pfValue}>{d.background.atmosphere}</span></div>}
        {d.background?.time_of_day && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>时间</span><span className={styles.pfValue}>{d.background.time_of_day}</span></div>}
        {d.characters?.length > 0 && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>角色</span><span className={styles.pfValue}>{d.characters.map((c: any) => `${c.name || c.char_id}${c.expression ? `(${c.expression})` : ''}${c.pose ? `[${c.pose}]` : ''}`).join('、')}</span></div>}
        {d.dialogue && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>对话</span><span className={styles.pfValue}>{d.dialogue}</span></div>}
        {d.sfx?.length > 0 && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>音效</span><span className={styles.pfValue}>{d.sfx.join('、')}</span></div>}
        {d.imagePromptHint && <div className={`${styles.promptFieldItem} ${styles.promptFieldItemFull}`}><span className={styles.pfLabel}>画面提示词</span><span className={styles.pfValue}>{d.imagePromptHint}</span></div>}
      </>
    )}
  </div>
  )}
</div>
```

- [ ] **Step 2: TypeScript 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

---

### Task 5: 最终构建验证

- [ ] **Step 1: Vite 生产构建**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx vite build`
Expected: 构建成功，无 TypeScript 错误

- [ ] **Step 2: 视觉确认清单**

手动检查（或截图）以下场景：
1. Episode 九宫格审核通过后，展开 Episode → 看到"分组 1"、"分组 2"等标题
2. 展开一个分组 → 左侧显示融合参考图 + 镜头列表（而非空的九宫格面板）
3. 折叠状态的摘要显示 "3 个分镜 · 12s"
4. 缩略图显示融合图（如有）
5. 提示词详情 → "分组分镜列表"区块显示每个 shot 的信息
6. 视频提示词区块正确显示多镜头连续拍摄指令
