# Project Controller 重构设计

## 背景

当前 `ProjectController` 混合了项目 CRUD、剧本管理和流水线编排，包含 12+ 个接口。本次重构：

1. 通过 REST 子资源路径拆分职责（ProjectController + ScriptController）
2. 三个实体（Project、Episode、Character）统一迁移为 Map 存储，重写所有依赖 Service
3. 剧本大纲改为 AI 输出结构化 JSON
4. 移除用户可见的章节概念 — 确认大纲后自动分批生成剧集
5. 新增状态回退，按阶段精确清理数据

## 设计决策

1. **Map 结构实体** — Project、Episode、Character 业务数据统一使用 `Map<String, Object>`（projectInfo、episodeInfo、characterInfo），所有依赖 Service 重写为 Map 访问
2. **结构化 JSON 大纲** — AI 输出扁平 JSON（含 `characters`、`items`、`episodes` 数组），存入 `projectInfo["script"]`
3. **无用户可见章节** — 确认大纲后自动分批生成剧集（每批 2-4 集），章节仅为内部实现细节
4. **逻辑删除** — Project 新增 `deleted` 字段（Boolean，默认 false），ProjectRepository 所有查询自动过滤 `deleted = false`
5. **状态回退** — `advance` 接口支持 `direction: "backward"`，按阶段边界精确清理数据
6. **Controller 拆分** — ProjectController 负责 CRUD + 状态/流水线，ScriptController 负责剧本操作

## 数据模型

### Project 实体

独立字段：`id`、`projectId`、`userId`、`status`、`deleted`（新增）、`createdAt`、`updatedAt`

`projectInfo` Map key 结构：
```json
{
  "storyPrompt": "故事提示词",
  "genre": "热血玄幻",
  "targetAudience": "18-30",
  "totalEpisodes": 10,
  "episodeDuration": 60,
  "visualStyle": "ANIME",
  "script": {
    "outline": "Markdown 全文（供前端展示）",
    "characters": [
      { "name": "林墨", "role": "主角", "personality": "冷静沉稳", "appearance": "黑发、深色风衣", "background": "前特工" }
    ],
    "items": [
      { "name": "暗影令牌", "description": "控制暗影组织的信物" }
    ],
    "episodes": [
      { "ep": 1, "title": "暗夜书店", "synopsis": "林墨的书店迎来神秘客人...", "characters": ["林墨"], "keyItems": ["暗影令牌"] }
    ]
  }
}
```

### Episode 实体

独立字段：`id`、`projectId`、`status`、`createdAt`、`updatedAt`

`episodeInfo` Map key 结构：
```json
{
  "episodeNum": 1,
  "title": "暗夜书店",
  "content": "完整剧本内容",
  "characters": "涉及角色文本",
  "keyItems": "关键物品文本",
  "continuityNote": "连贯性备注",
  "visualStyleNote": "视觉风格备注",
  "synopsis": "大纲中的梗概（从 script.episodes 复制）",
  "chapterTitle": "内部批次标签",
  "retryCount": 0,
  "storyboardJson": "分镜 JSON（由 StoryboardService 填充）",
  "errorMsg": "错误信息或 null",
  "productionStatus": "NOT_STARTED（由 EpisodeProductionService 填充）"
}
```

### Character 实体

独立字段：`id`、`projectId`、`status`、`createdAt`、`updatedAt`

`characterInfo` Map key 结构：
```json
{
  "charId": "CHAR-xxx",
  "name": "林墨",
  "role": "主角",
  "personality": "冷静沉稳",
  "appearance": "黑发、深色风衣",
  "background": "前特工",
  "images": { "front": "url", "side": "url", "back": "url" },
  "expressionImages": { "happy": "url", "angry": "url" }
}
```

## API 设计

### ProjectController — `/api/projects`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects` | 创建项目 |
| GET | `/api/projects?page&size&status&sortBy&sortOrder` | 项目列表（分页） |
| GET | `/api/projects/{id}` | 项目详情 |
| PUT | `/api/projects/{id}` | 全量更新 |
| PATCH | `/api/projects/{id}` | 部分更新 |
| DELETE | `/api/projects/{id}` | 逻辑删除（设置 deleted=true） |
| GET | `/api/projects/{id}/status` | 状态详情（含导航信息） |
| POST | `/api/projects/{id}/status/advance` | 推进或回退流水线 |

#### POST `/api/projects` — 创建项目

**请求体：** `ProjectCreateRequest`（保持不变）
```json
{ "storyPrompt": "string", "genre": "string", "targetAudience": "string", "totalEpisodes": 10, "episodeDuration": 60, "visualStyle": "ANIME" }
```

**响应：** `{ "data": { "projectId": "PROJ-xxxx" } }`

#### GET `/api/projects` — 项目列表

