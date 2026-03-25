# Step5 视频生产重写 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Step5 从分镜审核页面重写为视频生产工作台，按章节→剧集→片段三级展开式列表展示，每个片段包含四宫格漫画审核和 AI 视频生成流程。

**Architecture:** 展开式列表视图（Option B），剧集卡片和片段子卡片均可展开/折叠。片段展开后左右 50% 分栏显示四宫格漫画（16:9）和 AI 视频（16:9）。后端 API 暂用现有 episode/panel 级接口适配，后续按 segment 级重构。

**Tech Stack:** React 19, TypeScript, Less Modules, Zustand, Axios

> **样式方案说明：** 项目现有代码全部使用 Less Modules，不使用 Tailwind CSS。所有新组件必须使用 Less Modules（`.module.less`），引用项目已有的 CSS 变量（如 `var(--color-text-primary)` 等）和 design tokens。原型的 Tailwind 类名仅作视觉参考，实现时需转换为对应的 Less 写法。

**设计文档:** `docs/superpowers/specs/2026-03-25-step5-rewrite-design.md`

**原型参考:** `.superpowers/brainstorm/130-1774406436/step5-detail-v3.html`

---

## 文件结构

```
修改:
  frontend/wiset_aivideo_generator/src/pages/create/constants/steps.ts
    — 更新 Step5/Step6 的 label 和 description

  frontend/wiset_aivideo_generator/src/pages/create/CreateLayout.tsx
    — 无需修改。新 Step5page 必须保持 `{ project: Project }` prop 接口不变，CreateLayout 会传递 `project={currentProject}`

创建:
  frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
    — 重写主页面，章节→剧集→片段三级展开列表

  frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.module.less
    — 主页面样式

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx
    — 剧集卡片组件（可展开/折叠）

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less
    — 剧集卡片样式

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx
    — 片段子卡片组件（可展开/折叠，含四宫格+视频工作区）

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.module.less
    — 片段子卡片样式

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.tsx
    — 四宫格漫画面板（2×2 网格 + 审核操作 + 修改建议输入框）

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.module.less
    — 四宫格面板样式

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoPanel.tsx
    — AI 视频面板（播放器/占位/生成按钮）

  frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoPanel.module.less
    — AI 视频面板样式

  frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
    — Step5 相关类型定义（Chapter, Episode, Segment, SegmentStatus 等）

保留（不修改）:
  Step4page.tsx — 素材生成页面
  Step6page.tsx — 暂保留现有实现作为占位
  createStore.ts — 项目状态轮询 store
  episodeService.ts — 后端 API 服务
  projectService.ts — 项目 API 服务
  episode.types.ts — 现有类型定义
  project.types.ts — 现有类型定义

删除:
  Step5Card.tsx — 旧的面板卡片组件
  Step5Card.module.less
  Step5page.tsx.bak — 旧备份文件
```

---

### Task 1: 更新步骤配置和类型定义

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/constants/steps.ts`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts`

- [ ] **Step 1: 更新步骤标签**

```typescript
// steps.ts
export const CREATE_STEPS: Step[] = [
  { id: 1, label: '创意输入', description: '输入故事创意和生成参数' },
  { id: 2, label: '剧本编辑', description: '编辑AI生成的剧本' },
  { id: 3, label: '角色配置', description: '配置角色形象' },
  { id: 4, label: '素材生成', description: '生成角色素材图片' },
  { id: 5, label: '视频生产', description: '管理分镜、四宫格和视频生成' },  // 改
  { id: 6, label: '最终合成', description: '视频合成与导出' },           // 改
];
```

- [ ] **Step 2: 创建 Step5 类型定义**

```typescript
// types.ts

/** 片段流水线状态 */
export type SegmentPipelineStep = 'pending' | 'scene_ready' | 'comic_review' | 'comic_approved' | 'video_generating' | 'video_completed' | 'video_failed';

/** 片段状态 */
export interface SegmentState {
  segmentIndex: number;
  title: string;
  synopsis: string;
  sceneThumbnail: string | null;
  characterAvatars: { name: string; avatarUrl: string }[];
  pipelineStep: SegmentPipelineStep;
  comicUrl: string | null;
  videoUrl: string | null;
  feedback: string;
}

/** 剧集状态 */
export interface EpisodeState {
  episodeId: number;
  episodeIndex: number;
  title: string;
  segments: SegmentState[];
}

/** 章节状态 */
export interface ChapterState {
  chapterIndex: number;
  title: string;
  episodes: EpisodeState[];
}

/** 片段子卡片展开状态（手风琴模式：同时只展开一个剧集和一个片段）*/
export interface ExpansionState {
  expandedEpisodeId: number | null;
  expandedSegmentKey: string | null; // "episodeId-segmentIndex"
}
```

