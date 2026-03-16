# 漫画 AI 创作平台 API 文档

## 📋 项目概述

漫画 AI 创作平台是一个基于 Spring Boot 的智能漫画创作系统，整合了 GLM、Seedream、Seedance 等 AI 服务，实现从剧本生成到视频制作的完整流程。

---

## 🔐 认证说明

### JWT Token 认证

除了登录和注册接口外，所有接口都需要在 Header 中携带 JWT Token：

```
Authorization: Bearer {accessToken}
```

### Token 获取流程

1. **注册/登录** → 获取 `accessToken` 和 `refreshToken`
2. **使用 `accessToken`** → 调用需要认证的接口
3. **Token 过期 (401)** → 使用 `refreshToken` 刷新
4. **退出登录** → 当前 Token 立即失效

---

## 📚 API 接口列表

### 1. 认证模块 `/api/auth`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/auth/register` | POST | 用户注册 | ❌ |
| `/api/auth/login` | POST | 用户登录 | ❌ |
| `/api/auth/logout` | POST | 退出登录 | ✅ |
| `/api/auth/refresh` | POST | 刷新 Token | ❌ |
| `/api/auth/me` | GET | 获取当前用户信息 | ✅ |

#### 1.1 注册

```
POST /api/auth/register
```

**请求体：**
```json
{
  "username": "string (3-20字符)",
  "password": "string (6-20字符)",
  "email": "string (可选)"
}
```

**响应：**
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200000
  }
}
```

#### 1.2 登录

```
POST /api/auth/login
```

**请求体：**
```json
{
  "username": "string",
  "password": "string"
}
```

**响应：** 同注册接口

#### 1.3 退出登录

```
POST /api/auth/logout
Header: Authorization: Bearer {accessToken}
```

#### 1.4 刷新 Token

```
POST /api/auth/refresh
```

**请求体：**
```json
{
  "refreshToken": "string"
}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "accessToken": "新的 accessToken",
    "refreshToken": "新的 refreshToken",
    "tokenType": "Bearer",
    "expiresIn": 7200000
  }
}
```

#### 1.5 获取当前用户信息

```
GET /api/auth/me
Header: Authorization: Bearer {accessToken}
```

---

### 2. 项目管理模块 `/api/projects`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/projects` | POST | 创建项目 | ✅ |
| `/api/projects` | GET | 获取用户所有项目 | ✅ |
| `/api/projects/{projectId}` | GET | 获取项目详情 | ✅ |
| `/api/projects/{projectId}/generate-script` | POST | 生成剧本 | ✅ |
| `/api/projects/{projectId}/script` | GET | 获取剧本内容 | ✅ |
| `/api/projects/{projectId}/confirm-script` | POST | 确认剧本 | ✅ |
| `/api/projects/{projectId}/revise-script` | POST | 要求修改剧本 | ✅ |
| `/api/projects/{projectId}/advance` | POST | 推进流水线状态 | ✅ |

#### 2.1 创建项目

```
POST /api/projects
```

**请求体：**
```json
{
  "storyPrompt": "一个少年意外获得超能力，成为守护城市的英雄",
  "genre": "热血",
  "targetAudience": "青少年",
  "totalEpisodes": 10,
  "episodeDuration": 5
}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "projectId": "proj-xxx"
  }
}
```

#### 2.2 获取项目列表

```
GET /api/projects
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "projectId": "proj-xxx",
      "userId": "user-xxx",
      "storyPrompt": "...",
      "genre": "热血",
      "status": "SCRIPT_REVIEW",
      "createTime": "2025-03-13T10:00:00"
    }
  ]
}
```

#### 2.3 获取项目详情

```
GET /api/projects/{projectId}
```

#### 2.4 生成剧本

```
POST /api/projects/{projectId}/generate-script
```

**响应：**
```json
{
  "code": 200,
  "data": "SERIES-xxxx"  // 剧本系列ID
}
```

#### 2.5 获取剧本内容

```
GET /api/projects/{projectId}/script
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "project": { /* 项目信息 */ },
    "episodes": [
      {
        "id": 1,
        "seriesId": "SERIES-xxxx",
        "episodeNum": 1,
        "title": "第1集",
        "outlineNode": "剧情大纲",
        "status": "DRAFT"
      }
    ]
  }
}
```

#### 2.6 确认剧本

```
POST /api/projects/{projectId}/confirm-script
```

#### 2.7 要求修改剧本

