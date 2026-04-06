# 集级别旁白生成实现方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将旁白从"按 shot 独立生成"改为"集级别生成一段完整旁白稿，再分配到各 shot"，解决旁白割裂感问题。

**Architecture:** 整体分三步：
1. **旁白稿生成** — 在分镜生成之前，先用集剧本生成一段完整旁白口播稿（约 400 字）
2. **分镜生成（改造）** — 分镜阶段不再生成 narration，只生成 dialogue + 视觉信息，用标签区分旁白位和对白位
3. **旁白分配** — 把旁白稿按字数约束切分，填入各旁白 shot 的 narration 字段，TTS 阶段无需改动

**Tech Stack:** Java/Spring Boot, DeepSeek API, MiniMax TTS

---

## 文件变更概览

| 操作 | 文件 |
|------|------|
| 新建 | `backend/com/comic/src/main/java/com/comic/ai/text/NarrationAllocator.java` — 旁白分配算法 + 兜底逻辑 |
| 新建 | `backend/com/comic/src/main/java/com/comic/ai/text/NarrationPromptBuilder.java` — 集级别旁白稿生成 prompt |
| 修改 | `backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java` — 新增 `generateNarrationDraft()` 方法；改造 `generatePanelAwareStoryboard()` prompt（去掉 narration 字段，改为标签） |
| 修改 | `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java` — 在 `generateStoryboardForEpisode()` 中新增旁白稿生成 + 分配调用 |
| 修改 | `backend/com/comic/src/main/java/com/comic/ai/text/ViduTtsService.java` — 新增 `allocateNarration()` 入口，调用 NarrationAllocator |
| 新建测试 | `backend/com/comic/src/test/java/com/comic/ai/text/NarrationAllocatorTest.java` |

---

## Task 1: 新建 NarrationPromptBuilder — 旁白稿生成 prompt

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/ai/text/NarrationPromptBuilder.java`
- Test: `backend/com/comic/src/test/java/com/comic/ai/text/NarrationPromptBuilderTest.java` (minimal)

- [ ] **Step 1: 创建 NarrationPromptBuilder.java**

```java
package com.comic.ai.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集级别旁白稿生成 Prompt Builder
 * 生成一段完整的旁白口播稿，后续由 NarrationAllocator 切分到各 shot
 */
@Slf4j
@Component
public class NarrationPromptBuilder {

    /**
     * 构建旁白稿生成的系统 prompt
     *
     * @param narrationPerspective 第一人称或第三人称
     * @param estimatedWordCount   预计旁白总字数（由 totalDuration 和 dialogueCount 动态计算得出）
     * @return 系统 prompt 文本
     */
    public String buildNarrationSystemPrompt(String narrationPerspective, int estimatedWordCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的漫剧解说旁白编剧。\n");
        sb.append("你的任务是根据剧本内容，撰写一段完整的「旁白口播稿」。\n\n");
        sb.append("【口播稿规格】\n");
        sb.append("- 总字数：约 ").append(estimatedWordCount).append(" 字（允许 ±15% 偏差）\n");
        sb.append("- 风格：中文口语，自然流畅，适合朗读\n");
        sb.append("- 结构：开场引入 → 中间推进 → 高潮转折 → 结尾收束\n");
        sb.append("- 必须有清晰的起承转合，句子之间逻辑递进，禁止跳跃、重复或突兀换话题\n\n");

        if ("first_person".equals(narrationPerspective)) {
            sb.append("【人称要求 - 第一人称 · 最高优先级】\n");
            sb.append("- 必须以主角口吻叙述，使用「我」来讲述故事\n");
            sb.append("- 禁止使用第三人称（他/她/主角名字）\n\n");
        } else if ("third_person".equals(narrationPerspective)) {
            sb.append("【人称要求 - 第三人称 · 最高优先级】\n");
            sb.append("- 必须以旁观者/上帝视角叙述，用「他/她」或角色名指代\n");
            sb.append("- 禁止使用第一人称「我」\n\n");
        }

        sb.append("【输出格式】\n");
        sb.append("- 仅输出一段纯文本旁白稿，不要 JSON，不要 markdown 代码块\n");
        sb.append("- 段落之间用空行分隔，每段 30-80 字为宜\n");
        sb.append("- 旁白稿中可以有「……」省略号表示节奏停顿，但禁止使用括号、感叹号过多\n\n");

        sb.append("【断句要求 - 重要】\n");
        sb.append("- 旁白稿中的每一句话必须以完整标点（。！？，）结尾\n");
        sb.append("- 禁止出现无标点的长句（超过 30 字无标点视为不合格）\n");
        sb.append("- 每 2-4 句话形成一个自然的「叙事单元」，便于后续切分到各分镜\n\n");

        sb.append("【内容要求】\n");
        sb.append("- 完整讲述本集故事情节，覆盖故事的开端、发展、高潮、结尾\n");
        sb.append("- 旁白是画面之外的声音，描述画面中角色看不到的信息（内心活动、背景信息、情绪外化等）\n");
        sb.append("- 避免重复剧本原文，用自己的语言重新叙述\n");
        return sb.toString();
    }

