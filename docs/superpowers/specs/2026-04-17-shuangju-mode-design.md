# 爽剧模式（Shuangju Mode）设计文档

## 概述

在现有 realtime_animation 制作模式基础上，新增「爽剧模式」配置开关。开启后，整条剧本生成链（大纲 → 集剧本 → 分镜）的 DeepSeek prompt 全部切换为爽剧专用版本，产出高密度、快节奏、"三秒一个爽点"的短剧内容。

## 目标指标

| 指标 | 标准模式 | 爽剧模式 |
|------|----------|----------|
| 集剧本密度 | 5-7 字/秒 | 12-16 字/秒 |
| 90 秒集剧本字数 | 450-630 字 | 1080-1440 字 |
| 分镜 sceneDescription+dialogue 总字数 (90s) | ~500 字 | ~1300 字 |
| 爽点密度 | 无要求 | 平均每 3 秒一个 |
| 分镜 duration | 1-4 秒 AI 分配 | 1-4 秒 AI 分配（保持现有 clamp） |
| 分镜 hookPoint 字段 | 无 | 必填 |

## 涉及文件

### 前端
- `frontend/.../steps/Step1Content.tsx` — 新增「爽剧模式」Toggle
- `frontend/.../services/projectService.ts` — TypeScript 类型定义新增 `ScriptStyle`

### 后端
- `ScriptPromptBuilder.java` — 大纲 & 集剧本 prompt 爽剧分支（方法签名新增 `scriptStyle` 参数）
- `ScriptService.java` — 调用 prompt builder 时传入 `scriptStyle`
- `DeepSeekTextService.java` — 分镜 storyboard prompt 爽剧分支
- `StoryboardAgentService.java` — Agent Reasoner/Executor prompt 爽剧分支（方法签名新增 `scriptStyle` 参数）
- `PanelProductionService.java` — resolveShots() 传入 `scriptStyle` 给 Agent
- `ProjectInfoKeys.java` — 新增 `SCRIPT_STYLE` 常量
- `ProjectProductionMode.java` — 新增 `isShuangju(Project)` 工具方法
- `Project` 实体 / DTO — projectInfo 新增 `scriptStyle` 字段

---

## 模块 1：前端 Step1 — 爽剧模式 Toggle

### 改动
在 `Step1Content.tsx` 的题材类型附近新增一个 Toggle/Switch 组件。

### 数据模型
```typescript
// 新增类型
type ScriptStyle = "standard" | "shuangju";

// CreateProjectRequest 新增可选字段
scriptStyle?: ScriptStyle;  // 默认 "standard"

// projectInfo 中存储
projectInfo.scriptStyle: ScriptStyle
```

### 后端常量
```java
// ProjectInfoKeys.java
public static final String SCRIPT_STYLE = "scriptStyle";

// ProjectProductionMode.java 新增
public static boolean isShuangju(Project project) {
    return "shuangju".equals(
        project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard")
    );
}
```

### UI 行为
- Toggle 标签：「爽剧模式」
- 默认关闭（`scriptStyle: "standard"`）
- 开启后值为 `"shuangju"`
- 创建项目时写入 `projectInfo.scriptStyle`

### API 传输
创建项目 `POST /api/projects` 时，`scriptStyle` 包含在 projectInfo 中传到后端。

---

## 模块 2：大纲生成 Prompt — ScriptPromptBuilder

### 改动位置
- `buildScriptOutlineSystemPrompt()` — 方法签名新增 `String scriptStyle` 参数，追加爽剧段落
- `buildScriptOutlineUserPrompt()` — 方法签名新增 `String scriptStyle` 参数，追加风格标注
- `ScriptService.generateScriptOutline()` — 从 `projectInfo` 读取 `scriptStyle`，传入 prompt builder

### 调用链
```java
// ScriptService.generateScriptOutline()
String scriptStyle = (String) project.getProjectInfo()
    .getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");

// 现有分支（不变）
if (comicMode) {
    systemPrompt = comicCommentaryScriptPromptBuilder.buildScriptOutlineSystemPrompt(...);
} else {
    // scriptPromptBuilder 方法签名新增 scriptStyle 参数
    systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
        totalEpisodes, genre, targetAudience, chapterCount,
        episodesPerChapter, episodeDuration, scriptStyle  // ← 新增
    );
}
```

