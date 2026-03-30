# 分镜流水线重设计 - 设计规格书

## 概述

将当前的单面板漫画转视频流水线，替换为多分镜故事板流水线。每个 Panel 包含多个结构化分镜（DetailedStoryboardShot），渲染为 3x3 九宫格图片，经人工审核后，以融合参考图 + 多镜头提示词的方式调用 Vidu 生成视频。

**改动范围**：仅替换视频生成流水线。剧本大纲生成和角色设计保持不变。

**AI 服务**：DeepSeek（文本）+ Seedream（图片）+ Vidu（视频）— 不引入新依赖。

## 设计约束

- 一个 Panel = 一组分镜 = 一个 Vidu 视频（最长16秒）
- 分镜按贪心算法分组（每桶最长16秒），溢出则新建 Panel
- Panel 数量由分镜脚本生成后自动决定，不预设
- 九宫格图片支持多页（超过9个分镜时自动分页）
- 九宫格图片需人工审核后才能生成视频
- 融合参考图（切割后的小图拼接 + 可选角色参考）作为 Vidu 输入
- 不改数据库表结构 — 所有新数据存入 `panelInfo` JSON 字段

## 1. 数据模型

### Panel `panelInfo` JSON 结构

所有新数据存入现有的 `panelInfo` JSON 字段，不需要新建表。

```json
{
  "shots": [
    {
      "shotNumber": 1,
      "duration": 3,
      "scene": "教室 - 白天 - 靠窗最后一排",
      "characters": ["林霄", "苏晴"],
      "shotSize": "全景",
      "cameraAngle": "视平",
      "cameraMovement": "轨道推拉",
      "visualDescription": "林霄推开教室门，阳光从走廊洒入...",
      "dialogue": "无",
      "visualEffects": "浅景深，背景虚化",
      "audioEffects": "教室嘈杂声",
      "splitImageUrl": "https://oss.../shot-1.png"
    }
  ],
  "gridImages": ["https://oss.../grid-page1.png", "https://oss.../grid-page2.png"],
  "gridStatus": "pending",
  "gridRejectionFeedback": null,
  "fusionImageUrl": "https://oss.../fusion.png",
  "totalDuration": 16,
  "totalShots": 7,
  "gridPageCount": 1,

  "videoUrl": "https://oss.../video.mp4",
  "videoStatus": "pending",
  "videoTaskId": "vidu-task-xxx",
  "offPeak": false
}
```

### 删除的字段

- `comicUrl`, `comicStatus`, `comicPrompt`（四宫格漫画被九宫格分镜图取代）
- `backgroundUrl`, `backgroundStatus`, `backgroundPrompt`（不再单独生成背景图）

### 枚举值

**shotSize（景别）**：大远景 / 远景 / 全景 / 中景 / 中近景 / 近景 / 特写 / 大特写
**cameraAngle（角度）**：视平 / 高位俯拍 / 低位仰拍 / 斜拍 / 越肩 / 鸟瞰
**cameraMovement（运镜）**：固定 / 横移 / 俯仰 / 横摇 / 升降 / 轨道推拉 / 变焦推拉 / 正跟随 / 倒跟随 / 环绕 / 滑轨横移
**gridStatus（九宫格状态）**：pending / generating / generated / approved / rejected / failed
**videoStatus（视频状态）**：pending / generating / completed / failed
**gridRejectionFeedback**：字符串（可选，用户拒绝时的反馈意见）

## 2. 流水线状态机

### 新状态流程

```
SCRIPT_GENERATING → SCRIPT_REVIEW → CHARACTER_DESIGNING → CHARACTER_REVIEW
→ [不变: IMAGE_GENERATING → IMAGE_REVIEW → ASSET_LOCKED]  ← 角色设计不变
→ EPISODE_SCRIPT_GENERATING          （新增：结构化分集剧本JSON）
→ EPISODE_SCRIPT_GENERATING_FAILED   （新增：分集剧本生成失败）
→ STORYBOARD_GENERATING              （新增：分镜脚本 + 分组 + 九宫格图）
→ STORYBOARD_GENERATING_FAILED       （新增：分镜生成失败）
→ STORYBOARD_REVIEW                  （新增：人工审核九宫格）
→ PRODUCING                          （改造：融合图 + 多镜头Vidu）
→ COMPLETED
```