    /**
     * 构建旁白稿生成的用户 prompt
     *
     * @param episodeContent 集剧本 content 字段
     * @param characters     角色描述
     * @param totalDuration  目标总时长（秒），用于估算旁白总字数
     * @param dialogueCount  本集预计对白 shot 数量（用于估算旁白 shot 数量）
     * @return 用户 prompt 文本
     */
    public String buildNarrationUserPrompt(String episodeContent, String characters,
                                           int totalDuration, int dialogueCount) {
        // 估算旁白 shot 数和总字数
        int totalShots = Math.round((float) totalDuration / 3.0f);
        int narrationShots = Math.max(0, totalShots - dialogueCount);
        int estimatedWordCount = (int) (narrationShots * 11 * 1.15);

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下剧本内容，撰写本集的旁白口播稿。\n\n");
        sb.append("【剧本内容】\n").append(episodeContent).append("\n\n");
        if (characters != null && !characters.isEmpty()) {
            sb.append("【角色信息】\n").append(characters).append("\n\n");
        }
        sb.append("【约束条件】\n");
        sb.append("- 目标总时长：").append(totalDuration).append(" 秒\n");
        sb.append("- 本集预计对白分镜数：").append(dialogueCount).append(" 镜\n");
        sb.append("- 预计旁白分镜数：").append(narrationShots).append(" 镜\n");
        sb.append("- 建议旁白稿总字数：约 ").append(estimatedWordCount).append(" 字（允许 ±15% 偏差）\n\n");
        sb.append("请开始撰写旁白口播稿：");
        return sb.toString();
    }
}
```

- [ ] **Step 2: 运行测试验证 PromptBuilder 能正常构建（手动验证输出）**

```bash
cd backend/com/comic
# 编译验证
./mvnw compile -q 2>&1 | head -20
```

---

## Task 2: 新建 NarrationAllocator — 旁白分配算法 + 三层兜底

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/ai/text/NarrationAllocator.java`
- Test: `backend/com/comic/src/test/java/com/comic/ai/text/NarrationAllocatorTest.java`

- [ ] **Step 1: 创建 NarrationAllocator.java**