### System Prompt 追加内容（仅 shuangju 时生效）

```text
【爽剧节奏约束（爽剧模式生效）】
- 每集必须规划 8-10 个「爽点节拍」（hookBeats），平均每 3 秒一个
- 爽点类型包括但不限于：身份反转、实力碾压、打脸、情绪爆发、悬念揭晓、视觉冲击、言语怼回、绝地反杀
- 大纲中每集描述必须明确标注爽点位置和类型，格式：
  「爽点①：XXX（类型）」「爽点②：XXX（类型）」...
- 节奏要求：
  - 开头 3 秒必须有强力钩子（hook）：悬念、冲击画面、或反转
  - 中间部分密集爽点，不允许超过 6 秒无爽点的平铺段落
  - 结尾必须是强悬念或情绪高潮，驱动观众看下一集
- 每集概括字数增加到 200-300 字，以容纳爽点节拍标注
- 整体叙事节奏：快速推进，禁止冗长铺垫
```

### User Prompt 追加（仅 shuangju）
在 user prompt 末尾追加一行：
```text
剧本风格：爽剧（三秒一个爽点，节奏极快，短句驱动）
```

---

## 模块 3：集剧本生成 Prompt — ScriptPromptBuilder

### 改动位置
- `buildScriptEpisodeSystemPrompt()` — 方法签名新增 `String scriptStyle` 参数，替换字数约束 + 追加格式要求
- `ScriptService.generateChapterEpisodes()` — 读取 `scriptStyle` 传入 prompt builder

### 注意事项
`DeepSeekTextService.generateEpisodeScript()` 中也有内联 prompt 用于集剧本生成。需确认该路径是否仍在使用：
- 若已废弃：无需修改，但应标记 `@Deprecated`
- 若仍在使用：需同步添加 `scriptStyle` 参数和爽剧分支

### 字数密度调整（shuangju 模式）

替换原有的「内容量与时长匹配」段落：

```text
【内容量与时长匹配（爽剧模式 - 最高优先级）】
- 每秒需要约 12-16 个字的剧本内容（含场景描述、台词、动作描写）
- 60 秒 → content 约 720-960 字，至少 20 个爽点节拍
- 90 秒 → content 约 1080-1440 字，至少 30 个爽点节拍
- 120 秒 → content 约 1440-1920 字，至少 40 个爽点节拍
- 180 秒 → content 约 2160-2880 字，至少 60 个爽点节拍
- 300 秒 → content 约 3600-4800 字，至少 100 个爽点节拍
```

### 追加爽剧格式要求（shuangju 模式）

```text
【爽剧短句格式约束（爽剧模式生效）】
- 全文使用短句，每句不超过 20 个字
- 平均每 3 秒（约 36-48 字）必须出现一个明确的情绪或剧情爽点
- 爽点用 [爽点:描述] 标记，例如：[爽点:身份反转]、[爽点:实力碾压]
- 禁止超过 2 句的平铺叙述，必须快速推进情节
- 台词简短有力，每句台词不超过 15 个字
- 场景切换频率高，每 2-3 个爽点可切换一次场景
- 书写格式：
  (场景描述) 角色(情绪):台词 [爽点:XX]
  (动作描写)
- 示例片段：
  (豪华婚房，灯光昏暗)
  陆沉猛然睁眼。 [爽点:重生觉醒]
  冷汗浸透枕头。
  他侧头，看见身旁沉睡的姜眠。
  陆沉(震惊):你...还活着？
  他红了眼眶，颤抖着伸手。 [爽点:情绪爆发]
```

---

## 模块 4：分镜生成 — Agent 模式（StoryboardAgentService + DeepSeekTextService）

### 架构决策
爽剧模式下的 realtime_animation 也走 `StoryboardAgentService` 的 Reasoner → Executor Agent 循环，而非一次性生成。原因：
1. 爽点覆盖需要迭代检查
2. 字数密度高，一次性生成质量不可控
3. 复用已有 Agent 架构

### 路由与参数传递
实际上 `PanelProductionService.resolveShots()` 当前已经将所有非精修请求都通过 Agent 路径处理（包括 realtime 模式）。因此爽剧模式不改变路由逻辑，而是改变 Agent 内部的 prompt 选择。