**查询参数：** `page`（默认 1）、`size`（默认 20）、`status`（可选筛选）、`sortBy`（createdAt|updatedAt）、`sortOrder`（asc|desc）

**响应：** 分页的 `ProjectListItemResponse`

#### PUT/PATCH `/api/projects/{id}` — 更新项目

PUT：全量更新基础信息字段。PATCH：部分更新（仅更新提供的字段）。

#### GET `/api/projects/{id}/status` — 项目状态

**响应：**
```json
{
  "projectId": "PROJ-xxx",
  "currentStep": 4,
  "currentStatus": "CHARACTER_REVIEW",
  "previousStatus": "SCRIPT_CONFIRMED",
  "nextStatus": "CHARACTER_CONFIRMED",
  "canGoBack": true,
  "canAdvance": true,
  "isGenerating": false,
  "isFailed": false,
  "isReview": true,
  "completedSteps": [1, 2, 3],
  "stepHistory": [
    { "step": 1, "status": "OUTLINE_GENERATED", "label": "剧本大纲", "confirmed": true },
    { "step": 2, "status": "SCRIPT_CONFIRMED", "label": "剧本确认", "confirmed": true },
    { "step": 3, "status": "CHARACTER_EXTRACTED", "label": "角色提取", "confirmed": true },
    { "step": 4, "status": "CHARACTER_REVIEW", "label": "角色审核", "confirmed": false }
  ],
  "productionProgress": null,
  "storyboardProgress": null
}
```

生产/分镜阶段的丰富数据保留自当前 `ProjectStatusResponse`。

#### POST `/api/projects/{id}/status/advance` — 推进/回退

**前进请求：** `{ "direction": "forward", "event": "characters_confirmed" }`
**回退请求：** `{ "direction": "backward" }`

### ScriptController — `/api/projects/{projectId}/script`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/script` | 获取剧本内容（大纲 + 剧集） |
| POST | `/script/generate` | 生成大纲（AI 输出结构化 JSON） |
| POST | `/script/confirm` | 确认剧本（自动分批生成全部剧集） |
| POST | `/script/revise` | 修改剧本 |
| PATCH | `/script/outline` | 手动保存大纲（清除已有剧集） |

**已移除接口：** `generate-episodes`、`generate-all-episodes`（由确认时自动分批生成替代）

### 不变更的 Controller

EpisodeController、StoryController、CharacterController、FileController、AuthController、ConfigController、JobController、TaskController。

## 状态回退

### 回退清理规则

| 回退目标阶段 | 清理的数据 |
|-------------|-----------|
| OUTLINE_REVIEW | 删除所有 Episode |
| SCRIPT_CONFIRMED | 删除所有 Character |
| CHARACTER_REVIEW | 删除所有 CharacterImage |
| IMAGE_REVIEW | 删除所有 Storyboard |
| ASSET_LOCKED | 删除所有 Production |
| STORYBOARD_REVIEW | 删除所有 Production |

### 自动分批生成

确认剧本时，按以下规则分批生成剧集：

| 总集数 | 每批集数 |
|-------|---------|
| ≤ 3 | 一次性全部 |
| 4-6 | 每批 2 集 |
| 7-12 | 每批 3 集 |
| > 12 | 每批 4 集 |

## 实施阶段

### 阶段一：实体 + deleted + Repository

- Project 实体新增 `deleted` 字段
- ProjectRepository 所有查询加 `deleted = false` 过滤
- ProjectRepository 新增分页查询方法
- 定义 projectInfo / episodeInfo / characterInfo 的 key 常量

### 阶段二：ProjectStatus 枚举 + DTO 更新

- ProjectStatus 枚举新增 `getPreviousStatus()`、`getNextStatus()`、`canGoBack()`、`canAdvance()`、`getStepHistory()`
- ProjectStatusResponse 新增字段：previousStatus、nextStatus、canGoBack、canAdvance、stepHistory
- 新建 AdvanceRequest DTO：`{ direction, event }`
- 新建 ProjectUpdateRequest DTO（PUT/PATCH 请求体）
- 新建 StepHistoryItem DTO

### 阶段三：Service 层重写（核心）

- PipelineService：Map 访问、updateProject、逻辑删除、回退支持
- ScriptService：Map 访问、结构化 JSON 大纲、确认时自动分批生成
- CharacterExtractService：Map 访问
- CharacterImageGenerationService：Map 访问
- StoryboardService：Map 访问
- EpisodeProductionService：Map 访问
- EpisodeController：Map 访问
- PromptBuilder：大纲生成改为输出结构化 JSON

### 阶段四：Controller 重构

- 重构 ProjectController：CRUD + 分页 + 状态 + 回退（移除剧本相关接口）
- 新建 ScriptController：5 个剧本接口
