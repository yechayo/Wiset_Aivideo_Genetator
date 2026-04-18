# 两阶段分镜 Agent 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 StoryboardAgentService 从批量循环架构重构为两阶段流水线（Agent 做骨架 + Chat 做精修），解决剧情连贯性、角色一致性和场景一致性问题。

**Architecture:** 4 阶段流水线：Phase1 LLM结构分析 → Phase2 代码拆shot骨架 → Phase3 复用NarrativePlan → Phase4 接续式分段精修。三种模式（爽剧/普通/解说）统一走同一流程。

**Tech Stack:** Java 17, Spring Boot, DeepSeekTextService (LLM), Jackson (JSON), JUnit 5 + Mockito (测试)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java` | **重写** | 唯一改动的生产代码文件 |
| `backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java` | **重写** | 单元测试（mock LLM） |
| `backend/com/comic/src/test/java/com/comic/e2e/StoryboardV2IntegrationTest.java` | **新建** | 集成测试（真实 LLM 调用，手动验证） |

---

### Task 1: 新增数据类 + Phase 1 结构分析

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java`

- [ ] **Step 1: 将 `buildFallbackNarrativePlan` 从 `private` 改为包级可见（去掉 `private`）**

找到 line ~199 的 `private NarrativePlan buildFallbackNarrativePlan(...)` 去掉 `private` 修饰符，改为 `NarrativePlan buildFallbackNarrativePlan(...)`，使同包测试可以调用。

- [ ] **Step 2: 在文件底部（`NarrativePlan` 数据类之后）添加新数据类**

在 `NarrativePlan` 类之后、文件末尾 `}` 之前添加：

```java
// ==================== 两阶段架构数据类 ====================

public static class CharacterInScene {
    public String name;
    public String state;    // 角色当前状态
    public String position; // 角色在场景中的位置

    public CharacterInScene() {}
    public CharacterInScene(String name, String state, String position) {
        this.name = name; this.state = state; this.position = position;
    }
}

public static class StoryBeat {
    public int id;
    public String beat;
    public int duration;
    public String mood;
    public List<CharacterInScene> characters;

    public StoryBeat() {}
    public StoryBeat(int id, String beat, int duration, String mood, List<CharacterInScene> characters) {
        this.id = id; this.beat = beat; this.duration = duration;
        this.mood = mood; this.characters = characters;
    }
}

public static class StoryStructure {
    public String storyArc;
    public List<StoryBeat> beats;
    public List<Map<String, String>> transitions; // {from, to, bridge}

    public StoryStructure() { this.beats = new ArrayList<>(); this.transitions = new ArrayList<>(); }
}
```

- [ ] **Step 2: 添加 `import java.util.HashMap;`**（如果还没有的话，`Map` 已有）

- [ ] **Step 3: 在 `extractHookBeats()` 方法之后、`buildNarrativePlan()` 之前，添加 Phase 1 方法**

```java
// ==================== Phase 1: LLM 结构分析 ====================

/**
 * Phase 1: 用 LLM 分析剧本结构，拆成带角色状态的叙事节点。
 * 失败时返回 null，调用方走 fallback。
 */
StoryStructure analyzeStoryStructure(String episodeContent, String characters, int targetDuration) {
    String systemPrompt = buildAnalysisSystemPrompt();
    String userPrompt = buildAnalysisUserPrompt(episodeContent, characters, targetDuration);

    try {
        String output = reasoner.generate(systemPrompt, userPrompt);
        StoryStructure structure = parseStoryStructure(output);
        if (structure != null && structure.beats != null && !structure.beats.isEmpty()) {
            // 补全缺失的 transitions
            padTransitions(structure);
            log.info("[StoryboardAgent] Phase1 结构分析成功: {} 个beat, 弧线='{}'",
                    structure.beats.size(), structure.storyArc);
            return structure;
        }
    } catch (Exception e) {
        log.warn("[StoryboardAgent] Phase1 结构分析失败: {}", e.getMessage());
    }
    return null;
}

private String buildAnalysisSystemPrompt() {
    return "你是一位专业剧本结构分析师。你的任务是分析剧本，将其拆分为叙事节点（beat），每个节点标注在场角色及其状态。\n\n"
            + "输出纯 JSON（不要 markdown 代码块标记）：\n"
            + "{\n"
            + "  \"storyArc\": \"一句话概括本集故事弧线\",\n"
            + "  \"beats\": [\n"
            + "    {\n"
            + "      \"id\": 1,\n"
            + "      \"beat\": \"林晓星进入废弃工厂\",\n"
            + "      \"duration\": 12,\n"
            + "      \"mood\": \"紧张好奇\",\n"
            + "      \"characters\": [\n"
            + "        {\"name\": \"林晓星\", \"state\": \"警惕探索\", \"position\": \"工厂大厅\"}\n"
            + "      ]\n"
            + "    }\n"
            + "  ],\n"
            + "  \"transitions\": [\n"
            + "    {\"from\": 1, \"to\": 2, \"bridge\": \"推开铁门看到微光\"}\n"
            + "  ]\n"
            + "}\n\n"
            + "【关键约束】\n"
            + "1. 每个 beat 必须包含 characters 数组，标注谁在场、什么状态、在哪个位置\n"
            + "2. 相邻 beat 间必须有 transition，描述场景如何过渡\n"
            + "3. 所有 beat 的 duration 之和 = 目标时长 ± 10%\n"
            + "4. beat 数量 10-20 个\n"
            + "5. transitions 数组长度 = beats.length - 1\n"
            + "6. 确保角色在场逻辑合理：角色不会凭空出现或消失";
}

private String buildAnalysisUserPrompt(String episodeContent, String characters, int targetDuration) {
    return "目标总时长：" + targetDuration + " 秒\n"
            + "角色列表：" + characters + "\n\n"
            + "剧本内容：\n" + episodeContent + "\n\n"
            + "请输出剧本结构分析 JSON。";
}

StoryStructure parseStoryStructure(String json) {
    try {
        String cleaned = cleanJson(json);
        JsonNode root = objectMapper.readTree(cleaned);

        StoryStructure structure = new StoryStructure();
        structure.storyArc = root.has("storyArc") ? root.get("storyArc").asText("") : "";

        // 解析 beats
        JsonNode beatsNode = root.has("beats") ? root.get("beats") : null;
        if (beatsNode == null || !beatsNode.isArray() || beatsNode.isEmpty()) return null;

        for (JsonNode b : beatsNode) {
            StoryBeat beat = new StoryBeat();
            beat.id = b.has("id") ? b.get("id").asInt(0) : 0;
            beat.beat = b.has("beat") ? b.get("beat").asText("") : "";
            beat.duration = b.has("duration") ? b.get("duration").asInt(10) : 10;
            beat.mood = b.has("mood") ? b.get("mood").asText("") : "";

            // 解析 characters
            beat.characters = new ArrayList<>();
            if (b.has("characters") && b.get("characters").isArray()) {
                for (JsonNode c : b.get("characters")) {
                    String name = c.has("name") ? c.get("name").asText("") : "";
                    String state = c.has("state") ? c.get("state").asText("") : "";
                    String position = c.has("position") ? c.get("position").asText("") : "";
                    if (!name.isEmpty()) {
                        beat.characters.add(new CharacterInScene(name, state, position));
                    }
                }
            }
            structure.beats.add(beat);
        }

        // 解析 transitions
        structure.transitions = new ArrayList<>();
        if (root.has("transitions") && root.get("transitions").isArray()) {
            for (JsonNode t : root.get("transitions")) {
                Map<String, String> transition = new HashMap<>();
                transition.put("from", t.has("from") ? t.get("from").asText("") : "");
                transition.put("to", t.has("to") ? t.get("to").asText("") : "");
                transition.put("bridge", t.has("bridge") ? t.get("bridge").asText("场景自然过渡") : "场景自然过渡");
                structure.transitions.add(transition);
            }
        }

        return structure;
    } catch (Exception e) {
        log.warn("[StoryboardAgent] Phase1 解析失败: {}", e.getMessage());
        return null;
    }
}

/**
 * 补全缺失的 transitions：确保 transitions.length == beats.length - 1
 */
private void padTransitions(StoryStructure structure) {
    int expectedTransitions = structure.beats.size() - 1;
    while (structure.transitions.size() < expectedTransitions) {
        int fromId = structure.transitions.size() + 1;
        Map<String, String> t = new HashMap<>();
        t.put("from", String.valueOf(fromId));
        t.put("to", String.valueOf(fromId + 1));
        t.put("bridge", "场景自然过渡");
        structure.transitions.add(t);
    }
}
```

