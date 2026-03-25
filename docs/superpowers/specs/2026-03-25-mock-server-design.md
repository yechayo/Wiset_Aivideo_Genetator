# Mock Server 设计文档

## 目标

为 Wiset 前端开发提供一个独立的 Mock 服务器，模拟后端 REST API 和第三方 AI 服务，支持完整的 6 步流水线工作流调试。

## 技术选型

- **运行时**: Node.js + TypeScript
- **HTTP 框架**: Express
- **JWT**: jsonwebtoken（HS256，与后端一致）
- **数据生成**: @faker-js/faker（zh_CN locale）
- **ID 生成**: uuid
- **SSE 支持**: 原生 Express Response
- **包管理**: pnpm

## 项目结构

```
mock-server/
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts                    # 入口，启动 Express 服务
│   ├── config.ts                   # 端口、延迟配置等
│   ├── app.ts                      # Express app + 中间件注册
│   ├── middleware/
│   │   ├── auth.ts                 # JWT Mock 验证中间件
│   │   └── cors.ts                 # CORS 配置
│   ├── routes/
│   │   ├── auth.ts                 # /api/auth/*
│   │   ├── projects.ts             # /api/projects/*
│   │   ├── characters.ts           # /api/characters/*
│   │   ├── story.ts                # /api/story/*
│   │   ├── episodes.ts             # /api/episodes/*
│   │   ├── files.ts                # /api/files/*
│   │   ├── tasks.ts                # /api/tasks/*
│   │   ├── jobs.ts                 # /api/jobs/*（含 SSE）
│   │   ├── config.ts               # /api/config/*
│   │   └── debug.ts                # /api/__mock/*（调试辅助）
│   ├── state/
│   │   ├── store.ts                # 内存数据存储
│   │   ├── machine.ts              # 状态机：6 步流水线状态转换
│   │   └── seed.ts                 # 初始 Mock 数据生成
│   ├── services/
│   │   ├── delay.ts                # 模拟异步延迟
│   │   ├── jwt.ts                  # Mock JWT 签发/验证
│   │   └── media.ts                # 动态 SVG 占位图生成
│   └── types/
│       └── index.ts                # 共享类型定义
└── data/
    └── placeholder/                # 占位媒体文件（可选）
```

## 状态机设计

### 状态流转

```
CREATED
  → SCRIPT_GENERATING → SCRIPT_REVIEW → SCRIPT_CONFIRMED
  → CHARACTER_EXTRACTING → CHARACTER_REVIEW → CHARACTER_CONFIRMED
  → STORYBOARD_GENERATING → STORYBOARD_REVIEW → STORYBOARD_CONFIRMED
  → PRODUCING → PRODUCTION_REVIEW → COMPLETED
```

### 规则

- 每个状态对应一组可用操作
- 非法状态操作返回 `{ code: 409, message: "当前状态不允许此操作" }`
- 生成操作为异步：返回 taskId，前端轮询 `/api/tasks/{taskId}/status`
- 轮询根据经过时间模拟进度：0% → 30% → 70% → 100%

## 内存数据模型

```typescript
interface Store {
  users: Map<string, MockUser>
  projects: Map<string, MockProject>
  characters: Map<string, MockCharacter[]>   // projectId → characters
  episodes: Map<string, MockEpisode[]>       // projectId → episodes
  productions: Map<string, MockProduction>   // episodeId → production
  jobs: Map<string, MockJob>
  tasks: Map<string, MockTask>
  files: Map<string, MockFile>
}

interface MockProject {
  projectId: string
  userId: string
  storyPrompt: string
  genre: string
  status: ProjectStatus
  scriptOutline: string
  selectedChapter: number
  totalEpisodes: number
  episodeDuration: number
  visualStyle: string
  scriptRevisionNote?: string
  createdAt: string
  updatedAt: string
}

interface MockCharacter {
  charId: string
  projectId: string
  name: string
  role: string
  personality: string
  appearance: string
  background: string
  confirmed: boolean
  locked: boolean
  expressionStatus: string
  threeViewStatus: string
  expressionGridUrl: string
  threeViewGridUrl: string
  visualStyle: string
}

interface MockEpisode {
  episodeId: string
  projectId: string
  episodeNum: number
  title: string
  outlineNode: string
  storyboardJson: string
  content: string
  characters: string[]
  status: string
  productionStatus: string
  finalVideoUrl?: string
}

interface MockTask {
  taskId: string
  taskType: string
  projectId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  progress: number
  result?: any
  createdAt: string
  startedAt?: string
  completedAt?: string
}
```