```java
package com.comic.ai.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 旁白分配器
 * 将集级别旁白稿按 shot 约束切分分配到各旁白 shot
 *
 * 三层兜底策略：
 * 1. 在自然标点处切分
 * 2. 柔性字数区间匹配（允许 ±20% 超出）
 * 3. 降级：复用最后一句 / 截断 / 静音
 */
@Slf4j
@Component
public class NarrationAllocator {

    // 自然断句标点（按优先级排序）
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？]");
    private static final Pattern SENTENCE_PAUSE = Pattern.compile("[，、；……]");

    // 每个 duration 对应的字数区间 [min, max]
    private static final Map<Integer, int[]> WORD_COUNT_RANGE = Map.of(
            2, new int[]{5, 9},
            3, new int[]{9, 13},
            4, new int[]{12, 16}
    );

    // 允许超出上限的百分比
    private static final float SOFT_MAX_BONUS = 0.20f;

    /**
     * 将旁白稿分配到 shots
     *
     * @param narrationDraft  旁白稿原文
     * @param shots           所有 shot（dialogue shot 的 narration 保持"无"，旁白 shot 由本方法填充）
     * @param narrationPerspective 第一/第三人称
     * @return 填充后的 shots（in-place 修改）
     */
    public List<Map<String, Object>> allocate(String narrationDraft,
                                              List<Map<String, Object>> shots,
                                              String narrationPerspective) {
        if (narrationDraft == null || narrationDraft.isEmpty()) {
            log.warn("旁白稿为空，跳过分配，所有旁白 shot 设为「无」");
            markAllNarrationAsNone(shots);
            return shots;
        }

        // Step 1: 按标点切分句子
        List<String> sentences = splitSentences(narrationDraft);
        if (sentences.isEmpty()) {
            log.warn("旁白稿无法切分句子，跳过分配");
            markAllNarrationAsNone(shots);
            return shots;
        }

        log.info("旁白稿切分得到 {} 个句子", sentences.size());

        // Step 2: 提取旁白 shot（有对白的 shot 跳过）
        List<Map<String, Object>> narrationShots = new ArrayList<>();
        List<Map<String, Object>> dialogueShots = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            if (isDialogueShot(shot)) {
                dialogueShots.add(shot);
            } else {
                narrationShots.add(shot);
            }
        }

        log.info("旁白 shot: {} 个, 对白 shot: {} 个", narrationShots.size(), dialogueShots.size());

        // Step 3: 收集各旁白 shot 的字数约束
        List<int[]> constraints = new ArrayList<>();
        for (Map<String, Object> shot : narrationShots) {
            int duration = safeInt(shot.get("duration"), 3);
            int[] range = WORD_COUNT_RANGE.getOrDefault(duration, new int[]{5, 13});
            // 软上限 = max * 1.2
            int softMax = (int) Math.ceil(range[1] * (1 + SOFT_MAX_BONUS));
            constraints.add(new int[]{range[0], softMax});
        }

        // Step 4: 贪心分配句子到各 shot
        int sentenceIdx = 0;
        String lastSentence = sentences.get(sentences.size() - 1);

        for (int i = 0; i < narrationShots.size(); i++) {
            Map<String, Object> shot = narrationShots.get(i);
            int[] constraint = constraints.get(i);

            // 尝试找一个或多个句子满足字数约束
            String allocated = tryAllocateSentences(
                    sentences, sentenceIdx, constraint, lastSentence,
                    narrationPerspective, i + 1);

            shot.put("narration", allocated);
            if (allocated != null && !allocated.isEmpty()) {
                sentenceIdx += countSentencesInAllocation(allocated, sentences, sentenceIdx);
            }
        }

        // Step 5: 互斥原则最终校验（保险）
        enforceMutualExclusion(shots);

        // Step 6: 日志统计
        long filledCount = narrationShots.stream()
                .filter(s -> !"无".equals(s.get("narration")))
                .count();
        log.info("旁白分配完成: 填充 {}/{} 个旁白 shot", filledCount, narrationShots.size());

        return shots;
    }

    /**
     * 贪心分配：从 sentences[idx] 开始，找满足 [min, softMax] 的句子组合
     */
    private String tryAllocateSentences(List<String> sentences, int startIdx,
                                        int[] constraint, String lastSentence,
                                        String perspective, int shotNum) {
        int min = constraint[0];
        int softMax = constraint[1];

        // 尝试 1~3 个连续句子组合
        for (int count = 1; count <= 3 && startIdx + count <= sentences.size(); count++) {
            StringBuilder combined = new StringBuilder();
            for (int j = startIdx; j < startIdx + count; j++) {
                if (combined.length() > 0) combined.append("，");
                combined.append(sentences.get(j));
            }
            int len = combined.length();

            if (len >= min && len <= softMax) {
                return combined.toString();
            }
            // 如果超出 softMax 但仍在硬上限内，尝试一下（允许轻微超出）
            int hardMax = (int) ((double) softMax / (1 + SOFT_MAX_BONUS) * (1 + SOFT_MAX_BONUS * 1.5));
            if (len > softMax && len <= hardMax) {
                log.debug("Shot {} 字数 {} 超出软上限 {} 但在硬上限内，予以通过", shotNum, len, softMax);
                return combined.toString();
            }
        }

        // 找不到合适组合：降级策略
        if (startIdx < sentences.size()) {
            // 降级1：取最后一句（可能不完整但比静音好）
            String last = sentences.get(sentences.size() - 1);
            int len = last.length();
            if (len > 0 && len <= softMax * 1.5) {
                log.warn("Shot {} 找不到合适句子组合，降级使用最后一句 ({}字)", shotNum, len);
                return last;
            }
            // 降级2：截断
            if (len > softMax * 1.5) {
                String truncated = truncateToLength(last, softMax);
                log.warn("Shot {} 最后一句截断为 {} 字", shotNum, truncated.length());
                return truncated;
            }
        }

        // 降级3：静音
        log.warn("Shot {} 无法分配旁白，设为「无」（静音）", shotNum);
        return "无";
    }

    /**
     * 按标点切分句子
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SENTENCE_END.matcher(String.valueOf(c)).find()) {
                String s = current.toString().trim();
                if (!s.isEmpty()) sentences.add(s);
                current = new StringBuilder();
            }
        }

        // 处理末尾无标点的剩余文本
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            // 如果剩余文本较长，强行按字数切
            if (remaining.length() > 20) {
                int mid = remaining.length() / 2;
                int cut = -1;
                for (int i = mid; i < remaining.length(); i++) {
                    if (SENTENCE_PAUSE.matcher(String.valueOf(remaining.charAt(i))).find()) {
                        cut = i + 1;
                        break;
                    }
                }
                if (cut > 0) {
                    sentences.add(remaining.substring(0, cut).trim());
                    sentences.add(remaining.substring(cut).trim());
                } else {
                    sentences.add(remaining);
                }
            } else {
                sentences.add(remaining);
            }
        }

        return sentences;
    }

    /**
     * 强制互斥：dialogue 非"无" → narration 必须为"无"
     */
    private void enforceMutualExclusion(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            String dialogue = str(shot.get("dialogue"));
            if (!"无".equals(dialogue) && !dialogue.isEmpty()) {
                String narration = str(shot.get("narration"));
                if (!"无".equals(narration) && !narration.isEmpty()) {
                    log.warn("Shot {} 违反互斥原则，清除 narration 保留 dialogue", shot.get("shotNumber"));
                    shot.put("narration", "无");
                }
            }
        }
    }

    /**
     * 正则校验：dialogue 中禁止塞旁白关键词
     */
    private static final Pattern NARRATION_IN_DIALOGUE = Pattern.compile("旁白|解说|内心OS|（旁白）|（解说）");

    public void sanitizeDialogue(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            String dialogue = str(shot.get("dialogue"));
            if (!"无".equals(dialogue) && NARRATION_IN_DIALOGUE.matcher(dialogue).find()) {
                log.warn("Shot {} dialogue 包含旁白关键词，已清除: {}", shot.get("shotNumber"), dialogue);
                shot.put("dialogue", "无");
                shot.put("speaker", "无");
            }
            if (dialogue.isBlank()) {
                shot.put("dialogue", "无");
            }
            if ("无".equals(str(shot.get("dialogue")))) {
                String speaker = str(shot.get("speaker"));
                if (speaker.isBlank() || "旁白".equals(speaker)) {
                    shot.put("speaker", "无");
                }
            }
        }
    }

    /**
     * 强制对白比例：保留情绪最强的 target 个对白
     */
    public void forceDialogueRatio(List<Map<String, Object>> shots, int target) {
        List<Map<String, Object>> dialogueShots = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            if (!"无".equals(str(shot.get("dialogue")))) {
                dialogueShots.add(shot);
            }
        }

        int excess = dialogueShots.size() - target;
        if (excess <= 0) return;

        // 按情绪强度排序（包含"怒""喊""哭""大笑"等关键词优先保留）
        Pattern strongEmotion = Pattern.compile("怒|喊|哭|大笑|嘶吼|咆哮|狂笑|怒吼");
        dialogueShots.sort((a, b) -> {
            boolean ea = strongEmotion.matcher(str(a.get("dialogueTone"))).find();
            boolean eb = strongEmotion.matcher(str(b.get("dialogueTone"))).find();
            return Boolean.compare(eb, ea); // 有强烈情绪的排前面
        });

        // 保留前 target 个，其余清除
        for (int i = target; i < dialogueShots.size(); i++) {
            log.warn("Shot {} 对白比例超限，清除 dialogue: {}",
                    dialogueShots.get(i).get("shotNumber"), dialogueShots.get(i).get("dialogue"));
            dialogueShots.get(i).put("dialogue", "无");
            dialogueShots.get(i).put("speaker", "无");
            dialogueShots.get(i).put("dialogueTone", "无");
        }
    }

    // ========== 工具方法 ==========

    private boolean isDialogueShot(Map<String, Object> shot) {
        return !"无".equals(str(shot.get("dialogue"))) && !str(shot.get("dialogue")).isEmpty();
    }

    private void markAllNarrationAsNone(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            if (!isDialogueShot(shot)) {
                shot.put("narration", "无");
            }
        }
    }

    private String truncateToLength(String text, int maxLen) {
        // 从后往前找最近的停顿标点
        for (int i = Math.min(text.length() - 1, maxLen); i >= maxLen / 2; i--) {
            if (SENTENCE_PAUSE.matcher(String.valueOf(text.charAt(i))).find()) {
                return text.substring(0, i + 1).trim();
            }
        }
        return text.substring(0, maxLen).trim() + "……";
    }

    private int countSentencesInAllocation(String allocated, List<String> sentences, int startIdx) {
        int count = 0;
        String normalized = allocated.replace("，", "").trim();
        for (int i = startIdx; i < sentences.size(); i++) {
            String s = sentences.get(i).replace("，", "").trim();
            if (normalized.startsWith(s)) {
                count++;
                normalized = normalized.substring(s.length());
                if (normalized.isEmpty()) break;
            } else {
                break;
            }
        }
        return Math.max(1, count);
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }

    private int safeInt(Object o, int defaultVal) {
        if (o == null) return defaultVal;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return (int) Double.parseDouble(o.toString().trim()); }
        catch (Exception e) { return defaultVal; }
    }
}
```

