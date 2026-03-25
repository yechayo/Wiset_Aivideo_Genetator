# CharacterController 接口重新设计

## 目标

适配新架构（Map 存储、逻辑删除），补全缺失接口（删除），增强查询能力（分页、筛选），统一 RESTful 嵌套路径风格。

## 接口总览

基础路径：`/api/projects/{projectId}/characters`

### CRUD 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/projects/{projectId}/characters` | 分页列表（支持 role/name 筛选） |
| GET | `/api/projects/{projectId}/characters/{charId}` | 角色详情 |
| POST | `/api/projects/{projectId}/characters/extract` | AI 提取角色 |
| PUT | `/api/projects/{projectId}/characters/{charId}` | 更新角色信息 |
| DELETE | `/api/projects/{projectId}/characters/{charId}` | 逻辑删除角色 |

### 确认接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects/{projectId}/characters/confirm` | 批量确认所有角色 |

### 图片生成接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `.../characters/{charId}/generate/expression` | 生成表情网格 |
| POST | `.../characters/{charId}/generate/three-view` | 生成三视图 |
| POST | `.../characters/{charId}/generate/all` | 同时生成 |
| PUT | `.../characters/{charId}/visual-style` | 设置视觉风格 |
| POST | `.../characters/{charId}/retry/{type}` | 重试失败生成 |
| GET | `.../characters/{charId}/status` | 获取生成状态 |

## 详细设计

### 1. 列表查询（分页 + 筛选）

**请求：** `GET /api/projects/{projectId}/characters?page=1&size=10&role=PROTAGONIST&name=张`

参数：
- `page`: int（默认 1）
- `size`: int（默认 10）
- `role`: String（可选，PROTAGONIST/SUPPORTING/EXTRA）
- `name`: String（可选，模糊搜索）

响应：
```json
{
  "code": 200,
  "data": {
    "content": [
      {
        "charId": "char_001",
        "name": "张三",
        "role": "PROTAGONIST",
        "personality": "勇敢坚毅",
        "voice": "沉稳男声",
        "appearance": "...",
        "visualStyle": "ANIME",
        "expressionStatus": "COMPLETED",
        "threeViewStatus": "PENDING",
        "confirmed": false,
        "createdAt": "2026-03-25T10:00:00"
      }
    ],
    "totalElements": 5,
    "totalPages": 1,
    "page": 1,
    "size": 10
  }
}
```

### 2. 角色详情

**请求：** `GET /api/projects/{projectId}/characters/{charId}`

返回完整的 `characterInfo` Map，包含所有字段（基本信息 + 生成状态 + 图片 URL + 错误信息）。

### 3. 更新角色信息

**请求：** `PUT /api/projects/{projectId}/characters/{charId}`
```json
{
  "name": "张三",
  "personality": "勇敢坚毅",
  "voice": "沉稳男声",
  "appearance": "黑色短发，身材魁梧",
  "background": "退役军人"
}
```

- 仅允许在 `CHARACTER_REVIEW` 状态下更新
- 支持部分更新（只传需要修改的字段）

### 4. 逻辑删除角色

**请求：** `DELETE /api/projects/{projectId}/characters/{charId}`

逻辑：
- 仅允许在 `CHARACTER_REVIEW` 状态下删除（确认后不可删）
- 标记 `deleted = true`，不物理删除
- 删除最后一个角色不影响项目状态（项目仍停留在 `CHARACTER_REVIEW`）

响应：
```json
{
  "code": 200,
  "message": "角色已删除"
}
```

### 5. 批量确认

**请求：** `POST /api/projects/{projectId}/characters/confirm`

逻辑：
- 校验项目至少有一个未删除的角色
- 将所有未删除角色的 `confirmed` 设为 true
- 项目状态从 `CHARACTER_REVIEW` → `CHARACTER_CONFIRMED`

### 6. 生成表情网格

**请求：** `POST /api/projects/{projectId}/characters/{charId}/generate/expression`

逻辑：
- 仅 `CHARACTER_CONFIRMED` 状态下可操作
- 配角（SUPPORTING）跳过表情生成，直接返回提示
- 检查 `isGeneratingExpression` 防止重复提交
- 异步执行，接口立即返回

### 7. 生成三视图

**请求：** `POST /api/projects/{projectId}/characters/{charId}/generate/three-view`

逻辑：同上，操作 `threeView` 相关字段。所有角色类型均可生成。

### 8. 同时生成

**请求：** `POST /api/projects/{projectId}/characters/{charId}/generate/all`

逻辑：按顺序提交表情网格 + 三视图生成任务。

### 9. 设置视觉风格

**请求：** `PUT /api/projects/{projectId}/characters/{charId}/visual-style`
```json
{
  "visualStyle": "ANIME"
}
```

可选值：`3D`、`REAL`、`ANIME`。仅 `CHARACTER_CONFIRMED` 状态下可操作。

### 10. 重试失败生成

**请求：** `POST /api/projects/{projectId}/characters/{charId}/retry/{type}`

路径参数 `type`: `expression` | `threeView`

逻辑：
- 仅当对应状态为 `FAILED` 时可重试
- 重置错误信息，重新提交生成任务

### 11. 获取生成状态

**请求：** `GET /api/projects/{projectId}/characters/{charId}/status`

响应：
```json
{
  "code": 200,
  "data": {
    "charId": "char_001",
    "name": "张三",
    "expressionStatus": "COMPLETED",
    "threeViewStatus": "GENERATING",
    "isGeneratingExpression": false,
    "isGeneratingThreeView": true,
    "expressionGridUrl": "https://...",
    "threeViewGridUrl": null,
    "expressionError": null,
    "threeViewError": null,
    "visualStyle": "ANIME"
  }
}
```

## 数据模型变更

- `CharacterInfoKeys` 新增 `voice` 常量（保留原有 `personality`）
- `CharacterExtractService` 中 AI 提取 prompt 增加 `voice` 字段提取
- `Character` 实体新增 `deleted` 字段（逻辑删除）

## 与现有接口的变化

- **路径变化：** 所有接口从 `/api/characters/...` 改为 `/api/projects/{projectId}/characters/...`
- **新增：** `DELETE` 单角色逻辑删除
- **增强：** 列表接口增加分页和条件筛选参数
- **保留：** 图片生成接口逻辑不变，仅路径调整
- **字段变更：** 新增 `voice` 字段（`personality` 保留）