## API Mock 行为

### 认证（/api/auth）

| 端点 | Mock 行为 |
|------|----------|
| `POST /register` | 任意用户名密码成功，创建用户，返回 JWT |
| `POST /login` | 任意用户名密码成功，返回 JWT（accessToken + refreshToken） |
| `POST /logout` | 成功，token 加入黑名单 |
| `POST /refresh` | 验证 refreshToken，返回新 accessToken |
| `GET /me` | 从 token 解析用户信息返回 |

### 项目（/api/projects）

| 端点 | Mock 行为 |
|------|----------|
| `POST /` | 创建项目，状态 CREATED，faker 生成脚本大纲 |
| `GET /{id}` | 返回项目基本信息 |
| `GET /{id}/status` | 返回当前状态 + availableActions |
| `GET /` | 返回用户项目列表 |
| `POST /{id}/generate-script` | 状态 → SCRIPT_GENERATING，返回 taskId |
| `POST /{id}/generate-episodes` | 生成 Mock 分集，返回 taskId |
| `GET /{id}/script` | 返回 Mock 脚本内容 |
| `POST /{id}/confirm-script` | 校验状态为 SCRIPT_REVIEW，→ SCRIPT_CONFIRMED |
| `POST /{id}/revise-script` | 重新生成脚本大纲，返回 taskId |
| `PATCH /{id}/script-outline` | 直接保存大纲文本 |
| `POST /{id}/generate-all-episodes` | 批量生成所有分集 |
| `POST /{id}/advance` | 跳过当前步骤，快速推进 |

### 角色（/api/characters）

| 端点 | Mock 行为 |
|------|----------|
| `POST /extract` | 从脚本提取 3-5 个 Mock 角色（faker 中文姓名/性格/外貌） |
| `GET /` | 返回项目角色列表 |
| `PUT /{charId}` | 更新角色信息 |
| `POST /confirm` | 锁定所有角色 |
| `POST /{charId}/generate-all` | 返回 taskId，完成后设置 SVG URL |
| `POST /{charId}/generate-expression` | 生成表情包，返回 taskId |
| `POST /{charId}/generate-three-view` | 生成三视图，返回 taskId |
| `PUT /{charId}/visual-style` | 设置视觉风格 |
| `POST /{charId}/retry/{type}` | 重试生成，返回 taskId |
| `GET /{charId}/status` | 返回角色生成状态 |

### 分镜（/api/story）

| 端点 | Mock 行为 |
|------|----------|
| `POST /start-storyboard` | 逐集生成分镜，返回 jobId + SSE |
| `GET /storyboard/{episodeId}` | 返回 Mock 分镜 JSON（4-6 panel） |
| `GET /episodes` | 返回分集列表 |
| `POST /confirm-storyboard` | 确认当前分镜 |
| `POST /revise-storyboard` | 重新生成，返回新 taskId |
| `POST /retry-storyboard` | 重试失败分镜 |
| `POST /start-production` | 启动视频制作 |

### 视频制作（/api/episodes）

