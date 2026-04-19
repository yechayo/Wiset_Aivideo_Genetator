# 可灵 v3 视频生成集成设计

## 概述

将可灵（Kling）v3 图生视频模型集成为新的视频生成 provider，支持单镜头和多镜头视频生成。

## 目标

- 新增 `kling` provider，支持 `kling-v3` 模型
- 支持单镜头视频（1个 shot）和多镜头视频（2+ shots）
- 最小化对已有代码的影响：Vidu/Grok 零修改

## 架构

### 新增文件

| 文件 | 路径 | 职责 |
|------|------|------|
| `KlingProperties.java` | `com.comic.config` | 可灵 API 配置（apiKey, baseUrl, modelName, mode） |
| `KlingVideoService.java` | `com.comic.ai.video` | 实现 `VideoGenerationService`，对接可灵 API |

### 修改文件（最小改动）

| 文件 | 改动 |
|------|------|
| `VideoGenerationService.java` | 新增 `generateAsyncMultiShot()` default 方法和 `MultiShotPrompt` 内部类 |
| `AiServiceConfiguration.java` | 构造函数加 `KlingVideoService` 参数 + 注册 `"kling"` provider |
| `PanelProductionService.java` | `doGenerateVideoByPanelId()` 加 kling 多镜头分支 |
| `application.yml` | 新增 `comic.kling` 配置段 |

## 数据流

### 多镜头模式（provider=kling, shots>=2）

```
PanelProductionService.doGenerateVideoByPanelId()
  ├── panel.shots → List<MultiShotPrompt>(prompt, duration)
  ├── KlingVideoService.generateAsyncMultiShot(image, multiPrompts, totalDuration, model)
  │     ├── 构建 multi_shot=true, shot_type="customize" 请求
  │     ├── multi_prompt = [{index, prompt, duration}, ...]
  │     ├── POST /v1/videos/image2video → task_id
  │     └── 返回 taskId
  └── pollNewVideoTask() → getTaskStatus() → TaskStatus（统一格式）
```

### 单镜头模式（provider=kling, shots=1）

```
PanelProductionService.doGenerateVideoByPanelId()
  └── KlingVideoService.generateAsync(prompt, duration, aspectRatio, image, offPeak, model)
        ├── 构建 multi_shot=false 请求
        ├── POST /v1/videos/image2video → task_id
        └── 返回 taskId
```

## KlingVideoService 实现

### API 端点

| 操作 | 方法 | URL |
|------|------|-----|
| 提交任务 | POST | `{baseUrl}/v1/videos/image2video` |
| 查询任务 | GET | `{baseUrl}/v1/videos/image2video/{task_id}` |

### 请求参数映射

| 项目参数 | 可灵 API 字段 | 说明 |
|---------|--------------|------|
| fusionImageUrl | `image` | 融合参考图 URL |
| model | `model_name` | 固定 "kling-v3" 或由配置/项目设置决定 |
| mode | `mode` | "std" 或 "pro" |
| prompt | `prompt` | 单镜头时的完整提示词 |
| shots | `multi_prompt` | 多镜头结构化数组 |
| totalDuration | `duration` | 总时长（3-15s） |

### 多镜头请求体示例

```json
{
  "model_name": "kling-v3",
  "image": "https://xxx/fusion.jpg",
  "multi_shot": true,
  "shot_type": "customize",
  "multi_prompt": [
    {"index": 1, "prompt": "镜头1描述...", "duration": "3"},
    {"index": 2, "prompt": "镜头2描述...", "duration": "5"}
  ],
  "duration": "8",
  "mode": "std",
  "callback_url": "",
  "external_task_id": ""
}
```

### 鉴权

- Header: `Authorization: Bearer <token>`
- 可灵使用 access_key + secret_key 通过 JWT 签名生成 Bearer token
- token 有效期通常为 1800 秒，需在过期前刷新

### 任务状态映射

| 可灵状态 | 内部状态 |
|---------|---------|
| `submitted` | pending |
| `processing` | processing |
| `succeed` | completed |
| `failed` | failed |

### 响应解析

- 视频URL: `data.task_result.videos[0].url`
- 积分消耗: `data.final_unit_deduction`（字符串，需转整数）

### 并发控制

- `Semaphore(1)` 控制并发，与 Vidu 一致

### 错误处理

