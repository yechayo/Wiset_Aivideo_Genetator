# Episode & Panel API 设计

> 日期：2026-03-25
> 范围：Step5 视频生产（Episode CRUD + Panel CRUD + 全部生产逻辑）

## 背景

当前项目中 EpisodeController、StoryController、PanelController 职责混乱：
- EpisodeController 混杂了 CRUD 和大量生产接口
- StoryController 混入了剧集查询（`getEpisodes`）和分镜生成流程
- Panel 只有实体和 Repository，没有独立 Controller

本次设计将职责重新划分：
- **EpisodeController** → 纯 CRUD，与 CharacterController 风格统一
- **PanelController** → CRUD + 全部视频生产逻辑（从 EpisodeController 和 StoryController 迁入）
- **StoryController** → 废弃，职责拆分完毕后整体删除

## 数据模型

```
Project → Episode → Panel (分镜)
```

- **Episode**：剧集，包含多个分镜。支持完整 CRUD。
- **Panel**：分镜，视频生成的最小单位。前端文档中的 Segment = 后端的 Panel。
  - 每个 Panel 产出：1 背景图 + N 角色参考图 + 1 四宫格漫画 + 1 视频片段
  - 四宫格由 AI 一键生成（背景 + 角色 → 2×2 融合图 + 序号标注），是唯一审核点

## 生产流程（单 Panel）

```
① 背景图生成（自动）→ ② 匹配角色资产（自动）→ ③ 四宫格漫画（审核点）→ ④ AI 视频生成（手动触发）
```

1. **背景图**：根据分镜的 `scene_description` 调用图像生成 API
2. **匹配角色**：从 Step4 生成的角色素材中自动匹配该分镜涉及的角色
3. **四宫格漫画**：背景图 + 角色参考图 → AI 一键生成 2×2 融合图（带序号），用户审核
4. **AI 视频**：四宫格漫画 → 视频大模型（参考图）→ 视频片段

## API 路径规范

全部嵌套在 `/api/projects/{projectId}` 下，与 CharacterController 风格统一。

### EpisodeController

路径：`/api/projects/{projectId}/episodes`

#### CRUD

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 剧集列表（分页，支持 name 搜索） |
| GET | `/{episodeId}` | 剧集详情 |
| POST | `/` | 创建剧集 |
| PUT | `/{episodeId}` | 更新剧集（部分更新） |
| DELETE | `/{episodeId}` | 删除剧集（逻辑删除） |

### PanelController

路径：`/api/projects/{projectId}/episodes/{episodeId}/panels`

#### 分镜 CRUD

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 分镜列表 |
| GET | `/{panelId}` | 分镜详情 |
| POST | `/` | 创建分镜 |
| PUT | `/{panelId}` | 更新分镜（部分更新） |
| DELETE | `/{panelId}` | 删除分镜（逻辑删除） |

#### 分镜生成流程（从 StoryController 迁入）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/generate` | AI 生成分镜（LLM 生成 + 镜头增强） |
| GET | `/generate/{jobId}/status` | 分镜生成任务状态轮询 |
| POST | `/{panelId}/confirm` | 确认分镜内容 |
| POST | `/{panelId}/revise` | 修改分镜（带修改建议 feedback） |
| POST | `/{panelId}/retry` | 重试失败的生成 |

#### 生产状态查询

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{panelId}/production-status` | 单 Panel 完整生产状态（背景/四宫格/视频） |
| GET | `/production-statuses` | 批量获取该 Episode 下所有 Panel 的生产状态 |

#### 背景图生成

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{panelId}/background` | 获取背景图状态 + URL |
| POST | `/{panelId}/background` | 生成背景图（自动匹配角色） |
| POST | `/{panelId}/background/regenerate` | 重新生成背景图 |

#### 四宫格漫画（AI 融合，审核点）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{panelId}/comic` | 获取四宫格状态 + URL |
| POST | `/{panelId}/comic` | 生成四宫格漫画（背景 + 角色 → AI 融合） |
| POST | `/{panelId}/comic/approve` | 审核通过四宫格 |
| POST | `/{panelId}/comic/revise` | 退回重生成（带修改建议 feedback） |

#### AI 视频生成

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{panelId}/video` | 获取视频状态 + URL |
| POST | `/{panelId}/video` | 生成视频（四宫格 → 视频大模型） |
| POST | `/{panelId}/video/retry` | 重试失败的视频生成 |

## 归属校验

嵌套路由在 Service 层校验归属关系，查询和校验一步完成：

```java
// EpisodeRepository — 校验 episode 归属于 project
default Episode findByProjectIdAndId(String projectId, Long episodeId) {
    return selectOne(new LambdaQueryWrapper<Episode>()
        .eq(Episode::getProjectId, projectId)
        .eq(Episode::getId, episodeId));
}

// PanelRepository — 校验 panel 归属于 episode
default Panel findByEpisodeIdAndId(Long episodeId, Long panelId) {
    return selectOne(new LambdaQueryWrapper<Panel>()
        .eq(Panel::getEpisodeId, episodeId)
        .eq(Panel::getId, panelId));
}
```

查不到即归属不匹配或不存在，Controller 统一返回 404。

## Service 层变更

### 新建

- **ComicGenerationService**：四宫格 AI 融合生成（背景图 + 角色参考图 → 调用图像大模型 → 2×2 四宫格）
- **PanelProductionService**（重写）：按新的 4 步流水线编排（背景 → 角色 → 四宫格 → 视频）

### 保留

- **StoryboardService**：分镜生成/增强逻辑，被 PanelController 的 `/generate` 接口调用
- **ImageGenerationService**：图像生成能力，被背景图和四宫格生成复用
- **SeedanceVideoService**：视频生成能力，被视频生成复用

### 删除

- **GridSplitService**：九宫格切分逻辑，新流程不再需要
- **EpisodeController 中的融合上传/提交融合页/切分网格等接口**：手动 Canvas 融合流程废弃

### 废弃 Controller

- **StoryController**：职责拆分完毕后整体删除。`getEpisodes` 移到 EpisodeController，分镜生成流程移到 PanelController

## 不在本次范围

- Step6（视频拼接/下载）— 后续迭代
- Episode 逻辑删除字段添加 — 当前 Episode 实体无 `@TableLogic`，本次不加
- 前端 API 适配 — 本次只设计后端接口

## 接口总数

| Controller | 数量 | 说明 |
|-----------|------|------|
| EpisodeController | 5 | CRUD |
| PanelController | 23 | CRUD(5) + 分镜生成(5) + 状态查询(2) + 背景图(3) + 四宫格(4) + 视频(3) |
| **合计** | **28** | |