```
POST /api/projects/{projectId}/revise-script
```

**请求体：**
```json
{
  "revisionNote": "请增加更多动作戏"
}
```

#### 2.8 推进流水线

```
POST /api/projects/{projectId}/advance
```

**请求体：**
```json
{
  "event": "next"  // 或其他事件名称
}
```

---

### 3. 角色管理模块 `/api/characters`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/characters/extract` | POST | 从剧本中提取角色 | ✅ |
| `/api/characters` | GET | 获取项目角色列表 | ✅ |
| `/api/characters/{charId}` | PUT | 编辑角色信息 | ✅ |
| `/api/characters/confirm` | POST | 确认所有角色 | ✅ |

#### 3.1 提取角色

```
POST /api/characters/extract
```

**请求体：**
```json
{
  "projectId": "proj-xxx"
}
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "charId": "char-xxx",
      "name": "主角名",
      "description": "角色描述",
      "appearance": "外观描述",
      "personality": "性格特征"
    }
  ]
}
```

#### 3.2 获取项目角色列表

```
GET /api/characters?projectId={projectId}
```

#### 3.3 编辑角色

```
PUT /api/characters/{charId}
```

**请求体：**
```json
{
  "name": "新名称",
  "description": "新描述",
  "appearance": "新外观",
  "personality": "新性格"
}
```

#### 3.4 确认角色

```
POST /api/characters/confirm
```

**请求体：**
```json
{
  "projectId": "proj-xxx"
}
```

---

### 4. 故事/分镜模块 `/api/story`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/story/generate` | POST | 提交分镜生成任务 | ✅ |
| `/api/story/storyboard/{episodeId}` | GET | 查看分镜结果 | ✅ |
| `/api/story/episodes` | GET | 获取剧集列表 | ✅ |

#### 4.1 生成分镜

```
POST /api/story/generate
```

**请求体：**
```json
{
  "episodeId": 1
}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "jobId": "job-xxx"
  }
}
```

#### 4.2 查看分镜结果

```
GET /api/story/storyboard/{episodeId}
```

#### 4.3 获取剧集列表

```
GET /api/story/episodes?seriesId={seriesId}
```

---

### 5. 任务管理模块 `/api/jobs`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/jobs/{jobId}/progress` | GET | 订阅任务进度 (SSE) | ✅ |
| `/api/jobs/{jobId}` | GET | 查询任务状态 | ✅ |

#### 5.1 订阅任务进度 (SSE 实时推送)

```
GET /api/jobs/{jobId}/progress
```

前端使用方式：
```javascript
const eventSource = new EventSource('/api/jobs/{jobId}/progress', {
  headers: { 'Authorization': 'Bearer ' + token }
});

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('进度:', data.progress);
};
```

#### 5.2 查询任务状态 (轮询方式)

```
GET /api/jobs/{jobId}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "jobId": "job-xxx",
    "type": "STORYBOARD_GENERATION",
    "status": "COMPLETED",
    "progress": 100,
    "result": "{...}",
    "createTime": "2025-03-13T10:00:00"
  }
}
```

---

### 6. 任务执行模块 `/api/tasks`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/tasks/{taskId}/status` | GET | 查询任务状态 | ✅ |
| `/api/tasks/project/{projectId}` | GET | 获取项目任务列表 | ✅ |
| `/api/tasks/{taskId}/cancel` | POST | 取消任务 | ✅ |

#### 6.1 查询任务状态

```
GET /api/tasks/{taskId}/status
```

#### 6.2 获取项目任务列表

```
GET /api/tasks/project/{projectId}
```

#### 6.3 取消任务

```
POST /api/tasks/{taskId}/cancel
```

---

### 7. 文件管理模块 `/api/files`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/files/upload` | POST | 上传文件 | ✅ |
| `/api/files/upload/base64` | POST | Base64 上传 | ✅ |
| `/api/files/{fileId}/url` | GET | 获取文件 URL | ✅ |
| `/api/files/{fileId}` | DELETE | 删除文件 | ✅ |

#### 7.1 上传文件

```
POST /api/files/upload
Content-Type: multipart/form-data
```

**表单参数：**
```
file: (文件)
```

#### 7.2 Base64 上传

```
POST /api/files/upload/base64
```

**请求体：**
```json
{
  "base64Data": "data:image/png;base64,iVBORw0KG...",
  "fileName": "image.png"
}
```

#### 7.3 获取文件 URL

