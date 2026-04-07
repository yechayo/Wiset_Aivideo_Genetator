# 灵活里程碑导航 — 允许回溯修改已完成的步骤

## 背景

当前状态机为严格线性推进：`DRAFT → OUTLINE_CONFIRMED → EPISODE_CONFIRMED → ASSET_CONFIRMED → PANEL_CONFIRMED → COMPLETED`。回滚操作（`ROLLBACK_TO_*`）会级联删除下游数据，用户无法在不丢失后续工作的情况下返回修改前面的内容。这导致项目可扩展性不足。

## 目标

- 用户可以在任意步骤自由导航回已完成的前置步骤进行修改
- 回溯不删除下游数据
- 通过版本追踪检测上游数据变更，在下游步骤给出一致性提示
- 保留现有的向前推进流程不变

## 设计

### 1. 里程碑回溯（不删除数据）

**新增 API：**

```
POST /api/project/{id}/revisit/{targetMilestone}
```

逻辑：
- 校验 `targetMilestone <= maxReachedMilestone`（只能回溯到去过的步骤）
- 校验无正在进行的生成任务（Redis lock）
- 更新 `milestone` 为 `targetMilestone`
- 递增对应的 `dataVersion`
- 清除 Redis 缓存
- 发布 SSE 事件 `milestone-change`
- **不删除任何数据**

**移除现有回滚的级联删除：** `ROLLBACK_TO_*` 事件在 `ProjectMilestoneAction` 中的级联删除逻辑移除，改为仅更新状态 + 清缓存 + 发布事件。

### 2. maxReachedMilestone 字段

记录项目曾到达的最远里程碑，**只增不减**。

- 存储位置：`Project.projectInfo` JSON 中
- 递增时机：每次里程碑向前推进时更新
- 用途：决定用户可访问的最远步骤

### 3. 数据版本追踪（dataVersion）

在 `Project.projectInfo` 中新增：

```json
{
  "dataVersion": {
    "outline": 3,
    "episode": 2,
    "asset": 1,
    "panel": 0
  },
  "syncVersions": {
    "episode": 3,
    "asset": 2,
    "panel": 1
  }
}
```

- `dataVersion`：每次修改某级内容时递增对应版本号
- `syncVersions`：记录每个下游步骤生成时对应上游的版本快照
- 通过对比两者判断数据是否一致

**递增规则：**

| 用户操作 | 递增 dataVersion |
|---------|-----------------|
| 修改大纲内容 | `outline` |
| 修改分集剧本 | `episode` |
| 修改角色/素材 | `asset` |
| 修改分镜 | `panel` |

**一致性检测（后端）：** 在 `ProjectService.getEffectiveState()` 中，对比 `syncVersions` 和 `dataVersion`，不一致时在返回值的 `warnings` 数组中添加对应警告。

### 4. Guard 改造

现有数据修改 API 本身不依赖状态机 Guard（只检查数据是否存在）。需要改造的是 **confirm API**：

```java
// 改造前
guard: 状态必须是 OUTLINE_CONFIRMED 才能确认分集

// 改造后
guard: 状态必须是 OUTLINE_CONFIRMED 或 maxReachedMilestone >= EPISODE_CONFIRMED
      （曾经到达过分集确认，就允许再次确认）
```

| API | 改造前 | 改造后 |
|-----|-------|-------|
| 修改大纲 | 只在 DRAFT 状态 | DRAFT 或 maxReached >= OUTLINE_CONFIRMED |
| 确认大纲 | DRAFT → OUTLINE_CONFIRMED | 同上，且数据存在即可 |
| 修改分集 | 只在 OUTLINE_CONFIRMED | OUTLINE_CONFIRMED 或 maxReached >= EPISODE_CONFIRMED |
| 重新生成面板 | 只在 ASSET_CONFIRMED | ASSET_CONFIRMED 或 maxReached >= PANEL_CONFIRMED |
| 确认面板 | ASSET_CONFIRMED → PANEL_CONFIRMED | 同上 |

### 5. 前端改造

#### 5.1 路由守卫放开

```typescript
// 改造前
if (stepId > milestoneToStep(project.milestone)) {
  navigate(milestoneToStep(project.milestone))
}

// 改造后
const maxStep = milestoneToStep(project.maxReachedMilestone)
if (stepId > maxStep) {
  navigate(maxStep) // 仅阻止未来步骤
}
```

#### 5.2 侧边栏步骤条

- 已完成步骤（step <= maxReachedStep）：显示 ✓，可点击
- 当前步骤：高亮显示
- 未来步骤：灰色不可点击
- 点击已完成但非当前步骤时：弹出确认框 → 调用 revisit API → 跳转

#### 5.3 确认对话框

```
标题：返回修改
内容：返回「大纲与剧情」不会删除后续数据，但修改后可能导致下游内容不一致。
按钮：[取消] [确认返回]
```

#### 5.4 一致性警告 Toast

进入步骤时检查 `effectiveState.warnings`，展示 toast：

```
⚠️ 大纲已在分镜生成后被修改，建议重新审核分镜内容
```

用户可忽略，也可选择重新生成。

#### 5.5 面包屑导航

每个步骤页面顶部新增面包屑：

```
创意设定 > 大纲与剧情 > [角色与素材] > 分镜生产 > 合成与下载
```

已完成步骤可点击返回。

### 6. 改动文件清单

**后端：**

| 文件 | 改动 |
|------|------|
| `ProjectMilestoneAction.java` | 移除回滚的级联删除逻辑 |
| `ProjectMilestoneGuard.java` | Guard 增加 maxReachedMilestone 判断 |
| `ProjectService.java` | 新增 revisit 方法 + dataVersion 管理一致性检测 |
| `ProjectController.java` | 新增 `POST /revisit/{milestone}` 端点 |

**前端：**

| 文件 | 改动 |
|------|------|
| `CreateLayout.tsx` | 路由守卫放开 + 面包屑导航 |
| 侧边栏 Steps 组件 | 已完成步骤可点击 + 确认对话框 |
| `ProjectService` (API 层) | 新增 `revisitMilestone()` |
| effectiveState 类型定义 | 新增 `warnings` 字段 |
| 各 Step 页面 | 进入时检查 warnings 并展示 toast |

### 7. 不改动的内容

- 各步骤页面组件（Step1~5）的编辑逻辑
- 数据轮询机制
- SSE 事件推送机制
- 向前推进的确认流程