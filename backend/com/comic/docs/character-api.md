# 角色管理接口文档

> 所有接口均需要在 Header 中传递 JWT Token：`Authorization: Bearer {token}`

---

## 一、接口列表

### 1. 提取角色

从已确认的剧本中 AI 自动提取角色信息。

```
POST /api/characters/extract
```

**请求体：**
```json
{
  "projectId": "项目ID"
}
```

**响应：** `Result<List<CharacterDraftDTO>>`
```json
{
  "code": 200,
  "data": [
    {
      "charId": "char_xxx",
      "name": "角色名称",
      "role": "主角",
      "personality": "性格描述",
      "appearance": "外貌描述",
      "background": "背景故事",
      "confirmed": false
    }
  ]
}
```

---

### 2. 获取项目角色列表

```
GET /api/characters?projectId={projectId}
```

**响应：** `Result<List<CharacterDraftDTO>>`（同上）

---

### 3. 获取单个角色详情

```
GET /api/characters/{charId}
```

**响应：** `Result<CharacterStatusDTO>`
```json
{
  "code": 200,
  "data": {
    "charId": "char_xxx",
    "name": "角色名称",
    "role": "主角",
    "visualStyle": "D_3D",
    "expressionStatus": "COMPLETED",
    "threeViewStatus": "GENERATING",
    "expressionError": null,
    "threeViewError": null,
    "isGeneratingExpression": false,
    "isGeneratingThreeView": true,
    "standardImageUrl": null,
    "expressionGridUrl": "https://...",
    "threeViewGridUrl": null
  }
}
```

---

### 4. 编辑角色特征

```
PUT /api/characters/{charId}
```

**请求体：** `CharacterDraftDTO`
```json
{
  "name": "角色名称",
  "role": "主角",
  "personality": "性格描述",
  "appearance": "外貌描述",
  "background": "背景故事"
}
```

**响应：** `Result<Void>`

---

### 5. 确认所有角色

锁定项目下所有角色数据，确认后不可再编辑。

```
POST /api/characters/confirm
```

**请求体：**
```json
{
  "projectId": "项目ID"
}
```

**响应：** `Result<Void>`

---

### 6. 设置视觉风格

```
PUT /api/characters/{charId}/visual-style
```

**请求体：**
```json
{
  "visualStyle": "D_3D"
}
```

| 可选值 | 说明 |
|--------|------|
| `D_3D` | 3D 动漫风格（默认） |
| `REAL` | 真人写实风格 |
| `ANIME` | 2D 动漫风格 |

**响应：** `Result<Void>`

---

### 7. 生成九宫格表情大全图

一次性生成包含 9 种表情（开心、悲伤、愤怒、惊讶、恐惧、厌恶、轻蔑、害羞、平静）的大全图（2048x2048）。
**配角会自动跳过，返回 400 错误。**

```
POST /api/characters/{charId}/generate-expression
```

**响应：** `Result<Void>`

---

### 8. 生成三视图大全图

一次性生成包含正面、侧面、背面的大全图（1024x1536）。

```
POST /api/characters/{charId}/generate-three-view
```

**响应：** `Result<Void>`

---

### 9. 一键生成全部

为角色同时生成九宫格表情 + 三视图。**配角自动跳过表情生成，仅生成三视图。**

```
POST /api/characters/{charId}/generate-all
```

**响应：** `Result<Void>`

---

### 10. 重试生成

清除之前的结果并重新生成指定类型的图片。

```
POST /api/characters/{charId}/retry/{type}
```

| type 值 | 说明 |
|---------|------|
| `expression` | 重试九宫格表情 |
| `threeView` | 重试三视图 |

**响应：** `Result<Void>`

---

### 11. 获取生成状态

轮询此接口获取图片生成进度和结果。

```
GET /api/characters/{charId}/status
```

**响应：** `Result<CharacterStatusDTO>`

**状态值说明：**

| 字段 | 值 | 说明 |
|------|-----|------|
| `expressionStatus` / `threeViewStatus` | `null` | 未开始 |
| | `GENERATING` | 生成中 |
| | `COMPLETED` | 生成完成 |
| | `FAILED` | 生成失败（具体原因见 `expressionError` / `threeViewError`） |

---

## 二、调用顺序

```
前置条件：剧本已确认（SCRIPT_CONFIRMED）
         ↓
┌─────────────────────────────────────────────────┐
│  Phase 1: 角色提取                               │
│                                                   │
│  POST /api/characters/extract                     │
│    → AI 从剧本中自动提取角色                       │
│    → 项目状态: CHARACTER_EXTRACTING               │
│    → 完成后: CHARACTER_REVIEW                     │
└─────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────┐
│  Phase 2: 角色审核与编辑                          │
│                                                   │
│  GET  /api/characters?projectId=                  │
│    → 查看提取的角色列表                            │
│                                                   │
│  PUT  /api/characters/{charId}                    │
│    → 编辑角色信息（可多次，直到满意）               │
│                                                   │
│  POST /api/characters/confirm                     │
│    → 确认所有角色，锁定数据                        │
│    → 项目状态: CHARACTER_CONFIRMED                │
└─────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────┐
│  Phase 3: 图片生成                                │
│                                                   │
│  PUT  /api/characters/{charId}/visual-style       │
│    → 设置视觉风格（D_3D / REAL / ANIME）          │
│                                                   │
│  POST /api/characters/{charId}/generate-all       │
│    → 一键生成（九宫格 + 三视图）                   │
│    → 配角自动跳过表情，仅生成三视图                │
│                                                   │
│  GET  /api/characters/{charId}/status             │
│    → 轮询生成状态，直到 COMPLETED 或 FAILED        │
│                                                   │
│  POST /api/characters/{charId}/retry/{type}       │
│    → 失败时重试（expression / threeView）          │
└─────────────────────────────────────────────────┘
```

---

## 三、数据模型

### CharacterDraftDTO（角色列表/编辑）

| 字段 | 类型 | 说明 |
|------|------|------|
| charId | String | 角色唯一ID |
| name | String | 角色名称 |
| role | String | 角色定位：`主角` / `反派` / `配角` |
| personality | String | 性格描述 |
| appearance | String | 外貌描述 |
| background | String | 背景故事 |
| confirmed | Boolean | 是否已确认 |

### CharacterStatusDTO（角色详情/生成状态）

| 字段 | 类型 | 说明 |
|------|------|------|
| charId | String | 角色唯一ID |
| name | String | 角色名称 |
| role | String | 角色定位 |
| visualStyle | String | 视觉风格：`D_3D` / `REAL` / `ANIME` |
| expressionStatus | String | 表情生成状态：`GENERATING` / `COMPLETED` / `FAILED` |
| threeViewStatus | String | 三视图生成状态：`GENERATING` / `COMPLETED` / `FAILED` |
| expressionError | String | 表情生成错误信息 |
| threeViewError | String | 三视图生成错误信息 |
| isGeneratingExpression | Boolean | 是否正在生成表情 |
| isGeneratingThreeView | Boolean | 是否正在生成三视图 |
| standardImageUrl | String | 标准形象图URL |
| expressionGridUrl | String | 九宫格大全图URL（2048x2048） |
| threeViewGridUrl | String | 三视图大全图URL（1024x1536） |