---

### Task 2: 重写 Step5page 主页面（覆盖现有文件）

**Files:**
- Overwrite: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`（现有 837 行将被完全替换）
- Overwrite: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.module.less`（现有样式将被完全替换）

> **重要：** Step5page.tsx 已存在，此任务是覆盖重写而非创建新文件。新组件必须保持 `interface Step5pageProps extends StepContentProps { project: Project; }` 接口签名，因为 CreateLayout 会传递 `project={currentProject}`。旧版 Step5page 导入的 `GridFusionEditor` 不再需要，新版本不引入它。

- [ ] **Step 1: 创建 Step5page 主组件骨架**

组件职责：
- 从现有 API 加载项目数据（chapters/episodes/panels）
- 按章节数据构建 ChapterState[] 树形结构
- 渲染顶部统计栏（已生成/未生成 + 一键生成按钮占位）
- 渲染 ChapterGroup 列表
- 管理 expansion state（哪些剧集/片段展开）

参考原型 `.superpowers/brainstorm/130-1774406436/step5-detail-v3.html` 的顶部栏和章节分组结构。

关键实现点：
- 使用现有 `projectService.getScript(projectId)` 加载剧本数据
- 从 `ScriptContentResponse.episodes` 中按 `chapterTitle` 分组为章节
- 每个 episode 的 panels 按 4 个一组聚合为 segment
- polling 使用现有 `createStore` 的轮询机制
- 样式使用 Less Modules，引用项目已有 CSS 变量

- [ ] **Step 2: 创建主页面样式**

参考原型的视觉设计：
- 主体区域 `max-w-5xl mx-auto`
- 章节标题行：图标 + 章节名 + 分隔线 + 完成统计
- 统计区域使用绿色/灰色圆点

- [ ] **Step 3: 验证页面渲染**

Run: `cd frontend/wiset_aivideo_generator && npm run build`
Expected: 编译通过，Step5 页面显示空状态骨架

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx \
       frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.module.less
git commit -m "feat(step5): create main page with chapter grouping and stats bar"
```

---

### Task 3: 创建 EpisodeCard 组件

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less`

- [ ] **Step 1: 实现 EpisodeCard**

组件职责：
- 显示剧集标题、简介、完成状态
- 折叠/展开切换
- 折叠时：显示片段完成指示器（色块列表）
- 展开时：渲染 SegmentCard 列表

Props:
```typescript
interface EpisodeCardProps {
  chapterIndex: number;
  episode: EpisodeState;
  isExpanded: boolean;
  onToggle: () => void;
  expandedSegmentKey: string | null;
  onSegmentToggle: (key: string | null) => void;
}
```

三种状态的边框颜色（使用项目 Less 变量）：
- 已完成：绿色边框 + 绿色状态图标
- 进行中：白色边框 + 黄色状态图标
- 未开始：灰色边框 + 灰色状态图标（半透明）

- [ ] **Step 2: 创建样式**

- [ ] **Step 3: 验证编译**

Run: `cd frontend/wiset_aivideo_generator && npm run build`

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx \
       frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.module.less
git commit -m "feat(step5): add EpisodeCard component with expand/collapse"
```

---

### Task 4: 创建 SegmentCard 组件

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.module.less`

- [ ] **Step 1: 实现 SegmentCard**

组件职责：
- 折叠状态：单行显示（状态图标 + 片段编号 + 摘要 + 场景 + 角色 + 进度指示器）
- 展开状态：Header + 左右分栏内容区（ComicPanel + VideoPanel）

Props:
```typescript
interface SegmentCardProps {
  episodeId: number;
  segment: SegmentState;
  isExpanded: boolean;
  onToggle: () => void;
}
```

折叠状态右侧布局（从右到左）：
- 展开箭头图标
- 三步进度指示器（3 个圆点 + 2 条连线）
- 角色头像列表（圆形，显示姓氏首字）
- 场景缩略图标

- [ ] **Step 2: 创建样式**

- [ ] **Step 3: 验证编译**

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx \
       frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.module.less
git commit -m "feat(step5): add SegmentCard with collapsed summary and expanded workspace"
```

---

### Task 5: 创建 ComicPanel 组件

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.module.less`

- [ ] **Step 1: 实现 ComicPanel**

组件职责：
- 显示四宫格漫画（2×2 网格，16:9 比例容器）
- 审核操作按钮（通过 / 重新生成）
- 修改建议输入框（textarea）
- 未生成时显示占位

