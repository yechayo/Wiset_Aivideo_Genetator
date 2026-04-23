# 角色图片手动上传功能设计

## 概述

在角色与素材页面（Step3）的配置阶段，增加用户手动上传角色三视图/表情图的功能。上传的图片直接存储到 OSS，与 AI 生图走相同的后续状态路径（审核、锁定、驳回）。

## 背景

当前角色图片仅支持 AI 生图（Seedream / Nanobanana / GPT-Image-2），用户无法使用已有角色素材。后端 `OssService.uploadMultipartFile()` 已支持 multipart 文件上传，但缺少前后端对接。

## 需求

- 配置阶段提供「上传三视图」「上传表情图」按钮，与「生成图片」并列
- 支持 JPG/PNG/WebP，单张上传，不超过 10MB
- 上传后直接替代 AI 生图结果，状态变为 COMPLETED
- 可被审核、锁定、驳回，与 AI 生图流程一致

## 方案：直接上传替代（方案 A）

改动最小，复用现有 OssService 和角色状态管理。

## 后端改动

### 新增 API 端点

**文件**：`CharacterController.java`

```
POST /api/projects/{projectId}/characters/{charId}/upload/three-view
POST /api/projects/{projectId}/characters/{charId}/upload/expression
```

**参数**：`@RequestParam("file") MultipartFile file`

**处理逻辑**（在 `CharacterImageGenerationService` 中新增方法，标注 `@Transactional`）：

1. **归属校验**：通过 `charId` 查询角色，校验 `character.getProjectId()` 与路径参数 `projectId` 一致
2. **前置状态校验**：
   - `imagesLocked = true` 时拒绝上传（抛 BusinessException "角色图片已锁定"）
   - `isGeneratingThreeView / isGeneratingExpression = true` 时拒绝（抛 BusinessException "正在生成中，请稍后"）
3. **文件校验**：
   - 非空、大小 ≤ 10MB（`file.getSize() > 10 * 1024 * 1024` 时抛 BusinessException）
   - 用 `ImageIO.read(new ByteArrayInputStream(file.getBytes()))` 验证是否为有效图片，不支持则拒绝
4. **配角拦截**（仅 expression 端点）：若角色 `role = "配角"`，拒绝上传表情图（抛 BusinessException "配角不需要上传表情图"）
5. `OssService.uploadMultipartFile(file, "character")` → 获取 OSS URL
6. **更新 `characterInfo`**：
   - threeView 场景：`threeViewGridUrl` = OSS URL, `threeViewStatus` = `COMPLETED`, `isGeneratingThreeView` = false, 清除 `threeViewError`、`threeViewGridPrompt`
   - expression 场景：`expressionGridUrl` = OSS URL, `expressionStatus` = `COMPLETED`, `isGeneratingExpression` = false, 清除 `expressionError`、`expressionGridPrompt`
7. **推进 `charStatus`**：若该角色所有必要图片都已完成（主角需三视图+表情图，配角仅需三视图），设置 `charStatus = "review"`、`confirmed = true`
8. **重建 compositeReferenceUrl**：若主角/反派的三视图和表情图都已完成，调用 `OssService.combineImagesVertical()` 生成拼接图并缓存
9. **检查项目状态推进**：调用 `checkAndAdvanceProjectState(character)`，若所有角色都已完成则发布 `asset_image` 完成事件
10. 持久化到数据库

**返回**：`Result<Void>`

### 不改动

- 数据库 schema（复用 characterInfo JSON 字段）
- OssService（已有 uploadMultipartFile）
- 角色状态机逻辑

## 前端改动

### 1. characterService.ts

新增函数（直接复用 `apiClient.ts` 的 `post` 函数，传入 FormData 对象即可，拦截器已自动处理 multipart boundary）：

```typescript
export async function uploadThreeView(projectId: string, charId: string, file: File): Promise<ApiResponse<void>> {
  const formData = new FormData();
  formData.append('file', file);
  return post(`/api/projects/${projectId}/characters/${charId}/upload/three-view`, formData);
}

export async function uploadExpression(projectId: string, charId: string, file: File): Promise<ApiResponse<void>> {
  const formData = new FormData();
  formData.append('file', file);
  return post(`/api/projects/${projectId}/characters/${charId}/upload/expression`, formData);
}
```

### 2. Step3Merged.tsx UI 改动

在角色配置阶段（configuring）的表单中，增加两个上传入口，与「生成」按钮并列：

- 三视图行：显示当前状态 + 「生成三视图」「上传三视图」两个按钮
- 表情图行：显示当前状态 + 「生成表情图」「上传表情图」两个按钮（配角只显示三视图行）

交互流程：
1. 点击上传按钮 → 触发隐藏 `<input type="file" accept="image/png,image/jpeg,image/webp">`
2. 选择文件 → 客户端校验（≤10MB、图片格式） → 立即上传
3. 上传中 → 按钮显示 loading
4. 成功 → 刷新角色列表，状态变为 review
5. 失败 → alert 提示错误信息

### 3. 审核阶段（review）

无需额外改动。上传的图片与 AI 生成的图片使用相同的 URL 字段，review 阶段自然展示上传的图片，支持驳回回到 configuring 重新选择生成或上传。

## 文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `CharacterController.java` | 新增端点 | 2 个 upload 端点 |
| `CharacterImageGenerationService.java` | 新增方法 | `@Transactional uploadUserThreeView` / `uploadUserExpression` |
| `characterService.ts` | 新增函数 | `uploadThreeView` / `uploadExpression`（FormData + post） |
| `Step3Merged.tsx` | UI 改动 | 配置表单增加上传按钮（与生成按钮并列） |
| `Step3Merged.module.less` | 样式新增 | 上传按钮样式 |

## 不在范围内

- 图片裁剪/编辑功能
- 拖拽上传
- 批量上传
- 上传进度条（文件小，直接 loading 状态即可）