- [ ] **Step 4: 编写 Phase 1 的测试**

在 `StoryboardAgentServiceTest.java` 中添加：

```java
// ==================== Phase 1: 结构分析 ====================

@Test
void parseStoryStructure_shouldParseValidJson() {
    String json = "{\"storyArc\":\"发现秘密→追杀→反杀\","
            + "\"beats\":["
            + "{\"id\":1,\"beat\":\"进入工厂\",\"duration\":12,\"mood\":\"紧张\","
            + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"警惕\",\"position\":\"工厂\"}]},"
            + "{\"id\":2,\"beat\":\"发现实验室\",\"duration\":10,\"mood\":\"震惊\","
            + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"震惊\",\"position\":\"实验室\"},"
            + " {\"name\":\"陈墨\",\"state\":\"神秘\",\"position\":\"实验室\"}]}"
            + "],"
            + "\"transitions\":[{\"from\":\"1\",\"to\":\"2\",\"bridge\":\"推开铁门\"}]}";

    StoryboardAgentService.StoryStructure structure = agent.parseStoryStructure(json);

    assertNotNull(structure);
    assertEquals("发现秘密→追杀→反杀", structure.storyArc);
    assertEquals(2, structure.beats.size());
    assertEquals(1, structure.beats.get(0).id);
    assertEquals("林晓星", structure.beats.get(0).characters.get(0).name);
    assertEquals(2, structure.beats.get(1).characters.size());
    assertEquals(1, structure.transitions.size());
    assertEquals("推开铁门", structure.transitions.get(0).get("bridge"));
}

@Test
void parseStoryStructure_shouldReturnNullOnInvalidJson() {
    assertNull(agent.parseStoryStructure("not json"));
    assertNull(agent.parseStoryStructure("{\"storyArc\":\"xxx\"}")); // no beats
}
```

- [ ] **Step 5: 运行测试验证**

Run: `cd backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest#parseStoryStructure_shouldParseValidJson,StoryboardAgentServiceTest#parseStoryStructure_shouldReturnNullOnInvalidJson -Dsurefire.useFile=false`

- [ ] **Step 6: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(storyboard): Phase 1 数据类 + LLM 结构分析方法 + 测试"
```

---

### Task 2: Phase 2 代码拆 shot 骨架

**Files:**
- Modify: `StoryboardAgentService.java` (添加 `buildShotSkeletons` 方法)
- Modify: `StoryboardAgentServiceTest.java` (添加测试)

- [ ] **Step 1: 在 Phase 1 方法之后添加 Phase 2 方法**

```java
// ==================== Phase 2: 代码拆 shot 骨架 ====================

/**
 * Phase 2: 纯代码，把 StoryStructure 的 beats 拆成 shot 骨架。
 * 不调用 LLM。
 */
List<Map<String, Object>> buildShotSkeletons(StoryStructure structure, NarrativePlan plan) {
    List<Map<String, Object>> skeletons = new ArrayList<>();
    int shotNumber = 1;
    int accumulatedSeconds = 0;

    for (int beatIdx = 0; beatIdx < structure.beats.size(); beatIdx++) {
        StoryBeat beat = structure.beats.get(beatIdx);
        int shotCount = Math.max(1, (int) Math.round((double) beat.duration / 3.5));
        int avgDuration = Math.max(1, Math.min(4, beat.duration / shotCount));

        for (int i = 0; i < shotCount; i++) {
            Map<String, Object> skeleton = new HashMap<>();
            skeleton.put("shotNumber", shotNumber++);
            // 最后一个 shot 吸收余量时长
            int duration = (i == shotCount - 1)
                    ? Math.max(1, Math.min(4, beat.duration - avgDuration * (shotCount - 1)))
                    : avgDuration;
            skeleton.put("duration", duration);
            skeleton.put("beatId", beat.id);
            skeleton.put("beatIndex", beatIdx);
            skeleton.put("sceneHint", beat.beat);
            skeleton.put("mood", beat.mood);
            // 每个 shot 级别匹配 NarrativePhase（跨阶段边界的 beat 内的 shot 可分属不同阶段）
            skeleton.put("narrativePhase", matchNarrativePhase(accumulatedSeconds, plan));

            // 继承 beat 的角色信息
            List<Map<String, String>> charList = new ArrayList<>();
            if (beat.characters != null) {
                for (CharacterInScene c : beat.characters) {
                    Map<String, String> charMap = new HashMap<>();
                    charMap.put("name", c.name);
                    charMap.put("state", c.state);
                    charMap.put("position", c.position);
                    charList.add(charMap);
                }
            }
            skeleton.put("characters", charList);

            skeletons.add(skeleton);
            accumulatedSeconds += duration;
        }
    }

    log.info("[StoryboardAgent] Phase2 骨架生成: {} 个shot, 预估{}秒",
            skeletons.size(), accumulatedSeconds);
    return skeletons;
}

