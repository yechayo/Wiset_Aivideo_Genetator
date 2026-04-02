# 创作流程重构：6步→5步 + Step4 子阶段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将前端创作流程从 6 步简化为 5 步，Step4 增加 Tab 子阶段（脚本→九宫格→视频），后端调整 frontendStep 映射并增强 SSE 事件。

**Architecture:** 前端步骤重组（不改后端状态机），后端只改 `frontendStep` 映射和增加 panel 级别 SSE 事件。storyboard→panel 重命名已完成。

**Tech Stack:** React 18 + TypeScript + Zustand (前端), Spring Boot + Spring State Machine + Redis SSE (后端)

**Spec:** `docs/superpowers/specs/2026-04-02-step-redesign-design.md`

---

## Pre-completed

The following storyboard→panel rename was already done:
- `EpisodeController.java`: `/storyboard/approve|reject` → `/panel/approve|reject`, fields `storyboardApproved` → `panelApproved`
- `StateChangeEventPublisher.java`: `publishEpisodeStoryboardDone` → `publishEpisodePanelDone`, event `episode:storyboard_done` → `episode:panel_done`
- `PanelProductionService.java`: call site updated
- `ProjectService.java`: `storyboard_review` → `panel_review`
- `ProjectController.java`: removed `storyboard` from event match

---

## Task 1: Backend — frontendStep 映射调整

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java` (method `getProjectStateDetail`, lines ~192-311)

**Context:** switch 语句中每个 milestone case 设置 `frontendStep` 和 `completedSteps`。代码使用 `completedSteps.add(N)` 逐个添加。需改为 1-5 映射。

**新映射表（来自 spec）：**

| 里程碑 | 新 frontendStep | 新 completedSteps |
|--------|----------------|------------------|
| DRAFT（无大纲） | 1 | [] |
| DRAFT（有大纲） | 2 | [] |
| OUTLINE_CONFIRMED | 2 | [1] |
| EPISODE_CONFIRMED | 2 | [1, 2] |
| ASSET_CONFIRMED | 3 | [1, 2, 3] |
| PANEL_CONFIRMED | 4 | [1, 2, 3, 4] |
| COMPLETED | 5 | [1, 2, 3, 4, 5] |

- [ ] **Step 1: DRAFT case — 有大纲时 frontendStep=2**

找到 DRAFT case（约 line 192-203），当 outline 存在（`outline_review`）时，将 `frontendStep` 从 1 改为 2。`completedSteps` 保持为空。

- [ ] **Step 2: OUTLINE_CONFIRMED case — 确认不变**

`frontendStep = 2`，`completedSteps = [1]`。无需改动。确认即可。

- [ ] **Step 3: EPISODE_CONFIRMED case — frontendStep 3→2**

将 `frontendStep` 从 3 改为 2。**注意：** `completedSteps` 保持 `[1, 2]` 不变（EPISODE_CONFIRMED 表示 Step1 和 Step2 都已完成）。

- [ ] **Step 4: ASSET_CONFIRMED case — frontendStep 4→3**

将 `frontendStep` 从 4 改为 3。`completedSteps` 保持 `[1, 2, 3]` 不变。

- [ ] **Step 5: PANEL_CONFIRMED case — frontendStep 5→4**

将 `frontendStep` 从 5 改为 4。`completedSteps` 保持 `[1, 2, 3, 4]` 不变。

- [ ] **Step 6: COMPLETED case — frontendStep 6→5**

将 `frontendStep` 从 6 改为 5。将 `completedSteps` 从添加 1-6 改为只添加 1-5（移除 `completedSteps.add(6)`）。

- [ ] **Step 7: 验证编译**

Run: `cd backend/com && mvn compile -pl comic -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java
git commit -m "refactor: 调整 frontendStep 映射 1-6 → 1-5"
```

---

## Task 2: Backend — 禁用九宫格自动触发

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java` (line ~136)

**Context:** `approvePanel()` 方法在审核通过后调用 `checkAndStartGridGeneration(projectId)` 自动为所有集生成九宫格。新流程要求用户在 4b Tab 中手动逐集触发。

