# 前端 API 对接指南（完整版）

> **Base URL：** `http://<服务器地址>/`  
> **所有需要登录的接口** 均须在请求头携带：`Authorization: Bearer <accessToken>`  
> **统一响应格式：**
> ```json
> { "code": 0, "msg": "ok", "data": ... }
> ```
> `code != 0` 时 `msg` 为错误信息，`data` 可能为 null。

---

## 目录

1. [认证接口 `/api/auth`](#1-认证接口)
2. [项目接口 `/api/projects`](#2-项目接口)
3. [SSE 实时推送](#3-sse-实时推送)
4. [剧本接口 `/api/projects/{projectId}/script`](#4-剧本接口)
5. [剧集接口 `/api/projects/{projectId}/episodes`](#5-剧集接口)
6. [角色接口 `/api/projects/{projectId}/characters`](#6-角色接口)
7. [分镜接口 `/api/projects/{projectId}/episodes/{episodeId}/panels`](#7-分镜接口)
8. [生产状态查询](#8-生产状态查询)
9. [背景图接口](#9-背景图接口)
10. [四宫格漫画接口](#10-四宫格漫画接口)
11. [视频接口](#11-视频接口)
12. [任务接口 `/api/jobs`](#12-任务接口)
13. [文件接口 `/api/files`](#13-文件接口)
14. [状态机总览](#14-状态机总览)
15. [前端渲染规则](#15-前端渲染规则)

---

## 1. 认证接口

基础路径：`/api/auth`（除 `/logout` 和 `/me` 外，其余接口无需 Token）

---

### 1.1 注册

```
POST /api/auth/register
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名 |
| password | string | 是 | 密码 |
| email | string | 否 | 邮箱 |

**响应 `data`（AuthResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| accessToken | string | JWT 访问令牌 |
| refreshToken | string | 刷新令牌 |
| expiresIn | number | accessToken 过期秒数 |

**示例请求：**
```json
{
  "username": "alice",
  "password": "Abc12345!",
  "email": "alice@example.com"
}
```

**示例响应：**
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "dGhpcyBp...",
    "expiresIn": 86400
  }
}
```

---

### 1.2 登录

```
POST /api/auth/login
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名 |
| password | string | 是 | 密码 |

**响应 `data`：** 同注册（AuthResponse）。

**示例请求：**
```json
{
  "username": "alice",
  "password": "Abc12345!"
}
```

---

### 1.3 退出登录

```
POST /api/auth/logout
Authorization: Bearer <accessToken>
```

- 无请求体
- 响应 `data` 为 null
- 调用后该 Token **立即失效**，前端需清除本地存储的 Token

**示例响应：**
```json
{ "code": 0, "msg": "ok", "data": null }
```

---

### 1.4 刷新 Token

```
POST /api/auth/refresh
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| refreshToken | string | 是 | 登录时返回的 refreshToken |

**响应 `data`（TokenRefreshResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| accessToken | string | 新的 JWT 访问令牌 |
| expiresIn | number | 过期秒数 |

**示例请求：**
```json
{ "refreshToken": "dGhpcyBp..." }
```

---

### 1.5 获取当前用户信息

```
GET /api/auth/me
Authorization: Bearer <accessToken>
```

- 无请求参数

**响应 `data`（UserInfoResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| userId | string | 用户 ID |
| username | string | 用户名 |
| email | string | 邮箱 |

---

## 2. 项目接口

基础路径：`/api/projects`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 2.1 创建项目

```
POST /api/projects
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（ProjectCreateRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| storyPrompt | string | 是 | 故事提示词/大纲 |
| genre | string | 否 | 类型（ROMANCE / ACTION / COMEDY / THRILLER / FANTASY 等） |
| targetAudience | string | 否 | 目标受众（如：青少年、成人） |
| totalEpisodes | number | 否 | 总集数（默认12） |
| episodeDuration | number | 否 | 单集时长（秒，默认60） |
| visualStyle | string | 否 | 视觉风格（3D / ANIME / COMIC） |

**响应 `data`：**
```json
{ "projectId": "PROJ-xxxxxxxx" }
```

**示例请求：**
```json
{
  "storyPrompt": "一个关于都市白领逆袭的现代都市言情故事",
  "genre": "ROMANCE",
  "targetAudience": "青少年",
  "totalEpisodes": 12,
  "episodeDuration": 60,
  "visualStyle": "ANIME"
}
```

---

### 2.2 项目列表（分页）

```
GET /api/projects
Authorization: Bearer <accessToken>
```

**Query 参数：**

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| status | string | - | 按状态码筛选（如 PRODUCING、COMPLETED） |
| sortBy | string | createdAt | 排序字段（createdAt / updatedAt） |
| sortOrder | string | desc | 排序方向（asc / desc） |
| page | number | 1 | 页码（从1开始） |
| size | number | 20 | 每页数量 |

**示例：**
```
GET /api/projects?status=PRODUCING&page=1&size=20&sortBy=createdAt&sortOrder=desc
```

**响应 `data`：**
```json
{
  "items": [
    {
      "projectId": "PROJ-xxxxxxxx",
      "storyPrompt": "都市言情故事...",
      "genre": "ROMANCE",
      "targetAudience": "青少年",
      "totalEpisodes": 12,
      "episodeDuration": 60,
      "visualStyle": "ANIME",
      "statusCode": "PRODUCING",
      "statusDescription": "生产中",
      "currentStep": 5,
      "isGenerating": false,
      "isFailed": false,
      "isReview": false,
      "completedSteps": [1, 2, 3, 4],
      "createdAt": "2026-03-27T10:00:00",
      "updatedAt": "2026-03-27T12:00:00"
    }
  ],
  "total": 100,
  "page": 1,
  "size": 20
}
```

---

### 2.3 项目原始实体

```
GET /api/projects/{projectId}
Authorization: Bearer <accessToken>
```

返回项目原始实体，一般用于调试。**前端推荐使用 `/status` 接口获取状态。**

---

### 2.4 项目状态详情（核心接口，前端主要依赖）

```
GET /api/projects/{projectId}/status
Authorization: Bearer <accessToken>
```

**响应 `data`（ProjectStatusResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| projectId | string | 项目 ID |
| statusCode | string | 当前状态码（见状态机总览） |
| statusDescription | string | 状态描述（中文） |
| currentStep | number | 当前前端步骤 1~6 |
| isGenerating | boolean | 是否正在 AI 生成中 |
| isFailed | boolean | 是否处于失败态 |
| isReview | boolean | 是否处于人工审核态 |
| completedSteps | number[] | 已完成的步骤列表 |
| availableActions | string[] | 当前可执行的操作名列表 |
| productionProgress | number | 生产进度 0~100（PRODUCING 阶段有效） |
| productionSubStage | string | 生产子阶段（background / comic / video） |
| panelCurrentEpisode | number | 当前正在处理的剧集号 |
| panelTotalEpisodes | number | 总剧集数 |
| panelReviewEpisodeId | string | 当前待审核的剧集 ID |
| panelAllConfirmed | boolean | 所有分镜是否已全部确认 |

**示例响应：**
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "projectId": "PROJ-xxxxxxxx",
    "statusCode": "PANEL_REVIEW",
    "statusDescription": "分镜待审核",
    "currentStep": 5,
    "isGenerating": false,
    "isFailed": false,
    "isReview": true,
    "completedSteps": [1, 2, 3, 4],
    "availableActions": ["confirm_panels", "revise_panels"],
    "productionProgress": 0,
    "productionSubStage": null,
    "panelCurrentEpisode": 1,
    "panelTotalEpisodes": 12,
    "panelReviewEpisodeId": "101",
    "panelAllConfirmed": false
  }
}
```

> **推荐用法：** 每 3 秒轮询此接口，或使用 SSE 实时推送（见第3节，推荐）。

---

### 2.5 生产摘要（PRODUCING 阶段专用）

```
GET /api/projects/{projectId}/production/summary
Authorization: Bearer <accessToken>
```

仅在 `statusCode == "PRODUCING"` 时调用有意义。

**响应 `data`（ProjectProductionSummaryResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| currentEpisodeId | number | 当前 Panel 所属剧集 ID |
| currentPanelId | number | 当前正在生产的 Panel ID |
| currentPanelIndex | number | 当前 Panel 序号（从1开始） |
| totalPanelCount | number | 总 Panel 数量 |
| completedPanelCount | number | 已完成视频的 Panel 数量 |
| productionSubStage | string | 当前子阶段：background / comic / video |
| blockedReason | string \| null | 阻塞原因：panel_failed / panel_pending_review / null |

---

### 2.6 推进 / 回退状态

```
POST /api/projects/{projectId}/status/advance
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（AdvanceRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| direction | string | 是 | `"forward"` 推进 / `"backward"` 回退 |
| event | string | direction=forward 时必填 | 事件名（见下表） |

**常用事件名（direction=forward）：**

| event 值 | 触发时机 |
|---|---|
| `confirm_script` | 用户确认剧本 |
| `confirm_characters` | 用户确认角色 |
| `confirm_images` | 用户确认角色图片 |
| `all_panels_confirmed` | 用户完成所有分镜审核 |
| `retry` | 重试失败状态 |

**推进示例：**
```json
{ "direction": "forward", "event": "confirm_script" }
```

**回退示例：**
```json
{ "direction": "backward" }
```

---

### 2.7 更新项目

```
PUT /api/projects/{projectId}      （全量更新）
PATCH /api/projects/{projectId}    （部分更新）
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求体字段同创建项目（2.1），所有字段均为可选。

---

### 2.8 删除项目（逻辑删除）

```
DELETE /api/projects/{projectId}
Authorization: Bearer <accessToken>
```

- 无请求体
- 响应 `data` 为 null
- 逻辑删除，数据仍保留在数据库

---

## 3. SSE 实时推送

### 3.1 项目状态推送

```
GET /api/projects/{projectId}/status/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

- **超时时间：** 5 分钟（300秒），超时后前端需重新连接
- **推送事件名：** `status-change`
- **推送数据结构：** 同 `/status` 接口响应的 `data` 字段

**前端连接方式（推荐用 `@microsoft/fetch-event-source`，支持自定义 Header）：**

```bash
npm install @microsoft/fetch-event-source
```

```javascript
import { fetchEventSource } from '@microsoft/fetch-event-source';

const ctrl = new AbortController();

fetchEventSource(`/api/projects/${projectId}/status/stream`, {
  headers: { Authorization: `Bearer ${token}` },
  signal: ctrl.signal,
  onmessage(event) {
    if (event.event === 'status-change') {
      const data = JSON.parse(event.data);
      // data 结构同 /status 响应的 data 字段
      console.log(data.statusCode, data.currentStep, data.isGenerating);
    }
  },
  onerror(err) {
    // 断连后自动重连（指数退避）
    console.error('SSE error', err);
  }
});

// 组件卸载时断开连接
// ctrl.abort();
```

> **注意：** 原生 `EventSource` 不支持自定义 Header，必须使用上述封装库或在 URL 中携带 token（不推荐）。

---

### 3.2 任务进度推送（分镜生成等异步任务）

```
GET /api/jobs/{jobId}/progress
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

- `jobId` 由 `POST .../panels/generate` 接口返回
- 实时推送任务进度，完成或失败后连接自动关闭

**前端示例：**

```javascript
fetchEventSource(`/api/jobs/${jobId}/progress`, {
  headers: { Authorization: `Bearer ${token}` },
  onmessage(event) {
    const data = JSON.parse(event.data);
    console.log(data.progress, data.status, data.message);
  }
});
```

---

## 4. 剧本接口

基础路径：`/api/projects/{projectId}/script`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 4.1 获取剧本内容

```
GET /api/projects/{projectId}/script
Authorization: Bearer <accessToken>
```

- 无请求参数
- 返回剧本完整内容：大纲 + 各剧集内容

---

### 4.2 生成大纲

```
POST /api/projects/{projectId}/script/generate
Authorization: Bearer <accessToken>
```

- 无请求体
- 项目状态需为 `DRAFT`，触发 AI 生成大纲
- 响应 `data` 为 null，生成过程异步进行，通过 SSE 或轮询 `/status` 跟踪进度

---

### 4.3 生成指定章节的剧集

```
POST /api/projects/{projectId}/script/episodes/generate
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（EpisodeGenerateRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| chapter | number | 是 | 章节号（从1开始） |
| episodeCount | number | 否 | 本章生成集数 |
| modificationSuggestion | string | 否 | 修改建议（有则按建议重新生成） |

**示例请求：**
```json
{
  "chapter": 1,
  "episodeCount": 3,
  "modificationSuggestion": "增加更多冲突情节"
}
```

---

### 4.4 批量生成所有剩余章节的剧集

```
POST /api/projects/{projectId}/script/episodes/generate-all
Authorization: Bearer <accessToken>
```

- 无请求体
- 一键生成所有未生成章节的剧集

---

### 4.5 重新生成指定章节的剧集

```
POST /api/projects/{projectId}/script/episodes/revise
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求体同 4.3（EpisodeGenerateRequest）。

---

### 4.6 确认剧本

```
POST /api/projects/{projectId}/script/confirm
Authorization: Bearer <accessToken>
```

- 无请求体
- 确认剧本，自动触发状态推进：`SCRIPT_REVIEW → SCRIPT_CONFIRMED → CHARACTER_EXTRACTING`
- 响应 `data` 为 null

---

### 4.7 修改剧本（退回重生成大纲）

```
POST /api/projects/{projectId}/script/revise
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（ScriptReviseRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| revisionNote | string | 是 | 修改意见 |
| currentOutline | string | 否 | 当前大纲内容（可携带现有内容辅助修改） |

**示例请求：**
```json
{
  "revisionNote": "希望故事背景改为古代架空，增加武侠元素",
  "currentOutline": "现有大纲内容..."
}
```

---

### 4.8 手动保存大纲

```
PATCH /api/projects/{projectId}/script/outline
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体：**
```json
{ "outline": "修改后的大纲内容文本..." }
```

- `outline` 字段不能为空
- 响应 `data` 为 null

---

## 5. 剧集接口

基础路径：`/api/projects/{projectId}/episodes`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 5.1 剧集列表（分页）

```
GET /api/projects/{projectId}/episodes?page=1&size=10&name=关键词
Authorization: Bearer <accessToken>
```

**Query 参数：**

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| page | number | 1 | 页码 |
| size | number | 10 | 每页数量 |
| name | string | - | 标题模糊搜索 |

**响应 `data`：**
```json
{
  "items": [
    {
      "episodeId": 1,
      "title": "第一集",
      "chapterNo": 1,
      "episodeNo": 1,
      "status": "PANEL_CONFIRMED",
      "content": "剧集正文内容..."
    }
  ],
  "total": 12,
  "page": 1,
  "size": 10
}
```

---

### 5.2 剧集详情

```
GET /api/projects/{projectId}/episodes/{episodeId}
Authorization: Bearer <accessToken>
```

响应 `data` 结构同列表中的单个 item。

---

### 5.3 创建剧集

```
POST /api/projects/{projectId}/episodes
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（EpisodeCreateRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| title | string | 是 | 剧集标题 |
| content | string | 是 | 剧集正文内容 |
| chapterNo | number | 是 | 章节号 |
| episodeNo | number | 是 | 集号 |

---

### 5.4 更新剧集

```
PUT /api/projects/{projectId}/episodes/{episodeId}
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（EpisodeUpdateRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| title | string | 否 | 剧集标题 |
| content | string | 否 | 剧集正文内容 |

---

### 5.5 删除剧集

```
DELETE /api/projects/{projectId}/episodes/{episodeId}
Authorization: Bearer <accessToken>
```

- 无请求体，响应 `data` 为 null

---

## 6. 角色接口

基础路径：`/api/projects/{projectId}/characters`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 6.1 角色列表（分页）

```
GET /api/projects/{projectId}/characters?page=1&size=10&role=main&name=关键词
Authorization: Bearer <accessToken>
```

**Query 参数：**

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| page | number | 1 | 页码 |
| size | number | 10 | 每页数量 |
| role | string | - | 角色类型筛选（main / supporting） |
| name | string | - | 角色名称模糊搜索 |

**响应 `data`：**
```json
{
  "items": [
    {
      "charId": "CHAR-xxxx",
      "name": "李明",
      "role": "main",
      "description": "男主角，都市白领"
    }
  ],
  "total": 5,
  "page": 1,
  "size": 10
}
```

---

### 6.2 角色详情

```
GET /api/projects/{projectId}/characters/{charId}
Authorization: Bearer <accessToken>
```

**响应 `data`（CharacterStatusResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| charId | string | 角色 ID |
| name | string | 角色名称 |
| role | string | 角色类型（main / supporting） |
| description | string | 角色描述 |
| personalityTraits | string | 性格特征 |
| visualDescription | string | 外观描述 |
| expressionStatus | string | 九宫格表情生成状态（pending / generating / completed / failed） |
| expressionImageUrl | string | 九宫格表情图 URL |
| threeViewStatus | string | 三视图生成状态（pending / generating / completed / failed） |
| threeViewsUrl | string | 三视图 URL |
| isGeneratingExpression | boolean | 是否正在生成表情 |
| isGeneratingThreeView | boolean | 是否正在生成三视图 |

---

### 6.3 提取角色

```
POST /api/projects/{projectId}/characters/extract
Authorization: Bearer <accessToken>
```

- 无请求体
- 从剧本中自动提取角色信息，返回提取到的角色草稿列表
- 仅提取，不自动触发状态推进

---

### 6.4 更新角色信息

```
PUT /api/projects/{projectId}/characters/{charId}
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（CharacterUpdateRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| name | string | 否 | 角色名称 |
| role | string | 否 | 角色类型（main / supporting） |
| description | string | 否 | 角色描述 |
| personalityTraits | string | 否 | 性格特征 |
| visualDescription | string | 否 | 外观描述（影响图片生成效果） |

---

### 6.5 删除角色（逻辑删除）

```
DELETE /api/projects/{projectId}/characters/{charId}
Authorization: Bearer <accessToken>
```

- 无请求体，响应 `data` 为 null

---

### 6.6 确认角色

```
POST /api/projects/{projectId}/characters/confirm
Authorization: Bearer <accessToken>
```

- 无请求体
- 确认所有角色，锁定角色数据
- 自动触发状态推进：`CHARACTER_REVIEW → CHARACTER_CONFIRMED → IMAGE_GENERATING`

---

### 6.7 确认角色图片（进入分镜生成）

```
POST /api/projects/{projectId}/characters/images/confirm
Authorization: Bearer <accessToken>
```

- 无请求体
- 确认所有角色图片，锁定素材
- 自动触发状态推进：`IMAGE_REVIEW → ASSET_LOCKED → PANEL_GENERATING`

---

### 6.8 图片生成接口

| 方法 | 路径 | 说明 | 请求体 |
|---|---|---|---|
| POST | `/api/projects/{projectId}/characters/{charId}/generate/expression` | 生成九宫格表情图 | 无 |
| POST | `/api/projects/{projectId}/characters/{charId}/generate/three-view` | 生成三视图 | 无 |
| POST | `/api/projects/{projectId}/characters/{charId}/generate/all` | 一键生成（表情+三视图） | 无 |
| POST | `/api/projects/{projectId}/characters/{charId}/retry/{type}` | 重试失败的生成 | 无，`type` 为 `expression` 或 `three-view` |
| PUT | `/api/projects/{projectId}/characters/{charId}/visual-style` | 设置视觉风格 | `{"visualStyle": "ANIME"}` |
| GET | `/api/projects/{projectId}/characters/{charId}/status` | 获取生成状态（同角色详情） | - |

**设置视觉风格请求体：**
```json
{ "visualStyle": "ANIME" }
```

visualStyle 可选值：`3D` / `ANIME` / `COMIC`

---

## 7. 分镜接口

基础路径：`/api/projects/{projectId}/episodes/{episodeId}/panels`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 7.1 分镜列表

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels
Authorization: Bearer <accessToken>
```

- 无 Query 参数，返回该剧集下所有分镜列表

**响应 `data`（list）中每个分镜字段（PanelListItemResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| panelId | number | 分镜 ID |
| panelIndex | number | 分镜序号（从1开始） |
| content | string | 分镜文本描述 |
| shotType | string | 镜头类型（CLOSE_UP / MEDIUM / WIDE 等） |
| characterIds | string[] | 出场角色 ID 列表 |
| sceneDescription | string | 场景描述 |
| status | string | 分镜状态 |

---

### 7.2 分镜详情

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}
Authorization: Bearer <accessToken>
```

响应 `data` 结构同列表中的单个分镜。

---

### 7.3 创建分镜

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels
Authorization: Bearer <accessToken>
Content-Type: application/json
```

> ⚠️ 项目处于 `PRODUCING` 或 `VIDEO_ASSEMBLING` 状态时，**禁止**创建/更新/删除分镜，接口会返回错误：`当前项目正在生产或拼接中，无法变更分镜`

**请求体（PanelCreateRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| content | string | 是 | 分镜文本描述 |
| shotType | string | 否 | 镜头类型（CLOSE_UP / MEDIUM / WIDE / BIRD_EYE 等） |
| characterIds | string[] | 否 | 出场角色 ID 列表 |
| sceneDescription | string | 否 | 场景描述 |

**示例请求：**
```json
{
  "content": "李明走进咖啡厅，环顾四周",
  "shotType": "MEDIUM",
  "characterIds": ["CHAR-001"],
  "sceneDescription": "现代都市咖啡厅，午后阳光透过玻璃窗照入"
}
```

---

### 7.4 更新分镜

```
PUT /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}
Authorization: Bearer <accessToken>
Content-Type: application/json
```

> ⚠️ PRODUCING / VIDEO_ASSEMBLING 状态下禁止调用

**请求体（PanelUpdateRequest）：** 字段同创建，所有字段均可选。

---

### 7.5 删除分镜

```
DELETE /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}
Authorization: Bearer <accessToken>
```

> ⚠️ PRODUCING / VIDEO_ASSEMBLING 状态下禁止调用

- 无请求体，响应 `data` 为 null

---

### 7.6 AI 生成分镜

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/generate
Authorization: Bearer <accessToken>
```

- 无请求体
- 提交分镜生成异步任务，**立即返回 jobId**
- 前端用 jobId 轮询任务状态或订阅进度 SSE

**响应 `data`：**
```json
{ "jobId": "JOB-xxxxxxxx" }
```

---

### 7.7 查询分镜生成任务状态（轮询）

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/generate/{jobId}/status
Authorization: Bearer <accessToken>
```

**响应 `data`：**

| 字段 | 类型 | 说明 |
|---|---|---|
| jobId | string | 任务 ID |
| status | string | pending / processing / completed / failed / unknown |
| progress | number | 进度 0~100 |
| message | string | 进度描述 |
| errorMessage | string | 失败时的错误信息 |

**示例响应：**
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "jobId": "JOB-xxxxxxxx",
    "status": "processing",
    "progress": 60,
    "message": "正在生成镜头描述..."
  }
}
```

---

### 7.8 确认分镜内容

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/confirm
Authorization: Bearer <accessToken>
```

- 无请求体，响应 `data` 为 null
- 确认该剧集的所有分镜内容

---

### 7.9 修改分镜（退回重生成）

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/revise
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（PanelReviseRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| feedback | string | 是 | 修改意见 |

**示例请求：**
```json
{ "feedback": "镜头角度改为俯视，增加更多细节描述" }
```

---

### 7.10 重试失败的分镜生成

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/retry
Authorization: Bearer <accessToken>
```

- 无请求体，响应 `data` 为 null

---

## 8. 生产状态查询

基础路径：`/api/projects/{projectId}/episodes/{episodeId}/panels`

---

### 8.1 单 Panel 生产状态

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/production-status
Authorization: Bearer <accessToken>
```

**响应 `data`（PanelProductionStatusResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| panelId | number | Panel ID |
| overallStatus | string | 整体状态（pending / in_progress / completed / failed / paused） |
| currentStage | string | 当前子阶段（background / comic / video） |
| backgroundStatus | string | 背景图状态（pending / generating / completed / failed） |
| backgroundUrl | string | 背景图 URL |
| comicStatus | string | 四宫格状态（pending / generating / pending_review / approved / failed） |
| comicUrl | string | 四宫格图 URL |
| videoStatus | string | 视频状态（pending / generating / completed / failed） |
| videoUrl | string | 视频文件 URL |
| videoDuration | number | 视频时长（秒） |
| errorMessage | string | 失败时的错误信息 |

---

### 8.2 批量获取所有 Panel 生产状态

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/production-statuses
Authorization: Bearer <accessToken>
```

- 无 Query 参数
- 返回该剧集下所有 Panel 的生产状态列表（数组），每项结构同 8.1

---

## 9. 背景图接口

基础路径：`/api/projects/{projectId}/episodes/{episodeId}/panels`

---

### 9.1 获取背景图状态

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/background
Authorization: Bearer <accessToken>
```

**响应 `data`（PanelBackgroundResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| panelId | number | Panel ID |
| backgroundStatus | string | pending / generating / completed / failed |
| backgroundUrl | string | 背景图 URL（completed 时有效） |
| errorMessage | string | 失败原因 |

---

### 9.2 生成背景图

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/background
Authorization: Bearer <accessToken>
```

- 无请求体，自动匹配角色素材生成背景图
- 响应 `data` 为 null，异步生成

---

### 9.3 重新生成背景图

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/background/regenerate
Authorization: Bearer <accessToken>
```

- 无请求体，效果同 9.2（强制重新生成）

---

## 10. 四宫格漫画接口

基础路径：`/api/projects/{projectId}/episodes/{episodeId}/panels`

---

### 10.1 获取四宫格状态

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/comic
Authorization: Bearer <accessToken>
```

**响应 `data`（ComicStatusResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| panelId | number | Panel ID |
| comicStatus | string | pending / generating / pending_review / approved / failed |
| comicUrl | string | 四宫格图片 URL（生成完成时有效） |
| errorMessage | string | 失败原因 |

---

### 10.2 生成四宫格漫画

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/comic
Authorization: Bearer <accessToken>
```

- 无请求体，触发 AI 生成四宫格漫画

---

### 10.3 审核通过四宫格（触发视频生成）

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/comic/approve
Authorization: Bearer <accessToken>
```

- 无请求体
- 审核通过后**自动开始视频生成**，并推进生产流程到下一个 Panel
- `comicStatus` 需为 `pending_review` 才可调用

---

### 10.4 退回重生成四宫格

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/comic/revise
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（ComicReviseRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| feedback | string | 是 | 修改意见 |

**示例请求：**
```json
{ "feedback": "人物表情需要更开心，背景色调偏暖" }
```

> **重要：** `comicStatus = pending_review` 时前端**必须**展示审核按钮（通过 / 退回），用户点通过后调用 `/approve`，系统自动开始视频生成。

---

## 11. 视频接口

基础路径：`/api/projects/{projectId}/episodes/{episodeId}/panels`

---

### 11.1 获取视频状态

```
GET /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/video
Authorization: Bearer <accessToken>
```

**响应 `data`（VideoStatusResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| panelId | number | Panel ID |
| videoStatus | string | pending / generating / completed / failed |
| videoUrl | string | 视频文件 URL（completed 时有效） |
| videoDuration | number | 视频时长（秒） |
| errorMessage | string | 失败原因 |

---

### 11.2 手动触发视频生成

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/video
Authorization: Bearer <accessToken>
```

- 无请求体（正常流程由系统自动触发，手动接口供调试用）

---

### 11.3 重试失败的视频生成

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/video/retry
Authorization: Bearer <accessToken>
```

- 无请求体，重试后自动继续推进生产流程

---

## 12. 任务接口

基础路径：`/api/jobs`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 12.1 订阅任务进度（SSE）

```
GET /api/jobs/{jobId}/progress
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

- `jobId` 由分镜生成接口（7.6）返回
- 实时推送进度，任务完成或失败后连接自动关闭

**推送数据字段：**

| 字段 | 类型 | 说明 |
|---|---|---|
| status | string | pending / processing / completed / failed |
| progress | number | 进度 0~100 |
| message | string | 进度描述 |
| errorMessage | string | 失败时错误信息 |

**前端示例：**
```javascript
fetchEventSource(`/api/jobs/${jobId}/progress`, {
  headers: { Authorization: `Bearer ${token}` },
  onmessage(event) {
    const data = JSON.parse(event.data);
    setProgress(data.progress);
    if (data.status === 'completed') {
      // 刷新分镜列表
    }
  }
});
```

---

### 12.2 查询任务状态（轮询备用）

```
GET /api/jobs/{jobId}
Authorization: Bearer <accessToken>
```

**响应 `data`（Job 实体）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | 任务 ID |
| status | string | PENDING / RUNNING / SUCCESS / FAILED |
| progress | number | 进度 0~100 |
| progressMsg | string | 进度描述 |
| errorMsg | string | 失败时错误信息 |

---

## 13. 文件接口

基础路径：`/api/files`  
所有接口需要 `Authorization: Bearer <accessToken>`

---

### 13.1 上传文件（multipart）

```
POST /api/files/upload
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
```

**表单字段：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | File | 是 | 要上传的文件 |

**响应 `data`（FileUploadResponse）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| fileId | number | 文件 ID |
| url | string | 文件访问 URL |
| fileName | string | 文件名 |
| fileSize | number | 文件大小（字节） |

**前端示例：**
```javascript
const formData = new FormData();
formData.append('file', file);
await fetch('/api/files/upload', {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}` },
  body: formData
});
```

---

### 13.2 Base64 上传

```
POST /api/files/upload/base64
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体（Base64UploadRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| base64Data | string | 是 | Base64 编码的文件内容（含 `data:image/png;base64,` 前缀） |
| fileName | string | 是 | 文件名（如 `avatar.png`） |

响应 `data` 同 13.1。

---

### 13.3 获取文件 URL

```
GET /api/files/{fileId}/url
Authorization: Bearer <accessToken>
```

响应 `data` 为文件 URL 字符串。

---

### 13.4 删除文件

```
DELETE /api/files/{fileId}
Authorization: Bearer <accessToken>
```

- 无请求体，响应 `data` 为 null

---

## 14. 状态机总览

### 完整流转链

```
DRAFT
  ↓ （自动触发 start_script_generation）
OUTLINE_GENERATING → OUTLINE_REVIEW → EPISODE_GENERATING → SCRIPT_REVIEW
  ↓ 用户调用 POST /script/confirm
SCRIPT_CONFIRMED
  ↓ （自动触发 start_character_extraction）
CHARACTER_EXTRACTING → CHARACTER_REVIEW
  ↓ 用户调用 POST /characters/confirm
CHARACTER_CONFIRMED
  ↓ （自动触发 start_image_generation）
IMAGE_GENERATING → IMAGE_REVIEW
  ↓ 用户调用 POST /characters/images/confirm
ASSET_LOCKED
  ↓ （自动触发 start_panels）
PANEL_GENERATING → PANEL_REVIEW
  ↓ 用户逐剧集调用 POST /panels/{panelId}/confirm（全部确认后自动推进）
PRODUCING
  ↓ （后端自动，所有 Panel 视频生成完毕）
VIDEO_ASSEMBLING
  ↓ （后端自动，拼接完成）
COMPLETED
```

---

### 失败状态与重试

| 失败状态 | 所在步骤 | 重试方式 |
|---|---|---|
| OUTLINE_GENERATING_FAILED | 2 | 调用 `/status/advance` body: `{direction:"forward",event:"retry"}` |
| EPISODE_GENERATING_FAILED | 2 | 同上 |
| CHARACTER_EXTRACTING_FAILED | 3 | 同上 |
| IMAGE_GENERATING_FAILED | 4 | 同上 |
| PANEL_GENERATING_FAILED | 5 | 同上 |

---

### 状态码与前端步骤对应表

| 状态码 | currentStep | isGenerating | isReview | isFailed |
|---|---|---|---|---|
| DRAFT | 1 | false | false | false |
| OUTLINE_GENERATING | 2 | true | false | false |
| OUTLINE_REVIEW | 2 | false | true | false |
| EPISODE_GENERATING | 2 | true | false | false |
| SCRIPT_REVIEW | 2 | false | true | false |
| SCRIPT_CONFIRMED | 3 | false | false | false |
| OUTLINE_GENERATING_FAILED | 2 | false | false | true |
| EPISODE_GENERATING_FAILED | 2 | false | false | true |
| CHARACTER_EXTRACTING | 3 | true | false | false |
| CHARACTER_REVIEW | 3 | false | true | false |
| CHARACTER_CONFIRMED | 4 | false | false | false |
| CHARACTER_EXTRACTING_FAILED | 3 | false | false | true |
| IMAGE_GENERATING | 4 | true | false | false |
| IMAGE_REVIEW | 4 | false | true | false |
| ASSET_LOCKED | 4 | false | false | false |
| IMAGE_GENERATING_FAILED | 4 | false | false | true |
| PANEL_GENERATING | 5 | true | false | false |
| PANEL_REVIEW | 5 | false | true | false |
| PANEL_GENERATING_FAILED | 5 | false | false | true |
| PRODUCING | 5 | false | false | false |
| VIDEO_ASSEMBLING | 6 | true | false | false |
| COMPLETED | 6 | false | false | false |

---

## 15. 前端渲染规则

### SSE 优先，轮询兜底

推荐使用 SSE（`/api/projects/{projectId}/status/stream`）实时接收状态变更。SSE 断连时按指数退避重连，重连期间降级为每 3 秒轮询 `/status`。

---

### 步骤页面切换逻辑

```javascript
function renderPage(statusCode, currentStep, isFailed, isGenerating, isReview) {
  // 失败态：任意步骤都展示重试按钮
  if (isFailed) {
    return <FailedPage statusCode={statusCode} onRetry={() =>
      advanceStatus(projectId, 'forward', 'retry')
    } />;
  }

  switch (currentStep) {
    case 1:
      return <DraftPage />; // 填写故事信息，点击"生成大纲"
    case 2:
      if (isGenerating) return <ScriptGeneratingPage />;
      if (isReview) return <ScriptReviewPage statusCode={statusCode} />;
      return <ScriptPage />;
    case 3:
      if (isGenerating) return <CharacterExtractingPage />;
      if (isReview) return <CharacterReviewPage />;
      return <CharacterPage />;
    case 4:
      if (isGenerating) return <ImageGeneratingPage />;
      if (isReview) return <ImageReviewPage />;
      return <ImagePage />;
    case 5:
      if (statusCode === 'PANEL_GENERATING') return <PanelGeneratingPage />;
      if (statusCode === 'PANEL_REVIEW') return <PanelReviewPage />;
      if (statusCode === 'PRODUCING') return <ProductionPage />;
      return <PanelPage />;
    case 6:
      if (statusCode === 'VIDEO_ASSEMBLING') return <AssemblingPage />;
      if (statusCode === 'COMPLETED') return <CompletedPage />;
      break;
  }
}
```

---

### availableActions 与按钮映射

`/status` 接口返回的 `availableActions` 数组告知前端当前可展示哪些操作按钮：

| action 值 | 展示按钮 | 调用接口 |
|---|---|---|
| `generate_outline` | 生成大纲 | `POST /script/generate` |
| `generate_episodes` | 生成剧集 | `POST /script/episodes/generate` |
| `revise_outline` | 修改大纲 | `POST /script/revise` |
| `confirm_script` | 确认剧本 | `POST /script/confirm` |
| `confirm_characters` | 确认角色 | `POST /characters/confirm` |
| `confirm_images` | 确认图片 | `POST /characters/images/confirm` |
| `confirm_panels` | 确认分镜 | `POST /panels/{panelId}/confirm` |
| `revise_panels` | 修改分镜 | `POST /panels/{panelId}/revise` |
| `retry` | 重试 | `POST /status/advance` body: `{direction:"forward",event:"retry"}` |

---

### PRODUCING 阶段生产进度展示

```javascript
// 每5秒轮询生产摘要（或等待 SSE 更新）
const summary = await fetch(`/api/projects/${projectId}/production/summary`);
// 展示：当前Panel / 总Panel，子阶段（background/comic/video）
// blockedReason === 'panel_pending_review' 时展示四宫格审核弹窗
```

---

### 错误处理

| HTTP 状态码 | 含义 | 前端处理 |
|---|---|---|
| 200 | 成功，但需检查 `code` 字段 | `code != 0` 时显示 `msg` |
| 401 | Token 过期或无效 | 调用 `/auth/refresh` 刷新，失败则跳转登录 |
| 403 | 无权限 | 提示无权限 |
| 404 | 资源不存在 | 提示资源不存在 |
| 500 | 服务器错误 | 提示系统繁忙，稍后重试 |

---

### Token 刷新策略（推荐）

```javascript
// axios 响应拦截器示例
axios.interceptors.response.use(null, async (error) => {
  if (error.response?.status === 401 && !error.config._retry) {
    error.config._retry = true;
    const { data } = await axios.post('/api/auth/refresh', {
      refreshToken: localStorage.getItem('refreshToken')
    });
    localStorage.setItem('accessToken', data.data.accessToken);
    error.config.headers.Authorization = `Bearer ${data.data.accessToken}`;
    return axios(error.config);
  }
  return Promise.reject(error);
});
``` 