**重要**：角色设计部分（IMAGE_GENERATING → IMAGE_REVIEW → ASSET_LOCKED）保持不变。新流程从 ASSET_LOCKED 之后开始。

### 删除的状态

- `PANEL_GENERATING` — Panel 在 STORYBOARD_GENERATING 阶段自动创建
- `PANEL_REVIEW` — 被 STORYBOARD_REVIEW 取代
- `VIDEO_ASSEMBLING` — 一个 Panel 就是一个视频，不需要拼接

### 状态转换详情

| 从 | 到 | 触发条件 | 执行动作 |
|----|----|---------|---------|
| ASSET_LOCKED | EPISODE_SCRIPT_GENERATING | 所有角色素材锁定 | DeepSeek 生成结构化分集剧本 |
| EPISODE_SCRIPT_GENERATING | STORYBOARD_GENERATING | 自动（成功时） | DeepSeek 生成分镜脚本，分组，创建 Panel |
| EPISODE_SCRIPT_GENERATING | EPISODE_SCRIPT_GENERATING_FAILED | 失败时 | 存储错误信息，允许重试 |
| EPISODE_SCRIPT_GENERATING_FAILED | EPISODE_SCRIPT_GENERATING | 用户重试 | 重新调用 DeepSeek |
| STORYBOARD_GENERATING | STORYBOARD_REVIEW | 自动（成功时） | 九宫格图生成、切割、融合完成 |
| STORYBOARD_GENERATING | STORYBOARD_GENERATING_FAILED | 失败时 | 存储错误信息，允许重试 |
| STORYBOARD_GENERATING_FAILED | STORYBOARD_GENERATING | 用户重试 | 重新生成分镜 |
| STORYBOARD_REVIEW | PRODUCING | 所有 Panel 审核通过 | 为每个 Panel 调用 Vidu 生成视频 |
| PRODUCING | COMPLETED | 所有视频完成 | 最终状态 |

### 回滚支持

- STORYBOARD_REVIEW → EPISODE_SCRIPT_GENERATING（重新生成分镜）
- PRODUCING → STORYBOARD_REVIEW（重新审核）
- 回滚清理字段：shots, gridImages, gridStatus, fusionImageUrl, splitImageUrl, totalDuration, totalShots, gridPageCount

### 分集剧本输出格式

`DeepSeekTextService.generateEpisodeScript()` 产出的结构化 JSON：

```json
[
  {
    "title": "第1集：归来",
    "content": "详细剧本内容(200-250字/分钟)...",
    "characters": "李逍遥,赵灵儿",
    "keyItems": "包袱、褪色对联",
    "continuityNote": "本集为系列开篇，交代主角归乡背景"
  }
]
```

替代当前的自由文本格式。结构化字段为下游分镜脚本生成提供更好的输入。

### 错误处理

- **EPISODE_SCRIPT_GENERATING 失败**：状态设为 `EPISODE_SCRIPT_GENERATING_FAILED`，错误信息存入 episodeInfo，允许用户重试
- **STORYBOARD_GENERATING 失败**：状态设为 `STORYBOARD_GENERATING_FAILED`，错误信息存入项目状态，允许用户重试
- **九宫格图生成失败**：panelInfo.gridStatus = `failed`，存储错误信息，其他 Panel 继续生成
- **部分页失败**：已成功生成的页保留，失败的页可单独重试
- **Vidu 视频失败**：沿用现有重试逻辑（videoStatus = `failed`，允许按 Panel 重试）

### 视频生成守卫变更

原有的 `comicStatus == "approved"` 守卫条件改为 `gridStatus == "approved"` 后才允许 Vidu 视频生成。

## 3. 服务层

### 新增服务