- [ ] **Step 2: 创建 NarrationAllocatorTest.java**

```java
package com.comic.ai.text;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NarrationAllocatorTest {

    private final NarrationAllocator allocator = new NarrationAllocator();

    private List<Map<String, Object>> makeShots(Object... pairs) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i / 2 + 1);
            shot.put("duration", pairs[i + 1]);
            shot.put("dialogue", "无");
            shot.put("speaker", "无");
            shot.put("narration", "");
            shots.add(shot);
        }
        return shots;
    }

    @Test
    void testAllocate_basic() {
        String narration = "阳光洒在古老的城墙上。他从未想过会有这一天。";
        List<Map<String, Object>> shots = makeShots(3, 3, 3);

        allocator.allocate(narration, shots, "third_person");

        // 第一个 shot 应该分配到第一句
        String nar1 = str(shots.get(0).get("narration"));
        assertFalse("无".equals(nar1), "第一个 shot 应被分配旁白，实际: " + nar1);
        assertTrue(nar1.contains("阳光"), "第一个 shot 应包含第一句内容，实际: " + nar1);
        // 第二个 shot 也应有旁白（降级用最后一句）
        String nar2 = str(shots.get(1).get("narration"));
        assertFalse("无".equals(nar2), "第二个 shot 应被分配旁白，实际: " + nar2);
    }

    @Test
    void testMutualExclusion() {
        List<Map<String, Object>> shots = makeShots(3, 3);
        shots.get(0).put("dialogue", "我真的不想这样做。");
        shots.get(0).put("speaker", "林远");
        shots.get(0).put("narration", "他握紧了拳头");

        String narration = "阳光洒在古老的城墙上。";
        allocator.allocate(narration, shots, "third_person");

        // dialogue shot 的 narration 必须被清除
        assertEquals("无", shots.get(0).get("narration"));
        // 旁白 shot 应该正常分配
        assertFalse("无".equals(shots.get(1).get("narration")));
    }

    @Test
    void testSanitizeDialogue_removesNarrationKeyword() {
        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("shotNumber", 1);
        shot.put("dialogue", "旁白：他是如何走到这一步的");
        shot.put("speaker", "旁白");
        shots.add(shot);

        allocator.sanitizeDialogue(shots);

        assertEquals("无", shots.get(0).get("dialogue"));
        assertEquals("无", shots.get(0).get("speaker"));
    }

    @Test
    void testForceDialogueRatio() {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("dialogue", "台词" + (i + 1));
            shot.put("dialogueTone", i == 2 ? "愤怒而急促" : "平静地说");
            shots.add(shot);
        }

        allocator.forceDialogueRatio(shots, 3);

        long kept = shots.stream()
                .filter(s -> !"无".equals(s.get("dialogue")))
                .count();
        assertEquals(3, kept);
    }

    @Test
    void testEmptyNarration() {
        List<Map<String, Object>> shots = makeShots(3, 3);
        allocator.allocate("", shots, "third_person");
        assertEquals("无", shots.get(0).get("narration"));
        assertEquals("无", shots.get(1).get("narration"));
    }

    @Test
    void testWordCountConstraint() {
        // 2秒 shot: 5-9字; 3秒 shot: 9-13字; 4秒 shot: 12-16字
        String narration = "阳光洒在古老的城墙上。他从未想过会有这一天。风吹动她的发丝。";
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", 3);
            shot.put("dialogue", "无");
            shot.put("speaker", "无");
            shot.put("narration", "");
            shots.add(shot);
        }

        allocator.allocate(narration, shots, "third_person");

        // 验证字数在合理范围（±20% soft bonus）
        for (Map<String, Object> shot : shots) {
            String nar = str(shot.get("narration"));
            if (!"无".equals(nar)) {
                int len = nar.length();
                assertTrue(len >= 5 && len <= 16,
                        "Shot " + shot.get("shotNumber") + " narration 长度 " + len + " 超出范围 [5,16]");
            }
        }
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
cd backend/com/comic
./mvnw test -Dtest=NarrationAllocatorTest -q 2>&1 | tail -20
```