| 端点 | Mock 行为 |
|------|----------|
| `POST /{id}/produce` | 启动制作流水线，返回 productionId |
| `GET /project/{projectId}/pipeline` | 返回流水线状态 |
| `GET /{id}/production-status` | 模拟多阶段进度 |
| `GET /{id}/grid-info` | 返回 Mock 融合信息 |
| `POST /{id}/fusion-image` | 接收上传，返回 Mock fileUrl |
| `POST /{id}/submit-fusion` | 确认融合结果 |
| `POST /{id}/submit-fusion-page` | 提交分页融合 |
| `POST /{id}/split-grid-page` | 模拟网格拆分 |
| `GET /{id}/video-segments` | 返回视频片段列表 |
| `GET /{id}/panel-states` | 每个 panel 的融合/视频状态 |
| `POST /{id}/panels/{index}/generate-video` | 单面板重新生成 |
| `POST /{id}/auto-continue` | 手动继续流水线 |
| `POST /{id}/retry-production` | 重试失败制作 |

### 文件（/api/files）

| 端点 | Mock 行为 |
|------|----------|
| `POST /upload` | 接收文件，返回 Mock fileId + URL |
| `POST /upload/base64` | 接收 base64，返回 Mock fileId + URL |
| `GET /{fileId}/url` | 返回占位图 URL |
| `DELETE /{fileId}` | 从内存移除 |

### 任务（/api/tasks）

| 端点 | Mock 行为 |
|------|----------|
| `GET /{taskId}/status` | 根据经过时间返回模拟进度 |
| `GET /project/{projectId}` | 返回项目任务列表 |
| `POST /{taskId}/cancel` | 标记任务取消 |

### 任务流（/api/jobs）

| 端点 | Mock 行为 |
|------|----------|
| `GET /{jobId}/progress` | SSE 推送进度事件 |
| `GET /{jobId}` | 返回 Job 状态 |

### 配置（/api/config）

| 端点 | Mock 行为 |
|------|----------|
| `GET /` | 返回默认配置列表 |
| `GET /{key}` | 返回配置值 |
| `POST /` | 保存配置到内存 |
| `DELETE /{key}` | 删除配置 |

## Mock 媒体服务

### 动态 SVG 占位图

`GET /mock-media/{type}?params` 根据类型生成不同风格 SVG：

| 类型 | 参数 | 样式 |
|------|------|------|
| `character` | name, role | 蓝色调人物剪影 + 角色名 |
| `expression-sheet` | name | 3x3 网格，每格不同表情标注 |
| `three-view` | name | 三视图布局（正面/侧面/背面） |
| `storyboard-panel` | desc, index | 场景描述 + 画面编号 |
| `storyboard-grid` | episodeNum | 4 宫格分镜总览 |
| `scene-fusion` | desc | 融合参考图占位 |
| `thumbnail` | text | 通用缩略图 |

SVG 特点：带场景描述文字，不同类型用不同配色，方便调试时识别。

### 视频

所有视频生成操作完成后返回同一个公共短视频 URL，前端播放器可正常工作。

## 调试辅助接口

| 端点 | 说明 |
|------|------|
| `POST /api/__mock/reset` | 清空所有内存数据，重置为初始状态 |
| `GET /api/__mock/state` | 查看所有项目的状态机状态 |
| `POST /api/__mock/advance/{projectId}` | 一键推进项目到指定步骤 |
| `GET /api/__mock/dump` | 导出当前内存数据为 JSON |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MOCK_PORT` | `8081` | 服务端口 |
| `MOCK_DELAY_MS` | `2000` | 异步操作延迟（毫秒） |
| `MOCK_JWT_SECRET` | `mock-secret` | JWT 签名密钥 |
| `MOCK_AUTO_COMPLETE` | `true` | 轮询是否自动完成 |

## 前端对接

```bash
# .env.mock
VITE_API_BASE_URL=http://localhost:8081
```

前端切换到 Mock 模式只需修改 API base URL 指向 `localhost:8081`。

## 启动方式

```bash
cd mock-server
pnpm install
pnpm dev          # 开发模式（nodemon 热重载）
pnpm start        # 生产模式
```
