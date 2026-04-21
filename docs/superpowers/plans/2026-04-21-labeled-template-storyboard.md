# 分镜标签模板改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将分镜 sceneDescription 从自由文本改为结构化标签模板格式，LLM 直接输出标签文本，下游通过解析器按需提取。

**Architecture:** 新建 `StoryboardTemplateParser`（解析标签文本）和 `StoryboardTemplateAssembler`（拼装提示词模板），修改 `StoryboardAgentService` Phase 4 prompt 让 LLM 直接输出标签格式，修改 `PanelPromptBuilder` / `ComicCommentaryPanelPromptBuilder` 使用解析器提取可视标签。

**Tech Stack:** Java 11+ (Spring Boot), JUnit 5, React/TypeScript (前端)

**Spec:** `docs/superpowers/specs/2026-04-21-labeled-template-storyboard-design.md`

---

## File Structure

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateParser.java` | 解析标签模板文本，提取可视标签/内声/走位/音效 |
| 新建 | `backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateAssembler.java` | 拼装视频/图片提示词模板 |
| 新建 | `backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateParserTest.java` | 解析器单元测试 |
| 新建 | `backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateAssemblerTest.java` | 拼装器单元测试 |
| 修改 | `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java` | Phase 4 精修 prompt 改为标签模板格式 |
| 修改 | `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java` | 图片提示词用 extractVisualLabels；视频提示词用 assembleVideoTemplate |
| 修改 | `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java` | 同 PanelPromptBuilder 的改动 |
| 修改 | `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` | 移除 visualDescription 读取，移除 applyToShots 调用 |
| 修改 | `frontend/.../services/types/episode.types.ts` | StoryboardShot 接口：visualDescription → sceneDescription |
| 修改 | `frontend/.../steps/types.ts` | ShotEditForm 中 visualDescription → sceneDescription |
| 修改 | `frontend/.../steps/components/ShotDetail.tsx` | 展示 sceneDescription 替代 visualDescription |
| 修改 | `frontend/.../steps/components/EpisodeCard.tsx` | splitShot 描述展示改为 sceneDescription |
| 修改 | `frontend/.../steps/components/PanelGroupView.tsx` | shot 描述展示改为 sceneDescription |
| 修改 | `frontend/.../steps/components/StoryboardGrid.tsx` | 条件渲染和文本展示改为 sceneDescription |
| 修改 | `frontend/.../steps/components/VideoSegmentRow.tsx` | 描述展示改为 sceneDescription |
| 修改 | `frontend/.../steps/components/SegmentCard.tsx` | 视频提示词构建 + 展示改为 sceneDescription |
| 修改 | `frontend/.../steps/components/ScriptEpisodeCard.tsx` | 字段列表、标签名、fallback 全改为 sceneDescription |
| 修改 | `frontend/.../steps/Step4Production.tsx` | 多处 fallback 读取改为 sceneDescription |
| 修改 | `backend/.../StoryboardLabelTemplateFormatter.java` | 保留旧文件不动，向后兼容旧数据 |

---

### Task 1: 创建 StoryboardTemplateParser

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateParser.java`
- Create: `backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateParserTest.java`

- [ ] **Step 1: 写 StoryboardTemplateParser 数据类和空壳方法**

在 `backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateParser.java` 创建解析器：