| 服务 | 职责 |
|------|------|
| `StoryboardService` | 通过 DeepSeek 生成分镜脚本，贪心分组，自动创建 Panel |
| `GridImageService` | 生成 3x3 九宫格图（Seedream），切割为独立小图，拼接融合参考图 |

### 改造服务

| 服务 | 改动内容 |
|------|---------|
| `PanelProductionService` | 原：管理单 Panel 漫画→视频流程。现：管理 Panel 批量创建 + 触发九宫格生成 + 触发视频生成 |
| `ViduVideoService` | 输入从单张漫画图改为融合参考图。提示词改为多镜头格式。 |
| `PanelPromptBuilder` | 新增 `buildMultiShotPrompt()` 构建融合图的多镜头提示词 |
| `DeepSeekTextService` | 新增 `generateEpisodeScript()`（结构化JSON输出）和 `generateStoryboard()` |
| `PipelineService` | 新增状态转换节点 |

### 删除/弃用

| 组件 | 原因 |
|------|------|
| `ComicGenerationService` | 四宫格漫画被九宫格分镜图取代 |
| `PanelPromptBuilder.buildComicPrompt()` | 不再需要 |
| `PanelPromptBuilder.buildBackgroundPrompt()` | 不再单独生成背景 |

## 4. 分镜脚本生成逻辑（步骤④）

### 入口

`DeepSeekTextService.generateStoryboard(episodeContent, totalDuration, visualStyle)`

### 输入

- `episodeContent` — 步骤②输出的结构化剧本（含角色、对白、动作、场景描述）
- `totalDuration` — 从 episodeInfo 读取，默认60秒
- `visualStyle` — 从 projectInfo 读取：REAL / ANIME / 3D

### 动态参数

```
minShots = Math.floor(totalDuration / 4)          // 按4秒/镜算下限
recommendedShots = Math.floor(totalDuration / 2.5) // 推荐数
maxShots = totalDuration                            // 按1秒/镜算上限
```

### 系统提示词

专业影视分镜师角色。关键约束：
- 每个分镜时长：1-4秒
- 所有分镜时长总和必须 >= 目标时长
- 输出纯 JSON 数组
- 字段：shotNumber, duration, scene, characters, shotSize, cameraAngle, cameraMovement, visualDescription, dialogue, visualEffects, audioEffects

### 返回值解析

```
AI返回JSON → 清理markdown标记 → JSON.parse → 逐个校验：
  duration 钳制到 1-4秒
  自动计算 startTime/endTime
  缺失字段填默认值
→ 如果 shots 为空：抛出 BusinessException("分镜生成结果为空，请重试")
→ 返回 List<Map>
```

### 贪心分组 → 创建 Panel

```
maxDuration = 16秒
currentGroup = []
currentDuration = 0

for shot in shots:
    // 防护：单个分镜超过上限（经1-4秒钳制后不应出现）
    if shot.duration > maxDuration:
        shot.duration = maxDuration

    if currentDuration + shot.duration > maxDuration AND currentGroup 不为空:
        创建Panel(panelInfo.shots = currentGroup)
        currentGroup = []
        currentDuration = 0
    currentGroup.add(shot)
    currentDuration += shot.duration

// 最后一个组
if currentGroup 不为空:
    创建Panel(panelInfo.shots = currentGroup)

// 事务边界：所有 Panel 创建必须原子化
@Transactional
```

### 边界情况

- **返回0个分镜**：抛出 BusinessException，流水线进入 FAILED 状态
- **分镜时长超过4秒**：强制钳制到4秒
- **所有分镜装进一个 Panel**：单个 Panel，单次 Vidu 调用
- **实际数量**：60秒剧集预计 15-24 个分镜 → 2-4 个 Panel。无硬性上限，九宫格图成本与分镜数线性增长

## 5. 九宫格图流水线（步骤⑤⑥）

### GridImageService.generateGrids(panel)