- [ ] **Step 1: 注释掉自动触发调用**

在 `EpisodeController.java` 的 `approvePanel()` 方法中，注释掉 `checkAndStartGridGeneration(projectId);`：

```java
// checkAndStartGridGeneration(projectId);
// 新流程：九宫格由用户在 Step 4b 中手动逐集触发，不再自动批量生成
```

- [ ] **Step 2: 验证编译**

Run: `cd backend/com && mvn compile -pl comic -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java
git commit -m "refactor: 禁用审核通过后自动触发九宫格生成"
```

---

## Task 3: Backend — SSE panel 级别事件

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/statemachine/service/StateChangeEventPublisher.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

**Context:** 在视频生成完成/失败时发布 panel 级别 SSE 事件。注意：后端没有独立的 SSE 事件常量类，事件类型直接用字符串字面量。

- [ ] **Step 1: 在 StateChangeEventPublisher 增加 2 个方法**

```java
public void publishPanelVideoDone(String projectId, Long episodeId, Long panelId, String videoUrl) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("episodeId", episodeId);
    payload.put("panelId", panelId);
    payload.put("videoUrl", videoUrl);
    publishToRedis(projectId, "panel:video_done", payload);
}

public void publishPanelVideoFailed(String projectId, Long episodeId, Long panelId, String error) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("episodeId", episodeId);
    payload.put("panelId", panelId);
    payload.put("error", error);
    publishToRedis(projectId, "panel:video_failed", payload);
}
```

- [ ] **Step 2: 在 PanelProductionService 视频完成回调中增加 publish**

在 `doGenerateVideoByPanelId()` 方法中：
- 视频成功上传 OSS 并设置 videoUrl 之后：`eventPublisher.publishPanelVideoDone(projectId, episodeId, panelId, videoUrl);`
- 视频生成失败时：`eventPublisher.publishPanelVideoFailed(projectId, episodeId, panelId, errorMessage);`
- 需要确保 `projectId` 和 `episodeId` 在回调作用域内可访问（通过 panel → episode → project 链路获取）

- [ ] **Step 3: 验证编译**

Run: `cd backend/com && mvn compile -pl comic -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/statemachine/service/StateChangeEventPublisher.java backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: 增加 panel 级别 SSE 事件 (video_done, video_failed)"
```

---

## Task 4: Frontend — 步骤配置 + 路由 + 占位组件

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/constants/steps.ts`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/CreateLayout.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` (占位)
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5Compose.tsx` (占位)

**Context:** 步骤配置 6→5，路由调整，清理废弃的 import（TransitionOverlayContext、ScriptGeneratingOverlay）。

- [ ] **Step 1: 修改 steps.ts — 6步改5步**

```typescript
export const CREATE_STEPS: Step[] = [
  { id: 1, label: '创意与设定', description: '输入创意和设定' },
  { id: 2, label: '大纲与剧情', description: '生成大纲和剧情' },
  { id: 3, label: '角色与素材', description: '角色设定和图片' },
  { id: 4, label: '分镜生产', description: '脚本→九宫格→视频' },
  { id: 5, label: '合成与下载', description: '合成视频并下载' },
];
```

- [ ] **Step 2: 创建占位组件 Step4Production.tsx 和 Step5Compose.tsx**

先创建最小占位组件确保编译通过：

```typescript
// Step4Production.tsx
import React from 'react';
import type { StepContentProps } from './types';

const Step4Production: React.FC<StepContentProps> = (props) => {
  return <div>Step4 分镜生产（开发中）</div>;
};
export default Step4Production;
```

```typescript
// Step5Compose.tsx
import React from 'react';
import type { StepContentProps } from './types';