```java
package com.comic.service.production;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分镜标签模板解析器。
 * 解析 LLM 输出的标签模板文本（sceneDescription），按需提取可视标签、内声、走位、音效等。
 */
public final class StoryboardTemplateParser {

    // ===== 标签常量 =====
    private static final String LABEL_BLOCKING = "（走位）";
    private static final String LABEL_ACTION = "（动作）";
    private static final String LABEL_ENVIRONMENT = "（环境）";
    private static final String LABEL_STATE = "（状态）";
    private static final String LABEL_EXPRESSION = "（表情）";
    private static final String LABEL_AUDIO = "（音效）";
    private static final String PREFIX_INNER_VOICE = "内声：";

    // 半角 → 全角 归一化
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "[(（](走位|动作|环境|状态|表情|音效)[)）]|内声：");
    private static final Pattern BLOCKING_PATTERN = Pattern.compile(
            "[(（]走位[)）]\\s*(.+?)[｜|]\\s*位置锁[＝=](.+?)[｜|]\\s*姿态锁[＝=](.+?)[｜|]\\s*朝向锁[＝=](.+?)[｜|]\\s*道具锁[＝=](.+)");
    private static final Pattern INNER_VOICE_PATTERN = Pattern.compile(
            "内声：[（(](?:旁白)[）)]?[「『](.+?)[」』]|内声：[「『](?:([^：:]+)[：:])?(.+?)[」』]");

    private StoryboardTemplateParser() {}

    // ===== 数据类 =====
    public static class DialogueLine {
        public final String speaker;
        public final String content;
        public final String tone;
        public DialogueLine(String speaker, String content, String tone) {
            this.speaker = speaker; this.content = content; this.tone = tone;
        }
    }

    public static class BlockingInfo {
        public final String characterName;
        public final String position;
        public final String posture;
        public final String orientation;
        public final String prop;
        public BlockingInfo(String characterName, String position, String posture,
                           String orientation, String prop) {
            this.characterName = characterName;
            this.position = position; this.posture = posture;
            this.orientation = orientation; this.prop = prop;
        }
    }

    // ===== 公开方法 =====

    /**
     * 提取可视标签文本（动作+环境+状态+表情），跳过走位/内声/音效。
     * 返回纯文本，每行一个标签，保留标签名前缀。
     */
    public static String extractVisualLabels(String template) {
        if (template == null || template.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : template.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (isVisualLabel(trimmed)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    /**
     * 提取内声行列表。
     */
    public static List<DialogueLine> extractDialogue(String template) {
        if (template == null || template.trim().isEmpty()) return Collections.emptyList();
        List<DialogueLine> lines = new ArrayList<>();
        for (String line : template.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("内声：") && !trimmed.startsWith("内声:")) continue;
            String content = trimmed.substring(3).trim();
            // 提取括号内角色名
            String speaker = "";
            String tone = "";
            // 内声：（旁白）「...」
            if (content.startsWith("（旁白）") || content.startsWith("(旁白)")) {
                speaker = "旁白";
                content = content.substring(4).trim();
            }
            // 去掉书名号
            content = unwrapBrackets(content);
            // 内声：「角色名：台词」
            int colonIdx = content.indexOf('：');
            if (colonIdx < 0) colonIdx = content.indexOf(':');
            if (colonIdx > 0 && (speaker.isEmpty() || !"旁白".equals(speaker))) {
                speaker = content.substring(0, colonIdx);
                content = content.substring(colonIdx + 1);
            }
            lines.add(new DialogueLine(speaker, content, tone));
        }
        return lines;
    }

    /**
     * 提取音效列表。
     */
    public static List<String> extractAudioEffects(String template) {
        if (template == null || template.trim().isEmpty()) return Collections.emptyList();
        List<String> effects = new ArrayList<>();
        for (String line : template.split("\\R")) {
            String trimmed = line.trim();
            if (startsWithLabel(trimmed, LABEL_AUDIO)) {
                effects.add(extractLabelContent(trimmed, LABEL_AUDIO));
            }
        }
        return effects;
    }

    /**
     * 提取走位信息。
     */
    public static List<BlockingInfo> extractBlocking(String template) {
        if (template == null || template.trim().isEmpty()) return Collections.emptyList();
        List<BlockingInfo> result = new ArrayList<>();
        for (String line : template.split("\\R")) {
            String trimmed = line.trim();
            // 归一化半角
            String normalized = trimmed.replace('|', '｜').replace('=', '＝')
                    .replace('(', '（').replace(')', '）');
            Matcher m = BLOCKING_PATTERN.matcher(normalized);
            if (m.find()) {
                result.add(new BlockingInfo(
                        m.group(1).trim(), m.group(2).trim(), m.group(3).trim(),
                        m.group(4).trim(), m.group(5).trim()));
            }
        }
        return result;
    }

    /**
     * 判断是否为标签模板文本（兼容旧格式和新格式）。
     * 要求至少包含 2 个不同类型的标签，避免将包含单个关键词的自由文本误判为模板。
     */
    public static boolean isTemplateText(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String normalized = text.trim();
        Matcher m = LABEL_PATTERN.matcher(normalized);
        int count = 0;
        while (m.find()) count++;
        return count >= 2;
    }

    // ===== 内部方法 =====

    private static boolean isVisualLabel(String line) {
        return startsWithLabel(line, LABEL_ACTION)
                || startsWithLabel(line, LABEL_ENVIRONMENT)
                || startsWithLabel(line, LABEL_STATE)
                || startsWithLabel(line, LABEL_EXPRESSION);
    }

    private static boolean startsWithLabel(String line, String label) {
        if (line.startsWith(label)) return true;
        // 半角容错
        String halfWidth = label.replace('（', '(').replace('）', ')');
        return line.startsWith(halfWidth);
    }

    private static String extractLabelContent(String line, String label) {
        if (line.startsWith(label)) return line.substring(label.length()).trim();
        String halfWidth = label.replace('（', '(').replace('）', ')');
        if (line.startsWith(halfWidth)) return line.substring(halfWidth.length()).trim();
        return line;
    }

    private static String unwrapBrackets(String text) {
        if (text == null) return "";
        // 去掉首尾的「」或『』
        if ((text.startsWith("「") && text.endsWith("」"))
                || (text.startsWith("『") && text.endsWith("』"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
```

- [ ] **Step 2: 写 StoryboardTemplateParserTest 测试**

在 `backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateParserTest.java`：

