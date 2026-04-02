# 创作流程重构设计：6步 → 5步 + Step4 子阶段

## 概述

将前端创作流程从 6 步简化为 5 步，把视频审核合并到 Step4 的子阶段中。后端状态机（6 里程碑）不变，但需调整 `frontendStep` 映射从 1-6 改为 1-5，并增强 SSE 事件粒度。

## 新流程

| 前端步骤 | 名称 | 对应里程碑 | 操作 |
|---------|------|-----------|------|
| Step 1 | 创意与设定 | DRAFT（无大纲） | 只输入创意和设定参数，创建项目 |
| Step 2 | 大纲与剧情 | DRAFT → OUTLINE_CONFIRMED → EPISODE_CONFIRMED | 生成大纲 → 审核 → 生成剧情 → 确认 |
| Step 3 | 角色与素材 | EPISODE_CONFIRMED → ASSET_CONFIRMED | 提取角色 → 确认设定 → 生成图片 → 锁定 |
| Step 4 | 分镜生产 | ASSET_CONFIRMED → PANEL_CONFIRMED | 子阶段：脚本 → 九宫格 → 视频 |
| Step 5 | 合成与下载 | PANEL_CONFIRMED → COMPLETED | 合成视频 + 下载 |

## 后端 `frontendStep` 映射调整

后端 `ProjectService.getProjectStateDetail()` 的 `frontendStep` 必须从 1-6 改为 1-5：

| 里程碑 | 旧 frontendStep | 新 frontendStep |
|--------|----------------|----------------|
| DRAFT（无大纲） | 1 | 1 |
| DRAFT（有大纲，outline_review） | 1 | 2 |
| OUTLINE_CONFIRMED | 2 | 2 |
| EPISODE_CONFIRMED | 3 | 2 |
| ASSET_CONFIRMED | 4 | 3 |
| PANEL_CONFIRMED | 5 | 4 |
| COMPLETED | 6 | 5 |

对应的 `completedSteps` 数组也需调整：

| 里程碑 | 旧 completedSteps | 新 completedSteps |
|--------|------------------|------------------|
| DRAFT | [] | [] |
| OUTLINE_CONFIRMED | [1] | [1] |
| EPISODE_CONFIRMED | [1,2] | [1,2] |
| ASSET_CONFIRMED | [1,2,3] | [1,2,3] |
| PANEL_CONFIRMED | [1,2,3,4] | [1,2,3,4] |
| COMPLETED | [1,2,3,4,5,6] | [1,2,3,4,5] |

`availableActions` 不变，`effectiveState` 的 derived 逻辑不变（仍基于里程碑+数据存在性推导）。

## Step 4 内部子阶段

顶部 Tab 切换：`[ 脚本生成 | 九宫格图片 | 视频生成 ]`

### 4a 脚本生成
- 默认展示的 Tab
- 左侧：集数列表（按集展开）
- 右侧：当前选中集的脚本+故事板文本详情
- SSE 实时推送 episode 级别生成进度（`episode:script_done`）
- 每集可单独审核（通过/打回），使用现有 `PUT /episodes/{id}/storyboard/approve|reject` API
- 全部通过后出现"确认所有脚本"按钮 → 切换到 4b Tab（九宫格生成由用户在 4b 中手动触发）

**API 映射：**
| 用户操作 | API 调用 | 备注 |
|---------|---------|------|
| 查看脚本 | `GET /episodes/{id}` | |
| 生成所有集脚本 | `POST /episodes/{id}/script` | **项目级操作**：虽然 URL 含 episodeId，但后端实际为整个项目生成所有集的脚本+故事板 |
| 审核通过 | `PUT /episodes/{id}/storyboard/approve` | 逐集审核，通过后自动检查是否所有集都已通过，如是则不自动触发九宫格（见下） |
| 审核打回 | `PUT /episodes/{id}/storyboard/reject` | |

**关于九宫格自动触发：** 当前后端 `EpisodeController.approveStoryboard()` 中有 `checkAndStartGridGeneration()` 逻辑，在所有集故事板通过后自动触发九宫格。**需要禁用此自动触发**，改为在 4b 中由用户手动逐集触发生成，以匹配新的交互设计。

