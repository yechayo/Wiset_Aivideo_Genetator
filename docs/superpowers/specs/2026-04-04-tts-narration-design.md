# 漫剧解说 TTS 旁白系统设计

## 背景

当前漫剧解说（comic_commentary）模式下，视频模型无法生成完整的旁白语音，需要后期通过 TTS 合成。当前每集 9 个分镜中约 1-2 个有台词，其余需要生成旁白解说。

## 目标

1. Step1 增加旁白视角和音色配置
2. 后端减少解说模式下的台词密度，标注每个 shot 是否有台词
3. 后端集成 Vidu TTS API，为无台词 shot 生成旁白音频
4. Step4 增加 4d Tab 用于旁白语音管理

## 一、Step1 — 旁白配置

### 条件显示

仅当 `productionMode === 'comic_commentary'` 时显示旁白配置区域。

### 新增字段

| 字段 | 类型 | 说明 | 存储位置 |
|------|------|------|----------|
| `narrationPerspective` | `first_person` \| `third_person` | 旁白视角 | projectInfo |
| `narrationVoiceId` | string | 第三人称旁白音色 ID | projectInfo |
| `protagonistVoiceId` | string | 第一人称主角音色 ID | projectInfo |

### UI 逻辑

1. 选择「漫剧解说」后，下方出现旁白配置区域
2. 先选旁白视角（第一人称 / 第三人称）
3. 根据视角显示对应音色下拉选择：
   - 第一人称 → 主角音色选择（protagonistVoiceId）
   - 第三人称 → 旁白音色选择（narrationVoiceId）
4. 音色列表从 `docs/vidu音色.md` 预置，前端显示中文名，值为 voice_id

### 预置音色列表（精选）

| voice_id | 名称 |
|----------|------|
| `Chinese (Mandarin)_Male_Announcer` | 播报男声 |
| `Chinese (Mandarin)_News_Anchor` | 新闻女声 |
| `Chinese (Mandarin)_Radio_Host` | 电台男主播 |
| `Chinese (Mandarin)_Lyrical_Voice` | 抒情男声 |
| `Chinese (Mandarin)_Gentleman` | 温润男声 |
| `Chinese (Mandarin)_Sweet_Lady` | 甜美女声 |
| `male-qn-jingying` | 精英青年音色 |
| `male-qn-badao` | 霸道青年音色 |
| `female-yujie` | 御姐音色 |
| `female-tianmei` | 甜美女性音色 |

### 后端改动

- `ProjectCreateRequest.java`: 新增三个字段
- `ProjectInfoKeys.java`: 新增 `NARRATION_PERSPECTIVE`, `NARRATION_VOICE_ID`, `PROTAGONIST_VOICE_ID`
- `ProjectService.java`: 创建项目时写入 projectInfo

## 二、后端 — 减少台词 + Shot 级标注

### 修改 DeepSeekTextService

在 `generatePanelAwareStoryboard()` 的解说模式 prompt 中增加约束：

- 每集 9 个分镜中仅 1-2 个可以有台词（dialogue 不为"无"）
- 其余分镜必须无台词（dialogue 为"无"），通过 narration 旁白推动剧情
- 每个 shot 输出保持现有字段不变，台词的有无通过 `dialogue` 字段判断

### Shot 级别判断逻辑

```
hasDialogue = shot.dialogue != null && !shot.dialogue.isEmpty() && shot.dialogue != "无"
```

### Panel 级别派生

```
panelHasAnyDialogue = panel.shots 中任意一个 shot.hasDialogue == true
```

### Prompt 修改

- `narrationPerspective` 影响分镜生成 prompt：
  - 第一人称 → narration 以主角口吻叙述（"我..."）
  - 第三人称 → narration 以旁观者口吻叙述

### 视频生成 Prompt 改动

#### 1. 移除旁白文本（防止侵入）

视频生成 prompt 中**不得包含 narration 旁白文本**，否则视频模型可能尝试生成旁白音频导致侵入。

需修改：
- **后端** `ComicCommentaryPanelPromptBuilder.java`：移除 `解说旁白: ${nar}` 和 `解说旁白(口播): ${nar}`
- **前端** `Step4Production.tsx`：移除 `buildMultiShotPromptText()` 和 `buildGridPromptText()` 中的 narration 输出块

保留其他解说模式约束（闭嘴、慢运镜、字幕安全区等），仅移除 narration 文本。

#### 2. 音色特征匹配（第一人称模式）

当 `narrationPerspective === first_person` 时，视频 prompt 对白部分增加主角音色特征描述，让视频角色对白声音接近 TTS 旁白音色。

