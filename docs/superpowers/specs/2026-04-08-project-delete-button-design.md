# 项目列表页删除按钮

## 目标

在项目列表页（`/projects`）的项目卡片上添加删除按钮，允许用户删除项目。

## 背景

- 后端 `DELETE /api/projects/{projectId}` 已实现，使用 `@TableLogic` 软删除
- 前端 `apiClient.ts` 已有 `del()` 方法
- Character 删除已有可参考的模式（`window.confirm` + API 调用 + 刷新列表）
- 当前项目卡片整体是 `<Link>` 包裹，删除按钮需阻止事件冒泡

## 设计

### 改动范围

仅前端，无后端改动。

### 1. `projectService.ts` — 新增 deleteProject

```typescript
export async function deleteProject(projectId: string): Promise<ApiResponse<void>> {
  return del<ApiResponse<void>>(`/api/projects/${projectId}`);
}
```

### 2. `ProjectsPage.tsx` — 卡片添加删除按钮

- 位置：`projectHeader` 区域，标题和状态徽章之间
- 点击时 `e.preventDefault()` + `e.stopPropagation()` 阻止 `<Link>` 导航
- `window.confirm('确定要删除该项目吗？')` 确认
- 确认后调用 `deleteProject(projectId)`
- 成功后从 `projects` 状态中过滤掉该项目（本地更新）

### 3. `ProjectsPage.module.less` — 删除按钮样式

- 小尺寸图标按钮，默认半透明
- hover 时变红（`rgba(239, 68, 68, 0.8)`）
- 内联 SVG trash 图标

### 交互流程

```
用户点击 ✕ 按钮 → window.confirm 确认
  → 取消：无操作
  → 确认：DELETE API
    → 成功：从列表移除该项目
    → 失败：alert 错误信息
```

### 约束

- 所有状态的项目都可删除（草稿、生成中、已完成、失败）
- 使用软删除，数据不会真正丢失