### 4b 九宫格图片
- 每集展示为卡片，默认折叠
- 展开显示该集的九宫格图片
- 每集有独立的"生成九宫格"按钮（调用 `POST /episodes/{id}/grid/regenerate`），用户逐集手动触发
- 支持审核（通过/打回/重新生成）
- 审核通过时调用 `PUT /episodes/{id}/grid/approve`，后端自动创建 Panel 记录 + fusion image
- 全部审核通过后出现"进入视频生成"按钮 → 切换到 4c

**API 映射：**
| 用户操作 | API 调用 |
|---------|---------|
| 查看九宫格 | `GET /episodes/{id}/grid` |
| 生成九宫格 | `POST /episodes/{id}/grid/regenerate` | 用户手动逐集触发 |
| 审核通过 | `PUT /episodes/{id}/grid/approve` |
| 审核打回 | `PUT /episodes/{id}/grid/reject` |

### 4c 视频生成
- 按集分组，每集下展示各 panel 的视频卡片
- 每个 panel 有独立的"生成视频"按钮（`POST /panels/{id}/video`）
- 每集有"批量生成视频"按钮（前端循环调用各 panel 的 video API）
- 视频生成后可播放、审核
- 全部完成 → 用户点击"确认完成"按钮 → 调用 `POST /projects/{id}/status/advance` (CONFIRM_PANELS 事件) → 进入 Step 5

**API 映射：**
| 用户操作 | API 调用 |
|---------|---------|
| 生成视频(单个) | `POST /panels/{id}/video` |
| 批量重试失败 | `POST /projects/{id}/panels/retry-failed` |
| 确认完成 | `POST /projects/{id}/status/advance` (CONFIRM_PANELS) |

### Tab 切换逻辑
- 4a → 4b：所有集脚本确认后自动解锁（可手动回看）
- 4b → 4c：所有集九宫格审核通过后自动解锁
- 不锁定已完成 Tab，随时可回看
- Tab 激活状态由前端数据驱动（检查 episodes 的 gridStatus、panels 的 videoUrl），不依赖后端状态

### 错误处理
- 单个 panel 视频生成失败：该 panel 显示错误状态+重试按钮，不影响其他 panel
- 批量生成：失败的 panel 显示重试，用户可单独重试或点击"重试所有失败"
- "确认完成"按钮只在所有 panel 视频成功时可用

## 各 Step 变化

### Step 1：创意与设定
- **变化：** 从当前 Step1 去掉大纲生成和 OutlineEditor
- **保留：** 故事创意输入 + 类型/风格/受众/时长/集数设定
- **行为：** 点击"下一步"创建项目 + 自动跳转 Step 2

### Step 2：大纲与剧情
- **来源：** 当前 Step1 后半段 + 当前 Step2 合并
- **行为：** 进入时自动触发大纲生成 → 大纲完成后展示审核 → 确认大纲后展示剧情生成 UI → 确认剧情后进入 Step 3
- **后端事件：** 确认大纲触发 CONFIRM_OUTLINE（DRAFT→OUTLINE_CONFIRMED），确认剧情触发 CONFIRM_EPISODE（OUTLINE_CONFIRMED→EPISODE_CONFIRMED），但都停留在前端 Step 2

### Step 3：角色与素材
- **变化：** 基本不变

### Step 4：分镜生产
- **来源：** 当前 Step4 + Step5 合并
- **变化：** 内部增加 Tab 子阶段 UI

### Step 5：合成与下载
- **来源：** 当前 Step6 简化
- **行为：** 合成视频按钮 + 视频播放器 + 下载按钮

## 后端改动

### 1. `frontendStep` 映射调整（ProjectService.java）

修改 `getProjectStateDetail()` 中的 switch 语句，将 `frontendStep` 从 1-6 映射改为 1-5：
- OUTLINE_CONFIRMED 和 EPISODE_CONFIRMED 都映射到 `frontendStep = 2`
- ASSET_CONFIRMED 映射到 `frontendStep = 3`
- PANEL_CONFIRMED 映射到 `frontendStep = 4`
- COMPLETED 映射到 `frontendStep = 5`

### 2. SSE 事件增强

当前事件粒度为 episode 级别，增加 panel 级别事件：

| 事件 | 触发时机 | 数据载荷 | 改动位置 |
|------|---------|---------|---------|
| `episode:script_done` | 已有，每集脚本生成完成 | 不变 | 已有 |
| `episode:grid_status` | 已有，每集九宫格状态变化 | 不变 | 已有 |
| `panel:video_done` | 每个 panel 视频生成完成 | `{episodeId, panelId, videoUrl}` | PanelProductionService.doGenerateVideoByPanelId() 视频完成回调 |
| `panel:video_failed` | 视频生成失败 | `{episodeId, panelId, error}` | PanelProductionService.doGenerateVideoByPanelId() 失败回调 |