/**
 * 兜底骨架：Phase1 失败时均匀拆分
 */
List<Map<String, Object>> buildFallbackSkeletons(int targetDuration, String episodeContent,
                                                   String characters, NarrativePlan plan) {
    int shotCount = Math.max(3, (int) Math.round((double) targetDuration / 3.5));
    int avgDuration = Math.max(1, Math.min(4, targetDuration / shotCount));
    // 修正最后一个 shot 时长以确保总和精确等于 targetDuration
    int lastDuration = targetDuration - avgDuration * (shotCount - 1);
    // 如果 lastDuration 超出 1-4 范围，需要调整 shotCount
    if (lastDuration > 4 || lastDuration < 1) {
        shotCount = targetDuration / 3; // 重新计算
        avgDuration = 3;
        lastDuration = targetDuration - avgDuration * (shotCount - 1);
    }

    // 按字符数均匀拆分剧本作为 sceneHint
    String[] parts = splitContentEvenly(episodeContent, shotCount);

    List<Map<String, Object>> skeletons = new ArrayList<>();
    int accumulatedSeconds = 0;
    for (int i = 0; i < shotCount; i++) {
        Map<String, Object> skeleton = new HashMap<>();
        skeleton.put("shotNumber", i + 1);
        int duration = (i == shotCount - 1)
                ? Math.max(1, Math.min(4, lastDuration))
                : avgDuration;
        skeleton.put("duration", duration);
        skeleton.put("beatId", 1);
        skeleton.put("beatIndex", 0);
        skeleton.put("sceneHint", parts[i]);
        skeleton.put("mood", "");
        skeleton.put("narrativePhase", matchNarrativePhase(accumulatedSeconds, plan));

        // 使用全局角色列表
        List<Map<String, String>> charList = new ArrayList<>();
        if (characters != null && !characters.isEmpty()) {
            for (String name : characters.split("[,，]")) {
                Map<String, String> charMap = new HashMap<>();
                charMap.put("name", name.trim());
                charMap.put("state", "");
                charMap.put("position", "");
                charList.add(charMap);
            }
        }
        skeleton.put("characters", charList);

        skeletons.add(skeleton);
        accumulatedSeconds += duration;
    }

    log.info("[StoryboardAgent] Phase2 兜底骨架: {} 个shot", shotCount);
    return skeletons;
}

private String matchNarrativePhase(int accumulatedSeconds, NarrativePlan plan) {
    if (plan == null || plan.phases == null || plan.phases.isEmpty()) return "";
    int boundary = 0;
    for (NarrativePhase phase : plan.phases) {
        boundary += phase.allocatedSeconds;
        if (accumulatedSeconds < boundary) return phase.name;
    }
    return plan.phases.get(plan.phases.size() - 1).name;
}

private String[] splitContentEvenly(String content, int parts) {
    if (content == null || content.isEmpty()) {
        String[] result = new String[parts];
        java.util.Arrays.fill(result, "");
        return result;
    }
    String[] result = new String[parts];
    int chunkSize = Math.max(1, content.length() / parts);
    for (int i = 0; i < parts; i++) {
        int start = Math.min(i * chunkSize, content.length());
        int end = Math.min((i + 1) * chunkSize, content.length());
        if (i == parts - 1) end = content.length();
        result[i] = content.substring(start, end);
    }
    return result;
}
```

- [ ] **Step 2: 编写 Phase 2 测试**

```java
// ==================== Phase 2: 骨架生成 ====================

@Test
void buildShotSkeletons_shouldSplitBeatsIntoShots() {
    StoryboardAgentService.StoryStructure structure = new StoryboardAgentService.StoryStructure();
    structure.beats.add(new StoryboardAgentService.StoryBeat(1, "进入工厂", 10, "紧张",
            List.of(new StoryboardAgentService.CharacterInScene("林晓星", "警惕", "工厂"))));
    structure.beats.add(new StoryboardAgentService.StoryBeat(2, "发现实验室", 8, "震惊",
            List.of(new StoryboardAgentService.CharacterInScene("林晓星", "震惊", "实验室"),
                    new StoryboardAgentService.CharacterInScene("陈墨", "神秘", "实验室"))));

    StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(List.of(), 18);

    List<Map<String, Object>> skeletons = agent.buildShotSkeletons(structure, plan);

    assertFalse(skeletons.isEmpty());
    // 10s / 3.5 ≈ 3 shots, 8s / 3.5 ≈ 2 shots = 5 total
    assertTrue(skeletons.size() >= 4, "应生成至少 4 个骨架 shot");
    assertEquals("林晓星", ((List<Map<String, String>>) skeletons.get(0).get("characters")).get(0).get("name"));
}

@Test
void buildFallbackSkeletons_shouldGenerateSkeletons() {
    StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(List.of(), 30);
    List<Map<String, Object>> skeletons = agent.buildFallbackSkeletons(30, "剧本内容", "角色A,角色B", plan);

    assertFalse(skeletons.isEmpty());
    int totalDuration = skeletons.stream().mapToInt(s -> (int) s.get("duration")).sum();
    assertEquals(30, totalDuration, "兜底骨架时长应等于目标时长");
}
```

- [ ] **Step 3: 运行测试**

Run: `cd backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false`

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(storyboard): Phase 2 代码拆shot骨架 + 兜底方案 + 测试"
```

---

### Task 3: Phase 4 精修 prompt + 精修方法

**Files:**
- Modify: `StoryboardAgentService.java` (添加精修相关方法)

- [ ] **Step 1: 在 Phase 2 方法之后添加 Phase 4 方法**