改动点：
1. `StoryboardAgentService.generate()` 方法签名新增 `String scriptStyle` 参数
2. `PanelProductionService.resolveShots()` 从 `projectInfo` 读取 `scriptStyle`，传入 Agent
3. Agent 内部根据 `scriptStyle` 选择标准/爽剧 Reasoner 和 Executor prompt

```java
// StoryboardAgentService.generate() 签名变更
public List<Map<String, Object>> generate(
    String episodeContent, List<Map<String, Object>> characters,
    int targetDuration, String visualStyle,
    boolean comicMode, String narrationPerspective,
    String scriptStyle  // ← 新增
) {
    // 根据 scriptStyle 选择 prompt
    String reasonerPrompt = "shuangju".equals(scriptStyle)
        ? buildShuangjuReasonerSystemPrompt()
        : buildReasonerSystemPrompt();
    // ...
}
```

### Duration Clamp
现有 `Math.max(1, Math.min(4, duration))` clamp 保持不变。爽剧 Executor prompt 中的 duration 范围同步调整为 1-4 秒（与 clamp 一致，避免 LLM 生成 5 秒被静默截断）。

### Shot JSON Schema 新增字段

```json
{
  "shotNumber": 1,
  "duration": 3,
  "scene": "豪华婚房，灯光昏暗",
  "characters": ["陆沉"],
  "shotSize": "近景",
  "cameraAngle": "俯拍",
  "sceneDescription": "陆沉猛然睁眼，冷汗浸透枕头，瞳孔中映出天花板的水晶吊灯",
  "dialogue": "陆沉(震惊):你...还活着？",
  "speaker": "陆沉",
  "dialogueTone": "震惊颤抖",
  "visualEffects": "画面微微抖动，色调从灰暗变暖",
  "audioEffects": "心跳声加速、倒吸冷气",
  "transitionHint": "硬切",
  "hookPoint": "重生觉醒，男主发现自己回到过去"
}
```

新增字段：
- `hookPoint` (String, 爽剧模式必填): 该镜头的爽点描述，10-25 字
- 将 `hookPoint` 加入 `SHOT_EDITABLE_FIELDS` 白名单，允许用户在分镜编辑器中手动修改

### Reasoner Prompt 爽剧版

当 `scriptStyle == "shuangju"` 时，`buildReasonerSystemPrompt()` 使用：

```text
你是一个爽剧分镜规划 agent。你的任务是为短视频爽剧分镜生成做决策。

本集为爽剧模式：节奏极快，平均每 3 秒一个爽点，台词短促有力。

你的职责：
1. 从剧本中提取所有 [爽点:XX] 标记作为必须覆盖的 hookBeats
2. 分析剩余爽点和已生成进度
3. 决定下一批应覆盖哪些爽点（每批 3-5 个爽点）
4. 估算该批需要多少秒（基于爽点密度）
5. 当所有爽点覆盖完毕后，如果时长不足可选择 expand 或 pad

约束：
- 每个分镜 1-4 秒，AI 自行判断
- 平均每 3 秒一个爽点
- 优先完整覆盖所有爽点节拍
- 保持叙事连贯性，每批之间需要衔接

输出纯 JSON（与现有 ReasonerDecision 结构兼容）：
{
  "action": "generate|expand|pad|done",
  "nextBeatDescription": "爽点①:身份反转 → 爽点②:实力碾压 → 爽点③:打脸",
  "estimatedSeconds": 15,
  "targetBeatIndex": 2,
  "reasoning": "为什么做这个决策"
}

注意：复用现有 nextBeatDescription 字段（String），将多个爽点用 "→" 连接。
不新增 nextHookBeats 数组字段，避免修改 ReasonerDecision 数据类。
```

### Executor Prompt 爽剧版

当 `scriptStyle == "shuangju"` 时，`buildExecutorSystemPrompt()` 使用：