- HTTP 非 200：记录错误码和响应体，抛出 RuntimeException
- 任务失败：从 `task_status_msg` 提取错误信息

## VideoGenerationService 接口扩展

### 新增 MultiShotPrompt 内部类

```java
class MultiShotPrompt {
    private final String prompt;    // 镜头描述
    private final int duration;     // 镜头时长（秒）

    MultiShotPrompt(String prompt, int duration) {
        this.prompt = prompt;
        this.duration = duration;
    }
    // getters...
}
```

### 新增 generateAsyncMultiShot default 方法

```java
default String generateAsyncMultiShot(String referenceImage,
                                       List<MultiShotPrompt> multiPrompts,
                                       int totalDuration,
                                       String model) {
    throw new UnsupportedOperationException("多镜头视频生成未实现");
}
```

- 作为 default 方法，Vidu/Grok 实现无需任何修改
- 仅 `KlingVideoService` 覆写此方法

## PanelProductionService 改动

仅在 `doGenerateVideoByPanelId()` 方法的视频服务调用处（约第 556 行）加分支判断：

```java
String taskId;
if ("kling".equals(getVideoProvider(projectId)) && shots != null && shots.size() > 1) {
    // Kling 多镜头路径
    List<VideoGenerationService.MultiShotPrompt> multiPrompts = ...;
    taskId = videoService.generateAsyncMultiShot(fusionImageUrl, multiPrompts, totalDuration, videoModel);
} else {
    // 原有路径完全不变
    taskId = videoService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak, videoModel);
}
```

轮询逻辑 `pollNewVideoTask()` 不需要修改，因为 `KlingVideoService.getTaskStatus()` 返回统一的 `TaskStatus`。

## 配置

### application.yml 新增

```yaml
comic:
  kling:
    access-key: ${KLING_ACCESS_KEY:}
    secret-key: ${KLING_SECRET_KEY:}
    base-url: https://api-beijing.klingai.com
    model-name: kling-v3
    mode: std
```

### 项目级配置（projectInfo JSON）

- `VIDEO_PROVIDER`: "kling" — 切换到可灵
- `VIDEO_MODEL`: "kling-v3" 或其他可灵模型名

## 对现有功能的影响评估

| 场景 | 影响 |
|------|------|
| Vidu 单图视频 | 零修改 |
| Vidu 多图参考视频 | 零修改 |
| Grok 视频 | 零修改 |
| Kling 单镜头 | 走 `generateAsync`，与 Vidu 流程一致 |
| Kling 多镜头 | 走新 `generateAsyncMultiShot` 路径 |

## 时长限制

- 可灵 v3 支持 3-15 秒
- 多镜头模式：每个镜头最短 1 秒，所有镜头时长之和等于总时长
- `PanelProductionService` 中现有限制为 max 10s，kling provider 下需放宽到 15s

## 已确认事项

- std/pro 通过 `mode` 参数控制，作为项目级或请求级配置
- 前端需要增加模型选择 UI（kling-v3 std/pro 等选项）
- 前端有多个位置需要单独适配

## 前端适配清单

### Step1Content.tsx（项目创建页）

| 位置 | 改动 |
|------|------|
| `videoProviderOptions` 数组 | 添加 `{ value: 'kling', label: 'Kling V3' }` |
| 模型选项数组 | 新增 `klingModelOptions`：`kling-v3` std/pro |
| 视频模式选择 | Kling 暂仅支持首帧模式，不显示模式切换 |
| 模型选择 UI | provider=kling 时显示 kling 模型选项 |

### Step4Production.tsx（生产页）

| 位置 | 改动 |
|------|------|
| provider 判断 | 新增 `const isKling = videoProvider.toLowerCase() === 'kling'` |
| 提供商 Tab | 添加 Kling 按钮 |
| 模型标签 | provider=kling 时显示 Std/Pro 两个标签 |
| videoModel 存储 | Kling 用完整标识 `kling-v3-std` / `kling-v3-pro` |
| 错峰模式 | Kling 不支持错峰，隐藏错峰开关 |
| 时长限制 | Kling v3 最大 15s，UI 上放宽提示 |

### VideoSegmentRow.tsx（视频片段行）

| 位置 | 改动 |
|------|------|
| 模型标签显示 | 兼容 Kling 模型名显示（`kling-v3-std` → "Kling V3 Std"） |

### episodeService.ts

不需要修改 — API 参数已通用（`videoModel` 字段透传）