```java
// ==================== Phase 4: 接续式分段精修 ====================

private static final int REFINE_SEGMENT_SIZE = 15; // 每段 12-18 个 shot，取 15

/**
 * Phase 4: 分段精修骨架，填充完整字段。
 * 每段带前段最后 3 个精修 shot 作为接续上下文。
 */
List<Map<String, Object>> refineSkeletons(List<Map<String, Object>> skeletons,
                                            StoryStructure structure, NarrativePlan plan,
                                            String characters, String visualStyle,
                                            boolean comicMode, String narrationPerspective,
                                            String scriptStyle) {
    List<Map<String, Object>> allRefined = new ArrayList<>();
    List<Map<String, Object>> lastRefined = new ArrayList<>();

    String refineSystem = buildRefineSystemPrompt(comicMode, scriptStyle, narrationPerspective);

    for (int segStart = 0; segStart < skeletons.size(); segStart += REFINE_SEGMENT_SIZE) {
        int segEnd = Math.min(segStart + REFINE_SEGMENT_SIZE, skeletons.size());
        List<Map<String, Object>> segment = skeletons.subList(segStart, segEnd);

        String refineUser = buildRefineUserPrompt(segment, structure, plan, lastRefined,
                characters, visualStyle, comicMode, narrationPerspective, scriptStyle, segStart);

        List<Map<String, Object>> refined = refineSegment(refineSystem, refineUser, segment, comicMode, scriptStyle);
        if (refined == null || refined.isEmpty()) {
            // 精修失败，骨架转最小完整 shot
            log.warn("[StoryboardAgent] Phase4 段 {}-{} 精修失败，使用骨架兜底", segStart, segEnd);
            refined = skeletonToFallbackShots(segment);
        }

        // 赋予全局编号和时间戳
        for (Map<String, Object> shot : refined) {
            int d = Math.max(1, Math.min(4, ((Number) shot.getOrDefault("duration", 3)).intValue()));
            shot.put("duration", d);
        }

        allRefined.addAll(refined);

        // 保存最后 3 个用于下一段接续
        lastRefined = new ArrayList<>(refined.subList(
                Math.max(0, refined.size() - LAST_SHOTS_COUNT), refined.size()));

        // 质量检查
        int currentPhaseIdx = estimateCurrentPhaseIndex(segStart + refined.size(), skeletons, plan);
        String quality = checkBatchQuality(refined, plan, currentPhaseIdx, comicMode);
        log.info("[StoryboardAgent] Phase4 段 {}-{}: {}镜 {}", segStart, segEnd, refined.size(), quality);
    }

    return allRefined;
}

private String buildRefineSystemPrompt(boolean comicMode, String scriptStyle, String narrationPerspective) {
    StringBuilder sb = new StringBuilder();

    if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
        sb.append("你是一位爽剧分镜精修师。你的任务是将 shot 骨架精修为完整的分镜。\n");
        sb.append("节奏极快，三秒一个爽点。\n\n");
    } else if (comicMode) {
        sb.append("你是一位漫剧解说分镜精修师。你的任务是将 shot 骨架精修为完整的分镜。\n\n");
    } else {
        sb.append("你是一位专业分镜精修师。你的任务是将 shot 骨架精修为完整的分镜。\n\n");
    }

    sb.append("你会收到一组 shot 骨架（JSON 数组），每个骨架有：\n");
    sb.append("- shotNumber, duration, beatId, sceneHint, mood, characters(带name/state/position), narrativePhase\n\n");
    sb.append("你需要输出相同数量的完整 shot JSON 数组，每个 shot 包含：\n");
    sb.append("shotNumber, duration(保持不变), scene, characters(必须使用骨架中的角色全名),\n");
    sb.append("shotSize, cameraAngle, cameraMovement, sceneDescription,\n");
    sb.append("dialogue, speaker, dialogueTone, visualEffects, audioEffects, transitionHint\n");
    if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
        sb.append(", hookPoint\n");
    }
    if (comicMode) {
        sb.append(", narration\n");
    }
    sb.append("\n");

    // 角色一致性约束
    sb.append("【角色一致性 - 硬性约束】\n");
    sb.append("1. 每个 shot 的 characters 必须使用骨架中的角色名，禁止换名或用泛称（如\"主角\"\"反派\"\"路人\"）\n");
    sb.append("2. 只有骨架中列出的角色才能出现，不能凭空增减角色\n");
    sb.append("3. 角色状态必须连贯：如果上一个 shot 角色在\"奔跑\"，本 shot 不能突然\"坐着喝茶\"\n");
    sb.append("4. 角色位置变化必须合理：如果上一个 shot 在\"工厂大厅\"，本 shot 不能突然在\"地下实验室\"（除非有 transition 过渡）\n");
    sb.append("5. 骨架中的 characters 数组列出了本 beat 中在场的角色，这是唯一合法角色来源\n\n");

    // 场景一致性约束
    sb.append("【场景一致性】\n");
    sb.append("1. 相邻 shot 的场景不能凭空跳转，必须通过 transition 过渡\n");
    sb.append("2. sceneDescription 必须与骨架中的 mood 和 position 匹配\n");
    sb.append("3. 角色动作必须符合当前场景逻辑\n\n");

    // 对话约束（复用现有）
    sb.append("【对话约束 - 硬性要求】\n");
    sb.append("1. 不是每个镜头都需要 dialogue，约 40-60% 有 dialogue 即可\n");
    sb.append("2. dialogue 字数必须匹配 duration：1s≤5字, 2s≤8字, 3s≤15字, 4s≤20字\n");
    sb.append("3. 连续 3 个镜头不能都有 dialogue\n");
    sb.append("4. 当 dialogue 为空时，speaker 填 \"无\"，dialogueTone 填 \"无\"，sceneDescription 应更详细\n\n");

    // 模式特定约束
    if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
        sb.append("【爽剧模式】\n");
        sb.append("- hookPoint 字段必须填写，标注本镜头的爽点类型\n");
        sb.append("- 节奏极快，多用 1-2 秒快切镜头\n\n");
    }
    if (comicMode) {
        sb.append("【解说模式】\n");
        sb.append("- 每个镜头必须有 narration 字段（旁白口播稿）\n");
        sb.append("- narration 字数受 duration 约束：3s≤15字, 4s≤20字\n");
        sb.append("- narration 和 dialogue 不能同时存在\n");
        sb.append("- 当有 dialogue 时，narration 填 \"无\"\n");
        sb.append("- narration 优先级高于 dialogue：关键剧情节点用 narration 推进\n");
        if (narrationPerspective != null) {
            if ("third_person".equals(narrationPerspective)) {
                sb.append("- 旁白必须使用第三人称叙述\n");
            } else if ("first_person".equals(narrationPerspective)) {
                sb.append("- 旁白必须使用第一人称「我」叙述\n");
            }
        }
        sb.append("\n");
    }

    sb.append("输出纯 JSON 数组，不要 markdown 代码块。保持骨架的 shotNumber 和 duration 不变。");
    return sb.toString();
}

private String buildRefineUserPrompt(List<Map<String, Object>> segment,
                                      StoryStructure structure, NarrativePlan plan,
                                      List<Map<String, Object>> lastRefined,
                                      String characters, String visualStyle,
                                      boolean comicMode, String narrationPerspective,
                                      String scriptStyle, int globalOffset) {
    StringBuilder sb = new StringBuilder();

    // 全局故事弧线
    if (structure != null && structure.storyArc != null && !structure.storyArc.isEmpty()) {
        sb.append("## 全局故事弧线\n").append(structure.storyArc).append("\n\n");
    }

    // 叙事规划
    if (plan != null && plan.phases != null && !plan.phases.isEmpty()) {
        sb.append("## 叙事规划\n");
        for (int i = 0; i < plan.phases.size(); i++) {
            NarrativePhase phase = plan.phases.get(i);
            sb.append(i + 1).append(". ").append(phase.name)
              .append(" (").append(phase.allocatedSeconds).append("秒, 密度:").append(phase.dialogueDensity)
              .append(", 情绪:").append(phase.emotionalArc).append(")\n");
        }
        sb.append("\n");
    }

    // transitions（当前段涉及的 beat 间过渡）
    if (structure != null && structure.transitions != null && !structure.transitions.isEmpty()) {
        int firstBeatId = segment.isEmpty() ? 0 : (int) segment.get(0).getOrDefault("beatId", 0);
        int lastBeatId = segment.isEmpty() ? 0 : (int) segment.get(segment.size() - 1).getOrDefault("beatId", 0);
        List<String> relevantTransitions = new ArrayList<>();
        for (Map<String, String> t : structure.transitions) {
            int from = parseIntSafe(t.getOrDefault("from", "0"), 0);
            int to = parseIntSafe(t.getOrDefault("to", "0"), 0);
            if (from >= firstBeatId && to <= lastBeatId + 1) {
                relevantTransitions.add(t.get("bridge"));
            }
        }
        if (!relevantTransitions.isEmpty()) {
            sb.append("## 场景过渡\n");
            for (String bridge : relevantTransitions) {
                sb.append("- ").append(bridge).append("\n");
            }
            sb.append("\n");
        }
    }

    // 前段接续（完整 shot，不是摘要）
    if (!lastRefined.isEmpty()) {
        sb.append("## 前段接续（最后").append(lastRefined.size()).append("个精修shot，严格参照保持连贯）\n");
        try {
            sb.append(objectMapper.writeValueAsString(lastRefined)).append("\n\n");
        } catch (Exception e) {
            // fallback 到文本摘要
            for (Map<String, Object> shot : lastRefined) {
                sb.append("- 第").append(shot.get("shotNumber")).append("镜[")
                  .append(shot.get("duration")).append("秒] ")
                  .append(shot.getOrDefault("sceneDescription", shot.getOrDefault("scene", ""))).append("\n");
            }
            sb.append("\n");
        }
    }

    // 当前段骨架
    sb.append("## 当前段 shot 骨架（请精修为完整 shot）\n");
    try {
        sb.append(objectMapper.writeValueAsString(segment)).append("\n\n");
    } catch (Exception e) {
        sb.append("[骨架序列化失败]\n\n");
    }

    // 角色列表
    sb.append("## 角色列表\n").append(characters).append("\n");
    sb.append("（characters 字段必须使用骨架中给出的角色全名，禁止泛称）\n\n");

    // 风格
    sb.append("风格：").append(visualStyle).append("\n");

    // 解说模式人称
    if (comicMode && narrationPerspective != null) {
        if ("third_person".equals(narrationPerspective)) {
            sb.append("\n旁白人称：第三人称\n");
        } else if ("first_person".equals(narrationPerspective)) {
            sb.append("\n旁白人称：第一人称「我」\n");
        }
    }

    sb.append("\n请输出精修后的完整 shot JSON 数组。保持 shotNumber 和 duration 不变。");
    return sb.toString();
}

/**
 * 精修单段骨架：调用 LLM + 重试
 */
private List<Map<String, Object>> refineSegment(String systemPrompt, String userPrompt,
                                                  List<Map<String, Object>> fallbackSegment,
                                                  boolean comicMode, String scriptStyle) {
    for (int retry = 0; retry <= MAX_EXECUTOR_RETRIES; retry++) {
        try {
            String output = executor.generate(systemPrompt, userPrompt);
            List<Map<String, Object>> shots = parseShotArray(output);
            if (shots != null && !shots.isEmpty()) {
                return shots;
            }
        } catch (Exception e) {
            log.warn("[StoryboardAgent] Phase4 精修重试 {}/{}: {}", retry + 1, MAX_EXECUTOR_RETRIES, e.getMessage());
        }
    }
    return null;
}

/**
 * 骨架转最小完整 shot（兜底）
 */
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

/**
 * 估算当前 shot 在 skeletons 中的 NarrativePhase 索引
 */
private int estimateCurrentPhaseIndex(int globalShotIndex, List<Map<String, Object>> skeletons,
                                       NarrativePlan plan) {
    if (plan == null || plan.phases == null || skeletons.isEmpty()) return 0;
    // 计算到 globalShotIndex 处的累计时长
    int accDuration = 0;
    for (int i = 0; i < Math.min(globalShotIndex, skeletons.size()); i++) {
        accDuration += ((Number) skeletons.get(i).getOrDefault("duration", 3)).intValue();
    }
    int boundary = 0;
    for (int i = 0; i < plan.phases.size(); i++) {
        boundary += plan.phases.get(i).allocatedSeconds;
        if (accDuration < boundary) return i;
    }
    return plan.phases.size() - 1;
}
```

