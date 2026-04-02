# 创作流程重构设计：6步 → 5步 + Step4 子阶段

## 概述

将前端创作流程从 6 步简化为 5 步，把视频审核合并到 Step4 的子阶段中。后端状态机（6 里程碑）和 API 端点不变，仅增强 SSE 事件粒度至 panel 级别。

## 新流程

| 前端步骤 | 名称 | 对应里程碑 | 操作 |
|---------|------|-----------|------|
| Step 1 | 创意与设定 | DRAFT | 只输入创意和设定参数，创建项目 |
| Step 2 | 大纲与剧情 | DRAFT → OUTLINE_CONFIRMED → EPISODE_CONFIRMED | 生成大纲 → 审核 → 生成剧情 → 确认 |
| Step 3 | 角色与素材 | EPISODE_CONFIRMED → ASSET_CONFIRMED | 提取角色 → 确认设定 → 生成图片 → 锁定 |
| Step 4 | 分镜生产 | ASSET_CONFIRMED → PANEL_CONFIRMED | 子阶段：脚本 → 九宫格 → 视频 |
| Step 5 | 合成与下载 | PANEL_CONFIRMED → COMPLETED | 合成视频 + 下载 |

## Step 4 内部子阶段

顶部 Tab 切换：`[ 脚本生成 | 九宫格图片 | 视频生成 ]`

### 4a 脚本生成
- 默认展示的 Tab
- 左侧：集数列表（按集展开）
- 右侧：当前选中集的脚本+故事板文本详情
- SSE 实时推送 panel 级别生成进度
- 每集可单独审核（通过/打回）
- 全部通过后出现"确认所有脚本"按钮 → 解锁 4b

### 4b 九宫格图片
- 每集展示为卡片，默认折叠
- 展开显示该集的九宫格图片
- 每集可单独点击"生成九宫格"
- 支持审核（通过/打回/重新生成）
- 全部审核通过后出现"进入视频生成"按钮 → 解锁 4c

### 4c 视频生成
- 按集分组，每集下展示各 panel 的视频卡片
- 每个 panel 有独立的"生成视频"按钮
- 每集有"批量生成视频"按钮
- 视频生成后可播放、审核
- 全部完成 → Step 4 确认完成，进入 Step 5

### Tab 切换逻辑
- 4a → 4b：所有集脚本确认后自动解锁（可手动回看）
- 4b → 4c：所有集九宫格审核通过后自动解锁
- 不锁定已完成 Tab，随时可回看

## 各 Step 变化

### Step 1：创意与设定
- **变化：** 从当前 Step1 去掉大纲生成和 OutlineEditor
- **保留：** 故事创意输入 + 类型/风格/受众/时长/集数设定
- **行为：** 点击"下一步"只创建项目，不触发生成

### Step 2：大纲与剧情
- **来源：** 当前 Step1 后半段 + 当前 Step2 合并
- **行为：** 进入时自动触发大纲生成 → 大纲完成后展示审核 → 确认大纲后展示剧情生成 UI → 确认剧情后进入 Step 3

### Step 3：角色与素材
- **变化：** 基本不变

### Step 4：分镜生产
- **来源：** 当前 Step4 + Step5 合并
- **变化：** 内部增加 Tab 子阶段 UI

### Step 5：合成与下载
- **来源：** 当前 Step6 简化
- **行为：** 合成视频按钮 + 视频播放器 + 下载按钮

## 后端改动

### SSE 事件增强

当前事件粒度为 episode 级别，需增加 panel 级别事件：

| 新事件 | 触发时机 | 数据载荷 |
|--------|---------|---------|
| `panel:script_done` | 每个 panel 故事板文本生成完成 | `{episodeId, panelIndex, totalPanels}` |
| `panel:grid_done` | 每个 panel 九宫格生成完成 | `{episodeId, panelId, gridUrl}` |
| `panel:video_done` | 每个 panel 视频生成完成 | `{episodeId, panelId, videoUrl}` |
| `panel:video_failed` | 视频生成失败 | `{episodeId, panelId, error}` |

**改动文件：** `PanelProductionService.java` — 在 `generateEpisodeScripts`、`generateGridsForEpisode`、`doGenerateVideoByPanelId` 的异步回调中增加 SSE publish 调用。

### 不变的部分
- 状态机：6 里程碑 + events + guards + actions 全部不变
- Controller API 端点：不变
- ScriptService、CharacterService、GridImageService：不变
- 数据库 schema：不变

## 文件改动清单

### 前端

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `constants/steps.ts` | 修改 | 6 步改为 5 步 |
| `CreateLayout.tsx` | 修改 | 路由守卫映射调整 |
| `StepIndicator.module.less` | 修改 | 样式适配 5 步 |
| `Step1Input.tsx` | **新建** | 只保留创意输入+设定 |
| `Step1Content.tsx` | 修改 | 改为大纲+剧情（原 Step1 后半段 + Step2 合并） |
| `Step2page.tsx` | 删除 | 逻辑合并到新 Step2 |
| `Step3page.tsx` | 微调 | 基本不变 |
| `Step4Production.tsx` | **新建** | 合并 Step4+Step5，内部 Tab 三阶段 |
| `Step4page.tsx` | 删除 | 被 Step4Production 替代 |
| `Step5page.tsx` | 删除 | 视频审核移到 Step4 |
| `Step5Compose.tsx` | **新建** | 合成+下载 |
| `Step6page.tsx` | 删除 | 被 Step5Compose 替代 |
| `hooks/useSseProgress.ts` | 修改 | 增加 panel 级别事件处理 |
| `stores/createStore.ts` | 修改 | step 映射调整为 5 步 |

### 后端

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `PanelProductionService.java` | 修改 | 增加 panel 级别 SSE 事件发布 |
| SSE 事件常量 | 修改 | 增加新事件类型定义 |