```
1. 从 panelInfo.shots 取分镜数据
2. 计算分页
3. 每页：构建提示词 → Seedream 生成 → 存储 gridImage URL
4. 切割每页九宫格图 → 存储每个 shot 的 splitImageUrl
5. 拼接所有小图为融合参考图 → 存储 fusionImageUrl
```

### 分页计算

```
shotsPerGrid = 9 (3×3)
pageCount = Math.ceil(shots.size / 9)
```

### 每页提示词构建

```
风格前缀（根据 visualStyle 映射）
+ 九宫格规格（3×3, 16:9面板, 黑色边框）
+ 质量约束（角色一致性、无文字）
+ 角色参考图（来自上游角色设计步骤）
+ 场景一致性约束（同一场景 = 同一环境）
+ 面板内容：
  当页每个分镜：
    16:9 - {visualDescription} {shotSize映射} {cameraAngle映射} environment: {scene}
  空位："(empty panel - storyboard end)"
```

### Seedream 调用

```
SeedreamImageService.generate(
    prompt,              // 拼接好的多分镜九宫格提示词
    referenceImages,     // 角色表情图/三视图
    width, height        // 根据分辨率和宽高比计算
)
```

### 九宫格切割

纯 Java 图片处理（ImageIO + BufferedImage）：

```
对每张九宫格图：
  cols=3, rows=3
  panelWidth = Math.floor(img.width / 3)   // 用 floor 避免小数像素
  panelHeight = Math.floor(img.height / 3)

  for i in 0..8:
    row = i / 3, col = i % 3
    x = col * panelWidth
    y = row * panelHeight
    subImage = 裁剪(x, y, panelWidth, panelHeight)
    上传 OSS
    shot.splitImageUrl = ossUrl
```

注意：使用 `Math.floor` 而非整数除法，明确处理不能被3整除的图片尺寸。最右边/最下边可能有1-2像素被舍弃，视觉上可忽略。

### 融合参考图

将所有切割后的小图拼接成一张参考图：

```
布局计算：
  cols = 3
  rows = Math.ceil(totalShots / 3)

画布绘制：
  深色背景 (#1a1a1c)
  每张小图：contain 模式，居中绘制
  编号标签：白字黑底（①②③...）
  顶部标题行："分镜融合图 - 共N个镜头"

如果有角色参考图：
  底部追加角色三视图区域
  标题："角色参考"

→ 上传 OSS → panelInfo.fusionImageUrl
```

**空位处理**：填充深色背景（#1a1a1c），不画编号标签。

## 6. 视频生成（Vidu 多镜头）

### API 调用变化

```
改造前: Vidu.generateAsync(prompt, duration, "16:9", comicUrl, offPeak)
改造后: Vidu.generateAsync(prompt, duration, "16:9", fusionImageUrl, offPeak)
```

### 多镜头提示词构建

`PanelPromptBuilder.buildMultiShotPrompt(style, panelInfo)`:

```
"[风格前缀] 专业电影级画面。

多镜头连续拍摄指令，以下 {N} 个镜头必须在同一视频中连续呈现：

Shot 1:
duration: {shot1.duration}s
Scene: {shot1.shotSize}, {shot1.cameraAngle}, {shot1.cameraMovement}, {shot1.visualDescription}
{shot1.dialogue != "无" ? "对白: " + shot1.dialogue : ""}
{shot1.audioEffects != "无" ? "音效: [" + shot1.audioEffects + "]" : ""}

Shot 2:
...

## 画面衔接
视频应从参考图自然展开，多镜头间平滑过渡。
保持角色位置和动作的连贯性。
参考图中编号①②③对应 Shot 1/2/3 的画面内容。
```

### 风格前缀映射

```
REAL  → "专业人像摄影风格，电影级画面质感，真实写实，自然光影。"
ANIME → "2D动漫风格，日式动漫插画，漫画艺术风格，鲜明色彩，赛璐璐阴影。"
3D    → "3D动画风格，高精度3D建模，PBR材质渲染，半写实美学。"
```

### 生产流程

