# 剧本生成系统差距修复设计

## 概述

剧本生成系统（3.1 大纲生成 + 3.2 分章剧集生成）的核心流程已完整实现，但存在 5 项功能差距需要修复：前端 UX 问题、字段丢失、缺少批量生成等。

## 差距清单

| # | 差距 | 严重度 |
|---|------|--------|
| 1 | OutlineEditor 编辑不生效（功能性 Bug） | 高 |
| 2 | 确认按钮 UX 问题 | 中 |
| 3 | visualStyleNote 字段被丢弃 | 低 |
| 4 | 无批量生成功能 | 中 |
| 5 | episodesPerChapter 最小值为 1 | 低 |

## 设计方案

### 差距 1：OutlineEditor 双模式

**现状**：`OutlineEditor` 只有一个保存按钮，调用 `reviseScript` 触发 AI 重生成，用户编辑内容被丢弃。

**方案**：改为双按钮模式。

- **「保存修改」按钮**：直接将用户编辑的 Markdown 持久化到 `project.scriptOutline`。后端新增 `PATCH /api/projects/{projectId}/script-outline` 接口，接收 `{ outline: string }`。保存时删除该大纲下所有已生成剧集（因为大纲变了，剧集失效）。
- **「AI 重新生成」按钮**：保留现有 `reviseScript` 行为，但新增传递用户当前编辑的大纲内容作为上下文（`currentOutline` 字段），让 AI 基于用户编辑版本重新生成。

**后端改动**：
1. `ScriptService` 新增 `updateScriptOutline(projectId, outline)` — 直接保存，删除已生成剧集
2. `ScriptService.reviseOutline` 修改签名，新增 `String currentOutline` 参数
3. `ProjectController` 新增 `PATCH /api/projects/{projectId}/script-outline` 端点

### 差距 2：确认按钮 UX

**方案**：`pendingChapters.length > 0` 时确认按钮 `disabled`，按钮文案随状态变化。

- 有未完成章节：按钮禁用，文案"全部章节已生成后可确认"
- 全部完成：按钮可用，文案"确认剧本，进入下一步"

### 差距 3：visualStyleNote

**方案**：
- `Episode` 实体新增 `visualStyleNote` 字段（String，可为空）
- `ScriptService.parseAndSaveEpisodes` 提取 `"visualStyleNote"` JSON 字段
- 前端剧集详情展示该字段

### 差距 4：批量生成

**方案**：

后端新增 `POST /api/projects/{projectId}/generate-all-episodes` 接口。
- 按顺序遍历 `pendingChapters`，依次调用 `generateScriptEpisodes`
- 每章使用默认 `episodeCount`（从大纲章节标题提取或使用 `episodesPerChapter` 配置）
- 某章失败则停止并抛出异常，前端回退到逐章模式

前端在 `ChapterList` 上方新增「一键生成全部剩余章节」按钮。
- 点击后弹出确认对话框，显示待生成章节数
- 确认后调用批量生成接口
- 通过轮询 `getScript` 获取进度

### 差距 5：episodesPerChapter 最小值

**方案**：修改 `PromptBuilder.calculateScriptParameters`。

- 移除 `totalEpisodes <= 3` 时 `episodesPerChapter = 1` 的分支
- 最小 `episodesPerChapter = 2`
- `totalEpisodes <= 3` 时设为 2（1 章 2~3 集）
- `totalEpisodes = 4-6` 时设为 2
- 其余保持现有逻辑

## 改动范围

| 层 | 文件 | 改动 |
|----|------|------|
| 后端 | `ProjectController` | 新增 2 个接口 |
| 后端 | `ScriptService` | 新增 2 个方法 + 修改 1 个方法 |
| 后端 | `Episode` 实体 | 新增 `visualStyleNote` 字段 |
| 后端 | `PromptBuilder` | 修改 `calculateScriptParameters` |
| 前端 | `OutlineEditor` | 双按钮模式 |
| 前端 | `Step2page` | 确认按钮禁用 + 批量生成按钮 |
| 前端 | `projectService.ts` | 新增 2 个 API 调用 |
| 前端 | `project.types.ts` | 更新类型定义 |
