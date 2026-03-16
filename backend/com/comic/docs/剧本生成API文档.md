# 剧本生成两级架构 API 文档

## 概述

本文档描述剧本生成功能的两级架构 API 使用方式：

1. **第一级**：生成剧本大纲（Markdown格式，包含角色、物品、章节结构）
2. **第二级**：选择章节生成具体剧集（JSON格式）

## 认证

所有 API 需要在 Header 中携带 JWT Token：

```
Authorization: Bearer {token}
```

---

## API 使用流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        完整使用流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 创建项目                                                    │
│     POST /api/projects                                          │
│     └─→ 返回 projectId                                          │
│                                                                 │
│  2. 生成剧本大纲                                                │
│     POST /api/projects/{projectId}/generate-script              │
│     └─→ 状态变为 OUTLINE_REVIEW                                 │
│                                                                 │
│  3. 查看大纲和章节列表                                          │
│     GET /api/projects/{projectId}/script                        │
│     └─→ 返回 outline, chapters, nextChapter                     │
│                                                                 │
│  4. 按顺序生成章节剧集（循环）                                  │
│     POST /api/projects/{projectId}/generate-episodes            │
│     └─→ 每次生成一个章节，必须顺序生成                          │
│                                                                 │
│  5. 查看生成的剧集                                              │
│     GET /api/projects/{projectId}/script                        │
│     └─→ 返回 episodes 列表                                      │
│                                                                 │
│  6. 确认剧本（全部章节生成后）                                  │
│     POST /api/projects/{projectId}/confirm-script               │
│     └─→ 状态变为 SCRIPT_CONFIRMED                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## API 详细说明

### 1. 创建项目

**请求**
```
POST /api/projects
Content-Type: application/json
Authorization: Bearer {token}
```

**请求体**
```json
{
  "storyPrompt": "一个少年的修仙之路，从凡人到成仙的传奇故事",
  "genre": "玄幻",
  "targetAudience": "18-35岁男性",
  "totalEpisodes": 12,
  "episodeDuration": 60
}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| storyPrompt | String | 是 | 故事核心创意/提示词 |
| genre | String | 否 | 类型（如：玄幻、都市、悬疑） |
| targetAudience | String | 否 | 目标受众 |
| totalEpisodes | Integer | 是 | 总集数 |
| episodeDuration | Integer | 否 | 单集时长（秒） |

**响应**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "projectId": "proj_abc123"
  }
}
```

---

### 2. 生成剧本大纲

**请求**
```
POST /api/projects/{projectId}/generate-script
Authorization: Bearer {token}
```

**说明**
- 调用 AI 生成 Markdown 格式的剧本大纲
- 大纲包含：剧名、角色小传、物品设定、章节结构
- 状态变为 `OUTLINE_REVIEW`

**响应**
```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

---

### 3. 获取剧本内容（大纲 + 剧集）

**请求**
```
GET /api/projects/{projectId}/script
Authorization: Bearer {token}
```

**响应**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "project": {
      "projectId": "proj_abc123",
      "status": "OUTLINE_REVIEW",
      "totalEpisodes": 12,
      "scriptOutline": "# 剧名\n**一句话梗概**: ...",
      "episodesPerChapter": 4
    },
    "outline": "# 剧名\n**一句话梗概**: ...\n\n## 主要人物小传\n...",
    "chapters": [
      "#### 第1章：觉醒（第1-4集）",
      "#### 第2章：修炼（第5-8集）",
      "#### 第3章：试炼（第9-12集）"
    ],
    "generatedChapters": ["#### 第1章：觉醒（第1-4集）"],
    "pendingChapters": [
      "#### 第2章：修炼（第5-8集）",
      "#### 第3章：试炼（第9-12集）"
    ],
    "nextChapter": "#### 第2章：修炼（第5-8集）",
    "episodes": [
      {
        "id": 1,
        "episodeNum": 1,
        "title": "第1集：少年觉醒",
        "content": "剧本内容...",
        "characters": "林风,王师傅",
        "keyItems": "玄铁剑",
        "chapterTitle": "#### 第1章：觉醒（第1-4集）"
      }
    ]
  }
}
```

**字段说明**

| 字段 | 说明 |
|------|------|
| outline | Markdown 格式的完整大纲 |
| chapters | 所有章节列表（从大纲解析） |
| generatedChapters | 已生成剧集的章节 |
| pendingChapters | 待生成剧集的章节 |
| nextChapter | 下一个待生成的章节（用于前端自动选择） |
| episodes | 已生成的剧集列表 |

---

### 4. 生成指定章节的剧集

**请求**
```
POST /api/projects/{projectId}/generate-episodes
Content-Type: application/json
Authorization: Bearer {token}
```

**请求体**
```json
{
  "chapter": "#### 第1章：觉醒（第1-4集）",
  "episodeCount": 4,
  "modificationSuggestion": "可选：希望本章节奏更快一些"
}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| chapter | String | 是 | 要生成的章节（从 chapters 列表中选择） |
| episodeCount | Integer | 否 | 拆分集数，默认 4 |
| modificationSuggestion | String | 否 | 修改建议 |

**重要规则**
- **必须顺序生成**：只能生成 `nextChapter` 指向的章节
- 如果尝试跳过章节，会返回错误

**响应**
```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