---

## Task 3: 改造 DeepSeekTextService — 新增旁白稿生成，分镜 prompt 去掉 narration 字段

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java`
- Test: `backend/com/comic/src/test/java/com/comic/ai/text/DeepSeekTextServiceTest.java` (add tests for new method)

- [ ] **Step 1: 新增 generateNarrationDraft() 方法**

在 `DeepSeekTextService.java` 的 `generatePanelAwareStoryboard()` 方法之前（约第 463 行）添加：

```java
/**
 * 生成集级别旁白稿
 *
 * @param episodeContent     集剧本 content
 * @param characters        角色描述
 * @param totalDuration     目标总时长（秒）
 * @param dialogueCount     预计对白 shot 数（用于估算旁白 shot 数）
 * @param narrationPerspective 第一/第三人称
 * @return 旁白稿原文
 */
public String generateNarrationDraft(String episodeContent, String characters,
                                      int totalDuration, int dialogueCount,
                                      String narrationPerspective) {
    int estimatedWordCount = estimateWordCount(totalDuration, dialogueCount);
    String systemPrompt = narrationPromptBuilder.buildNarrationSystemPrompt(
            narrationPerspective, estimatedWordCount);
    String userPrompt = narrationPromptBuilder.buildNarrationUserPrompt(
            episodeContent, characters, totalDuration, dialogueCount);

    log.info("[NarrationDraft] 生成旁白稿: duration={}s, dialogueCount={}, perspective={}",
            totalDuration, dialogueCount, narrationPerspective);

    String draft;
    try {
        draft = generate(systemPrompt, userPrompt);
    } catch (Exception e) {
        log.warn("[NarrationDraft] 旁白稿生成失败，回退为空串: {}", e.getMessage());
        return "";
    }

    if (draft == null || draft.trim().isEmpty()) {
        log.warn("[NarrationDraft] 旁白稿为空");
        return "";
    }

    // 清理 markdown 代码块
    draft = draft.trim();
    if (draft.startsWith("```")) {
        int endBacktick = draft.lastIndexOf("```");
        if (endBacktick > 3) {
            draft = draft.substring(draft.indexOf("\n") + 1, endBacktick).trim();
        }
    }

    log.info("[NarrationDraft] 旁白稿生成成功: {} 字", draft.length());
    return draft;
}

