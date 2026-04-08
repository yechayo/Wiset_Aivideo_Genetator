# 逐集流水线阶段设计

> **Review 2026-04-08**: 代码自 4/7 以来无实质改动，spec 仍然可行。已补充 gridStatus 中间态说明、移除自动切 tab 行为、明确退回脚本接口为必须项。

## 问题

当前步骤 4 使用**全有或全无的标签页解锁**机制：

```
tab4bUnlocked = 所有集数 panelApproved
tab4cUnlocked = 所有集数 gridApproved
```

批量生成 20 集时，用户必须审核完所有脚本才能看到 4b 标签页。
这阻塞了并行工作流，导致流程感觉迟缓。

## 目标

每一集独立地经历 4a → 4b → 4c 流程。
不同集数可以同时处于不同阶段。

## 设计决策

- **标签页交互**：保留 4a/4b/4c 标签页，每个标签页按当前阶段筛选集数。
- **阶段模型**：`pipelineStage` 由现有的 `panelApproved` + `gridStatus` 字段推导得出（前端计算，无需新增后端字段）。
- **驳回**：在 4b 中驳回后仍留在 4b；可通过单独的操作退回 4a。
- **退回脚本接口**：必须新增后端接口，现有 `/panel/reject` 只设 `panelApproved=false`，不会清 `gridStatus/gridImages/splitShots`。

## 阶段推导逻辑

```
pipelineStage:
  panelApproved == false  →  "script"
  panelApproved == true && gridStatus != "approved"  →  "grid"
  panelApproved == true && gridStatus == "approved"  →  "video"
```

`gridStatus` 可能的值：`pending`, `text_ready`, `generating`, `generated`, `rejected`, `failed`, `approved`。
推导逻辑中 `!= "approved"` 自然覆盖所有中间态，无需特殊处理。

无需新增后端字段。

## 标签页内容变更

- **3 个标签页始终解锁**，移除锁定图标。
- **标签页 4a（脚本）**：
  - 主区域：`pipelineStage === "script"` 的集数（正常生成/通过/驳回操作）。
  - 折叠底部：`pipelineStage === "grid"` 或 `"video"` 的集数（只读，标记"已通过 → 4b" / "已通过 → 4c"）。
- **标签页 4b（分镜）**：
  - 主区域：`pipelineStage === "grid"` 的集数（正常生成/通过/驳回操作）。
  - 折叠底部：`pipelineStage === "video"` 的集数（只读，标记"已通过 → 4c"）。
- **标签页 4c（视频）**：
  - 主区域：`pipelineStage === "video"` 的集数（正常视频操作）。
  - 无需折叠底部。

## 驳回流程

| 操作 | 效果 |
|---|---|
| 在 4a 中驳回脚本 | `panelApproved = false`，留在"脚本"阶段（现有行为） |
| 在 4b 中驳回分镜 | `gridStatus = "rejected"`，留在"分镜"阶段（现有行为） |
| 在 4b 中的"退回脚本阶段"按钮 | 新增：重置 `panelApproved = false`，集数退回 4a |

## 前端改动

### `Step4Production.tsx`

1. **移除全局解锁逻辑**（`tab4bUnlocked`、`tab4cUnlocked`）。
2. **移除通过后自动切 tab 行为**（当前 approvePanel/approveGrid 成功后自动跳转下一个 tab，逐集模式下不再适用）。
3. **新增 `pipelineStage` 计算属性**到 `EpisodeState` 或内联推导。
4. **按标签页筛选集数**，基于 `pipelineStage`。
5. **渲染折叠"已完成"区域**，在 4a 和 4b 标签页底部。
6. **移除锁定图标**，从标签按钮上。
7. **更新标签页完成指示器**：基于各标签页集数计数显示勾选标记，而非全局解锁。

### `types.ts`

- 添加 `pipelineStage` 字段到 `EpisodeState`（或在组件中计算）。

## 后端改动

### 必须新增：退回脚本阶段接口

```
PUT /api/projects/{projectId}/episodes/{episodeId}/panel/reject-to-script
```

设置 `panelApproved = false`，重置 `gridStatus` 为 `pending`，清空 `gridImages`/`splitShots`。

现有 `/panel/reject` 只设 `panelApproved = false`，不清 grid 数据，不能直接复用。

## 需要修改的文件

| 文件 | 改动 |
|---|---|
| `Step4Production.tsx` | 标签页筛选、解锁逻辑移除、自动切 tab 移除、折叠区域 |
| `types.ts` | 添加 `pipelineStage` 类型 |
| `Step4Production.module.less` | 折叠底部样式、已完成标记样式 |
| `EpisodeController.java` | 新增退回脚本阶段接口 |