- [ ] **Step 2: 运行全量测试确保不破坏现有功能**

Run: `cd backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false`

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java
git commit -m "feat(storyboard): Phase 4 精修 prompt + 分段精修 + 兜底策略"
```

---

### Task 4: 新主入口 `generateV2()` + 清理旧方法

**Files:**
- Modify: `StoryboardAgentService.java` (替换 `generate()` 路由)
- Modify: `StoryboardAgentServiceTest.java` (重写测试)

- [ ] **Step 1: 替换 `generate()` 方法体，路由到 `generateV2()`**

将 `generate()` 方法（约 line 1035-1043）替换为：

```java
// ==================== 主入口 ====================

public List<Map<String, Object>> generate(String episodeContent, String characters,
                                           int targetDuration, String visualStyle,
                                           boolean comicMode, String narrationPerspective,
                                           String scriptStyle) {
    return generateV2(episodeContent, characters, targetDuration, visualStyle,
            comicMode, narrationPerspective, scriptStyle);
}

/**
 * 两阶段架构：Phase1 结构分析 → Phase3 规划 → Phase2 骨架(需要phase信息) → Phase4 精修
 * 注意：Phase3 在 Phase2 之前，因为骨架生成需要 NarrativePlan 的阶段归属信息
 */
private List<Map<String, Object>> generateV2(String episodeContent, String characters,
                                               int targetDuration, String visualStyle,
                                               boolean comicMode, String narrationPerspective,
                                               String scriptStyle) {
    log.info("[StoryboardAgent] V2 启动: target={}s, mode={}", targetDuration,
            ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? "爽剧" : (comicMode ? "解说" : "标准"));

    // === Phase 1: LLM 结构分析 ===
    StoryStructure structure = analyzeStoryStructure(episodeContent, characters, targetDuration);

    // === Phase 3: 全局导演规划 ===
    // Phase3 在 Phase2 之前执行，因为骨架生成需要知道每个 shot 属于哪个叙事阶段
    List<String> hooks = extractHookBeats(episodeContent);
    String existingArc = (structure != null && structure.storyArc != null) ? structure.storyArc : "";
    NarrativePlan plan = buildNarrativePlan(episodeContent, hooks, targetDuration, characters, scriptStyle, comicMode);
    if (!existingArc.isEmpty()) {
        plan.storyArcSummary = existingArc;
    }

    // === Phase 2: 代码拆 shot 骨架 ===
    List<Map<String, Object>> skeletons;
    if (structure != null && structure.beats != null && !structure.beats.isEmpty()) {
        skeletons = buildShotSkeletons(structure, plan);
    } else {
        skeletons = buildFallbackSkeletons(targetDuration, episodeContent, characters, plan);
    }

    log.info("[StoryboardAgent] Phase2 完成: {} 个shot骨架", skeletons.size());

    // === Phase 4: 接续式分段精修 ===
    List<Map<String, Object>> refinedShots = refineSkeletons(skeletons, structure, plan,
            characters, visualStyle, comicMode, narrationPerspective, scriptStyle);

    // 赋予全局编号和时间戳
    int t = 0;
    for (int i = 0; i < refinedShots.size(); i++) {
        Map<String, Object> shot = refinedShots.get(i);
        shot.put("globalShotNumber", i + 1);
        shot.put("startTime", t);
        int d = ((Number) shot.getOrDefault("duration", 3)).intValue();
        t += d;
        shot.put("endTime", t);
    }

    log.info("[StoryboardAgent] V2 完成: {}镜, {}s", refinedShots.size(), t);
    return refinedShots;
}
```

- [ ] **Step 2: 删除旧方法**

删除以下方法（它们不再被 `generateV2` 调用）：
- `generateReAct()` (line ~394-515)
- `generateIterative()` (line ~1047-1105)
- `buildReactSystemPrompt()` (line ~256-312)
- `buildReactUserPrompt()` (line ~317-360)
- `parseReactOutput()` (line ~367-389)
- `callExecutor()` 两个重载 (line ~520-554)
- `buildReasonerSystemPrompt()` (line ~621-672)
- `buildReasonerPrompt()` 两个重载 (line ~674-743)
- `buildExecutorSystemPrompt()` (line ~747-811)
- `buildExecutorPrompt()` 两个重载 (line ~813-875)
- `buildPlanSystemPrompt()` (line ~558-565)
- `buildPlanUserPrompt()` (line ~567-575)
- `parsePlan()` (line ~577-598)
- `buildFallbackPlan()` (line ~600-617)
- `updateCurrentPhase()` (line ~914-925)
- `getCurrentPhaseInfo()` (line ~930-935)
- `accumulateShots()` (line ~889-909)

保留以下方法：
- `parseReasonerDecision()` (虽然 V2 不用，但外部可能有引用，保留以避免编译错误)
- `buildNarrativePlan()`, `buildFallbackNarrativePlan()`, `parseNarrativePlan()`, `buildNarrativePlanSystemPrompt()`, `buildNarrativePlanUserPrompt()`
- `checkBatchQuality()`, `getMaxDialogueChars()`, `getDialogueRatio()`, `getDialogueDensityPercent()`
- `parseShotArray()`, `cleanJson()`, `truncationRecovery()`, `regexExtractShots()`, `extractShotsFromNode()`
- `parseIntSafe()`
- 所有数据类

- [ ] **Step 3: 重写测试文件 `StoryboardAgentServiceTest.java`**

删除所有旧测试（`generateReAct_*`, `buildReactSystemPrompt_*`, `parseReactOutput_*`, `buildReasonerPrompt_*`, `buildExecutorPrompt_*`, `parsePlan_*`, `buildPlanUserPrompt_*`），替换为新测试：

```java
package com.comic.service.production;

