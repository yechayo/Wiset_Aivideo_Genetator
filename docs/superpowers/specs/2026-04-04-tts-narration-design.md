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

Vidu TTS API 支持 `<#x#>` 标签控制语音间隔（x 为秒数，范围 [0.01, 99.99]），用于在有台词的 shot 位置插入静音，避免旁白与视频中角色对白重合。

对每个 panel，按 shot 顺序拼接 TTS 文本：

```
遍历 panel.shots:
  if shot.hasDialogue:
    // 有台词的 shot：插入与该 shot 时长相等的静音停顿
    ttsText += "<#${shot.duration}#>"
  else:
    // 无台词的 shot：输出 narration 旁白
    ttsText += shot.narration

如果 ttsText 最终为空（所有 shot 都有台词）→ 跳过不生成
如果 ttsText 以停顿标记开头 → 移除首段停顿（前面没有语音）
```

示例（某 panel 5 个 shot，shot2 和 shot4 有台词）：
```
"这是开场的旁白解说。<#5.0#>这是中间的旁白。<#3.0#>这是结尾的旁白。"
```

对应时间线：
- shot1(3s, 无台词): TTS 朗读 "这是开场的旁白解说。"
- shot2(5s, 有台词): TTS 静音 5s（视频播放角色对白）
- shot3(4s, 无台词): TTS 朗读 "这是中间的旁白。"
- shot4(3s, 有台词): TTS 静音 3s（视频播放角色对白）
- shot5(4s, 无台词): TTS 朗读 "这是结尾的旁白。"

注意：TTS 实际朗读时长与 shot 时长不一定完全匹配，但通过停顿可以避免旁白与对白直接重合。

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

## 四、前端 — Step4 4d Tab（旁白语音）

### Tab 结构

```
4a 脚本生成 → 4b 九宫格图片 → 4c 视频生成 + 4d 旁白语音（并行）
```

### 解锁逻辑

4d 在 4b（九宫格）全部通过后解锁，与 4c 同时解锁，不依赖 4c 视频状态。

### 4d 界面

按集分组，每个集展开后显示 panel 列表。每个 panel 展示内部 shot 列表：

```
第1集 标题                          [批量生成旁白]
├── 分组 1
│   ├── 分镜1: "画面描述..." [有台词]    ← 灰色标记
│   ├── 分镜2: "画面描述..." [旁白] "旁白文本预览..."
│   └── 分镜3: "画面描述..." [旁白] "旁白文本预览..."
│   状态: ✅ 已生成旁白  [▶ 播放]
├── 分组 2
│   ├── 分镜4: "画面描述..." [旁白] "旁白文本预览..."
│   └── 分镜5: "画面描述..." [旁白] "旁白文本预览..."
│   状态: ⏳ 待生成  [生成旁白]
```

### 交互

- **Panel 级别操作**：每个 panel 一键生成旁白
- **批量生成**：某集内所有需要旁白的 panel 一键生成
- **播放预览**：生成后显示播放按钮
- **Stats bar**：已完成旁白数 / 需要旁白总数

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
| `Step4Production.tsx` | 新增 4d Tab |
| `Step4Production.module.less` | 4d 样式 |
| `types.ts` | 新增字段 |
| `episodeService.ts` | 新增 TTS API |
| `project.types.ts` | CreateProjectRequest 新增字段 |