Props:
```typescript
interface ComicPanelProps {
  comicUrl: string | null;
  pipelineStep: SegmentPipelineStep;
  onApprove: () => void;
  onRegenerate: (feedback: string) => void;
}
```

状态渲染：
- `pending`/`scene_ready`：占位提示"生成场景和四宫格中..."
- `comic_review`：显示四宫格图片 + 审核按钮 + 输入框
- `comic_approved`：显示四宫格图片 + 已审核标记
- `video_completed`：显示四宫格图片 + 已完成标记

- [ ] **Step 2: 创建样式**

四宫格容器使用 Less 实现 `aspect-ratio: 16/9`，内部用 CSS Grid 实现 2×2 布局。

- [ ] **Step 3: 验证编译**

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.tsx \
       frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.module.less
git commit -m "feat(step5): add ComicPanel with review actions and feedback input"
```

---

### Task 6: 创建 VideoPanel 组件

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoPanel.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoPanel.module.less`

- [ ] **Step 1: 实现 VideoPanel**

组件职责：
- 显示 AI 视频播放器或占位
- 生成视频按钮
- 视频状态展示

Props:
```typescript
interface VideoPanelProps {
  videoUrl: string | null;
  pipelineStep: SegmentPipelineStep;
  onGenerateVideo: () => void;
}
```

状态渲染：
- 四宫格审核前：禁用按钮 + 占位提示
- `comic_approved`：启用"生成视频"按钮
- `video_generating`：loading 状态 + 进度提示
- `video_completed`：视频播放器
- `video_failed`：错误提示 + 重试按钮

- [ ] **Step 2: 创建样式**

视频容器使用 Less 实现 `aspect-ratio: 16/9`。

- [ ] **Step 3: 验证编译**

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoPanel.tsx \
       frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoPanel.module.less
git commit -m "feat(step5): add VideoPanel with player, placeholder and generate button"
```

---

### Task 7: 清理旧文件和集成验证

**Files:**
- Delete: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.tsx`
- Delete: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.module.less`
- Delete: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx.bak`
- Note: `Step5page.tsx` 和 `Step5page.module.less` 已在 Task 2 中覆盖重写，无需额外处理

- [ ] **Step 1: 删除旧组件文件**

```bash
rm frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.tsx
rm frontend/wiset_aivideo_generator/src/pages/create/steps/components/Step5Card.module.less
rm frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx.bak
```

- [ ] **Step 2: 检查旧组件的 import 引用**

确认没有其他文件 import Step5Card。

- [ ] **Step 3: 完整构建验证**

Run: `cd frontend/wiset_aivideo_generator && npm run build`
Expected: 编译通过，无 TypeScript 错误

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(step5): remove old Step5Card components and verify build"
```

---

### Task 8: 数据适配和 API 集成

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

- [ ] **Step 1: 接入现有 API 加载数据**

在 Step5page 中：
- 使用 `projectService.getScript(projectId)` 加载剧本数据（`ScriptContentResponse`）
- 从 `episodes` 按 `chapterTitle` 分组为章节
- 每个 episode 调用 `episodeService.getPanelStates(episodeId)` 获取面板状态
- 将 panels 按 4 个一组聚合为 segments
- 映射 segment 流水线状态逻辑：
  - 全部 panel `fusionStatus === 'completed'` → `comic_approved`
  - 部分完成 → `comic_review`
  - 全部 panel `videoStatus === 'completed'` → `video_completed`
  - 任一 `videoStatus === 'failed'` → `video_failed`
  - 否则 → `pending` 或 `scene_ready`

- [ ] **Step 2: 接入操作 API（segment→panel 映射）**

在 SegmentCard/ComicPanel/VideoPanel 中：
- 审核通过（整个 segment）：对 segment 内 4 个 panel 逐个调用现有确认/推进接口
- 重新生成四宫格（单个画面）：调用 `episodeService` 的对应重试接口 + 修改建议
- 生成视频：对 segment 内 4 个 panel 逐个调用 `generateSinglePanelVideo`

注意：现有 API 是 panel 级别的，segment→panel 映射在前端完成。后端 segment 级 API 待后续实现。

- [ ] **Step 3: 构建验证**

Run: `cd frontend/wiset_aivideo_generator && npm run build`

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
git commit -m "feat(step5): integrate with existing APIs for data loading and actions"
```

---

### Task 9: 最终验证

- [ ] **Step 1: 前端完整构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build`
Expected: 零错误

- [ ] **Step 2: 后端编译验证**

Run: `cd backend && mvn compile -q`
Expected: 编译通过（确保前端改动不破坏后端）

- [ ] **Step 3: 最终提交（如有修复）**

```bash
git add -A
git commit -m "feat(step5): final verification and cleanup"
```