const Step5Compose: React.FC<StepContentProps> = (props) => {
  return <div>Step5 合成与下载（开发中）</div>;
};
export default Step5Compose;
```

- [ ] **Step 3: 修改 CreateLayout.tsx**

1. **更新 imports：** 移除 `Step4page`、`Step5Transition`、`Step6page`，新增 `Step4Production`、`Step5Compose`
2. **移除 TransitionOverlayContext 相关代码：** 移除 `ScriptGeneratingOverlay` import、`useTransitionOverlay` hook、`isTransitionOverlayVisible` 状态（Step1 不再生成为什么要过渡动画）
3. **`renderStepContent()` switch 改为 5 个 case：**
```typescript
case 1: return <Step1Content ... />;
case 2: return <Step2page ... />;
case 3: return <Step3Merged ... />;  // 已在用，无需改
case 4: return <Step4Production ... />;
case 5: return <Step5Compose ... />;
```
4. **更新 COMPLETED 状态的 completedSteps** 为 `[1, 2, 3, 4, 5]`

- [ ] **Step 4: 验证编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/constants/steps.ts frontend/wiset_aivideo_generator/src/pages/create/CreateLayout.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step5Compose.tsx
git commit -m "refactor: 前端步骤配置 6步→5步 + 路由调整 + 占位组件"
```

---

## Task 5: Frontend — Step1Content 简化

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`

**Context:** 当前 Step1 包含大纲生成和 OutlineEditor。去掉这些，只保留创意输入 + 设定参数。

- [ ] **Step 1: 移除大纲相关 API 调用**

移除 `generateScript`、`getScript`、`updateScriptOutline`、`reviseScript`、`confirmScript` 的 import 和调用。

- [ ] **Step 2: 移除大纲 UI 组件**

移除 OutlineEditor 组件引用、`statusInfo.statusCode === 'outline_review'` 条件分支、ScriptGeneratingOverlay。

- [ ] **Step 3: 保留设定表单**

确保保留：故事创意 textarea、类型/风格/受众/时长/集数选择。

- [ ] **Step 4: 修改提交行为**

点击按钮 → `createProject()` 创建项目 → 自动跳转 Step 2 (`navigate(getStepUrl(2))`)。不触发生成。

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx
git commit -m "refactor: Step1 简化为纯创意输入+设定"
```

---

## Task 6: Frontend — Step2 合并大纲+剧情

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step2page.tsx`

**Context:** 合并原 Step1 的大纲生成/审核 + 原 Step2 的剧情生成。进入时自动触发大纲生成。

- [ ] **Step 1: 搬入大纲生成逻辑**

从原 Step1Content 搬入：
- 进入时检测是否有 outline，没有则自动触发 `generateScript()`
- 生成中显示 loading 状态
- 搬入 OutlineEditor 组件 import

- [ ] **Step 2: 搬入大纲审核 UI**

- 显示 OutlineEditor 供用户审核大纲
- "确认大纲"按钮 → `confirmScript()` (CONFIRM_OUTLINE, DRAFT→OUTLINE_CONFIRMED)
- "修改大纲"按钮 → `reviseScript()`

- [ ] **Step 3: 搬入剧情生成逻辑**

从原 Step2 搬入：
- 大纲确认后展示"生成剧情"按钮
- 调用 `generateAllEpisodes()`
- SSE 监听 `episode:script_done` 刷新集列表

- [ ] **Step 4: 搬入剧情确认逻辑**

- 展示生成的集列表
- "确认剧情"按钮 → `confirmScript()` (CONFIRM_EPISODE, OUTLINE_CONFIRMED→EPISODE_CONFIRMED)
- 确认后自动跳转 Step 3

- [ ] **Step 5: 用 statusInfo 驱动阶段切换**

通过 `statusInfo.statusCode` 或 `statusInfo.currentStep` 驱动显示哪个阶段（`outline_review` → 大纲审核，`episode_review` → 剧情确认等），无需额外 useState。

- [ ] **Step 6: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step2page.tsx
git commit -m "refactor: Step2 合并大纲生成+审核+剧情生成"
```

---

## Task 7: Frontend — Step4Production 带 Tab 子阶段

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` (从占位扩展)
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less`

**Context:** 最复杂的组件，合并原 Step4 + Step5 功能，内部 Tab 分三阶段。

- [ ] **Step 1: 创建 Tab 切换框架**