```
GET /api/files/{fileId}/url
```

#### 7.4 删除文件

```
DELETE /api/files/{fileId}
```

---

### 8. 系统配置模块 `/api/config`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/config` | GET | 获取所有配置 | ✅ |
| `/api/config/{configKey}` | GET | 获取单个配置值 | ✅ |
| `/api/config` | POST | 保存或更新配置 | ✅ |
| `/api/config/{configKey}` | DELETE | 删除配置 | ✅ |

#### 8.1 获取所有配置

```
GET /api/config
```

#### 8.2 获取单个配置值

```
GET /api/config/{configKey}
```

#### 8.3 保存或更新配置

```
POST /api/config
```

**请求体：**
```json
{
  "configKey": "ai.provider.text",
  "configValue": "glm",
  "configType": "string",
  "description": "文本生成AI提供商"
}
```

#### 8.4 删除配置

```
DELETE /api/config/{configKey}
```

---

### 9. AI 测试模块 `/api/test/ai`

| 接口 | 方法 | 说明 | 是否需要认证 |
|------|------|------|-------------|
| `/api/test/ai/call` | POST | 测试 AI 调用 | ❌ |
| `/api/test/ai/info` | GET | 获取 AI 服务信息 | ❌ |
| `/api/test/ai/ping` | GET | 快速测试 | ❌ |

#### 9.1 测试 AI 调用

```
POST /api/test/ai/call
```

**请求体：**
```json
{
  "systemPrompt": "你是一个专业的漫画编剧",
  "userPrompt": "请生成一个热血漫画的故事大纲"
}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "service": "GLM-Text",
    "duration": "1234ms",
    "response": "AI生成的回复内容...",
    "availableSlots": 4
  }
}
```

#### 9.2 获取 AI 服务信息

```
GET /api/test/ai/info
```

#### 9.3 快速测试

```
GET /api/test/ai/ping
```

---

## 📊 项目状态流转

```
DRAFT (草稿)
  ↓ 生成剧本
SCRIPT_GENERATING (剧本生成中)
  ↓ 生成完成
SCRIPT_REVIEW (剧本待审核)
  ├─→ 确认 → SCRIPT_CONFIRMED
  └─→ 修改 → SCRIPT_REVISION_REQUESTED
       ↓
       SCRIPT_GENERATING (重新生成)
SCRIPT_CONFIRMED
  ↓ 提取角色
CHARACTER_EXTRACTING (角色提取中)
  ↓
CHARACTER_REVIEW (角色待审核)
  ↓ 确认
CHARACTER_CONFIRMED
  ↓ 生成分镜
STORYBOARD_GENERATING (分镜生成中)
  ↓
STORYBOARD_REVIEW (分镜待审核)
  ↓
COMPLETED (完成)
```

---

## 🔧 通用响应格式

### 成功响应

```json
{
  "code": 200,
  "msg": "success",
  "data": { /* 响应数据 */ }
}
```

### 错误响应

```json
{
  "code": 400/401/404/500,
  "msg": "错误描述",
  "data": null
}
```

### 常见错误码

| 错误码 | 说明 |
|-------|------|
| 400 | 请求参数错误 |
| 401 | 未认证或 Token 过期 |
| 403 | 无权限访问 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

---

## 🚀 快速开始

### 1. 注册用户

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123",
    "email": "test@example.com"
  }'
```

### 2. 登录获取 Token

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'
```

### 3. 创建项目

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {accessToken}" \
  -d '{
    "storyPrompt": "一个少年获得超能力的故事",
    "genre": "热血",
    "targetAudience": "青少年",
    "totalEpisodes": 10,
    "episodeDuration": 5
  }'
```

### 4. 生成剧本

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/generate-script \
  -H "Authorization: Bearer {accessToken}"
```

---

## 📝 配置说明

### AI 服务配置

项目支持以下 AI 服务：

| 功能 | 提供商 | 模型 |
|------|-------|------|
| 文本生成 | GLM | glm-4-flash |
| 图片生成 | Seedream | doubao-seedream-5-0-260128 |
| 视频生成 | Seedance | doubao-seedance-1-5-pro-251215 |

### 环境变量

```bash
# 火山引擎 API Key
export ARK_API_KEY=your_ark_api_key

# JWT 密钥（生产环境必须修改）
export JWT_SECRET=your-jwt-secret-key
```

---

*文档生成时间：2025-03-13*