```java
package com.comic.service.production;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryboardTemplateParserTest {

    private static final String SAMPLE_TEMPLATE =
            "（走位）纪兰嫣｜位置锁=玉石台阶边｜姿态锁=坐着｜朝向锁=侧对广场中央｜道具锁=无\n" +
            "（走位）谢长音｜位置锁=广场中央席位｜姿态锁=端坐｜朝向锁=面向玉石台阶边｜道具锁=茶盏\n" +
            "（动作）谢长音端起茶盏\n" +
            "（动作）谢长音将茶盏凑近唇边\n" +
            "（环境）温热茶雾袅袅升起\n" +
            "（状态）纪兰嫣坐在台阶上、姿势随意\n" +
            "（环境）金色阳光透过稀薄云层\n" +
            "（表情）纪兰嫣轻轻蹙眉\n" +
            "（表情）纪兰嫣双眼放空\n" +
            "内声：「谢长音：天品水灵根，甚是少见。」\n" +
            "（音效）内声贴耳【AUX】\n" +
            "内声：「谢长音：加之如此容颜，定会被各峰觊觎。」\n" +
            "（音效）心声微沉【AUX】";

    @Test
    void extractVisualLabels_shouldReturnOnlyActionEnvStateExpression() {
        String result = StoryboardTemplateParser.extractVisualLabels(SAMPLE_TEMPLATE);
        assertFalse(result.contains("走位"));
        assertFalse(result.contains("内声"));
        assertFalse(result.contains("音效"));
        assertTrue(result.contains("（动作）谢长音端起茶盏"));
        assertTrue(result.contains("（环境）温热茶雾袅袅升起"));
        assertTrue(result.contains("（状态）纪兰嫣坐在台阶上、姿势随意"));
        assertTrue(result.contains("（表情）纪兰嫣轻轻蹙眉"));
    }

    @Test
    void extractVisualLabels_shouldReturnEmpty_forNullInput() {
        assertEquals("", StoryboardTemplateParser.extractVisualLabels(null));
        assertEquals("", StoryboardTemplateParser.extractVisualLabels(""));
    }

    @Test
    void extractDialogue_shouldParseSpeakerAndContent() {
        var lines = StoryboardTemplateParser.extractDialogue(SAMPLE_TEMPLATE);
        assertEquals(2, lines.size());
        assertEquals("谢长音", lines.get(0).speaker);
        assertEquals("天品水灵根，甚是少见。", lines.get(0).content);
        assertEquals("谢长音", lines.get(1).speaker);
        assertEquals("加之如此容颜，定会被各峰觊觎。", lines.get(1).content);
    }

    @Test
    void extractDialogue_shouldHandleNarrationFormat() {
        String template = "内声：（旁白）「黄昏时分，夕阳西下。」";
        var lines = StoryboardTemplateParser.extractDialogue(template);
        assertEquals(1, lines.size());
        assertEquals("旁白", lines.get(0).speaker);
        assertEquals("黄昏时分，夕阳西下。", lines.get(0).content);
    }

    @Test
    void extractDialogue_shouldReturnEmpty_forNoInnerVoice() {
        String template = "（动作）某人做了某事\n（环境）天气很好";
        var lines = StoryboardTemplateParser.extractDialogue(template);
        assertTrue(lines.isEmpty());
    }

    @Test
    void extractAudioEffects_shouldReturnAudioLabels() {
        var effects = StoryboardTemplateParser.extractAudioEffects(SAMPLE_TEMPLATE);
        assertEquals(2, effects.size());
        assertTrue(effects.get(0).contains("内声贴耳"));
        assertTrue(effects.get(1).contains("心声微沉"));
    }

    @Test
    void extractBlocking_shouldParsePositionPoseOrientationProp() {
        var blocking = StoryboardTemplateParser.extractBlocking(SAMPLE_TEMPLATE);
        assertEquals(2, blocking.size());
        assertEquals("纪兰嫣", blocking.get(0).characterName);
        assertEquals("玉石台阶边", blocking.get(0).position);
        assertEquals("坐着", blocking.get(0).posture);
        assertEquals("侧对广场中央", blocking.get(0).orientation);
        assertEquals("无", blocking.get(0).prop);

        assertEquals("谢长音", blocking.get(1).characterName);
        assertEquals("茶盏", blocking.get(1).prop);
    }

    @Test
    void extractBlocking_shouldHandleHalfWidthChars() {
        String template = "(走位)张三|位置锁=门口|姿态锁=站立|朝向锁=面向窗户|道具锁=剑";
        var blocking = StoryboardTemplateParser.extractBlocking(template);
        assertEquals(1, blocking.size());
        assertEquals("张三", blocking.get(0).characterName);
        assertEquals("门口", blocking.get(0).position);
    }

    @Test
    void isTemplateText_shouldRecognizeNewFormat() {
        assertTrue(StoryboardTemplateParser.isTemplateText(SAMPLE_TEMPLATE));
    }

    @Test
    void isTemplateText_shouldRecognizeHalfWidthFormat() {
        assertTrue(StoryboardTemplateParser.isTemplateText("(动作)某人做了某事\n(环境)天气很好"));
    }

    @Test
    void isTemplateText_shouldReturnFalse_forPlainFreeText() {
        assertFalse(StoryboardTemplateParser.isTemplateText("这是一个普通的自由文本描述"));
        assertFalse(StoryboardTemplateParser.isTemplateText(null));
        assertFalse(StoryboardTemplateParser.isTemplateText(""));
        assertFalse(StoryboardTemplateParser.isTemplateText("（动作）只有一行")); // 不足2个标签
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardTemplateParserTest -Dsurefire.useFile=false`

Expected: 全部 PASS