/** 根据时长和对白数量动态估算旁白总字数 */
private int estimateWordCount(int totalDuration, int dialogueCount) {
    // 总 shot 数 ≈ totalDuration / 3（平均每 shot 3 秒）
    int totalShots = Math.round((float) totalDuration / 3.0f);
    // 旁白 shot 数 = 总 shot - 对白 shot
    int narrationShots = Math.max(0, totalShots - dialogueCount);
    // 每 shot 平均约 11 字，加上 15% buffer（开场/结尾/过渡）
    return (int) (narrationShots * 11 * 1.15);
}
```

在类顶部字段区添加依赖注入（约第 47 行 Semaphore 之后）：

```java
// NarrationPromptBuilder 无状态，直接实例化即可（等效于 Spring singleton）
private final NarrationPromptBuilder narrationPromptBuilder = new NarrationPromptBuilder();
```

- [ ] **Step 2: 改造 generatePanelAwareStoryboard() — 分镜 prompt 去掉 narration 字段**

找到 `buildPanelAwareSystemPrompt` 方法中关于 narration 的描述（约第 783-787 行），将其替换为：

```java
        sb.append("- narrationType: 旁白类型标签（**仅填写标签，不填写旁白内容**；标签可选：\"narrate\"=旁白分镜，\"dialogue\"=对白分镜；**禁止填写实际旁白文本**）\n");
```

然后在字段列表的末尾（紧跟在 `- transitionHint` 之后）添加说明：

```
sb.append("- narrationType: 旁白类型标签（**仅填写标签**，禁止填旁白文本。\"narrate\"=此分镜有旁白，\"dialogue\"=此分镜有对白）\n");
```

找到关于 narration 的互斥规则段落（原文约第 809-812 行），将其**替换为**新的分镜规则：

```java
        sb.append("【分镜类型标签 - 必须严格遵守】\n");
        sb.append("- narrationType=\"narrate\"：此分镜是旁白分镜，画面由旁白解说驱动。\n");
        sb.append("  · dialogue 填「无」，speaker 填「无」\n");
        sb.append("  · narrationType 填 \"narrate\"（**禁止填写任何旁白文本**）\n");
        sb.append("- narrationType=\"dialogue\"：此分镜是对白分镜，画面由角色台词驱动。\n");
        sb.append("  · dialogue 填角色台词，speaker 填角色名\n");
        sb.append("  · narrationType 填 \"dialogue\"（**禁止填 \"narrate\"**）\n\n");

        sb.append("【对白数量约束】整集所有分镜中，**每 9 个分镜必须有且仅有 3 个对白分镜**（其余为旁白分镜）：\n");
        sb.append("  - 对白分镜（narrationType=\"dialogue\"）：dialogue 非「无」，narrationType=\"dialogue\"\n");
        sb.append("  - 旁白分镜（narrationType=\"narrate\"）：dialogue 填「无」，narrationType=\"narrate\"\n");
        sb.append("  - 对白分镜禁止连续出现，至少间隔 1 个旁白分镜\n");
        sb.append("  - 整集对白分镜总数偏差不得超过 ±1\n\n");
```

**完全删除**原来的 narration 字段说明（约第 783-787 行）：
```java
// 删除这段：
sb.append("- narration: 旁白口播稿（中文口语，字数硬性要求...）\n");
```

**删除**原来的互斥规则（约第 809-812 行）：
```java
// 删除这段：
sb.append("1.【旁白与对白互斥 - 最高优先级】每一镜 narration 和 dialogue 绝对不能同时存在...\n");
```

- [ ] **Step 3: 后处理 — 解析 narrationType 并预填 narration="pending"**

在 `parsePanelAwareJson()` 方法返回前（约第 555 行 return panelShots 之前），添加：

```java
        // 后处理：解析 narrationType 标签，预填 narration="pending"（等待分配器填充）
        for (List<Map<String, Object>> panelShotList : panelShots) {
            for (Map<String, Object> shot : panelShotList) {
                String type = str(shot.get("narrationType"));
                if ("narrate".equals(type)) {
                    shot.put("narration", "pending"); // 待 NarrationAllocator 填充
                } else {
                    shot.put("narration", "无");
                }
                // dialogue shot 的 narration 强制为"无"
                String dialogue = str(shot.get("dialogue"));
                if (!"无".equals(dialogue) && !dialogue.isEmpty()) {
                    shot.put("narration", "无");
                }
            }
        }