import com.comic.ai.text.DeepSeekTextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StoryboardAgentServiceTest {

    private StoryboardAgentService agent;
    private DeepSeekTextService mockReasoner;
    private DeepSeekTextService mockExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockReasoner = mock(DeepSeekTextService.class);
        mockExecutor = mock(DeepSeekTextService.class);
        objectMapper = new ObjectMapper();
        agent = new StoryboardAgentService(mockReasoner, mockExecutor, objectMapper);
    }

    // ==================== Phase 1: 结构分析 ====================

    @Test
    void parseStoryStructure_shouldParseValidJson() {
        String json = "{\"storyArc\":\"发现秘密\","
                + "\"beats\":["
                + "{\"id\":1,\"beat\":\"进入工厂\",\"duration\":12,\"mood\":\"紧张\","
                + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"警惕\",\"position\":\"工厂\"}]},"
                + "{\"id\":2,\"beat\":\"发现实验室\",\"duration\":10,\"mood\":\"震惊\","
                + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"震惊\",\"position\":\"实验室\"}]}"
                + "],"
                + "\"transitions\":[{\"from\":\"1\",\"to\":\"2\",\"bridge\":\"推开铁门\"}]}";

        StoryboardAgentService.StoryStructure s = agent.parseStoryStructure(json);
        assertNotNull(s);
        assertEquals(2, s.beats.size());
        assertEquals("林晓星", s.beats.get(0).characters.get(0).name);
        assertEquals(1, s.transitions.size());
    }

    @Test
    void parseStoryStructure_shouldReturnNullOnInvalid() {
        assertNull(agent.parseStoryStructure("bad"));
        assertNull(agent.parseStoryStructure("{\"storyArc\":\"x\"}"));
    }

    // ==================== Phase 2: 骨架生成 ====================

    @Test
    void buildShotSkeletons_shouldSplitBeatsIntoShots() {
        StoryboardAgentService.StoryStructure structure = new StoryboardAgentService.StoryStructure();
        structure.beats.add(new StoryboardAgentService.StoryBeat(1, "进入工厂", 10, "紧张",
                List.of(new StoryboardAgentService.CharacterInScene("林晓星", "警惕", "工厂"))));
        StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(List.of(), 10);

        List<Map<String, Object>> skeletons = agent.buildShotSkeletons(structure, plan);
        assertFalse(skeletons.isEmpty());
        assertTrue(skeletons.size() >= 2);
    }

    @Test
    void buildFallbackSkeletons_shouldMatchTargetDuration() {
        StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(List.of(), 30);
        List<Map<String, Object>> skeletons = agent.buildFallbackSkeletons(30, "内容", "A,B", plan);
        int total = skeletons.stream().mapToInt(s -> (int) s.get("duration")).sum();
        // 整数除法可能导致不精确，允许 ±2s 误差
        assertTrue(total >= 28 && total <= 32,
                "兜底骨架时长应接近目标时长 30s，实际: " + total);
    }

    // ==================== Hook 提取 ====================

    @Test
    void extractHookBeats_shouldExtractAllMarkers() {
        List<String> hooks = agent.extractHookBeats("内容 [爽点:专注细节] 枪身 [爽点:觉醒] 命中 [爽点:碾压]");
        assertEquals(3, hooks.size());
    }

    @Test
    void extractHookBeats_shouldReturnEmptyOnNoMarkers() {
        assertTrue(agent.extractHookBeats("普通内容").isEmpty());
    }

    // ==================== 叙事规划 ====================

    @Test
    void buildFallbackNarrativePlan_shouldSumToTarget() {
        StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(List.of("a","b","c"), 120);
        int total = plan.phases.stream().mapToInt(p -> p.allocatedSeconds).sum();
        assertEquals(120, total);
    }

    // ==================== V2 集成 (mock) ====================

    @Test
    void generate_shouldProduceShotsViaV2() {
        // Phase1: 结构分析
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("{\"storyArc\":\"测试\",\"beats\":[{\"id\":1,\"beat\":\"开场\",\"duration\":15,\"mood\":\"紧张\","
                        + "\"characters\":[{\"name\":\"A\",\"state\":\"警惕\",\"position\":\"工厂\"}]}],"
                        + "\"transitions\":[]}")
                // Phase3: 叙事规划
                .thenReturn("{\"storyArcSummary\":\"测试\",\"phases\":[{\"name\":\"开场\",\"startBeat\":1,\"endBeat\":1,"
                        + "\"allocatedSeconds\":15,\"dialogueDensity\":\"sparse\",\"maxDialogueChars\":\"3秒≤15字\","
                        + "\"emotionalArc\":\"冲击\",\"pacingNote\":\"快切\"}]}");

        // Phase4: 精修
        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn("[{\"shotNumber\":1,\"duration\":3,\"scene\":\"工厂\",\"characters\":[\"A\"],"
                        + "\"shotSize\":\"MEDIUM\",\"cameraAngle\":\"eye_level\",\"cameraMovement\":\"static\","
                        + "\"sceneDescription\":\"A走进工厂\",\"dialogue\":\"\",\"speaker\":\"无\","
                        + "\"dialogueTone\":\"无\",\"visualEffects\":\"无\",\"audioEffects\":\"无\","
                        + "\"transitionHint\":\"下一镜\"}]");

        List<Map<String, Object>> result = agent.generate("剧本内容", "A", 15, "cinematic", false, null, "standard");

        assertFalse(result.isEmpty());
        // 2 reasoner calls (Phase1 + Phase3) + at least 1 executor call (Phase4)
        verify(mockReasoner, times(2)).generate(anyString(), anyString());
        verify(mockExecutor, atLeastOnce()).generate(anyString(), anyString());
    }

    @Test
    void generate_shouldFallbackWhenPhase1Fails() {
        when(mockReasoner.generate(anyString(), anyString()))
                // Phase1 失败
                .thenReturn("not json")
                // Phase3 叙事规划 fallback
                .thenReturn("not json either");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn("[{\"shotNumber\":1,\"duration\":3,\"scene\":\"场景1\",\"characters\":[\"A\"],"
                        + "\"shotSize\":\"MEDIUM\",\"cameraAngle\":\"eye_level\",\"cameraMovement\":\"static\","
                        + "\"sceneDescription\":\"描述\",\"dialogue\":\"\",\"speaker\":\"无\","
                        + "\"dialogueTone\":\"无\",\"visualEffects\":\"无\",\"audioEffects\":\"无\","
                        + "\"transitionHint\":\"下一镜\"}]");

        List<Map<String, Object>> result = agent.generate("剧本", "A", 10, "cinematic", false, null, "standard");
        assertFalse(result.isEmpty(), "Phase1 失败应走兜底路径");
    }

    // ==================== 质量检查 ====================

    @Test
    void checkBatchQuality_shouldReportOverLength() {
        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> shot = new HashMap<>();
        shot.put("shotNumber", 1);
        shot.put("duration", 3);
        shot.put("dialogue", "这段台词非常非常非常长超过了十五个字的上限");
        shots.add(shot);

        String report = agent.checkBatchQuality(shots, null, -1, false);
        assertTrue(report.contains("超长") || report.contains("超过"));
    }

    // ==================== 旧方法保留测试 ====================

    @Test
    void parseReasonerDecision_shouldFallbackOnBadInput() {
        StoryboardAgentService.ReasonerDecision d = agent.parseReasonerDecision("bad");
        assertEquals("generate", d.action);
    }
}
```

- [ ] **Step 4: 运行全量测试**

Run: `cd backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false`

Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(storyboard): generateV2 主入口 + 删除旧批量循环 + 重写测试"
```