- [ ] **Step 4: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateParser.java
git add backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateParserTest.java
git commit -m "feat(storyboard): add StoryboardTemplateParser for labeled template parsing"
```

---

### Task 2: 创建 StoryboardTemplateAssembler

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateAssembler.java`
- Create: `backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateAssemblerTest.java`

- [ ] **Step 1: 写 StoryboardTemplateAssembler**

在 `backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateAssembler.java`：

```java
package com.comic.service.production;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分镜标签模板拼装器。
 * 将 scene 字段、角色名、各 shot 的 sceneDescription 拼装为完整的提示词模板。
 */
public final class StoryboardTemplateAssembler {

    private static final String LABEL_SCENE = "（场景）";
    private static final String LABEL_CHARACTER = "（出场）";

    private StoryboardTemplateAssembler() {}

    /**
     * 拼装完整视频提示词模板。
     * @param scenes 各 shot 的 scene 字段（会去重）
     * @param characterNames 本组所有出场角色名
     * @param shotDescriptions 各 shot 的 sceneDescription（标签模板文本）
     */
    public static String assembleVideoTemplate(List<String> scenes,
                                                List<String> characterNames,
                                                List<String> shotDescriptions) {
        StringBuilder sb = new StringBuilder();

        // （场景）行：去重拼接
        Set<String> uniqueScenes = new LinkedHashSet<>();
        for (String s : scenes) {
            if (s != null && !s.trim().isEmpty()) uniqueScenes.add(s.trim());
        }
        sb.append(LABEL_SCENE).append(uniqueScenes.isEmpty() ? "无" : String.join("｜", uniqueScenes)).append('\n');

        // （出场）行：角色名拼接
        List<String> validNames = new ArrayList<>();
        for (String n : characterNames) {
            if (n != null && !n.trim().isEmpty()) validNames.add(n.trim());
        }
        sb.append(LABEL_CHARACTER).append(validNames.isEmpty() ? "无" : String.join("、", validNames)).append('\n');

        // 各 shot 的 sceneDescription 依次追加
        for (String desc : shotDescriptions) {
            if (desc != null && !desc.trim().isEmpty()) {
                sb.append('\n').append(desc.trim()).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * 拼装图片提示词模板（单 shot 的可视标签 + 简化场景）。
     */
    public static String assembleImageTemplate(String scene, String visualLabels) {
        StringBuilder sb = new StringBuilder();
        if (scene != null && !scene.trim().isEmpty()) {
            sb.append("场景：").append(scene.trim()).append('。');
        }
        if (visualLabels != null && !visualLabels.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(visualLabels.trim());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: 写 StoryboardTemplateAssemblerTest**

在 `backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateAssemblerTest.java`：

```java
package com.comic.service.production;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class StoryboardTemplateAssemblerTest {

    @Test
    void assembleVideoTemplate_shouldDeduplicateScenes() {
        String result = StoryboardTemplateAssembler.assembleVideoTemplate(
                Arrays.asList("登仙广场", "登仙广场", "山门"),
                Arrays.asList("纪兰嫣", "谢长音"),
                Arrays.asList("（动作）谢长音端起茶盏", "（动作）纪兰嫣歪了歪头"));
        assertTrue(result.startsWith("（场景）登仙广场｜山门"));
        assertTrue(result.contains("（出场）纪兰嫣、谢长音"));
        assertTrue(result.contains("（动作）谢长音端起茶盏"));
    }

    @Test
    void assembleVideoTemplate_shouldHandleEmptyInputs() {
        String result = StoryboardTemplateAssembler.assembleVideoTemplate(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertTrue(result.contains("（场景）无"));
        assertTrue(result.contains("（出场）无"));
    }

    @Test
    void assembleImageTemplate_shouldCombineSceneAndVisualLabels() {
        String result = StoryboardTemplateAssembler.assembleImageTemplate(
                "登仙广场",
                "（动作）谢长音端起茶盏\n（环境）温热茶雾");
        assertTrue(result.startsWith("场景：登仙广场。"));
        assertTrue(result.contains("（动作）谢长音端起茶盏"));
    }

    @Test
    void assembleImageTemplate_shouldHandleNullInputs() {
        assertEquals("", StoryboardTemplateAssembler.assembleImageTemplate(null, null));
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardTemplateAssemblerTest -Dsurefire.useFile=false`

Expected: 全部 PASS

- [ ] **Step 4: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardTemplateAssembler.java
git add backend/com/comic/src/test/java/com/comic/service/production/StoryboardTemplateAssemblerTest.java
git commit -m "feat(storyboard): add StoryboardTemplateAssembler for prompt template assembly"
```

---

### Task 3: 修改 StoryboardAgentService Phase 4 Prompt

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java`
  - `buildRefineSystemPrompt()` (line 435-525)
  - `buildRefineUserPrompt()` (line 527-625)
  - `skeletonToFallbackShots()` (line 643-661)

- [ ] **Step 1: 修改 buildRefineSystemPrompt**

在 `buildRefineSystemPrompt` 方法中：
1. 更新输出字段列表：移除 `visualDescription`，保留其余字段
2. 在字段列表后新增 sceneDescription 标签模板格式说明和完整示例
3. 在角色一致性约束前新增标签规则段
4. 在模式分支中新增标签策略段

找到以下代码段（约 line 449）：
```java
        sb.append("你需要输出相同数量的完整 shot JSON 数组，每个 shot 包含：\n");
        sb.append("shotNumber, duration(保持不变), scene, characters(必须使用骨架中的角色全名),\n");
        sb.append("shotSize, cameraAngle, cameraMovement, sceneDescription,\n");
        sb.append("dialogue, speaker, dialogueTone, visualEffects, audioEffects, transitionHint\n");
```

替换为：
```java
        sb.append("你需要输出相同数量的完整 shot JSON 数组，每个 shot 包含：\n");
        sb.append("shotNumber, duration(保持不变), scene, characters(必须使用骨架中的角色全名),\n");
        sb.append("shotSize, cameraAngle, cameraMovement, sceneDescription,\n");
        sb.append("dialogue, speaker, visualEffects, audioEffects, transitionHint\n");
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            sb.append(", hookPoint\n");
        }
        if (comicMode) {
            sb.append(", narration\n");
        }
        sb.append("\n");
```

然后在 `sb.append("【角色一致性` 之前插入标签模板格式说明：

```java
        sb.append("【sceneDescription 标签模板格式 - 必须严格遵守】\n");
        sb.append("sceneDescription 必须使用以下标签模板格式，每行一个标签，不要写自由文本：\n\n");
        sb.append("格式规范：\n");
        sb.append("（走位）{角色名}｜位置锁={位置}｜姿态锁={姿态}｜朝向锁={朝向}｜道具锁={道具}\n");
        sb.append("（动作）{角色名}{简单句描述一个原子动作}\n");
        sb.append("（环境）{简单句描述环境细节}\n");
        sb.append("（状态）{角色名}{简单句描述状态}\n");
        sb.append("（表情）{角色名}{简单句描述表情变化}\n");
        sb.append("内声：「{角色名}：{台词}」\n");
        sb.append("（音效）{音效描述}\n\n");
        sb.append("规则：\n");
        sb.append("1. 每个标签只用一个简单句，禁止复合句\n");
        sb.append("2. 同类标签可出现多次，每次描述一个原子事件\n");
        sb.append("3. （动作）/（状态）/（表情）内容必须以角色名开头\n");
        sb.append("4. 不需要写（场景）和（出场），系统会自动拼装\n");
        sb.append("5. 内声和（音效）可以交错出现\n");
        sb.append("6. 该类别无内容时写（动作）无\n\n");
        sb.append("示例：\n");
        sb.append("（走位）纪兰嫣｜位置锁=玉石台阶边｜姿态锁=坐着｜朝向锁=侧对广场中央｜道具锁=无\n");
        sb.append("（走位）谢长音｜位置锁=广场中央席位｜姿态锁=端坐｜朝向锁=面向玉石台阶边｜道具锁=茶盏\n");
        sb.append("（动作）谢长音端起茶盏\n");
        sb.append("（动作）谢长音将茶盏凑近唇边\n");
        sb.append("（环境）温热茶雾袅袅升起\n");
        sb.append("（状态）纪兰嫣坐在台阶上、姿势随意\n");
        sb.append("（表情）纪兰嫣轻轻蹙眉\n");
        sb.append("内声：「谢长音：天品水灵根，甚是少见。」\n");
        sb.append("（音效）内声贴耳\n\n");
```

在模式分支中（爽剧/解说/标准）新增标签策略：

找到爽剧模式段（约 line 501），在 `sb.append("- hookPoint` 后新增：
```java
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            // ... 现有 hookPoint 规则 ...
            sb.append("【标签策略】以（动作）和（表情）为主，每个镜头1-3个原子动作。内声密集（70%+镜头有台词）。环境描写精简。1-2s镜头只写1个（动作）+1个（表情）。走位可简略。\n\n");
        }
```

找到解说模式段，在现有规则后新增：
```java
            sb.append("【标签策略】以（环境）和（状态）为主，营造画面氛围。内声使用旁白格式「内声：（旁白）「…」」。不用角色对话。走位可简略。每个镜头2-3个环境/状态标签。\n\n");
```

在标准模式（即 else 兜底位置）新增：
```java
        if (!ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) && !comicMode) {
            sb.append("【标签策略】动作、环境、状态、表情平衡使用，内声适度。\n\n");
        }
```

- [ ] **Step 2: 修改对话约束段**

在对话约束段中，移除 `dialogueTone` 引用。找到（约 line 477）：
```java
        sb.append("4. 当 dialogue 为空时，speaker 填 \"无\"，dialogueTone 填 \"无\"，sceneDescription 应更详细\n\n");
```
替换为：
```java
        sb.append("4. 当 dialogue 为空时，speaker 填 \"无\"，sceneDescription 应更详细\n\n");
```

- [ ] **Step 3: 修改 skeletonToFallbackShots**

在 `skeletonToFallbackShots` 方法中，移除 `visualDescription` 相关字段，生成标签模板格式的 sceneDescription：

找到（约 line 643-661）：
```java
    private List<Map<String, Object>> skeletonToFallbackShots(List<Map<String, Object>> skeletons) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> sk : skeletons) {
            Map<String, Object> shot = new HashMap<>(sk);
            shot.putIfAbsent("scene", sk.getOrDefault("sceneHint", ""));
            shot.putIfAbsent("sceneDescription", sk.getOrDefault("sceneHint", ""));
            shot.putIfAbsent("shotSize", "MEDIUM");
            shot.putIfAbsent("cameraAngle", "eye_level");
            shot.putIfAbsent("cameraMovement", "static");
            shot.putIfAbsent("dialogue", "");
            shot.putIfAbsent("speaker", "无");
            shot.putIfAbsent("dialogueTone", "无");
            shot.putIfAbsent("visualEffects", "无");
            shot.putIfAbsent("audioEffects", "无");
            shot.putIfAbsent("transitionHint", "过渡到下一镜");
            result.add(shot);
        }
        return result;
    }
```

替换为：
```java
    private List<Map<String, Object>> skeletonToFallbackShots(List<Map<String, Object>> skeletons) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> sk : skeletons) {
            Map<String, Object> shot = new HashMap<>(sk);
            shot.putIfAbsent("scene", sk.getOrDefault("sceneHint", ""));
            String hint = (String) sk.getOrDefault("sceneHint", "");
            shot.putIfAbsent("sceneDescription", "（动作）" + (hint.isEmpty() ? "无" : hint));
            shot.putIfAbsent("shotSize", "MEDIUM");
            shot.putIfAbsent("cameraAngle", "eye_level");
            shot.putIfAbsent("cameraMovement", "static");
            shot.putIfAbsent("dialogue", "");
            shot.putIfAbsent("speaker", "无");
            shot.putIfAbsent("visualEffects", "无");
            shot.putIfAbsent("audioEffects", "无");
            shot.putIfAbsent("transitionHint", "过渡到下一镜");
            result.add(shot);
        }
        return result;
    }
```

- [ ] **Step 4: 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -pl . -q`

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java
git commit -m "feat(storyboard): update Phase 4 refine prompt for labeled template format"
```

---

### Task 4: 修改 PanelPromptBuilder

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java`
  - `buildGridPrompt()` (line 264-308): 用 extractVisualLabels 替代 flattenMultilineText
  - `buildMultiShotPrompt()` (line 422-477): 用 sceneDescription 替代 visualDescription

- [ ] **Step 1: 修改 buildGridPrompt 中的 sceneDescription 处理**

找到 buildGridPrompt 方法中（约 line 270-286）：
```java
            String sceneDescription = getShotValue(shot, "sceneDescription", "scene_description");
            if (sceneDescription != null && !sceneDescription.isEmpty()) {
                sceneDescription = flattenMultilineText(sceneDescription);
                // 5×5 大宫格时裁剪描述，避免 prompt 过长导致上游超时
                if (isLargeGrid && sceneDescription.length() > 80) {
                    sceneDescription = sceneDescription.substring(0, 80) + "…";
                }
                sb.append(sceneDescription);
            } else {
                String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
                sb.append(flattenMultilineText(visualDescription != null ? visualDescription : ""));
                String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
                if (cameraMovement != null && !cameraMovement.isEmpty()) {
                    sb.append("，").append(cameraMovement);
                }
            }
```

替换为：
```java
            String sceneDescription = getShotValue(shot, "sceneDescription", "scene_description");
            if (sceneDescription != null && !sceneDescription.isEmpty()) {
                String visualText;
                if (StoryboardTemplateParser.isTemplateText(sceneDescription)) {
                    visualText = StoryboardTemplateParser.extractVisualLabels(sceneDescription);
                } else {
                    visualText = flattenMultilineText(sceneDescription);
                }
                if (isLargeGrid && visualText.length() > 80) {
                    visualText = visualText.substring(0, 80) + "…";
                }
                sb.append(visualText);
            } else {
                String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
                sb.append(flattenMultilineText(visualDescription != null ? visualDescription : ""));
                String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
                if (cameraMovement != null && !cameraMovement.isEmpty()) {
                    sb.append("，").append(cameraMovement);
                }
            }
```

在文件顶部添加 import：
```java
import com.comic.service.production.StoryboardTemplateParser;
```

- [ ] **Step 2: 修改 buildMultiShotPrompt 中 previousPanelLastShot 的承接上下文**

找到（约 line 380）：
```java
            String prevDesc = (String) previousPanelLastShot.get("visualDescription");
            if (prevDesc != null && !prevDesc.isEmpty()) {
                sb.append("- 画面状态：").append(prevDesc).append("\n");
            }
```

替换为：
```java
            String prevDesc = (String) previousPanelLastShot.get("sceneDescription");
            if (prevDesc == null || prevDesc.isEmpty()) {
                prevDesc = (String) previousPanelLastShot.get("visualDescription");
            }
            if (prevDesc != null && !prevDesc.isEmpty()) {
                sb.append("- 画面状态：").append(prevDesc).append("\n");
            }
```

- [ ] **Step 3: 修改 buildMultiShotPrompt 中 per-shot Scene 描述**

找到（约 line 432-438）：
```java
                                if (sceneDescription != null && !sceneDescription.isEmpty()) {
                                    sb.append("Scene: ").append(sceneDescription).append("\n");
                                } else {
                                    sb.append("Scene: ").append(shotSize != null ? shotSize : "")
                                        .append("，").append(cameraAngle != null ? cameraAngle : "")
                                        .append("，").append(cameraMovement != null ? cameraMovement : "")
                                        .append("，").append(visualDescription != null ? visualDescription : "").append("\n");
                                }
```

替换为：
```java
                                if (sceneDescription != null && !sceneDescription.isEmpty()) {
                                    sb.append("Scene: ").append(sceneDescription).append("\n");
                                } else if (visualDescription != null && !visualDescription.isEmpty()) {
                                    sb.append("Scene: ").append(shotSize != null ? shotSize : "")
                                        .append("，").append(cameraAngle != null ? cameraAngle : "")
                                        .append("，").append(cameraMovement != null ? cameraMovement : "")
                                        .append("，").append(visualDescription).append("\n");
                                } else {
                                    sb.append("Scene: ").append(shotSize != null ? shotSize : "")
                                        .append("，").append(cameraAngle != null ? cameraAngle : "")
                                        .append("，").append(cameraMovement != null ? cameraMovement : "")
                                        .append("\n");
                                }
```

- [ ] **Step 4: 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -pl . -q`

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(storyboard): use StoryboardTemplateParser for image/video prompt extraction"
```

---

### Task 5: 修改 ComicCommentaryPanelPromptBuilder

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java`
  - `buildNarrativeContext()` (line 58-60): visualDescription → sceneDescription
  - `buildGridPrompt()` (line 188-193): 同 PanelPromptBuilder 改动
  - `buildMultiShotPrompt()` (line 324-333): 同 PanelPromptBuilder 改动

- [ ] **Step 1: 修改 buildNarrativeContext**

找到 buildNarrativeContext 中（约 line 58-60）：
```java
            String desc = getShotValue(shot, "sceneDescription", "scene_description");
            if (desc == null || desc.isEmpty()) {
                desc = getShotValue(shot, "visualDescription", "visual_description");
            }
```
保持不变（已有 fallback 逻辑，无需改动）。

- [ ] **Step 2: 修改 buildMultiShotPrompt 中 previousPanelLastShot 的承接上下文**

找到 buildMultiShotPrompt 中（约 line 277）：
```java
            String prevDesc = (String) previousPanelLastShot.get("visualDescription");
```

改为优先读 sceneDescription：
```java
            String prevDesc = (String) previousPanelLastShot.get("sceneDescription");
            if (prevDesc == null || prevDesc.isEmpty()) {
                prevDesc = (String) previousPanelLastShot.get("visualDescription");
            }
```

- [ ] **Step 3: 修改 buildGridPrompt 中 sceneDescription 处理**

找到 buildGridPrompt 中 sceneDescription 处理段（与 PanelPromptBuilder 类似结构），将 `sceneDescription` 直接使用改为先判断是否标签模板、若是则提取可视标签：

```java
            String sceneDescription = getShotValue(shot, "sceneDescription", "scene_description");
            if (sceneDescription != null && !sceneDescription.isEmpty()) {
                String visualText;
                if (StoryboardTemplateParser.isTemplateText(sceneDescription)) {
                    visualText = StoryboardTemplateParser.extractVisualLabels(sceneDescription);
                } else {
                    visualText = sceneDescription;
                }
                sb.append(visualText);
            } else {
                // 保留 visualDescription fallback
                ...
            }
```

在文件顶部添加 import：
```java
import com.comic.service.production.StoryboardTemplateParser;
```

- [ ] **Step 3: 修改 buildMultiShotPrompt 中 Scene 描述**

同 PanelPromptBuilder Step 3 的改动模式。

- [ ] **Step 4: 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -pl . -q`

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java
git commit -m "feat(storyboard): adapt ComicCommentaryPanelPromptBuilder for labeled template"
```

---

### Task 6: 修改 PanelProductionService

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`
  - Line ~782, ~944, ~1473: visualDescription → sceneDescription
  - Line ~1991, ~2060: 移除 `StoryboardLabelTemplateFormatter.applyToShots()` 调用
  - Line ~2436: 从 SHOT_EDITABLE_FIELDS 移除 visualDescription

- [ ] **Step 1: 修改 visualDescription 回退匹配（line ~782 和 ~1473）**

找到两处回退匹配逻辑：
```java
    // 回退：通过 visualDescription 匹配
    String firstDesc = (String) shots.get(0).get("visualDescription");
    if (firstDesc != null) {
        for (int i = 0; i < allSplitShots.size(); i++) {
            if (firstDesc.equals(allSplitShots.get(i).get("visualDescription"))) {
```

两处都改为优先用 sceneDescription，fallback visualDescription：
```java
    // 回退：通过描述匹配
    String firstDesc = (String) shots.get(0).get("sceneDescription");
    if (firstDesc == null) firstDesc = (String) shots.get(0).get("visualDescription");
    if (firstDesc != null) {
        String firstDescFinal = firstDesc;
        for (int i = 0; i < allSplitShots.size(); i++) {
            String splitDesc = (String) allSplitShots.get(i).get("sceneDescription");
            if (splitDesc == null) splitDesc = (String) allSplitShots.get(i).get("visualDescription");
            if (firstDescFinal.equals(splitDesc)) {
```

- [ ] **Step 2: 修改画面提示词构建（line ~944）**

找到：
```java
    String desc = getStr(shot, "visualDescription");
    if (desc == null || desc.isEmpty()) desc = getStr(shot, "sceneDescription");
```

改为优先 sceneDescription：
```java
    String desc = getStr(shot, "sceneDescription");
    if (desc == null || desc.isEmpty()) desc = getStr(shot, "visualDescription");
```

- [ ] **Step 3: 移除 StoryboardLabelTemplateFormatter.applyToShots 调用（line ~1991 和 ~2060）**

找到两处：
```java
    StoryboardLabelTemplateFormatter.applyToShots(shots);
```

将这两行替换为注释或直接删除。LLM 现在直接输出标签模板格式，不需要后处理。

- [ ] **Step 4: 修改 SHOT_EDITABLE_FIELDS（line ~2436）**

找到：
```java
    "sceneDescription", "visualDescription", "narration", ...
```

移除 `"visualDescription"`：
```java
    "sceneDescription", "narration", ...
```

- [ ] **Step 5: 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -pl . -q`

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat(storyboard): migrate PanelProductionService from visualDescription to sceneDescription"
```

---

### Task 7: 前端改动 — visualDescription 全量迁移为 sceneDescription

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/episode.types.ts` (line 148-166)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts` (line ~86)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotDetail.tsx` (line 51)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx` (line ~326)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelGroupView.tsx` (line ~70)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/StoryboardGrid.tsx` (line ~79-82)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/VideoSegmentRow.tsx` (line ~52)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx` (line ~315, ~369)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ScriptEpisodeCard.tsx` (line ~10, ~18, ~34, ~81, ~248)
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx` (line ~132, ~189, ~525, ~706, ~709)

**迁移规则**：所有 `shot.visualDescription` / `s.visualDescription` / `shot['visualDescription']` 替换为 `shot.sceneDescription`（或 `s.sceneDescription`），保留 `|| shot.scene || ''` 等 fallback 不变。

- [ ] **Step 1: 全局搜索替换 visualDescription → sceneDescription**

在前端目录下搜索所有 `visualDescription` 引用：

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && grep -rn "visualDescription" src/ --include="*.ts" --include="*.tsx"`

对每个匹配文件执行以下替换规则：
- 类型定义中 `visualDescription: string` → `sceneDescription: string`
- 属性读取中 `shot.visualDescription` → `shot.sceneDescription`
- Fallback 链中 `s.visualDescription || s.visual_description` → `s.sceneDescription || s.visualDescription`（保留旧字段 fallback）
- 字段名映射中 `'visualDescription'` → `'sceneDescription'`

**注意**：`ScriptEpisodeCard.tsx` 中有字段列表和标签映射，需要将 `visualDescription` 在字段名和标签中同时替换。

- [ ] **Step 2: 修改 ShotDetail.tsx 展示**

找到（约 line 51）：
```typescript
        <FieldRow label="画面描述" value={shot.visualDescription} />
```

替换为：
```typescript
        <FieldRow label="分镜描述" value={shot.sceneDescription} />
```

- [ ] **Step 3: TypeScript 编译验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit 2>&1 | head -50`

Expected: 无编译错误（0 errors）。如果有错误，逐一修复遗漏的引用点。

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/
git commit -m "feat(storyboard): migrate all frontend visualDescription references to sceneDescription"
```

---

### Task 8: 更新测试文件 + 全量验证

**Files:**
- Modify: `backend/com/comic/src/test/java/com/comic/service/production/StoryboardLabelTemplateFormatterTest.java` (保留不动)
- Modify: `backend/com/comic/src/test/java/com/comic/e2e/StoryboardLabelTemplateRealTest.java` (保留不动)
- 可能修改: `backend/com/comic/src/test/java/com/comic/ai/PanelPromptBuilderTest.java` (visualDescription → sceneDescription)
- 可能修改: 其他引用 visualDescription 的测试文件

**关于 dialogueTone**：spec 中未明确列为删除字段，但 Phase 4 prompt 中已移除。保持字段存在于前端/后端类型中（向后兼容），LLM 不再输出时该字段为空/null，不会报错。后续可在独立 PR 中清理。

- [ ] **Step 1: 搜索并修复所有测试中的 visualDescription 引用**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && grep -rn "visualDescription" src/test/ --include="*.java"`

对每个匹配文件：
- 测试数据中 `shot.put("visualDescription", ...)` → 改为 `shot.put("sceneDescription", ...)` 并确保值为标签模板格式（含标签前缀如 `（动作）`）
- 如果测试依赖 `flattenMultilineText` 行为（自由文本输入），改为输入标签模板文本以走 `extractVisualLabels` 分支
- 断言中检查 `visualDescription` 的 → 改为检查 `sceneDescription`

- [ ] **Step 2: 确认旧测试仍通过**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardLabelTemplateFormatterTest -Dsurefire.useFile=false`

Expected: 全部 PASS（旧 formatter 未修改）

- [ ] **Step 3: 全量测试验证**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dsurefire.useFile=false`

Expected: 全部 PASS。如有失败，根据错误信息修复测试数据。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "test(storyboard): update test files for labeled template migration"
```