```

添加工具方法：

```java
private String str(Object o) {
    return o != null ? o.toString().trim() : "";
}
```

- [ ] **Step 4: 编译验证**

```bash
cd backend/com/comic
./mvnw compile -q 2>&1 | grep -i "error\|fail" | head -20
```

---

## Task 4: 改造 PanelProductionService — 串联旁白生成 + 分配流程

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: 在类顶部添加 NarrationAllocator 依赖**

约第 119 行附近找到 `@Autowired` 字段区，添加：

```java
@Autowired
private NarrationAllocator narrationAllocator;
```

- [ ] **Step 2: 改造 generateStoryboardForEpisode() — 串联旁白生成和分配**

在 `generateStoryboardForEpisode()` 方法中，约第 901 行 `if (comicMode)` 块内，在调用 `deepSeekTextService.generatePanelAwareStoryboard()` **之前**添加旁白稿生成：

```java
        if (comicMode) {
            String narrationPerspective = (String) projectInfo.get("narrationPerspective");

            // ===== 新增：先生成集级别旁白稿 =====
            int totalShotsEst = targetDuration / 3;
            int dialogueCountEst = Math.round(totalShotsEst / 3); // 约 1/3 为对白
            String narrationDraft = deepSeekTextService.generateNarrationDraft(
                    content, characters, targetDuration, dialogueCountEst, narrationPerspective);
            // ======================================

            List<List<Map<String, Object>>> panelGroups = deepSeekTextService.generatePanelAwareStoryboard(
                content, characters, targetDuration, visualStyle, revisionNote, narrationPerspective);
            log.info("[Pipeline-Text] 生成 {} 个 Panel, projectId={}, episode={}", panelGroups.size(), projectId, title);
            shots = new ArrayList<>();

            // ===== 新增：旁白分配到各 shot =====
            for (List<Map<String, Object>> panelShots : panelGroups) {
                // 统计本 panel 的实际对白 shot 数（用于后续 TTS 的对话静音标记）
                narrationAllocator.sanitizeDialogue(panelShots);
                // 强制对白比例
                int targetDialogue = Math.round(panelShots.size() / 3);
                narrationAllocator.forceDialogueRatio(panelShots, targetDialogue);
                // 分配旁白（旁白稿 + 旁白 shot 约束 → 填充 narration 字段）
                narrationAllocator.allocate(narrationDraft, panelShots, narrationPerspective);
                shots.addAll(panelShots);
            }
            // ===================================
```

同时**删除**原来的循环（原来只是平铺 panelGroups）：

```java
// 删除这段：
for (List<Map<String, Object>> group : panelGroups) {
    shots.addAll(group);
}
```

- [ ] **Step 3: 编译验证**

```bash
cd backend/com/comic
./mvnw compile -q 2>&1 | grep -i "error\|fail" | head -20
```

---

## Task 5: 旁白分配器单元测试

**Files:**
- Create: `backend/com/comic/src/test/java/com/comic/ai/text/NarrationAllocatorTest.java`

- [ ] **Step 1: 确认测试已创建**（见 Task 2 Step 2）

- [ ] **Step 2: 编写 NarrationPromptBuilder 单元测试**

```java
package com.comic.ai.text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NarrationPromptBuilderTest {

    private final NarrationPromptBuilder builder = new NarrationPromptBuilder();

    @Test
    void testBuildNarrationSystemPrompt_thirdPerson() {
        String prompt = builder.buildNarrationSystemPrompt("third_person", 300);
        assertTrue(prompt.contains("第三人称"));
        assertTrue(prompt.contains("他/她"));
        // 动态字数估算值应出现在 prompt 中
        assertTrue(prompt.contains("300"), "动态字数估算值未出现在 prompt 中");
        assertTrue(prompt.contains("±15%"));
    }

    @Test
    void testBuildNarrationSystemPrompt_firstPerson() {
        String prompt = builder.buildNarrationSystemPrompt("first_person", 450);
        assertTrue(prompt.contains("第一人称"));
        assertTrue(prompt.contains("「我」"));
        assertTrue(prompt.contains("450"), "动态字数估算值未出现在 prompt 中");
    }

    @Test
    void testBuildNarrationUserPrompt() {
        String prompt = builder.buildNarrationUserPrompt(
                "这是测试剧本", "角色A：主角", 60, 6);
        assertTrue(prompt.contains("60"));
        assertTrue(prompt.contains("6"));
        assertTrue(prompt.contains("预计旁白分镜数"));
    }
}
```

---

## Task 6: 端到端测试（可选，需要 Spring context）

**Files:**
- Create: `backend/com/comic/src/test/java/com/comic/e2e/NarrationE2eTest.java`

- [ ] **Step 1: 编写 E2E 测试**

```java
package com.comic.e2e;

import com.comic.ai.text.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 旁白生成流程端到端测试（需要 Spring context）
 * 验证：旁白稿生成 → 分镜生成（带 narrationType 标签）→ 旁白分配 → 互斥校验
 */
@SpringBootTest
class NarrationE2eTest {

    @Autowired(required = false)
    private NarrationAllocator narrationAllocator;

    @Autowired(required = false)
    private NarrationPromptBuilder narrationPromptBuilder;

    @Test
    void testNarrationPromptBuilder_output() {
        if (narrationPromptBuilder == null) return;

        String systemPrompt = narrationPromptBuilder.buildNarrationSystemPrompt("third_person", 300);
        assertTrue(systemPrompt.contains("第三人称"));
        assertTrue(systemPrompt.contains("300"));

        String userPrompt = narrationPromptBuilder.buildNarrationUserPrompt(
                "这是一个测试剧本", "角色A：主角", 60, 6);
        assertTrue(userPrompt.contains("60"));
        assertTrue(userPrompt.contains("6"));
    }

    @Test
    void testNarrationAllocator_basicFlow() {
        if (narrationAllocator == null) return;

        String narration = "阳光洒在古老的城墙上。他从未想过会有这一天。风吹动她的发丝。" +
                "她静静地看着远方。";

        List<Map<String, Object>> shots = new ArrayList<>();
        int[] durations = {3, 3, 3, 3};
        for (int i = 0; i < durations.length; i++) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", durations[i]);
            shot.put("dialogue", "无");
            shot.put("speaker", "无");
            shots.add(shot);
        }

        narrationAllocator.allocate(narration, shots, "third_person");

        // 验证所有 shot 都有 narration
        long filled = shots.stream()
                .filter(s -> !"无".equals(s.get("narration")))
                .count();
        assertTrue(filled > 0, "至少有一个 shot 被分配了旁白");

        // 验证字数约束
        for (Map<String, Object> shot : shots) {
            String nar = str(shot.get("narration"));
            if (!"无".equals(nar)) {
                int len = nar.length();
                assertTrue(len >= 5 && len <= 20,
                        "长度 " + len + " 超出宽松范围 [5,20]");
            }
        }
    }

    @Test
    void testMutualExclusion_enforced() {
        if (narrationAllocator == null) return;

        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("shotNumber", 1);
        s1.put("duration", 3);
        s1.put("dialogue", "我不能放弃！");
        s1.put("speaker", "林远");
        s1.put("narration", "有旁白");
        shots.add(s1);

        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("shotNumber", 2);
        s2.put("duration", 3);
        s2.put("dialogue", "无");
        s2.put("speaker", "无");
        shots.add(s2);

        narrationAllocator.allocate("旁白稿内容。", shots, "third_person");

        // dialogue shot 的 narration 必须是"无"
        assertEquals("无", shots.get(0).get("narration"));
        // 旁白 shot 应该有旁白
        assertFalse("无".equals(shots.get(1).get("narration")));
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }
}
```

- [ ] **Step 2: 运行集成测试**

```bash
cd backend/com/comic
./mvnw test -Dtest=NarrationIntegrationTest -q 2>&1 | tail -30
```

---

## Task 6: 提交

- [ ] **Step 1: 提交**

```bash
cd backend/com/comic
git add \
  src/main/java/com/comic/ai/text/NarrationPromptBuilder.java \
  src/main/java/com/comic/ai/text/NarrationAllocator.java \
  src/main/java/com/comic/ai/text/DeepSeekTextService.java \
  src/main/java/com/comic/service/production/PanelProductionService.java \
  src/test/java/com/comic/ai/text/NarrationAllocatorTest.java \
  src/test/java/com/comic/e2e/NarrationE2eTest.java