```typescript
type SubPhase = 'script' | 'grid' | 'video';

const Step4Production: React.FC<StepContentProps> = ({ projectId, statusInfo }) => {
  const [activeTab, setActiveTab] = useState<SubPhase>('script');
  const { episodes, loadEpisodes } = useEpisodeData(projectId);

  // Tab 解锁逻辑：根据 episodes 数据判断
  const isScriptDone = episodes.every(ep => ep.panelApproved);
  const isGridDone = episodes.every(ep => ep.gridStatus === 'approved');
  const isVideoDone = // 检查所有 panel 视频完成

  return (
    <div>
      <TabBar activeTab={activeTab} onTabChange={setActiveTab}
              scriptDone={isScriptDone} gridDone={isGridDone} />
      {activeTab === 'script' && <ScriptTab ... />}
      {activeTab === 'grid' && <GridTab ... />}
      {activeTab === 'video' && <VideoTab ... />}
    </div>
  );
};
```

- [ ] **Step 2: 实现 4a 脚本生成 Tab**

从原 Step4page 搬入脚本逻辑：
- 集数列表（左侧）+ 脚本详情（右侧）
- "生成所有脚本"按钮 → `POST /episodes/{id}/script`（项目级）
- 每集审核通过/打回 → `PUT /episodes/{id}/panel/approve`、`PUT /episodes/{id}/panel/reject`
- SSE 监听 `episode:script_done`、`episode:panel_done` 刷新列表
- 全部通过后自动解锁 4b Tab

- [ ] **Step 3: 实现 4b 九宫格图片 Tab**

从原 Step4page 搬入九宫格逻辑：
- 每集卡片展示九宫格图片
- 每集"生成九宫格"按钮 → `POST /episodes/{id}/grid/regenerate`
- 审核通过/打回 → `PUT /episodes/{id}/grid/approve`、`PUT /episodes/{id}/grid/reject`
- SSE 监听 `episode:grid_status` 刷新
- 全部审核通过后自动解锁 4c Tab

- [ ] **Step 4: 实现 4c 视频生成 Tab**

从原 Step5Transition + Step5page 搬入视频逻辑：
- 按 panel 展示视频卡片
- 每 panel "生成视频"按钮 → `POST /panels/{id}/video`
- 每集"批量生成视频"按钮（前端循环调用）
- SSE 监听 `panel:video_done`、`panel:video_failed` 刷新
- "确认完成"按钮 → `POST /projects/{id}/status/advance` (CONFIRM_PANELS) → 仅所有视频完成时可用

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.module.less
git commit -m "feat: Step4Production 带 Tab 子阶段 (脚本/九宫格/视频)"
```

---

## Task 8: Frontend — Step5Compose 合成+下载

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5Compose.tsx` (从占位扩展)
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5Compose.module.less`

**Context:** 从原 Step6page 搬入合成+下载逻辑。

- [ ] **Step 1: 实现 Step5Compose**

搬入核心逻辑：
1. "合成视频"按钮 → `POST /projects/{id}/videos/merge`
2. 合成状态：从 `statusInfo.finalVideoUrl` 获取或轮询等待
3. 视频播放器（`<video>` 标签）
4. 下载按钮（`<a download>`）

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5Compose.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step5Compose.module.less
git commit -m "feat: Step5Compose 合成+下载页面"
```

---

## Task 9 (PARALLEL with Tasks 7-8): Frontend — SSE hook 更新

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useSseProgress.ts`

**Context:** 1) 更新 `episode:storyboard_done` → `episode:panel_done`（后端已重命名）；2) 新增 panel 视频事件。

- [ ] **Step 1: 更新事件名 storyboard_done → panel_done**

在 SSE event listener 中，将 `episode:storyboard_done` 改为 `episode:panel_done`。同步更新 callback 接口名 `onEpisodeStoryboardDone` → `onEpisodePanelDone`。

- [ ] **Step 2: 扩展 SseProgressCallbacks 接口**

```typescript
export interface SseProgressCallbacks {
  // ... existing callbacks (已改名 onEpisodePanelDone) ...
  onPanelVideoDone?: (data: { episodeId: number; panelId: number; videoUrl: string }) => void;
  onPanelVideoFailed?: (data: { episodeId: number; panelId: number; error: string }) => void;
}
```

- [ ] **Step 3: 在事件监听中增加 handler**

```typescript
case 'panel:video_done':
  callbacks.onPanelVideoDone?.(JSON.parse(event.data));
  break;