```text
你是一位专做「爽剧」短视频的分镜师。节奏极快，三秒一个爽点，画面冲击力强。

关键约束：
- 每个分镜时长 1-4 秒，由你根据内容自行判断
- 快节奏内容（闪回、反转、打击）用 1-2 秒
- 需要情绪释放或重要对白的内容用 3-4 秒
- 每个分镜必须有 hookPoint（爽点）
- sceneDescription 使用短句，动态描写，30-50 字
- dialogue 简短有力，0-15 字，允许为「无」
- audioEffects 必填，增强爽感

输出纯 JSON 数组。每个分镜：
- shotNumber: 镜头编号
- duration: 时长（1-4秒）
- scene: 场景概述
- characters: 出场角色数组
- shotSize: 景别（大远景/远景/全景/中景/中近景/近景/特写/大特写）
- cameraAngle: 角度（视平/俯拍/仰拍/斜拍/越肩/鸟瞰）
- cameraMovement: 运镜方式
- sceneDescription: 画面描述（短句，动态，30-50字）
- dialogue: 角色台词或「无」
- speaker: 说话人或「无」
- dialogueTone: 对白语气
- visualEffects: 视觉特效或「无」
- audioEffects: 音效（必填）
- transitionHint: 镜头衔接提示
- hookPoint: 本镜头的爽点（10-25字，必填）

【AI视频生成原则】
1.【单主体原则】每个分镜最多1个角色动作，禁止双人互动。
2.【慢动作原则】运镜缓慢，角色动作微小。

【风格要求】
- 场景描述用短句，避免「然后」「接着」等连接词
- 强调视觉冲击：表情特写、动作定格、光影变化
- 台词像打脸金句：简短、有力、记忆点强
```

### 字数密度控制

Executor 的 user prompt 中动态计算目标字数：
```text
本批目标：覆盖以下爽点 [...], 预计 {seconds} 秒
sceneDescription 目标总字数：{seconds * 11} 字左右
dialogue 目标总字数：{seconds * 4} 字左右
(sceneDescription + dialogue 合计约 {seconds * 15} 字)
```

90 秒总计：90 × 15 ≈ 1350 字，接近 1300 字目标。（使用 15 而非 14 以留 margin，LLM 通常 under-produce 10-20%）

### `[爽点:XX]` 标记解析

Reasoner 需要从集剧本中提取 `[爽点:XX]` 标记来确定需覆盖的 hookBeats。由于 LLM 输出格式可能有变体，使用容错正则提取：

```java
// 匹配 [爽点:XX] [爽点：XX] 【爽点:XX】 等变体
Pattern HOOK_PATTERN = Pattern.compile("[\\[【]\\s*爽点\\s*[:：]\\s*(.+?)\\s*[\\]】]");
```

提取出的 hookBeats 列表传入 Reasoner 的 user prompt 中。

### 单集项目（totalEpisodes==1）

`ScriptPromptBuilder.buildSingleEpisodePrompt()` 也需要支持 `scriptStyle` 参数。当 shuangju 时，追加相同的爽剧格式约束和字数密度要求。

---

## 数据流总览

```
Step1: 用户开启「爽剧模式」Toggle
  → projectInfo.scriptStyle = "shuangju"

Step2 大纲生成:
  → ScriptPromptBuilder 读取 scriptStyle
  → system prompt 追加爽剧节奏约束
  → DeepSeek 生成含 hookBeats 标注的大纲
  → 用户审阅/修改大纲

Step2 集剧本生成:
  → ScriptPromptBuilder 读取 scriptStyle
  → 字数密度 12-16字/秒，短句格式，[爽点:XX] 标记
  → DeepSeek 生成高密度爽剧剧本
  → 用户审阅/修改剧本

Step4 分镜生成:
  → PanelProductionService 读取 scriptStyle，传入 StoryboardAgentService
  → Agent 路径（已有，不改路由）根据 scriptStyle 选择 prompt 分支
  → Reasoner 用正则提取 [爽点:XX] 作为 hookBeats，迭代决策
  → Executor 每批生成镜头，含 hookPoint 字段
  → duration 1-4秒 AI 自行分配
  → sceneDescription + dialogue 总计 ≈ 1300 字
```

---

## 不在范围内

- comic_commentary 模式的爽剧支持（仅 realtime_animation）
- 前端分镜编辑器对 hookPoint 字段的展示/编辑（后续可加）
- 后置验证/自动重试（如需要可后续迭代）
- 其他 scriptStyle 选项（如悬疑、治愈等）