**错误响应**
```json
{
  "code": 400,
  "message": "必须顺序生成章节。下一个待生成的章节是：#### 第2章：修炼（第5-8集）",
  "data": null
}
```

---

### 5. 确认剧本

**请求**
```
POST /api/projects/{projectId}/confirm-script
Authorization: Bearer {token}
```

**前置条件**
- 所有章节的剧集都已生成
- 状态为 `SCRIPT_REVIEW`

**响应**
```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

---

### 6. 修改剧本

#### 6.1 修改大纲

**请求**
```
POST /api/projects/{projectId}/revise-script
Content-Type: application/json
Authorization: Bearer {token}
```

**请求体**
```json
{
  "revisionNote": "希望主角性格更加果断，减少犹豫的情节"
}
```

**说明**
- 不传 `chapter` 字段时，修改大纲
- 修改大纲会**删除所有已生成的剧集**，需要重新生成

#### 6.2 修改指定章节的剧集

**请求体**
```json
{
  "revisionNote": "本章打斗场面不够精彩，请增加动作描写",
  "chapter": "#### 第2章：修炼（第5-8集）",
  "episodeCount": 4
}
```

**说明**
- 传入 `chapter` 字段时，只重新生成该章节的剧集
- 不影响其他章节

---

### 7. 获取项目状态

**请求**
```
GET /api/projects/{projectId}
Authorization: Bearer {token}
```

**响应**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "projectId": "proj_abc123",
    "status": "OUTLINE_REVIEW",
    "totalEpisodes": 12,
    "scriptOutline": "...",
    "selectedChapter": null,
    "episodesPerChapter": 4
  }
}
```

---

## 项目状态流转

```
DRAFT                      # 项目创建
    ↓
OUTLINE_GENERATING         # 正在生成大纲
    ↓
OUTLINE_REVIEW             # 大纲待审核（可选择章节生成分集）
    ↓
EPISODE_GENERATING         # 正在生成分集（每次生成一个章节）
    ↓
SCRIPT_REVIEW              # 分集待审核
    ↓
SCRIPT_CONFIRMED           # 剧本确认完成
```

---

## 完整示例

### 场景：创建一个12集的玄幻短剧

```bash
# 1. 创建项目
curl -X POST http://localhost:8080/api/projects \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "storyPrompt": "一个少年的修仙之路",
    "genre": "玄幻",
    "totalEpisodes": 12,
    "episodeDuration": 60
  }'

# 响应: {"code":200,"data":{"projectId":"proj_abc123"}}

# 2. 生成大纲
curl -X POST http://localhost:8080/api/projects/proj_abc123/generate-script \
  -H "Authorization: Bearer {token}"

# 3. 查看大纲
curl http://localhost:8080/api/projects/proj_abc123/script \
  -H "Authorization: Bearer {token}"

# 响应包含 chapters: ["#### 第1章：...", "#### 第2章：...", "#### 第3章：..."]

# 4. 生成第1章剧集
curl -X POST http://localhost:8080/api/projects/proj_abc123/generate-episodes \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"chapter": "#### 第1章：觉醒（第1-4集）", "episodeCount": 4}'

# 5. 生成第2章剧集
curl -X POST http://localhost:8080/api/projects/proj_abc123/generate-episodes \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"chapter": "#### 第2章：修炼（第5-8集）", "episodeCount": 4}'

# 6. 生成第3章剧集
curl -X POST http://localhost:8080/api/projects/proj_abc123/generate-episodes \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"chapter": "#### 第3章：试炼（第9-12集）", "episodeCount": 4}'

# 7. 查看所有剧集
curl http://localhost:8080/api/projects/proj_abc123/script \
  -H "Authorization: Bearer {token}"

# 8. 确认剧本
curl -X POST http://localhost:8080/api/projects/proj_abc123/confirm-script \
  -H "Authorization: Bearer {token}"
```

---

## 常见错误

| 错误信息 | 原因 | 解决方案 |
|---------|------|---------|
| 项目不存在 | projectId 无效 | 检查 projectId 是否正确 |
| 当前状态不能生成大纲 | 状态不是 DRAFT | 先创建新项目 |
| 必须顺序生成章节 | 尝试跳过章节 | 按顺序生成，使用 nextChapter |
| 请先生成所有章节的剧集 | 未完成所有章节 | 继续生成剩余章节 |
| 当前状态不能修改剧本 | 状态不在 SCRIPT_REVIEW | 检查当前状态 |

---

## 数据结构

### Episode 剧集

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| projectId | String | 项目ID |
| episodeNum | Integer | 集数 |
| title | String | 标题 |
| content | String | 剧本内容 |
| characters | String | 涉及角色列表 |
| keyItems | String | 关键物品列表 |
| continuityNote | String | 连贯性说明 |
| chapterTitle | String | 所属章节标题 |
| status | String | 状态 |
| storyboardJson | String | 分镜数据（分镜阶段使用） |

### Project 项目

| 字段 | 类型 | 说明 |
|------|------|------|
| projectId | String | 项目ID |
| status | String | 当前状态 |
| scriptOutline | String | 剧本大纲（Markdown） |
| selectedChapter | String | 当前选中章节 |
| episodesPerChapter | Integer | 每章集数 |
| totalEpisodes | Integer | 总集数 |
| storyPrompt | String | 故事提示词 |
| genre | String | 类型 |
| targetAudience | String | 目标受众 |
