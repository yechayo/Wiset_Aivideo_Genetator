# Kling Omni 架构重构设计

**日期**: 2026-04-19
**状态**: 已批准

## 背景

当前 Kling 视频生成使用 `image2video` API（`/v1/videos/image2video`），存在两个问题：

1. **融合图作为首帧**：Kling 把参考图当成视频首帧，融合图（分镜网格 + 角色参考）不适合当首帧
2. **声音说英文**：`sound=on` 生成的语音是英文

需要迁移到 Kling Omni API（`/v1/videos/omni-video`），使用多图参考 + `<<<image_N>>>` prompt 引用，彻底弃用融合图和 image2video API。

## API 对比

| 项目 | image2video (旧) | omni-video (新) |
|------|-----------------|-----------------|
| 提交端点 | `POST /v1/videos/image2video` | `POST /v1/videos/omni-video` |
| 查询端点 | `GET /v1/videos/image2video/{id}` | `GET /v1/videos/omni-video/{id}` |
| 图片参数 | `image: "url"` (单张 string) | `image_list: [{image_url}, ...]` (多张 array) |
| 多镜头 | `multi_prompt` + 单图 | `multi_prompt` + `image_list` + `<<<image_N>>>` |
| 模型 | `kling-v3` | `kling-v3-omni` |
| 图片上限 | 1 | 7 (无视频无主体时) |
| 镜头上限 | 6 | 6 |
| 时长范围 | 3-15s | 3-15s |
| 声音 | sound: on/off | sound: on/off |

## 核心设计

### 1. 贪心分组 + image_list 填充

每个 Panel 有 N 个 shots（分镜）和可选的角色参考图。分组策略与 Vidu 一致：分镜图优先，剩余位置插入角色参考图。

**每组约束**（取最严格）：
- 镜头数 ≤ 6（multi_prompt 限制）
- 总图片数 ≤ 7（image_list 限制，无视频无主体时）
- 总时长 ≤ 15s（duration 限制）

**image_list 填充策略**：
1. 分镜图（splitImageUrl）优先填入
2. 剩余位置插入角色参考图（characterReferences）
3. 无剩余位置则全部分镜图

**示例**：4 镜头 + 2 角色
```
image_list = [shot1, shot2, shot3, shot4, char1, char2]  // 6 ≤ 7
```

### 2. Prompt 构建

分镜图和角色图按顺序放入 image_list，prompt 中用 `<<<image_N>>>` 引用：
- `<<<image_1>>>` ~ `<<<image_{n}>>>`：分镜图（n = 该组镜头数）
- `<<<image_{n+1}>>>` ~ `<<<image_{n+m}>>>`：角色图（m = 角色数）

每个镜头的 multi_prompt prompt 包含：
- `<<<image_N>>>` 引用该镜头的分镜图
- visualDescription 作为镜头描述
- 角色图引用（如适用）

### 3. 多组视频拼接

一个 Panel 被分成多组后，每组生成一个视频。多个视频通过现有 `PanelService.concat` 逻辑拼接。

## 后端改动

### KlingVideoService（改造）

直接改写 `KlingVideoService.java`，不新建类：
- 端点改为 `/v1/videos/omni-video`
- 查询端点改为 `/v1/videos/omni-video/{id}`
- 新增 `generateOmniAsync()` 方法，构建 image_list + multi_prompt
- 移除旧的 `generateAsync()` 和 `generateAsyncMultiShot()` 中对 image2video 的依赖
- JWT 鉴权不变
- 信号量限流不变

**请求体构建**：
```json
{
  "model_name": "kling-v3-omni",
  "image_list": [{"image_url": "shot1_url"}, {"image_url": "char1_url"}],
  "multi_shot": true,
  "shot_type": "customize",
  "multi_prompt": [
    {"index": 1, "prompt": "<<<image_1>>> visual description", "duration": "3"}
  ],
  "duration": "5",
  "mode": "std",
  "sound": "on"
}
```

### PanelProductionService（新增 Kling 分支）

Kling 分支替换现有逻辑：
1. 收集 shots 的 splitImageUrl 和 visualDescription
2. 收集 characterReferences（角色参考图 URL）
3. 贪心分组（max 6 镜头, max 7 图片, max 15s）
4. 每组调用 `KlingVideoService.generateOmniAsync()`
5. 不再需要 fusionImageUrl
6. 多组视频 → 现有拼接逻辑

### VideoGenerationService 接口

新增 Omni 风格方法签名，或扩展现有方法以支持 image_list。

### 模型处理

- 前端选择 std/pro
- 后端映射为 `kling-v3-omni` + mode=std/pro
- 移除旧的 kling-v3-std/kling-v3-pro 分解逻辑

## 前端改动

### Step4 Provider 锁定

- Provider 存储在 project 级别，不存 localStorage
- 一旦选定不可随意切换（Kling ↔ Vidu）
- 避免 model 跨 provider 串用（之前 turbo 误发到 Kling 的 bug）

### 模型选项更新

Kling 模型选项改为 `kling-v3-omni` (std/pro)。

### 移除融合图依赖

Kling 路径不再依赖 fusionImageUrl 进行视频生成。

## 不变的部分

- JWT 鉴权逻辑
- Vidu 完整流程（零改动）
- 融合图生成（Vidu 仍需要，Kling 跳过）
- TTS 后期配音流程（VideoAudioMergeService）
- 任务轮询逻辑（状态映射相同：submitted/processing/succeed/failed）

## 声音处理

保留 `sound=on`，Omni 生成环境音效。英文语音问题后续单独解决。项目已有 TTS 后期配音流程作为备选。