建立 voice_id → 音色描述映射：

| voice_id | 音色描述（写入视频 prompt） |
|----------|--------------------------|
| `male-qn-jingying` | 角色说话声音为年轻精英男性，声线沉稳自信、清晰有力 |
| `male-qn-badao` | 角色说话声音为霸道青年男性，声线低沉威严、气场强大 |
| `male-qn-qingse` | 角色说话声音为青涩年轻男性，声线清新自然 |
| `male-qn-daxuesheng` | 角色说话声音为青年大学生，声线阳光爽朗 |
| `female-yujie` | 角色说话声音为成熟御姐女性，声线优雅从容 |
| `female-tianmei` | 角色说话声音为甜美年轻女性，声线清脆悦耳 |
| `female-shaonv` | 角色说话声音为少女，声线活泼灵动 |
| `female-chengshu` | 角色说话声音为成熟女性，声线温婉知性 |

- 第三人称模式 → 不加音色描述（旁白是画外音）
- 第一人称模式 → 对白行格式：`对白(主角，音色描述): "台词内容"`

## 三、后端 — Vidu TTS 服务

### 新建 ViduTtsService

调用 `POST https://api.vidu.cn/ent/v2/audio-tts`（同步接口，直接返回 file_url）。

### TTS 文本拼接与停顿控制

#### 数据来源

旁白文本来自分镜生成阶段，每个 shot 已有：
- `narration`: 旁白文本（AI 生成的，描述当前画面内容，12-45字，与视觉描述匹配）
- `dialogue`: 角色台词（大部分为"无"，仅 1-2 个 shot 有台词）
- `duration`: 该镜头在视频中的时长（秒）

TTS 不需要额外生成文本，直接复用每个 shot 的 `narration` 字段。

#### 旁白与画面的配合逻辑

每个 panel 的 TTS 音频对应一段连续视频，按 shot 顺序播放：

| shot 类型 | 视频内容 | TTS 处理 | 原因 |
|-----------|----------|----------|------|
| 无台词（dialogue="无"） | 纯画面，无角色说话 | **朗读 narration** | 旁白解说推动剧情 |
| 有台词（dialogue≠"无"） | 角色在视频中说话 | **静音停顿 `<#duration#>`** | 避免旁白和角色对白重合 |

#### 间隔时间的获取

使用每个 shot 的 `duration` 字段作为停顿时长。该字段在分镜生成时已确定，代表该镜头在最终视频中的播放时长。

#### TTS 文本拼接算法

```
对每个 panel，按 shot 顺序拼接:

segments = []
for shot in panel.shots:
    if shot.hasDialogue:
        // 有台词：插入与 shot 时长相等的静音
        segments.add("<#${shot.duration}#>")
    else if shot.narration 存在且不为 "无":
        // 无台词：输出旁白文本
        segments.add(shot.narration)

ttsText = segments.join("")

// 边界处理：
// - 如果 ttsText 为空（所有 shot 都有台词）→ 该 panel 跳过 TTS 生成
// - 如果 ttsText 以停顿标记开头 → 移除首段停顿（前面没有语音）
// - 连续多个有台词 shot → 连续插入多个停顿（累加）
```

#### 示例

某 panel 有 5 个 shot：

| shot | duration | dialogue | narration | TTS 处理 |
|------|----------|----------|-----------|----------|
| 1 | 3s | 无 | "阳光洒在古老的城墙上..." | 朗读 |
| 2 | 5s | "你来了" | "无" | 静音 5s |
| 3 | 4s | 无 | "她站在城门前，目光坚毅" | 朗读 |
| 4 | 3s | "我等你很久了" | "无" | 静音 3s |
| 5 | 4s | 无 | "风吹动她的发丝，仿佛在诉说着什么" | 朗读 |

TTS 文本：`"阳光洒在古老的城墙上。<#5.0#>她站在城门前，目光坚毅。<#3.0#>风吹动她的发丝，仿佛在诉说着什么"`

播放时间线：
```
|--- 朗读 ---|-- 静音 5s --|--- 朗读 ---|-- 静音 3s --|--- 朗读 ---|
 shot1(3s)    shot2(角色对白)  shot3(4s)    shot4(角色对白)  shot5(4s)
```

#### 语速策略

使用自然语速（`voice_setting_speed` 默认 1.0），不精确匹配 shot 时长。
- 分镜生成时 AI 已将 narration 控制在 12-45 字，自然语速朗读约 3-10 秒，与典型 shot 时长基本匹配
- 整体听感连贯即可，不追求帧级同步

