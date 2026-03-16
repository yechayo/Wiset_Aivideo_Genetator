# Apifox 接口认证配置指南

## 📋 概述

本项目的所有业务接口都需要 JWT Token 认证，本文档详细说明如何在 Apifox 中配置和使用。

---

## 🔐 认证方式

### JWT Bearer Token 认证

所有需要认证的接口都在 HTTP Header 中携带 Token：

```
Authorization: Bearer <your_token>
```

**示例**：
```http
GET /api/projects HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## 📊 接口认证清单

### ✅ 需要认证的接口

| 模块 | 路径前缀 | 说明 |
|------|---------|------|
| 项目管理 | `/api/projects/*` | 项目的所有操作 |
| 角色管理 | `/api/characters/*` | 角色的提取、编辑、确认 |
| 故事生成 | `/api/story/*` | 分镜生成、查询 |
| 任务管理 | `/api/jobs/*` | 任务状态查询、进度订阅 |
| 任务跟踪 | `/api/tasks/*` | 异步任务状态 |
| 配置管理 | `/api/config/*` | 系统配置管理 |
| 文件管理 | `/api/files/*` | 文件上传下载 |
| 用户信息 | `/api/auth/me` | 获取当前用户信息 |
| 用户登出 | `/api/auth/logout` | 退出登录 |

### ❌ 不需要认证的接口

| 接口 | 路径 | 说明 |
|------|------|------|
| 用户注册 | `POST /api/auth/register` | 新用户注册 |
| 用户登录 | `POST /api/auth/login` | 用户登录获取Token |
| 刷新Token | `POST /api/auth/refresh` | 刷新过期的Token |
| AI测试 | `/api/test/ai/*` | AI服务测试接口 |

---

## 🚀 Apifox 配置步骤

### 方式一：导入 OpenAPI 文档（推荐）

#### 1. 访问 OpenAPI JSON
启动项目后，访问：
```
http://localhost:8080/v3/api-docs
```

复制所有 JSON 内容。

#### 2. 在 Apifox 中导入
1. 打开 Apifox 项目
2. 点击 **"导入"** → **"OpenAPI"**
3. 选择 **"URL或文件"** → **"粘贴 JSON 内容"**
4. ⭐ **重要**：勾选 **"自动创建鉴权组件"**
5. 点击 **"确定"** 导入

#### 3. 查看自动创建的鉴权组件
- 左侧菜单 → **"设置"** → **"鉴权组件"**
- 应该能看到名为 **"bearerAuth"** 的鉴权组件

#### 4. 使用鉴权组件
- 打开需要认证的接口（如 `POST /api/projects`）
- 点击右上角 **"Auth 设置"**
- 选择 **"bearerAuth"** 组件
- 输入你的 JWT Token
- 保存后，所有接口会自动带上 Authorization header

---

### 方式二：手动配置鉴权组件

#### 1. 创建鉴权组件
1. 打开 Apifox 项目
2. 左侧菜单 → **"设置"** → **"鉴权组件"**
3. 点击 **"新建鉴权组件"**

#### 2. 填写配置
```
名称：bearerAuth
类型：Bearer Token
参数名：Authorization
前缀：Bearer
Token 位置：Header
```

#### 3. 应用到接口
- 打开需要认证的接口
- 点击 **"Auth 设置"**
- 选择 **"bearerAuth"**
- 输入 Token

---

### 方式三：环境变量 + Pre-request Script

#### 1. 添加环境变量
1. 点击右上角 **"环境管理"**
2. 选择当前环境
3. 添加变量：
   ```
   变量名：token
   变量值：你的实际 JWT Token
   ```

#### 2. 配置 Pre-request Script
在接口的 **"Pre-request Script"** 中添加：

```javascript
// 自动从环境变量读取 token
pm.request.headers.add({
    key: 'Authorization',
    value: 'Bearer ' + pm.environment.get('token')
});
```

---

## 🔑 获取 Token 的步骤

### 1. 注册用户
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "123456",
    "email": "test@example.com"
  }'
```

### 2. 登录获取 Token
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "123456"
  }'
```

**响应示例**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer"
  }
}
```

### 3. 复制 Token
从响应中复制 `accessToken` 的值。

---

## 💡 前端调用示例

### JavaScript/Fetch
```javascript
fetch('http://localhost:8080/api/projects', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + token
  },
  body: JSON.stringify({
    storyPrompt: '一个热血玄幻故事',
    genre: '热血玄幻',
    targetAudience: '青少年',
    totalEpisodes: 20,
    episodeDuration: 180
  })
})
.then(response => response.json())
.then(data => console.log(data));
```

### Axios
```javascript
axios.post('http://localhost:8080/api/projects', {
  storyPrompt: '一个热血玄幻故事',
  genre: '热血玄幻',
  targetAudience: '青少年',
  totalEpisodes: 20,
  episodeDuration: 180
}, {
  headers: {
    'Authorization': 'Bearer ' + token
  }
})
.then(response => console.log(response.data));
```

### cURL
```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "storyPrompt": "一个热血玄幻故事",
    "genre": "热血玄幻",
    "targetAudience": "青少年",
    "totalEpisodes": 20,
    "episodeDuration": 180
  }'
```

---

## 🔒 Token 刷新机制

### Access Token 过期
当 Access Token 过期时（默认2小时），会收到 401 错误。

### 刷新 Token
使用 RefreshToken 获取新的 Access Token：

```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "your_refresh_token"
  }'
```

**响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "新的access_token",
    "refreshToken": "新的refresh_token"
  }
}
```

---

## ⚠️ 常见问题

### 1. Token 无效
**错误信息**：`401 Unauthorized`
**原因**：
- Token 过期
- Token 格式错误
- Token 被加入黑名单（用户已登出）

**解决**：重新登录获取新 Token

### 2. Apifox 显示锁图标但无法设置 Token
**原因**：未正确导入鉴权组件

**解决**：
1. 检查是否在导入时勾选了 "自动创建鉴权组件"
2. 手动创建鉴权组件

### 3. Swagger UI 中没有锁图标
**原因**：`@SecurityRequirement` 注解未生效

**解决**：
1. 检查 `SwaggerConfig.java` 中的 `@SecurityScheme` 配置
2. 重启应用

---

## 📚 相关文档

- [AI 服务架构文档](AI_SERVICE_ARCHITECTURE.md)
- [数据库设计文档](DATABASE_SCHEMA.md)
- [API 文档](http://localhost:8080/swagger-ui.html)

---

## ✅ 检查清单

使用 Apifox 测试接口前，请确认：

- [ ] 已导入 OpenAPI 文档到 Apifox
- [ ] 已自动创建 "bearerAuth" 鉴权组件
- [ ] 已注册用户账号
- [ ] 已登录获取 Token
- [ ] 已在 Apifox 中配置 Token
- [ ] Token 格式正确（包含 "Bearer " 前缀）

---

## 🎯 快速测试

### 1. 测试登录接口（不需要 Token）
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'
```

### 2. 测试需要认证的接口（需要 Token）
```bash
# 先替换 <your_token> 为实际 Token
curl -X GET http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <your_token>"
```

---

**配置完成后，Apifox 会自动为所有需要认证的接口添加 Authorization header！** 🚀
