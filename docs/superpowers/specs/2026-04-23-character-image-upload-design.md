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

**处理逻辑**（在 `CharacterImageGenerationService` 中新增方法）：

1. 校验文件：非空、大小 ≤ 10MB、Content-Type 为 image/jpeg|png|webp
2. `OssService.uploadMultipartFile(file, "character")` → 获取 OSS URL
3. 更新 `characterInfo`：
   - threeView 场景：`threeViewGridUrl` = OSS URL, `threeViewStatus` = `COMPLETED`, `isGeneratingThreeView` = false, 清除 `threeViewError`
   - expression 场景：`expressionGridUrl` = OSS URL, `expressionStatus` = `COMPLETED`, `isGeneratingExpression` = false, 清除 `expressionError`
4. 持久化到数据库

**返回**：`Result<Void>`

### 不改动

- 数据库 schema（复用 characterInfo JSON 字段）
- OssService（已有 uploadMultipartFile）
- 角色状态机逻辑

## 前端改动

### 1. characterService.ts

新增函数：

```typescript
export async function uploadThreeView(projectId: string, charId: string, file: File): Promise<ApiResponse<void>>
export async function uploadExpression(projectId: string, charId: string, file: File): Promise<ApiResponse<void>>
```

使用 FormData + multipart/form-data 调用对应端点。

### 2. Step3Merged.tsx UI 改动

在角色配置阶段（configuring）的表单中，增加两个上传入口：

- 三视图行：显示当前状态 + 「上传三视图」按钮
- 表情图行：显示当前状态 + 「上传表情图」按钮

交互流程：
1. 点击按钮 → 触发隐藏 `<input type="file" accept="image/png,image/jpeg,image/webp">`
2. 选择文件 → 客户端校验（≤10MB、图片格式） → 立即上传
3. 上传中 → 按钮显示 loading
4. 成功 → 刷新角色列表，状态变为 review
5. 失败 → alert 提示错误信息

### 3. 审核阶段（review）

无需额外改动。上传的图片与 AI 生成的图片使用相同的 URL 字段，review 阶段自然展示上传的图片，支持驳回重新配置。

## 文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `CharacterController.java` | 新增端点 | 2 个 upload 端点 |
| `CharacterImageGenerationService.java` | 新增方法 | uploadUserThreeView / uploadUserExpression |
| `characterService.ts` | 新增函数 | uploadThreeView / uploadExpression |
| `Step3Merged.tsx` | UI 改动 | 配置表单增加上传按钮 |
| `Step3Merged.module.less` | 样式新增 | 上传按钮样式 |

## 不在范围内

- 图片裁剪/编辑功能
- 拖拽上传
- 批量上传
- 上传进度条（文件小，直接 loading 状态即可）
