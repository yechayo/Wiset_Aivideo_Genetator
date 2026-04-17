# 爽剧模式（Shuangju Mode）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "shuangju mode" (爽剧模式) toggle that coordinates three DeepSeek prompt stages — outline, episode script, and storyboard — to produce fast-paced, high-density drama content with a hookPoint every ~3 seconds.

**Architecture:** A `scriptStyle` config field flows from the frontend toggle through `projectInfo` to three backend prompt builders. Each builder conditionally appends shuangju-specific prompt text when `scriptStyle == "shuangju"`. The StoryboardAgentService selects shuangju-specific Reasoner/Executor prompts. No new services or tables are created — this is purely a prompt-chain coordination with minimal plumbing.

**Tech Stack:** React + TypeScript (frontend), Spring Boot + Java (backend), DeepSeek API (LLM)

**Spec:** `docs/superpowers/specs/2026-04-17-shuangju-mode-design.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `backend/.../constant/ProjectInfoKeys.java` | Add `SCRIPT_STYLE` constant |
| Modify | `backend/.../util/ProjectProductionMode.java` | Add `isShuangju()` utility |
| Modify | `backend/.../ai/ScriptPromptBuilder.java` | Shuangju prompt branches for outline + episode |
| Modify | `backend/.../service/script/ScriptService.java` | Pass `scriptStyle` to prompt builders |
| Modify | `backend/.../service/production/StoryboardAgentService.java` | Shuangju Reasoner + Executor prompts |
| Modify | `backend/.../service/production/PanelProductionService.java` | Pass `scriptStyle` to Agent; add `hookPoint` to editable fields |
| Modify | `frontend/.../services/types/project.types.ts` | Add `ScriptStyle` type + field |
| Modify | `frontend/.../pages/create/steps/Step1Content.tsx` | Shuangju toggle UI |
| Modify | `frontend/.../pages/create/steps/components/ScriptEpisodeCard.tsx` | Add `hookPoint` to editable fields |
| Test | `backend/.../test/.../StoryboardAgentServiceTest.java` | Existing test file — add shuangju cases |

---

## Task 1: Backend Constants & Utility

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java` (after line 29)
- Modify: `backend/com/comic/src/main/java/com/comic/util/ProjectProductionMode.java` (after `isComicCommentary` methods, ~line 36)

- [ ] **Step 1: Add `SCRIPT_STYLE` constant to `ProjectInfoKeys.java`**

Add at the end of the class (before the closing `}`):

```java
public static final String SCRIPT_STYLE = "scriptStyle";
```

- [ ] **Step 2: Add `isShuangju()` methods to `ProjectProductionMode.java`**

Add after the existing `isComicCommentary` methods (after line 36):

```java
public static boolean isShuangju(Project project) {
    if (project == null) return false;
    return isShuangju(project.getProjectInfo());
}

public static boolean isShuangju(Map<String, Object> projectInfo) {
    if (projectInfo == null) return false;
    Object raw = projectInfo.get(ProjectInfoKeys.SCRIPT_STYLE);
    if (raw == null) return false;
    return "shuangju".equals(raw.toString().trim());
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java \
       backend/com/comic/src/main/java/com/comic/util/ProjectProductionMode.java
git commit -m "feat(shuangju): add SCRIPT_STYLE constant and isShuangju() utility"
```

---