**说明：**
- `panel:script_done` 不新增：脚本生成时 panel 尚未创建（panel 在九宫格审核通过后才创建），自然粒度是 episode 级别的 `episode:script_done`（已有）
- `panel:grid_done` 不新增：九宫格生成是 episode 级别操作，Panel 记录在九宫格审核通过后才创建。已有 `episode:grid_status` 足够

**改动文件：**
- `PanelProductionService.java` — 在视频完成/失败回调中增加 publish 调用
- `StateChangeEventPublisher.java` — 增加 2 个 panel 级别 publish 方法
- SSE 事件常量类 — 增加 `panel:video_done`、`panel:video_failed` 类型

### 不变的部分
- 状态机：6 里程碑 + events + guards + actions 全部不变
- Controller API 端点签名：不变（URL、参数、返回值不变）
- ScriptService、CharacterService、GridImageService：不变
- 数据库 schema：不变

## URL 路由

路由从 `/project/{id}/step/{1-6}` 改为 `/project/{id}/step/{1-5}`。旧 URL（step/6）通过 CreateLayout 的路由守卫自动重定向到正确的步骤。

## 迁移计划

部署时需考虑进行中的项目：
- **DRAFT / OUTLINE_CONFIRMED / EPISODE_CONFIRMED / ASSET_CONFIRMED** — frontendStep 值变化，但前端会重新获取状态，无影响
- **PANEL_CONFIRMED（旧 Step 5）** — 新映射 frontendStep=4，用户刷新后进入新 Step 4，视频审核功能已移到 Step 4 的 4c 子阶段，体验一致
- **COMPLETED（旧 Step 6）** — 新映射 frontendStep=5，用户刷新后进入新 Step 5，合成+下载功能不变

无需数据迁移，因为 frontendStep 是实时从里程碑推导的。

## 文件改动清单

### 前端

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `constants/steps.ts` | 修改 | 6 步改为 5 步 |
| `CreateLayout.tsx` | 修改 | 路由守卫映射调整，路由从 6 步改为 5 步 |
| `StepIndicator.module.less` | 修改 | 样式适配 5 步 |
| `Step1Content.tsx` | **重写** | 简化为纯输入+设定，去掉大纲生成 |
| `Step2page.tsx` | **重写** | 合并当前 Step1 大纲部分 + Step2 剧情 |
| `Step3Merged.tsx` | 微调 | 基本不变（已在工作树中） |
| `Step3page.tsx` | 删除 | 旧版，已被 Step3Merged 替代 |
| `Step1Review.module.less` | 删除或复用 | 未使用的样式文件（如 Step2 不需要则删除） |
| **新建** `Step4Production.tsx` | **新建** | 合并当前 Step4+Step5+Step5Transition，内部 Tab 三阶段 |
| `Step4page.tsx` | 删除 | 被 Step4Production 替代 |
| `Step5Transition.tsx` | 删除 | 视频审核移到 Step4 的 4c |
| `Step5page.tsx` | 删除 | 被 Step5Compose 替代 |
| **新建** `Step5Compose.tsx` | **新建** | 合成+下载 |
| `Step6page.tsx` | 删除 | 被 Step5Compose 替代 |
| `hooks/useSseProgress.ts` | 修改 | 增加 `panel:grid_done`、`panel:video_done`、`panel:video_failed` 事件处理 |
| `stores/createStore.ts` | 修改 | step 映射调整为 5 步 |

### 后端

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `ProjectService.java` | 修改 | `frontendStep` 映射从 1-6 改为 1-5，DRAFT+大纲映射到 Step 2 |
| `PanelProductionService.java` | 修改 | 视频完成/失败回调增加 panel 级别 SSE 事件发布 |
| `EpisodeController.java` | 修改 | 禁用 `approveStoryboard()` 中的 `checkAndStartGridGeneration()` 自动触发 |
| `StateChangeEventPublisher.java` | 修改 | 增加 2 个 panel 级别 publish 方法 |
| SSE 事件常量类 | 修改 | 增加 `panel:video_done`、`panel:video_failed` 类型 |
