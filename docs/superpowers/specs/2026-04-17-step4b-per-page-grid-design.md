# Step 4b 逐页九宫格生成 + 去重 UI

## Context

当前 Step 4b 存在以下问题：
1. **GridEpisodeCard** 和 **DoneEpisodeCard** 大量重复代码（九宫格图片展示、分页、prompt 预览）
2. 生成/重新生成是整集级别，无法单独操作某一页
3. StoryboardGrid 展示全部 shots，不是当前页的 shots

## 设计

### 1. 合并 GridEpisodeCard + DoneEpisodeCard

删除 `DoneEpisodeCard`，`GridEpisodeCard` 统一处理所有 `gridStatus`：
- `pending` / `rejected`：显示生成按钮
- `generating`：显示 spinner
- `generated`：显示通过/退回按钮
- `approved`：只读预览 + 退回按钮

### 2. 逐页生成/重新生成

**后端**：
- 新增端点 `POST /api/projects/{projectId}/episodes/{episodeId}/grid/regenerate/page/{pageIndex}`
- `GridImageService.generateGridPage(panelId, pageIndex)`：只生成指定页
  - 计算 adaptivePages，取 `pages[pageIndex]` 对应的 shots
  - 生成九宫格图片、split、fusion
  - 更新 `gridImages[pageIndex]`、`splitShots` 对应区间
  - 如果所有页都有图片 → 设置 `gridStatus = generated`

**前端**：
- 分页 tab 旁加"生成"/"重新生成"小按钮（每页独立）
- 保留顶部"全部生成"按钮（一键生成所有未完成的页）

### 3. 当前页显示当前页 shots

- `StoryboardGrid` 接收 `pageShots`（当前页的 shots），不再接收全部
- `GridEpisodeCard` 根据 `gridConfigs[pageIndex]` 或 `buildAdaptivePages` 计算 fromIdx/toIdx，slice 出当前页 shots

### 4. 状态同步与持久化

**状态同步**：
- 逐页生成后，SSE 事件携带 `pageIndex`，前端精确更新 `gridImages[pageIndex]`
- 无 SSE 时，polling `loadEpisodes()` 获取最新 `gridImages` 数组

**刷新持久化**：
- 所有状态存后端 `episodeInfo`：`gridImages[]`、`gridPrompts[]`、`gridConfigs[]`、`gridStatus`
- 页面刷新后 `loadEpisodes()` 重建完整状态
- 逐页生成状态由 `gridImages[pageIndex]` 是否有 URL 推导，无需新增字段

## 改动文件

| 文件 | 改动 |
|------|------|
| `GridEpisodeCard.tsx` | 合并 DoneEpisodeCard 功能，加逐页生成按钮，传 pageShots |
| `DoneEpisodeCard` (Step4Production.tsx 内联) | 删除 |
| `StoryboardGrid.tsx` | 接收 pageShots |
| `Step4Production.tsx` | 移除 DoneEpisodeCard 引用，4b tab 统一用 GridEpisodeCard |
| `EpisodeController.java` | 新增逐页生成端点 |
| `GridImageService.java` | 新增 `generateGridPage()` 方法 |
| `episodeService.ts` | 新增 `regenerateEpisodeGridPage()` API |