## Task 2: ScriptPromptBuilder — Outline Shuangju Branch

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/ScriptPromptBuilder.java`

The goal: add a `String scriptStyle` parameter to `buildScriptOutlineSystemPrompt()` and `buildScriptOutlineUserPrompt()`. When `"shuangju"`, append extra prompt text.

- [ ] **Step 1: Add `scriptStyle` parameter to `buildScriptOutlineSystemPrompt()`**

Change the method signature at line 12 from:

```java
public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience,
                                              int chapterCount, int episodesPerChapter, int episodeDuration) {
```

To:

```java
public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience,
                                              int chapterCount, int episodesPerChapter, int episodeDuration,
                                              String scriptStyle) {
```

- [ ] **Step 2: Pass `scriptStyle` to `buildSingleEpisodePrompt()`**

At line 16, change:
```java
return buildSingleEpisodePrompt(genre, params, episodeDuration);
```
To:
```java
return buildSingleEpisodePrompt(genre, params, episodeDuration, scriptStyle);
```

- [ ] **Step 3: Append shuangju outline text in `buildScriptOutlineSystemPrompt()`**

After the existing return string (line 39), change the method body so that the base prompt is built first into a variable, then shuangju text is conditionally appended. Replace the existing `return "你是一名..."` block (lines 19-39) with:

```java
String base = "你是一名专业的漫画剧本编剧。\n"
        + "【集数硬约束（最高优先级，必须严格遵守）】\n"
        + "用户设定的总集数为 " + totalEpisodes + " 集。\n\n"
        + "请根据用户提供的信息生成结构化的剧本大纲。\n"
        + "题材类型：" + genre + "\n"
        + "目标受众：" + targetAudience + "\n"
        + "总集数：" + totalEpisodes + " 集\n"
        + "章节数：" + chapterCount + "（每个章节包含 " + episodesPerChapter + " 集）\n"
        + "每集目标时长：" + episodeDuration + " 秒\n\n"
        + "输出格式：仅返回 JSON，不要 markdown 代码块标记。\n"
        + "JSON 结构：\n"
        + "{\n"
        + "  \"outline\": \"Markdown 格式的完整大纲文本\"\n"
        + "}\n\n"
        + "要求：\n"
        + "1. outline 包含完整的世界观、角色小传、关键物品设定、章节剧情线\n"
        + "2. outline 中的章节剧情线必须使用「### 第X章」格式（如 ### 第一章、### 第二章），每章描述该章包含 " + episodesPerChapter + " 集的剧情走向\n"
        + "3. 章节标题中必须包含对应集数范围，格式如「### 第一章: 标题（第1-2集）」\n"
        + "4. 每集需列出标题并用 100-200 字概括核心冲突和结尾钩子\n"
        + "5. 每集描述 1-2 个关键场景转折点，确保后续编剧能据此展开完整剧本\n"
        + "6. 章节剧情线要体现节奏变化：标注每集中哪些部分是高潮（需展开），哪些是过渡（需简洁）";

if ("shuangju".equals(scriptStyle)) {
    base += "\n\n【爽剧节奏约束（爽剧模式生效）】\n"
            + "- 每集必须规划 8-10 个「爽点节拍」（hookBeats），平均每 3 秒一个\n"
            + "- 爽点类型包括但不限于：身份反转、实力碾压、打脸、情绪爆发、悬念揭晓、视觉冲击、言语怼回、绝地反杀\n"
            + "- 大纲中每集描述必须明确标注爽点位置和类型，格式：\n"
            + "  「爽点①：XXX（类型）」「爽点②：XXX（类型）」...\n"
            + "- 节奏要求：\n"
            + "  - 开头 3 秒必须有强力钩子（hook）：悬念、冲击画面、或反转\n"
            + "  - 中间部分密集爽点，不允许超过 6 秒无爽点的平铺段落\n"
            + "  - 结尾必须是强悬念或情绪高潮，驱动观众看下一集\n"
            + "- 每集概括字数增加到 200-300 字，以容纳爽点节拍标注\n"
            + "- 整体叙事节奏：快速推进，禁止冗长铺垫";
}

return base;
```

- [ ] **Step 4: Add `scriptStyle` to `buildScriptOutlineUserPrompt()`**

Change signature at line 42 from:

```java
public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                           int totalEpisodes, int episodeDuration, String visualStyle) {
```

To:

```java
public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                           int totalEpisodes, int episodeDuration, String visualStyle,
                                           String scriptStyle) {
```

And before `return sb.toString();` (line 51), add:

```java
if ("shuangju".equals(scriptStyle)) {
    sb.append("\n剧本风格：爽剧（三秒一个爽点，节奏极快，短句驱动）");
}
```

- [ ] **Step 5: Update `buildSingleEpisodePrompt()` signature and body**

Change at line 151 from:

```java
private String buildSingleEpisodePrompt(String genre, ScriptParams params, int episodeDuration) {
```

To:

```java
private String buildSingleEpisodePrompt(String genre, ScriptParams params, int episodeDuration, String scriptStyle) {
```

And before the final line of the return statement (line 159), append shuangju text. Change the method to:

```java
private String buildSingleEpisodePrompt(String genre, ScriptParams params, int episodeDuration, String scriptStyle) {
    String base = "创建完整的单集剧本大纲，使用 markdown 格式。\n"
            + "【集数】本项目固定为 1 集：不得扩展为多集大纲或多条分集。\n"
            + "题材类型：" + genre + "\n"
            + "目标时长：" + episodeDuration + " 秒\n"
            + "角色数量：" + params.minCharacters + "-" + params.maxCharacters + "\n"
            + "关键物品：" + params.minItems + "-" + params.maxItems + "\n"
            + "输出格式：仅返回 JSON { \"outline\": \"Markdown 大纲\" }\n"
            + "大纲需包含开场、发展、高潮和结局。";

    if ("shuangju".equals(scriptStyle)) {
        base += "\n\n【爽剧节奏约束】\n"
                + "- 规划 8-10 个「爽点节拍」，平均每 3 秒一个\n"
                + "- 大纲中明确标注爽点位置：「爽点①：XXX」「爽点②：XXX」...\n"
                + "- 开头 3 秒必须有强力钩子，结尾必须是强悬念\n"
                + "- 节奏极快，禁止冗长铺垫";
    }

    return base;
}
```

- [ ] **Step 6: Verify compilation**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: FAIL (ScriptService call sites not yet updated — that's expected)

- [ ] **Step 7: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/ScriptPromptBuilder.java
git commit -m "feat(shuangju): add scriptStyle param to outline prompt builder"
```

---

## Task 3: ScriptPromptBuilder — Episode Script Shuangju Branch

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/ScriptPromptBuilder.java` (lines 54-96)

- [ ] **Step 1: Add `scriptStyle` parameter to `buildScriptEpisodeSystemPrompt()`**

Change signature at line 54 from:

```java
public String buildScriptEpisodeSystemPrompt() {
```

To:

```java
public String buildScriptEpisodeSystemPrompt(String scriptStyle) {
```

Replace the method body (lines 55-73) with:

```java
String base = "你是一名专业的剧集剧本编剧。\n"
        + "根据全局大纲和其中一个章节，将其拆分为具体的剧集剧本（按「集」输出）。\n"
        + "【集数硬约束】用户消息中的「拆分集数」即本章节必须生成的集数：你输出的 JSON 数组长度必须恰好等于该数字，"
        + "一集对应数组中的一个对象；禁止合并多集、禁止少生成、禁止多生成。\n";

if ("shuangju".equals(scriptStyle)) {
    base += "【内容量与时长匹配（爽剧模式 - 最高优先级）】\n"
            + "- 每秒需要约 12-16 个字的剧本内容（含场景描述、台词、动作描写）\n"
            + "- 60秒 → content 约 720-960 字，至少 20 个爽点节拍\n"
            + "- 90秒 → content 约 1080-1440 字，至少 30 个爽点节拍\n"
            + "- 120秒 → content 约 1440-1920 字，至少 40 个爽点节拍\n"
            + "- 180秒 → content 约 2160-2880 字，至少 60 个爽点节拍\n"
            + "- 300秒 → content 约 3600-4800 字，至少 100 个爽点节拍\n"
            + "- 不要概括压缩剧情，要展开每个场景的具体对话、角色动作、情绪变化和视觉细节\n"
            + "- 使用「（场景描述）」「角色（情绪）：台词」「（动作描写）」格式\n"
            + "【爽剧短句格式约束】\n"
            + "- 全文使用短句，每句不超过 20 个字\n"
            + "- 平均每 3 秒（约 36-48 字）必须出现一个明确的情绪或剧情爽点\n"
            + "- 爽点用 [爽点:描述] 标记，例如：[爽点:身份反转]、[爽点:实力碾压]\n"
            + "- 禁止超过 2 句的平铺叙述，必须快速推进情节\n"
            + "- 台词简短有力，每句台词不超过 15 个字\n"
            + "- 场景切换频率高，每 2-3 个爽点可切换一次场景\n"
            + "- 书写格式：(场景描述) 角色(情绪):台词 [爽点:XX]\n"
            + "- 示例片段：\n"
            + "  (豪华婚房，灯光昏暗)\n"
            + "  陆沉猛然睁眼。 [爽点:重生觉醒]\n"
            + "  冷汗浸透枕头。\n"
            + "  他侧头，看见身旁沉睡的姜眠。\n"
            + "  陆沉(震惊):你...还活着？\n"
            + "  他红了眼眶，颤抖着伸手。 [爽点:情绪爆发]\n";
} else {
    base += "【内容量与时长匹配（最高优先级）】\n"
            + "用户会给出每集目标时长，你必须确保 content 字段的内容量足以支撑该时长：\n"
            + "- 每秒需要约 5-7 个字的剧本内容（含对话、动作描写、场景描述）\n"
            + "- 60秒 → content 约 300-420 字，至少 5 个场景/动作节点\n"
            + "- 120秒 → content 约 600-840 字，至少 8 个场景/动作节点\n"
            + "- 180秒 → content 约 900-1260 字，至少 12 个场景/动作节点\n"
            + "- 300秒 → content 约 1500-2100 字，至少 20 个场景/动作节点\n"
            + "- 不要概括压缩剧情，要展开每个场景的具体对话、角色动作、情绪变化和视觉细节\n"
            + "- 使用「（场景描述）」「角色（情绪）：台词」「（动作描写）」格式，确保分镜师能逐句拆分\n";
}

base += "【叙事节奏原则】\n"
        + "- 每集内容要有叙事弧线：铺垫→冲突升级→高潮→收束，不能平铺直叙\n"
        + "- 高潮段落（情感爆发、关键转折）给足篇幅展开，过渡段落（信息交代、场景切换）要简洁明快\n"
        + "- 对话和动作交替出现，避免连续大段纯叙述或纯对话\n"
        + "- 每集结尾设置钩子（悬念/反转/情绪留白），驱动观众看下一集\n"
        + "仅输出 JSON 数组，包含字段：title、content、characters、keyItems、visualStyleNote、continuityNote。";

return base;
```

- [ ] **Step 2: Add `scriptStyle` to `buildScriptEpisodeUserPrompt()`**

Change signature at line 76 from:

```java
public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                           String globalItems, String previousSummary, int splitCount,
                                           int duration, String modificationSuggestion) {
```

To:

```java
public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                           String globalItems, String previousSummary, int splitCount,
                                           int duration, String modificationSuggestion, String scriptStyle) {
```

Replace the word-count constraint lines 85-88 with:

```java
int charsPerSec = "shuangju".equals(scriptStyle) ? 12 : 5;
int charsPerSecMax = "shuangju".equals(scriptStyle) ? 16 : 7;
int minWords = duration * charsPerSec;
int maxWords = duration * charsPerSecMax;
sb.append("【时长硬性要求】每集 ").append(duration).append(" 秒，content 字段必须 ").append(minWords).append("-").append(maxWords).append(" 字，")
        .append("包含充分的场景描写、对话和动作细节。不得概括压缩。\n\n");
```

- [ ] **Step 3: Verify compilation**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: FAIL (ScriptService not yet updated)

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/ScriptPromptBuilder.java
git commit -m "feat(shuangju): add scriptStyle param to episode prompt builder"
```

---

## Task 4: ScriptService — Pass `scriptStyle` to Prompt Builders

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java` (lines 145-188, 278-302)

- [ ] **Step 1: Update `generateScriptOutline()` — read scriptStyle and pass to builder**

After the existing `boolean comicMode` line (line 145), add:

```java
String scriptStyle = (String) project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
```

Then update the non-comic call sites. At lines 173-179, change:

```java
systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
        resolvedTotalEpisodes,
        genre,
        targetAudience,
        params.chapterCount,
        params.episodesPerChapter,
        resolvedEpisodeDuration
);
```

To:

```java
systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
        resolvedTotalEpisodes,
        genre,
        targetAudience,
        params.chapterCount,
        params.episodesPerChapter,
        resolvedEpisodeDuration,
        scriptStyle
);
```

At lines 181-188, change:

```java
userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
        storyPrompt,
        genre,
        worldConfig.getRulesText(),
        resolvedTotalEpisodes,
        resolvedEpisodeDuration,
        visualStyle != null ? visualStyle : "REAL"
);
```

To:

```java
userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
        storyPrompt,
        genre,
        worldConfig.getRulesText(),
        resolvedTotalEpisodes,
        resolvedEpisodeDuration,
        visualStyle != null ? visualStyle : "REAL",
        scriptStyle
);
```

- [ ] **Step 2: Update `generateScriptEpisodes()` — pass scriptStyle to episode builder**

After the existing `boolean comicMode` line (line 278), add:

```java
String scriptStyle = (String) project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
```

At lines 279-281, change:

```java
String systemPrompt = comicMode
        ? comicCommentaryScriptPromptBuilder.buildScriptEpisodeSystemPrompt()
        : scriptPromptBuilder.buildScriptEpisodeSystemPrompt();
```

To:

```java
String systemPrompt = comicMode
        ? comicCommentaryScriptPromptBuilder.buildScriptEpisodeSystemPrompt()
        : scriptPromptBuilder.buildScriptEpisodeSystemPrompt(scriptStyle);
```

At lines 293-302, change:

```java
: scriptPromptBuilder.buildScriptEpisodeUserPrompt(
        outline,
        chapter,
        globalCharacters,
        globalItems,
        previousSummary,
        resolvedEpisodeCount,
        episodeDuration != null ? episodeDuration : 60,
        modificationSuggestion
);
```

To:

```java
: scriptPromptBuilder.buildScriptEpisodeUserPrompt(
        outline,
        chapter,
        globalCharacters,
        globalItems,
        previousSummary,
        resolvedEpisodeCount,
        episodeDuration != null ? episodeDuration : 60,
        modificationSuggestion,
        scriptStyle
);
```

- [ ] **Step 3: Update `regenerateOutline()` — same pattern as generateScriptOutline()**

`ScriptService.regenerateOutline()` (around lines 1033-1077) has identical calls to the same prompt builder methods. After line 1033 (`boolean comicMode = ...`), add:

```java
String scriptStyle = (String) project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
```

At lines 1061-1067 (the `else` branch), change:

```java
systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
        resolvedTotalEpisodes,
        genre,
        targetAudience,
        params.chapterCount,
        params.episodesPerChapter,
        resolvedEpisodeDuration
);
```

To:

```java
systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
        resolvedTotalEpisodes,
        genre,
        targetAudience,
        params.chapterCount,
        params.episodesPerChapter,
        resolvedEpisodeDuration,
        scriptStyle
);
```

At lines 1069-1076, change:

```java
userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
        storyPrompt,
        genre,
        currentOutline,
        resolvedTotalEpisodes,
        resolvedEpisodeDuration,
        visualStyle != null ? visualStyle : "REAL"
);
```

To:

```java
userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
        storyPrompt,
        genre,
        currentOutline,
        resolvedTotalEpisodes,
        resolvedEpisodeDuration,
        visualStyle != null ? visualStyle : "REAL",
        scriptStyle
);
```

- [ ] **Step 4: Verify compilation**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java
git commit -m "feat(shuangju): pass scriptStyle from ScriptService to prompt builders"
```