```
遍历所有 gridStatus = "approved" 的 Panel：
  1. 读取 fusionImageUrl 和 shots
  2. 计算 totalDuration = sum(shot.duration)
  3. 构建多镜头提示词
  4. ViduVideoService.generateAsync(multiShotPrompt, totalDuration, "16:9", fusionImageUrl)
  5. 存储 videoTaskId → 开始轮询
  6. 完成时：下载 → 上传 OSS → panelInfo.videoUrl
  7. 所有 Panel 完成 → COMPLETED
```

## 7. API 接口

### 改造的接口

| 方法 | 路径 | 变化 |
|------|------|------|
| POST | `/panels/{panelId}/video` | 内部改为：融合参考图 + 多镜头提示词 |
| GET | `/panels/{panelId}/video` | 不变 |
| POST | `/panels/{panelId}/video/retry` | 不变 |

### 删除的接口

| 方法 | 路径 | 原因 |
|------|------|------|
| POST | `/panels/{panelId}/background` | 不再单独生成背景图 |
| POST | `/panels/{panelId}/comic` | 不再生成四宫格漫画 |
| POST | `/panels/{panelId}/comic/approve` | 被九宫格审核取代 |

### 新增接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/episodes/{episodeId}/script` | 触发结构化分集剧本生成 |
| GET | `/episodes/{episodeId}/script` | 获取分集剧本内容 |
| POST | `/episodes/{episodeId}/storyboard` | 生成分镜脚本 + 创建 Panel + 生成九宫格图 |
| GET | `/episodes/{episodeId}/panels` | 获取该集所有 Panel（含分镜数据） |
| PUT | `/panels/{panelId}/grid/approve` | 审核通过九宫格，自动触发视频生成 |
| PUT | `/panels/{panelId}/grid/reject` | 拒绝九宫格。请求体：`{ "reason": "..." }`。设置 gridStatus=`rejected`，原因存入 `gridRejectionFeedback` |
| POST | `/panels/{panelId}/grid/regenerate` | 重新渲染九宫格图（不重新调用 DeepSeek 生成分镜）。清除已有的 splitImageUrl/fusionImageUrl，设置 gridStatus=`generating`，重新执行 Seedream → 切割 → 融合 |
| PUT | `/episodes/{episodeId}/grid/approve-all` | 一键通过所有 Panel 的九宫格 |

## 8. 前端改动

### 改造的页面

| 页面 | 改动 |
|------|------|
| 创建流程 `create/` | 分集剧本步骤调整（展示结构化JSON），后续步骤对接新接口 |
| Panel 管理 | 展示从"四宫格漫画"变为"九宫格分镜图"，支持多页浏览 |
| 视频生产页 | 新增分镜时间线视图（每个 shot 显示切割小图 + 元数据），审核按钮，批量审核 |

### 新增组件

| 组件 | 用途 |
|------|------|
| `StoryboardGrid` | 九宫格分镜图展示（支持多页切换） |
| `ShotTimeline` | 分镜时间线（横向排列，显示每个 shot 的时长/景别/运镜） |
| `ShotDetail` | 单个分镜详情（切割小图 + 字段描述） |
| `GridReviewPanel` | 审核面板（通过 / 拒绝 / 重新生成） |
| `BatchReviewBar` | 批量审核工具栏（一键全通过） |

### 删除组件

| 组件 | 原因 |
|------|------|
| `ComicApproval` | 被 GridReviewPanel 取代 |
| `BackgroundStep` | 不再单独生成背景 |

### Store 状态变更

projectStore / episodeStore 新增字段：

```
storyboardShots  — 当前 Panel 的分镜数组
gridImages       — 九宫格图 URL 列表
gridStatus       — 审核状态
fusionImageUrl   — 融合参考图 URL
```

### 用户操作流程

1. 查看九宫格分镜图（支持多页）
2. 点击查看单个分镜详情（切割小图 + 景别/运镜/描述）
3. 逐 Panel 审核：通过 → 自动触发视频生成
4. 或一键全通过 → 批量触发视频生成
5. 查看视频生成进度
6. 完成后播放预览