### 音色选择

```
if narrationPerspective == "first_person":
    voiceId = projectInfo.protagonistVoiceId
else:
    voiceId = projectInfo.narrationVoiceId
```

### Panel 数据新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `ttsAudioUrl` | String | TTS 音频 URL |
| `ttsStatus` | `pending` \| `generating` \| `completed` \| `failed` | TTS 状态 |

存储在 panel 的 `panelInfo` JSON 中，与 `videoUrl` 同级。

### 新增 API 端点

**单个 panel TTS：**
```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/tts
Response: { code: 0, data: { ttsAudioUrl: "...", ttsStatus: "completed" } }
```

**批量 TTS（某集所有需要旁白的 panel）：**
```
POST /api/projects/{projectId}/episodes/{episodeId}/tts/batch
Response: { code: 0, data: { generated: 5, skipped: 2 } }
```

### 查询接口扩展

`getBatchProductionStatuses` 响应中每个 panel 增加 `ttsAudioUrl`、`ttsStatus`。

## 四、前端 — 集成到 4c 视频生成 Tab

### 不新增 Tab

TTS 旁白功能直接集成到现有的 4c 视频生成 Tab 中，不新增 4d Tab。

### Panel 展开区域改为左右两栏布局

每个 panel 的展开详情区域改为左右两栏：

```
┌──────────────────────────────────────────────────────┐
│ 分组 1                                    [生成旁白]  │
├──────────────────────┬───────────────────────────────┤
│     📹 视频预览       │     🔊 旁白语音               │
│                      │                               │
│  ┌────────────────┐  │  分镜1: 阳光洒在城墙上... [旁白] │
│  │                │  │  分镜2: "你来了"       [有台词]  │
│  │   video player  │  │  分镜3: 她站在城门前... [旁白]  │
│  │                │  │                               │
│  └────────────────┘  │  状态: ✅ 已生成  [▶ 播放旁白]   │
│                      │                               │
│  分镜描述 | 提示词     │  旁白文本预览...               │
├──────────────────────┴───────────────────────────────┤
│  融合参考图 | 积分 | 任务ID                             │
└──────────────────────────────────────────────────────┘
```

- **左栏**：视频播放器 + 分镜描述 + 提示词按钮（保持现有功能）
- **右栏**：旁白语音管理
  - shot 列表，每个 shot 标注 [旁白] 或 [有台词]
  - 无台词 shot 显示 narration 文本预览
  - 旁白生成按钮 + 状态 + 播放器

### 批量操作

集级别的 header 区域增加「批量生成旁白」按钮，一键为该集所有无台词 panel 生成 TTS。

### Stats bar

在现有视频 stats bar 旁增加旁白统计：`N / M 旁白已生成`

### 前端数据结构

```typescript
// SegmentState 新增
ttsAudioUrl?: string;
ttsStatus?: 'pending' | 'generating' | 'completed' | 'failed';

// Shot 级别标注（从 dialogue 派生，用于 UI 展示）
// hasDialogue = dialogue != null && dialogue != "" && dialogue != "无"
```

### 前端 API

episodeService.ts 新增：

```typescript
generatePanelTts(projectId, episodeId, panelId)
batchGenerateTts(projectId, episodeId)
```

## 五、文件改动清单

### 后端

| 文件 | 改动 |
|------|------|
| `ProjectCreateRequest.java` | 新增 3 个字段 |
| `ProjectInfoKeys.java` | 新增 3 个常量 |
| `ProjectService.java` | 创建项目写入新字段 |
| `DeepSeekTextService.java` | 修改解说模式 prompt：减少台词、标注视角 |
| `ComicCommentaryScriptPromptBuilder.java` | prompt 增加旁白视角影响 |
| **新建** `ViduTtsService.java` | Vidu TTS API 调用 |
| `PanelController.java` | 新增 TTS 端点 |
| `PanelService.java` | TTS 生成逻辑、状态更新 |
| 查询接口相关 Service | 返回 ttsAudioUrl/ttsStatus |

### 前端

| 文件 | 改动 |
|------|------|
| `Step1Content.tsx` | 旁白配置 UI |
| `Step4Production.tsx` | Panel 展开区域改为左右两栏，集成 TTS 控制 |
| `Step4Production.module.less` | 左右两栏布局样式、旁白播放器样式 |
| `types.ts` | 新增字段 |
| `episodeService.ts` | 新增 TTS API |
| `project.types.ts` | CreateProjectRequest 新增字段 |