---

### Task 5: 新增集成测试 `StoryboardV2IntegrationTest`

**Files:**
- Create: `backend/com/comic/src/test/java/com/comic/e2e/StoryboardV2IntegrationTest.java`

- [ ] **Step 1: 创建集成测试文件**

```java
package com.comic.e2e;

import com.comic.service.production.StoryboardAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两阶段分镜 Agent 集成测试
 * 使用真实 LLM 调用，验证新架构端到端效果。
 * 不需要创建项目，直接调用 generate()。
 *
 * 运行方式: mvn test -pl . -Dtest=StoryboardV2IntegrationTest -Dsurefire.useFile=false
 */
@SpringBootTest
class StoryboardV2IntegrationTest {

    @Autowired
    StoryboardAgentService service;

    private static final String SAMPLE_SCRIPT =
            "林晓星是一名普通大学生，某天偶然进入了一座废弃的工厂。"
            + "[爽点:悬念开场] 工厂内部幽暗潮湿，到处是锈迹斑斑的机器。"
            + "她小心翼翼地探索着每一个房间。突然，她发现了一扇隐藏的铁门。"
            + "[爽点:意外发现] 推开铁门，眼前是一个高科技地下实验室。"
            + "实验室内闪烁着蓝光，中央有一个神秘的装置。"
            + "就在她靠近装置时，一个黑衣人突然出现在她身后。"
            + "[爽点:危机降临] \"你不该来这里的。\"黑衣人冷冷地说。"
            + "林晓星转身面对黑衣人，发现对方竟然是她失踪多年的哥哥——林晓辰。"
            + "[爽点:身份反转] \"哥...你怎么会在这里？\"林晓星震惊不已。"
            + "林晓辰眼中闪过一丝复杂的情绪：\"快走，这里不是你该待的地方。\""
            + "就在这时，实验室的警报突然响起，红光闪烁。"
            + "[爽点:危机升级] \"他们发现了！\"林晓辰拉着林晓星向出口跑去。"
            + "两人穿过狭窄的走廊，身后传来追赶的脚步声。"
            + "林晓辰按下一个隐藏按钮，墙壁打开露出一条密道。"
            + "[爽点:绝境逢生] \"记住，不管发生什么，不要相信任何人。\"林晓辰把一个U盘塞进林晓星手中。"
            + "密道关闭，林晓星独自一人站在黑暗中，紧握着U盘。"
            + "[爽点:悬念结尾] 她抬头看向头顶的微光，眼中燃起坚定的光芒。";

    @Test
    void testShuangjuMode() {
        List<Map<String, Object>> shots = service.generate(
                SAMPLE_SCRIPT, "林晓星,林晓辰", 60, "cinematic",
                false, null, "shuangju");

        // 基础断言
        assertFalse(shots.isEmpty(), "应生成分镜");
        int totalDuration = shots.stream()
                .mapToInt(s -> ((Number) s.getOrDefault("duration", 3)).intValue()).sum();
        assertTrue(totalDuration >= 40 && totalDuration <= 80,
                "总时长应在 40-80s 范围内，实际: " + totalDuration);

        // 打印供人工审查
        System.out.println("=== 爽剧模式分镜 (" + shots.size() + "镜, " + totalDuration + "s) ===");
        for (Map<String, Object> shot : shots) {
            System.out.printf("  #%s [%ss] %s | dialogue=%s | hookPoint=%s%n",
                    shot.get("shotNumber"), shot.get("duration"),
                    shot.getOrDefault("sceneDescription", shot.getOrDefault("scene", "")),
                    shot.getOrDefault("dialogue", ""),
                    shot.getOrDefault("hookPoint", ""));
        }

        // 角色一致性检查
        for (Map<String, Object> shot : shots) {
            Object chars = shot.get("characters");
            if (chars instanceof List) {
                for (Object c : (List<?>) chars) {
                    String name = c.toString();
                    assertTrue(name.equals("林晓星") || name.equals("林晓辰") || name.isEmpty(),
                            "角色名应为林晓星或林晓辰，实际: " + name);
                }
            }
        }
    }

    @Test
    void testStandardMode() {
        List<Map<String, Object>> shots = service.generate(
                "主角踏入神秘森林，遇到导师，开始修炼之旅。"
                + "在修炼过程中，他逐渐发现了森林中隐藏的秘密。"
                + "最终他与暗影兽决战，守护了森林的和平。",
                "林风(主角),苏晴(导师)", 60, "ANIME",
                false, null, "standard");

        assertFalse(shots.isEmpty());
        System.out.println("=== 标准模式分镜 (" + shots.size() + "镜) ===");
        for (Map<String, Object> shot : shots) {
            System.out.printf("  #%s [%ss] %s | dialogue=%s%n",
                    shot.get("shotNumber"), shot.get("duration"),
                    shot.getOrDefault("sceneDescription", ""),
                    shot.getOrDefault("dialogue", ""));
        }
    }

    @Test
    void testComicCommentaryMode() {
        List<Map<String, Object>> shots = service.generate(
                "在一个风雨交加的夜晚，小明独自走在回家的路上。"
                + "他不知道的是，命运的齿轮已经开始转动。",
                "小明(主角)", 30, "cinematic",
                true, "third_person", "standard");

        assertFalse(shots.isEmpty());
        System.out.println("=== 解说模式分镜 (" + shots.size() + "镜) ===");
        for (Map<String, Object> shot : shots) {
            System.out.printf("  #%s [%ss] narration=%s | dialogue=%s%n",
                    shot.get("shotNumber"), shot.get("duration"),
                    shot.getOrDefault("narration", ""),
                    shot.getOrDefault("dialogue", ""));
        }

        // 解说模式：至少部分 shot 应有 narration
        long narrationCount = shots.stream()
                .filter(s -> {
                    String n = s.getOrDefault("narration", "").toString();
                    return !n.isEmpty() && !n.equals("无");
                }).count();
        assertTrue(narrationCount > 0, "解说模式应有 narration");
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile test-compile -pl .`

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/test/java/com/comic/e2e/StoryboardV2IntegrationTest.java
git commit -m "test(storyboard): 新增 V2 集成测试，可直接验证新架构"
```

---

### Task 6: 全量验证 + 清理

**Files:**
- Modify: `StoryboardAgentService.java` (清理未使用的 import 和字段)

- [ ] **Step 1: 清理未使用的旧数据类字段和方法**

检查并清理：
- `AgentState` 整个类 — generateV2 不使用 AgentState，直接用局部变量。删除。
- `ReactOutput` 类 — 如果不再被引用则删除
- `PlanSegment` 类 — 如果不再被引用则删除
- `ReasonerDecision` 类 — 检查外部引用，无引用则删除
- `ACTION_PATTERN` 和 `THOUGHT_PATTERN` — 如果不再使用则删除
- `MAX_ROUNDS` 常量 — 如果不再使用则删除
- `LAST_SHOTS_COUNT` — Phase 4 仍使用，保留

- [ ] **Step 2: 运行全量测试**

Run: `cd backend/com/comic && mvn test -pl . -Dsurefire.useFile=false`

Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor(storyboard): 清理未使用的旧架构代码"
```

---

## 验证清单

完成所有 Task 后：

- [ ] `mvn test -pl . -Dtest=StoryboardAgentServiceTest` 通过
- [ ] `mvn compile test-compile -pl .` 无编译错误
- [ ] `StoryboardV2IntegrationTest` 编译通过（手动运行验证 LLM 效果）
- [ ] `generate()` 公共 API 签名不变
- [ ] 无 `callExecutor`, `generateReAct`, `generateIterative` 等旧方法残留（除了保留的 `parseReasonerDecision`）