case 'panel:video_failed':
  callbacks.onPanelVideoFailed?.(JSON.parse(event.data));
  break;
```

- [ ] **Step 4: 更新所有 callback 消费者**

Grep `onEpisodeStoryboardDone` 引用，改为 `onEpisodePanelDone`。主要在 Step4page 和 Step5page（即将被删除，只需确认 Step4Production 使用新名称）。

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/hooks/useSseProgress.ts
git commit -m "feat: SSE hook 更新事件名 + 增加 panel 视频事件"
```

---

## Task 10: 清理旧文件

**Files to delete (all under `frontend/wiset_aivideo_generator/src/pages/create/steps/`):**
- `Step4page.tsx` + `Step4page.module.less`
- `Step5Transition.tsx`
- `Step5page.tsx` + `Step5page.module.less`
- `Step6page.tsx` + `Step6page.module.less`
- `Step3page.tsx` (已被 Step3Merged 替代)
- `Step1Review.module.less` (如未被引用)

- [ ] **Step 1: 删除旧文件**

```bash
cd frontend/wiset_aivideo_generator/src/pages/create/steps
rm -f Step4page.tsx Step4page.module.less Step5Transition.tsx Step5page.tsx Step5page.module.less Step6page.tsx Step6page.module.less Step3page.tsx Step1Review.module.less
```

- [ ] **Step 2: 确认无残留 import**

```bash
grep -r "Step4page\|Step5Transition\|Step5page\|Step6page\|Step3page\|Step1Review" --include="*.tsx" --include="*.ts" frontend/
```

Expected: 无匹配

- [ ] **Step 3: Commit**

```bash
git add -A frontend/wiset_aivideo_generator/src/pages/create/steps/
git commit -m "chore: 清理旧的步骤组件文件"
```

---

## Task 11: 端到端验证

- [ ] **Step 1: 启动后端，确认编译和 API 正常**

Run: `cd backend/com && mvn spring-boot:run -pl comic`
验证：`GET /api/projects/{id}/status` 返回 `frontendStep` 在 1-5 范围内。

- [ ] **Step 2: 启动前端，确认 5 步 UI 正常**

Run: `cd frontend/wiset_aivideo_generator && npm run dev`
验证：StepIndicator 显示 5 步，路由 /step/1-5 正常工作。

- [ ] **Step 3: 走一遍完整流程（手动）**

1. Step 1: 输入创意 → 创建项目 → 自动跳转 Step 2
2. Step 2: 自动生成大纲 → 审核 → 生成剧情 → 确认 → 跳转 Step 3
3. Step 3: 提取角色 → 生成图片 → 确认 → 跳转 Step 4
4. Step 4: 4a 脚本生成/审核 → 4b 九宫格生成/审核 → 4c 视频生成/审核 → 确认 → 跳转 Step 5
5. Step 5: 合成视频 → 下载

- [ ] **Step 4: Commit 最终状态**

```bash
git add -A
git commit -m "chore: 端到端验证通过，5步流程重构完成"
```

---

## Task Dependency Graph

```
Task 1 (Backend frontendStep) ─┐
Task 2 (Backend disable auto) ──┤
Task 3 (Backend SSE events) ────┤
                                 ├→ Task 4 (Frontend config + 占位) → Task 5 (Step1) → Task 6 (Step2) → Task 7 (Step4) → Task 8 (Step5) → Task 10 (Cleanup) → Task 11 (E2E)
                                                                              Task 9 (SSE hook) ────┘ (PARALLEL with 7-8)
```

**Parallel execution:**
- Tasks 1-3 (backend): can run in parallel
- Task 9 (SSE hook): parallel with Tasks 7-8
- Task 10: must be after all frontend tasks
- Task 11: must be last