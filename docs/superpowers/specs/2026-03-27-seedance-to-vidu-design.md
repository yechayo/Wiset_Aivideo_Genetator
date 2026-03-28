# Design: 将 Seedance 视频生成替换为 Vidu

## 背景

当前项目使用火山引擎 Seedance API 生成视频。需要完全替换为 Vidu API（图生视频）。

## 决策

- **完全替换**：删除 Seedance 实现，新建 Vidu 实现，不做并存/切换
- **默认模型**：`viduq3-turbo`
- **功能范围**：只保留文档中覆盖的核心功能（图生视频、查询、取消），裁剪 Draft 样片、批量查询等

## 文件变更

| 操作 | 文件 | 说明 |
|------|------|------|
| 删除 | `SeedanceVideoService.java` | 旧的 Seedance 实现 |
| 新建 | `ViduVideoService.java` | 新的 Vidu 实现，实现 `VideoGenerationService` 接口 |
| 修改 | `ArkProperties.java` → `ViduProperties.java` | 改为 Vidu 配置（apiKey, baseUrl, model） |
| 修改 | `application.yml` | 替换 `comic.ark` 配置段为 `comic.vidu` |

## API 对照

| | Seedance (当前) | Vidu (目标) |
|---|---|---|
| 创建 | `POST ark.cn-beijing.volces.com/api/v3/contents/generations/tasks` | `POST api.vidu.cn/ent/v2/img2video` |
| 查询 | `GET .../contents/generations/tasks/{taskId}` | `GET api.vidu.cn/ent/v2/tasks/{id}/creations` |
| 取消 | `DELETE .../contents/generations/tasks/{taskId}` | `POST api.vidu.cn/ent/v2/tasks/{id}/cancel` |
| 认证 | `Bearer {api_key}` | `Token {api_key}` |
| 创建返回 ID | `id` | `task_id` |
| 状态字段 | `status`: queued/running/succeeded/failed | `state`: created/queueing/processing/success/failed |
| 视频 URL | `content.video_url` | `creations[0].url` |
| 错误信息 | `error.message` | `err_code` |

## ViduVideoService 实现

实现 `VideoGenerationService` 接口的四个方法：

### generateAsync
- `POST https://api.vidu.cn/ent/v2/img2video`
- 请求体：`model`, `images`（首帧图 URL 数组）, `prompt`, `duration`, `resolution`, `watermark=false`
- 返回 `task_id`

### getTaskStatus
- `GET https://api.vidu.cn/ent/v2/tasks/{id}/creations`
- 解析 `state` → 统一状态，`creations[0].url` → videoUrl

### downloadVideo
- 复用 `getTaskStatus` 取 URL

### cancelOrDeleteTask（保留为 public 方法）
- `POST https://api.vidu.cn/ent/v2/tasks/{id}/cancel`
- 请求体：`{ "id": "taskId" }`

## 状态映射

| Vidu state | 统一状态 | 进度 |
|---|---|---|
| created | pending | 10 |
| queueing | pending | 20 |
| processing | processing | 50 |
| success | completed | 100 |
| failed | failed | 0 |

## 配置

`ViduProperties`（`comic.vidu` 前缀）：
- `apiKey`：占位符，后续用户自行填写
- `baseUrl`：`https://api.vidu.cn/ent/v2`
- `model`：`viduq3-turbo`

## 不变的部分

- `VideoGenerationService` 接口
- `PanelProductionService`（通过接口调用）
- `PanelController`
- 前端代码