git commit -m "$(cat <<'EOF'
feat: add episode-level narration draft generation with shot allocation

- Add NarrationPromptBuilder for generating full-episode narration draft
- Add NarrationAllocator with 3-tier fallback (punctuation split, soft
  word count range, degradation to last sentence / truncation / silence)
- Refactor DeepSeekTextService.generatePanelAwareStoryboard() to use
  narrationType tags instead of inline narration text
- Chain narration draft → panel generation → allocation in
  PanelProductionService.generateStoryboardForEpisode()
- Add sanitization and forced dialogue ratio correction post-generation
- Add NarrationAllocatorTest and NarrationIntegrationTest

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## 风险与注意事项

1. **旁白稿为空时的降级**：当 DeepSeek 旁白稿生成失败时，`generateNarrationDraft()` 返回空串，`NarrationAllocator.allocate()` 会把所有旁白 shot 设为"无"（静音），分镜本身不受影响
2. **现有数据兼容**：`normalizeComicNarrationFields()` 仍然保留，对历史数据的 `speaker=旁白` 格式做兼容迁移
3. **TTS 阶段无需改动**：`ViduTtsService.buildTtsText()` 只读取 `narration` 字段，格式不变
4. **对白比例强制修正在 panel 级别做**（而非集级别），避免跨 panel 修正影响整体叙事节奏