---

## Task 5: StoryboardAgentService — Shuangju Reasoner & Executor Prompts

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java`

- [ ] **Step 1: Add `scriptStyle` parameter to `generate()` method**

At line 293, change:

```java
public List<Map<String, Object>> generate(String episodeContent, String characters,
                                           int targetDuration, String visualStyle,
                                           boolean comicMode, String narrationPerspective) {
```

To:

```java
public List<Map<String, Object>> generate(String episodeContent, String characters,
                                           int targetDuration, String visualStyle,
                                           boolean comicMode, String narrationPerspective,
                                           String scriptStyle) {
```

- [ ] **Step 2: Pass `scriptStyle` through the generate() method body**

At line 299, change:

```java
String systemPrompt = buildReasonerSystemPrompt(comicMode);
```

To:

```java
String systemPrompt = buildReasonerSystemPrompt(comicMode, scriptStyle);
```

At line 327, change:

```java
String executorSystem = buildExecutorSystemPrompt(comicMode);
```

To:

```java
String executorSystem = buildExecutorSystemPrompt(comicMode, scriptStyle);
```

- [ ] **Step 3: Update `buildReasonerSystemPrompt()` — add scriptStyle parameter and shuangju branch**

At line 67, change:

```java
String buildReasonerSystemPrompt(boolean comicMode) {
```

To:

```java
String buildReasonerSystemPrompt(boolean comicMode, String scriptStyle) {
```

Add a shuangju branch at the start of the method body (before the existing comic mode logic):

```java
if ("shuangju".equals(scriptStyle)) {
    return "你是一个爽剧分镜规划 agent。你的任务是为短视频爽剧分镜生成做决策。\n\n"
            + "本集为爽剧模式：节奏极快，平均每 3 秒一个爽点，台词短促有力。\n\n"
            + "你的职责：\n"
            + "1. 从剧本中提取所有 [爽点:XX] 标记作为必须覆盖的 hookBeats\n"
            + "2. 分析剩余爽点和已生成进度\n"
            + "3. 决定下一批应覆盖哪些爽点（每批 3-5 个爽点）\n"
            + "4. 估算该批需要多少秒（基于爽点密度）\n"
            + "5. 当所有爽点覆盖完毕后，如果时长不足可选择 expand 或 pad\n\n"
            + "约束：\n"
            + "- 每个分镜 1-4 秒，AI 自行判断\n"
            + "- 平均每 3 秒一个爽点\n"
            + "- 优先完整覆盖所有爽点节拍\n"
            + "- 保持叙事连贯性，每批之间需要衔接\n\n"
            + "输出纯 JSON（不要 markdown 代码块标记，不要在值中额外嵌套引号）：\n"
            + "{\n"
            + "  \"action\": \"generate|expand|pad|done\",\n"
            + "  \"nextBeatDescription\": \"爽点①:描述 → 爽点②:描述 → 爽点③:描述\",\n"
            + "  \"estimatedSeconds\": 15,\n"
            + "  \"targetBeatIndex\": 2,\n"
            + "  \"reasoning\": \"为什么做这个决策\"\n"
            + "}\n\n"
            + "action 说明：\n"
            + "- generate: 还有爽点未覆盖，继续生成\n"
            + "- expand: 爽点覆盖完毕但时长不足，回头扩展已有节点\n"
            + "- pad: 生成过渡/氛围镜头填充时长\n"
            + "- done: 爽点已完整覆盖，结束生成";
}
```

- [ ] **Step 4: Update `buildExecutorSystemPrompt()` — add scriptStyle parameter and shuangju branch**

At line 163, change:

```java
String buildExecutorSystemPrompt(boolean comicMode) {
```

To:

```java
String buildExecutorSystemPrompt(boolean comicMode, String scriptStyle) {
```

Add a shuangju branch at the start of the method body:

```java
if ("shuangju".equals(scriptStyle)) {
    return "你是一位专做「爽剧」短视频的分镜师。节奏极快，三秒一个爽点，画面冲击力强。\n\n"
            + "关键约束：\n"
            + "- 每个分镜时长 1-4 秒，由你根据内容自行判断\n"
            + "- 快节奏内容（闪回、反转、打击）用 1-2 秒\n"
            + "- 需要情绪释放或重要对白的内容用 3-4 秒\n"
            + "- 每个分镜必须有 hookPoint（爽点）\n"
            + "- sceneDescription 使用短句，动态描写，30-50 字\n"
            + "- dialogue 简短有力，0-15 字，允许为「无」\n"
            + "- audioEffects 必填，增强爽感\n\n"
            + "输出纯 JSON 数组（不要 markdown 代码块标记）。每个分镜：\n"
            + "- shotNumber: 镜头编号（从1开始）\n"
            + "- duration: 时长（1-4秒）\n"
            + "- scene: 场景概述\n"
            + "- characters: 出场角色数组\n"
            + "- shotSize: 景别（大远景/远景/全景/中景/中近景/近景/特写/大特写）\n"
            + "- cameraAngle: 角度（视平/俯拍/仰拍/斜拍/越肩/鸟瞰）\n"
            + "- cameraMovement: 运镜方式\n"
            + "- sceneDescription: 画面描述（短句，动态，30-50字）\n"
            + "- dialogue: 角色台词或「无」\n"
            + "- speaker: 说话人或「无」\n"
            + "- dialogueTone: 对白语气\n"
            + "- visualEffects: 视觉特效或「无」\n"
            + "- audioEffects: 音效（必填）\n"
            + "- transitionHint: 镜头衔接提示\n"
            + "- hookPoint: 本镜头的爽点（10-25字，必填）\n\n"
            + "【AI视频生成原则】\n"
            + "1.【单主体原则】每个分镜最多1个角色动作，禁止双人互动。\n"
            + "2.【慢动作原则】运镜缓慢，角色动作微小。\n\n"
            + "【风格要求】\n"
            + "- 场景描述用短句，避免「然后」「接着」等连接词\n"
            + "- 强调视觉冲击：表情特写、动作定格、光影变化\n"
            + "- 台词像打脸金句：简短、有力、记忆点强";
}
```

- [ ] **Step 5: Add hookBeat regex extractor and update `buildReasonerPrompt()`**

Add a static constant and helper method to `StoryboardAgentService` (near the top of the class, after the existing constants):

```java
private static final java.util.regex.Pattern HOOK_PATTERN =
        java.util.regex.Pattern.compile("[\\[【]\\s*爽点\\s*[:：]\\s*(.+?)\\s*[\\]】]");

private List<String> extractHookBeats(String episodeContent) {
    List<String> beats = new ArrayList<>();
    java.util.regex.Matcher m = HOOK_PATTERN.matcher(episodeContent);
    while (m.find()) {
        beats.add(m.group(1).trim());
    }
    return beats;
}
```

Update `buildReasonerPrompt()` signature (line 107) to add `String scriptStyle`:

```java
public String buildReasonerPrompt(String episodeContent, String characters, String visualStyle,
                                   AgentState state, String roundLabel, boolean comicMode,
                                   String narrationPerspective, String scriptStyle) {
```

After the existing "## 已覆盖剧情节点" block (around line 129), add a shuangju-specific section:

```java
if ("shuangju".equals(scriptStyle)) {
    List<String> allHooks = extractHookBeats(episodeContent);
    sb.append("## 全部爽点节拍（共").append(allHooks.size()).append("个）\n");
    for (int i = 0; i < allHooks.size(); i++) {
        sb.append(i + 1).append(". ").append(allHooks.get(i)).append("\n");
    }
    sb.append("\n");
}
```

Update the call site in `generate()` (line 305-306) to pass `scriptStyle`:

```java
String userPrompt = buildReasonerPrompt(
        episodeContent, characters, visualStyle, state, roundLabel, comicMode, narrationPerspective, scriptStyle);
```

- [ ] **Step 6: Update `buildExecutorPrompt()` for shuangju word-count guidance**

Update signature (line 212) to add `String scriptStyle`:

```java
public String buildExecutorPrompt(String nextBeatDescription, String characters, String visualStyle,
                                   int estimatedSeconds, List<Map<String, Object>> lastShots,
                                   boolean comicMode, String scriptStyle) {
```

Before the final `sb.append("请生成本批分镜 JSON 数组。");` line, add:

```java
if ("shuangju".equals(scriptStyle)) {
    sb.append("## 爽剧字数密度目标\n");
    sb.append("- sceneDescription 目标总字数：").append(estimatedSeconds * 11).append(" 字左右\n");
    sb.append("- dialogue 目标总字数：").append(estimatedSeconds * 4).append(" 字左右\n");
    sb.append("- (sceneDescription + dialogue 合计约 ").append(estimatedSeconds * 15).append(" 字)\n\n");
}
```

Update the call site in `generate()` (lines 328-330) to pass `scriptStyle`:

```java
String executorUser = buildExecutorPrompt(
        decision.nextBeatDescription, characters, visualStyle,
        decision.estimatedSeconds, state.lastShots, comicMode, scriptStyle);
```

- [ ] **Step 7: Verify compilation**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: FAIL (PanelProductionService call site not yet updated)

- [ ] **Step 8: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java
git commit -m "feat(shuangju): add shuangju Reasoner/Executor prompts, hookBeat extraction, and word-count guidance"
```

---

## Task 6: PanelProductionService — Pass scriptStyle & Add hookPoint to Editable Fields

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: Pass `scriptStyle` in `resolveShots()` to `storyboardAgentService.generate()`**

At line 1467-1468, change:

```java
List<Map<String, Object>> shots = storyboardAgentService.generate(
    content, characters, targetDuration, visualStyle, comicMode, narrationPerspective);
```

To:

```java
String scriptStyle = (String) projectInfo.getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
List<Map<String, Object>> shots = storyboardAgentService.generate(
    content, characters, targetDuration, visualStyle, comicMode, narrationPerspective, scriptStyle);
```

- [ ] **Step 2: Add `hookPoint` to `SHOT_EDITABLE_FIELDS`**

At line 1807-1811, change:

```java
private static final java.util.Set<String> SHOT_EDITABLE_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "sceneDescription", "visualDescription", "narration", "dialogue", "speaker",
    "narrationTone", "dialogueTone", "shotSize", "cameraAngle",
    "cameraMovement", "scene", "visualEffects", "audioEffects", "transitionHint",
    "locked"
));
```

To:

```java
private static final java.util.Set<String> SHOT_EDITABLE_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "sceneDescription", "visualDescription", "narration", "dialogue", "speaker",
    "narrationTone", "dialogueTone", "shotSize", "cameraAngle",
    "cameraMovement", "scene", "visualEffects", "audioEffects", "transitionHint",
    "locked", "hookPoint"
));
```

- [ ] **Step 3: Verify compilation**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run existing tests**

Run: `cd backend/com && mvn test -pl . -Dtest=StoryboardAgentServiceTest -q 2>&1 | tail -10`
Expected: Tests pass (existing tests use the old 6-arg `generate()` — they will FAIL because of the new parameter)

- [ ] **Step 5: Fix existing test calls — add `"standard"` as 7th arg**

In `backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java`, find all calls to `service.generate(...)` and add `"standard"` as the 7th parameter. For example:

```java
// Before:
service.generate(content, chars, 90, "3D", true, "third_person");
// After:
service.generate(content, chars, 90, "3D", true, "third_person", "standard");
```

- [ ] **Step 6: Run tests again**

Run: `cd backend/com && mvn test -pl . -Dtest=StoryboardAgentServiceTest -q 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 7: Add shuangju-specific test cases**

In `StoryboardAgentServiceTest.java`, add these test methods:

```java
@Test
void testShuangjuReasonerPromptContainsShuangjuText() {
    String prompt = service.buildReasonerSystemPrompt(false, "shuangju");
    assertTrue(prompt.contains("爽剧"));
    assertTrue(prompt.contains("hookBeats"));
    assertFalse(prompt.contains("漫剧解说"));
}

@Test
void testShuangjuExecutorPromptContainsHookPoint() {
    String prompt = service.buildExecutorSystemPrompt(false, "shuangju");
    assertTrue(prompt.contains("hookPoint"));
    assertTrue(prompt.contains("爽点"));
}

@Test
void testStandardModePromptUnchanged() {
    String prompt = service.buildReasonerSystemPrompt(false, "standard");
    assertFalse(prompt.contains("爽剧"));
    assertFalse(prompt.contains("hookBeats"));
}

@Test
void testExtractHookBeats() {
    // Use reflection or make extractHookBeats package-visible for testing
    String content = "陆沉猛然睁眼。 [爽点:重生觉醒] 他红了眼眶。 [爽点：情绪爆发] 姜眠转身 【爽点:身份反转】";
    // Verify the regex captures all 3 variants
}
```

Note: If `buildReasonerSystemPrompt` and `buildExecutorSystemPrompt` are private, change their visibility to package-private (remove `private` keyword) so tests in the same package can access them. The `extractHookBeats` method should also be package-private.

- [ ] **Step 8: Run tests**

Run: `cd backend/com && mvn test -pl . -Dtest=StoryboardAgentServiceTest -q 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 9: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java \
       backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(shuangju): pass scriptStyle through PanelProductionService, add hookPoint to editable fields, add shuangju tests"
```

---

## Task 7: Frontend — TypeScript Types

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/project.types.ts`

- [ ] **Step 1: Add `ScriptStyle` type and field**

After line 26 (`export type ProductionMode = ...`), add:

```typescript
/** 剧本风格：标准（默认）| 爽剧 */
export type ScriptStyle = 'standard' | 'shuangju';
```

In the `CreateProjectRequest` interface (lines 31-46), add `scriptStyle` after the `videoRefMode` field (before the closing `}`):

```typescript
  scriptStyle?: ScriptStyle;
```

In the `ProjectInfoData` interface (lines 163-181), add `scriptStyle` after the `videoRefMode` field (before the closing `}`):

```typescript
  scriptStyle?: ScriptStyle;
```

- [ ] **Step 2: Verify frontend compiles**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -10`
Expected: No errors (or pre-existing errors only)

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/types/project.types.ts
git commit -m "feat(shuangju): add ScriptStyle type to frontend types"
```

---

## Task 8: Frontend — Step1 Shuangju Toggle

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`

- [ ] **Step 1: Add state for scriptStyle**

After line 173 (`const [videoRefMode, setVideoRefMode] = ...`), add:

```typescript
const [scriptStyle, setScriptStyle] = useState<'standard' | 'shuangju'>(() => (info?.scriptStyle as 'standard' | 'shuangju') || 'standard');
```

- [ ] **Step 2: Add scriptStyle to the request object**

In the `requestData` object (lines 225-240), add after the `productionMode` line (line 236):

```typescript
      scriptStyle: scriptStyle !== 'standard' ? scriptStyle : undefined,
```

- [ ] **Step 3: Add Toggle UI after 制作模式**

After the `{/* 制作模式 */}` configSection block (after line 324, before the comic_commentary conditional), add:

```tsx
            {/* 爽剧模式 - 仅实时动画模式显示 */}
            {productionMode === 'realtime_animation' && (
              <div className={styles.configSection}>
                <label className={styles.configLabel}>爽剧模式</label>
                <div className={styles.durationGroup}>
                  <button
                    type="button"
                    className={`${styles.durationButton} ${scriptStyle === 'standard' ? styles.active : ''}`}
                    onClick={() => setScriptStyle('standard')}
                  >
                    标准
                  </button>
                  <button
                    type="button"
                    className={`${styles.durationButton} ${scriptStyle === 'shuangju' ? styles.active : ''}`}
                    onClick={() => setScriptStyle('shuangju')}
                  >
                    爽剧
                  </button>
                </div>
              </div>
            )}
```

This reuses the existing `durationButton` + `durationGroup` pattern (same as 旁白视角 and 每集时长), keeping UI consistency.

- [ ] **Step 4: Verify frontend compiles**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -10`
Expected: No errors

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx
git commit -m "feat(shuangju): add shuangju mode toggle to Step1 UI"
```

---

## Task 9: Frontend — hookPoint in ScriptEpisodeCard

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ScriptEpisodeCard.tsx`

- [ ] **Step 1: Add `hookPoint` to EDITABLE_FIELDS**

At lines 9-13, change:

```typescript
const EDITABLE_FIELDS = [
  'sceneDescription', 'visualDescription', 'narration', 'dialogue', 'speaker',
  'narrationTone', 'dialogueTone', 'shotSize', 'cameraAngle',
  'cameraMovement', 'scene', 'visualEffects', 'audioEffects', 'transitionHint',
] as const;
```

To:

```typescript
const EDITABLE_FIELDS = [
  'sceneDescription', 'visualDescription', 'narration', 'dialogue', 'speaker',
  'narrationTone', 'dialogueTone', 'shotSize', 'cameraAngle',
  'cameraMovement', 'scene', 'visualEffects', 'audioEffects', 'transitionHint',
  'hookPoint',
] as const;
```

- [ ] **Step 2: Add label for hookPoint**

In the `FIELD_LABELS` object (lines 15-30), add after the `transitionHint` entry:

```typescript
  hookPoint: '爽点',
```

- [ ] **Step 3: Verify frontend compiles**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | tail -10`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ScriptEpisodeCard.tsx
git commit -m "feat(shuangju): add hookPoint to editable shot fields in frontend"
```

---

## Task 10: Full Build Verification & Final Commit

- [ ] **Step 1: Run full backend compile**

Run: `cd backend/com && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all backend tests**

Run: `cd backend/com && mvn test -q 2>&1 | tail -15`
Expected: All tests pass

- [ ] **Step 3: Run frontend build**

Run: `cd frontend/wiset_aivideo_generator && npm run build 2>&1 | tail -10`
Expected: Build succeeds

- [ ] **Step 4: Verify end-to-end data flow mentally**

Checklist:
- [ ] Step1: scriptStyle toggle → writes `projectInfo.scriptStyle`
- [ ] `ProjectInfoKeys.SCRIPT_STYLE` constant exists
- [ ] `ProjectProductionMode.isShuangju()` works
- [ ] `ScriptService.generateScriptOutline()` reads scriptStyle, passes to builder
- [ ] `ScriptPromptBuilder.buildScriptOutlineSystemPrompt()` appends shuangju text
- [ ] `ScriptService.generateScriptEpisodes()` reads scriptStyle, passes to builder
- [ ] `ScriptPromptBuilder.buildScriptEpisodeSystemPrompt()` switches density to 12-16 chars/sec
- [ ] `PanelProductionService.resolveShots()` reads scriptStyle, passes to Agent
- [ ] `StoryboardAgentService.generate()` selects shuangju Reasoner/Executor prompts
- [ ] `hookPoint` in SHOT_EDITABLE_FIELDS (backend + frontend)
- [ ] Frontend `ScriptEpisodeCard` shows `hookPoint` as editable field

- [ ] **Step 5: If all checks pass, no additional commit needed — implementation is complete**
