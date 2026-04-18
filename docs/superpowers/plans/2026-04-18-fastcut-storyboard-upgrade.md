# 快切防翻车分镜升级 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 升级 StoryboardAgentService 的骨架生成（引入 1-5s 变化时长）和精修 prompt（加入快切防翻车指南），使生成的分镜适配 AI 视频快切风格。

**Architecture:** 在现有 V2 4-Phase 架构上修改。Part 1 改 Phase 2 的代码层（`allocateDuration` + 骨架方法），Part 2 改 Phase 4 的 prompt 层（精修 system/user prompt）。不改 Phase 1 和 Phase 3。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-04-18-fastcut-storyboard-upgrade-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java` | Modify | 新增 `allocateDuration()`，改 `buildShotSkeletons()`、`buildFallbackSkeletons()`、`buildRefineSystemPrompt()`、`buildRefineUserPrompt()` |
| `backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java` | Modify | 更新 duration 预期值，新增 `allocateDuration` 测试 |

---

### Task 1: 新增 `allocateDuration` 方法 + 测试

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java`
- Modify: `backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java`

- [ ] **Step 1: 写 `allocateDuration` 的测试**

在 `StoryboardAgentServiceTest.java` 的 `// ==================== Phase 2: 骨架生成 ====================` 区域末尾添加：

```java
@Test
void allocateDuration_openingPhase_shouldMostlyReturn2s() {
    // 开场钩子: phaseLocalIndex % 5 == 0 给 1s, 其余 2s
    assertEquals(2, agent.allocateDuration(0, 5, "开场钩子", 0));
    assertEquals(2, agent.allocateDuration(1, 5, "开场钩子", 2));
    assertEquals(2, agent.allocateDuration(2, 5, "开场钩子", 4));
    assertEquals(2, agent.allocateDuration(3, 5, "开场钩子", 6));
    assertEquals(1, agent.allocateDuration(4, 5, "开场钩子", 8)); // 第5个镜头(index=4)给1s
}

@Test
void allocateDuration_setupPhase_shouldAlternate2and3() {
    // 铺垫发展: 偶数 index 给 2s, 奇数给 3s
    assertEquals(2, agent.allocateDuration(0, 4, "铺垫发展", 0));
    assertEquals(3, agent.allocateDuration(1, 4, "铺垫发展", 2));
    assertEquals(2, agent.allocateDuration(2, 4, "铺垫发展", 5));
    assertEquals(3, agent.allocateDuration(3, 4, "铺垫发展", 7));
}

@Test
void allocateDuration_conflictPhase_shouldBeFast() {
    // 冲突升级: index % 5 < 2 给 1s, 其余 2s
    assertEquals(1, agent.allocateDuration(0, 5, "冲突升级", 0));
    assertEquals(1, agent.allocateDuration(1, 5, "冲突升级", 1));
    assertEquals(2, agent.allocateDuration(2, 5, "冲突升级", 3));
    assertEquals(2, agent.allocateDuration(3, 5, "冲突升级", 5));
    assertEquals(2, agent.allocateDuration(4, 5, "冲突升级", 7));
}

@Test
void allocateDuration_climaxPhase_shouldInsertSlowMo() {
    // 高潮爆发: index % 5 == 4 给 4s(升格), 其余交替 1s/2s
    assertEquals(2, agent.allocateDuration(0, 5, "高潮爆发", 0));
    assertEquals(1, agent.allocateDuration(1, 5, "高潮爆发", 2));
    assertEquals(2, agent.allocateDuration(2, 5, "高潮爆发", 3));
    assertEquals(1, agent.allocateDuration(3, 5, "高潮爆发", 5));
    assertEquals(4, agent.allocateDuration(4, 5, "高潮爆发", 6)); // 升格
}

@Test
void allocateDuration_resolvePhase_shouldBeLong() {
    // 收束悬念: 全部 3s, 最后一个给 5s
    assertEquals(3, agent.allocateDuration(0, 3, "收束悬念", 0));
    assertEquals(3, agent.allocateDuration(1, 3, "收束悬念", 3));
    assertEquals(5, agent.allocateDuration(2, 3, "收束悬念", 6)); // 最后一个
}

@Test
void allocateDuration_unknownPhase_shouldReturn3() {
    // 兜底: 未知 phase 返回 3
    assertEquals(3, agent.allocateDuration(0, 3, "未知阶段", 0));
    assertEquals(3, agent.allocateDuration(2, 3, "随便什么", 6));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest#allocateDuration_openingPhase -Dsurefire.useFile=false -q 2>&1 | tail -5`
Expected: FAIL (method not found)

- [ ] **Step 3: 实现 `allocateDuration` 方法**

在 `StoryboardAgentService.java` 的 `buildFallbackSkeletons()` 方法之后（第 280 行附近），`matchNarrativePhase()` 方法之前（第 282 行），插入：

```java
// ==================== 时长节奏分配 ====================

/**
 * 根据叙事阶段分配镜头时长（确定性，不使用随机数）
 *
 * @param phaseLocalIndex  当前 shot 在同一叙事阶段内的索引
 * @param phaseLocalCount  当前叙事阶段内的总 shot 数
 * @param phaseName        叙事阶段名称
 * @param allocatedSeconds 已分配的总秒数
 * @return 镜头时长 (1-5s)
 */
int allocateDuration(int phaseLocalIndex, int phaseLocalCount, String phaseName, int allocatedSeconds) {
    if (phaseName == null || phaseName.isEmpty()) return 3;

    // 收束悬念: 全部 3s, 最后一个给 5s
    if (phaseName.contains("收束") || phaseName.contains("悬念")) {
        return (phaseLocalIndex == phaseLocalCount - 1) ? 5 : 3;
    }
    // 开场钩子: 80% 用 2s, 20% 用 1s
    if (phaseName.contains("开场") || phaseName.contains("钩子")) {
        return (phaseLocalIndex % 5 == 4) ? 1 : 2;
    }
    // 铺垫发展: 交替 2s 和 3s
    if (phaseName.contains("铺垫") || phaseName.contains("发展")) {
        return (phaseLocalIndex % 2 == 0) ? 2 : 3;
    }
    // 冲突升级: 60% 用 2s, 40% 用 1s
    if (phaseName.contains("冲突") || phaseName.contains("升级")) {
        return (phaseLocalIndex % 5 < 2) ? 1 : 2;
    }
    // 高潮爆发: 每5个插一个4s升格, 其余交替1s/2s
    if (phaseName.contains("高潮") || phaseName.contains("爆发")) {
        if (phaseLocalIndex % 5 == 4) return 4;
        return (phaseLocalIndex % 2 == 0) ? 2 : 1;
    }

    // 兜底: 均匀 3s
    return 3;
}
```

- [ ] **Step 4: 运行 allocateDuration 测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest="StoryboardAgentServiceTest#allocateDuration*" -Dsurefire.useFile=false -q 2>&1 | tail -5`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(storyboard): 新增 allocateDuration 方法 + 6 个节奏分配测试"
```

---

### Task 2: 改造 `buildShotSkeletons()` 使用节奏分配

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java` (行 186-229)
- Modify: `backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java` (行 63-72)

- [ ] **Step 1: 更新 `buildShotSkeletons_shouldSplitBeatsIntoShots` 测试**

当前测试（行 63-72）不验证具体 duration 值。更新为验证时长分配有变化（不全相同）：

```java
@Test
void buildShotSkeletons_shouldSplitBeatsIntoShots() {
    StoryboardAgentService.StoryStructure structure = new StoryboardAgentService.StoryStructure();
    structure.beats.add(new StoryboardAgentService.StoryBeat(1, "进入工厂", 10, "紧张",
            Collections.singletonList(new StoryboardAgentService.CharacterInScene("林晓星", "警惕", "工厂"))));
    StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(Collections.emptyList(), 10);

    List<Map<String, Object>> skeletons = agent.buildShotSkeletons(structure, plan);
    assertFalse(skeletons.isEmpty());
    assertTrue(skeletons.size() >= 2);

    // 验证时长总和接近 beat.duration
    int total = skeletons.stream().mapToInt(s -> (int) s.get("duration")).sum();
    assertTrue(total >= 9 && total <= 11, "时长总和应接近 beat.duration 10s，实际: " + total);

    // 验证每个 shot 时长在 1-5s 范围内
    for (Map<String, Object> s : skeletons) {
        int d = (int) s.get("duration");
        assertTrue(d >= 1 && d <= 5, "duration 应在 1-5s 范围内，实际: " + d);
    }
}
```

- [ ] **Step 2: 改造 `buildShotSkeletons()` 方法**

将行 186-229 的 `buildShotSkeletons()` 改为使用 `allocateDuration`：

```java
List<Map<String, Object>> buildShotSkeletons(StoryStructure structure, NarrativePlan plan) {
    List<Map<String, Object>> skeletons = new ArrayList<>();
    int shotNumber = 1;
    int accumulatedSeconds = 0;

    // 预计算每个 beat 的 shot 数
    int[] beatShotCounts = new int[structure.beats.size()];
    for (int i = 0; i < structure.beats.size(); i++) {
        beatShotCounts[i] = Math.max(1, (int) Math.round((double) structure.beats.get(i).duration / 3.0));
    }

    // 预计算每个 narrative phase 的 shot 数（用于 phaseLocalIndex）
    // phaseBoundary: phase name → 已分配的 shot 数
    Map<String, Integer> phaseLocalCounter = new HashMap<>();

    for (int beatIdx = 0; beatIdx < structure.beats.size(); beatIdx++) {
        StoryBeat beat = structure.beats.get(beatIdx);
        int shotCount = beatShotCounts[beatIdx];

        int beatAllocated = 0;
        for (int i = 0; i < shotCount; i++) {
            Map<String, Object> skeleton = new HashMap<>();
            skeleton.put("shotNumber", shotNumber++);

            String phaseName = matchNarrativePhase(accumulatedSeconds, plan);
            skeleton.put("narrativePhase", phaseName);

            int phaseLocalIndex = phaseLocalCounter.getOrDefault(phaseName, 0);

            int duration;
            if (i == shotCount - 1) {
                // 最后一个 shot: 补齐剩余时长
                duration = Math.max(1, Math.min(5, beat.duration - beatAllocated));
            } else {
                // 按叙事阶段节奏分配
                int plannedDuration = allocateDuration(phaseLocalIndex, shotCount, phaseName, accumulatedSeconds);
                // 确保不超过 beat 剩余时长（至少留 1s 给后续 shot）
                int remaining = beat.duration - beatAllocated;
                int minRemaining = (shotCount - i - 1); // 每个 remaining shot 至少 1s
                duration = Math.max(1, Math.min(5, Math.min(plannedDuration, remaining - minRemaining)));
            }

            skeleton.put("duration", duration);
            skeleton.put("beatId", beat.id);
            skeleton.put("beatIndex", beatIdx);
            skeleton.put("sceneHint", beat.beat);
            skeleton.put("mood", beat.mood);

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
            beatAllocated += duration;
            phaseLocalCounter.put(phaseName, phaseLocalIndex + 1);
        }
    }

    log.info("[StoryboardAgent] Phase2 骨架生成: {} 个shot, 预估{}秒",
            skeletons.size(), accumulatedSeconds);
    return skeletons;
}
```

注意：需要新增 `import java.util.Map;`（已在文件中存在，无需额外导入）。

- [ ] **Step 3: 运行全部测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false -q 2>&1 | tail -10`
Expected: PASS (all tests)

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(storyboard): buildShotSkeletons 使用叙事阶段节奏分配时长"
```

---

### Task 3: 改造 `buildFallbackSkeletons()` 使用节奏分配

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java` (行 234-280)
- Modify: `backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java` (行 75-81)

- [ ] **Step 1: 更新 `buildFallbackSkeletons_shouldMatchTargetDuration` 测试**

当前测试用 `30s / 3.5 ≈ 9 shots`，全 3s，总和 27s。改造后会引入变化时长，需要更新：

```java
@Test
void buildFallbackSkeletons_shouldMatchTargetDuration() {
    StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(Collections.emptyList(), 30);
    List<Map<String, Object>> skeletons = agent.buildFallbackSkeletons(30, "内容", "A,B", plan);
    int total = skeletons.stream().mapToInt(s -> (int) s.get("duration")).sum();
    assertTrue(total >= 25 && total <= 35,
            "兜底骨架时长应接近目标时长 30s，实际: " + total);

    // 验证每个 shot 时长在 1-5s 范围内
    for (Map<String, Object> s : skeletons) {
        int d = (int) s.get("duration");
        assertTrue(d >= 1 && d <= 5, "duration 应在 1-5s 范围内，实际: " + d);
    }
}
```

- [ ] **Step 2: 改造 `buildFallbackSkeletons()` 方法**

将行 234-280 改为使用 `allocateDuration`。Fallback 没有 beat 边界，所以用全局 shot 索引，但在 phase 切换时重置 `phaseLocalIndex`：

```java
List<Map<String, Object>> buildFallbackSkeletons(int targetDuration, String episodeContent,
                                                   String characters, NarrativePlan plan) {
    int shotCount = Math.max(3, (int) Math.round((double) targetDuration / 3.0));
    String[] parts = splitContentEvenly(episodeContent, shotCount);

    List<Map<String, Object>> skeletons = new ArrayList<>();
    int accumulatedSeconds = 0;
    String lastPhase = "";
    int phaseLocalIndex = 0;

    for (int i = 0; i < shotCount; i++) {
        Map<String, Object> skeleton = new HashMap<>();
        skeleton.put("shotNumber", i + 1);

        String phaseName = matchNarrativePhase(accumulatedSeconds, plan);

        // 追踪 phase 内局部索引
        if (!phaseName.equals(lastPhase)) {
            phaseLocalIndex = 0;
            lastPhase = phaseName;
        }

        int duration;
        if (i == shotCount - 1) {
            // 最后一个 shot: 补齐剩余时长
            duration = Math.max(1, Math.min(5, targetDuration - accumulatedSeconds));
        } else {
            int plannedDuration = allocateDuration(phaseLocalIndex, shotCount, phaseName, accumulatedSeconds);
            int remaining = targetDuration - accumulatedSeconds;
            int minRemaining = (shotCount - i - 1);
            duration = Math.max(1, Math.min(5, Math.min(plannedDuration, remaining - minRemaining)));
        }

        skeleton.put("duration", duration);
        skeleton.put("beatId", 1);
        skeleton.put("beatIndex", 0);
        skeleton.put("sceneHint", parts[i]);
        skeleton.put("mood", "");
        skeleton.put("narrativePhase", phaseName);

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
        phaseLocalIndex++;
    }

    log.info("[StoryboardAgent] Phase2 兜底骨架: {} 个shot", shotCount);
    return skeletons;
}
```

- [ ] **Step 3: 运行全部测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false -q 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java backend/com/comic/src/test/java/com/comic/service/production/StoryboardAgentServiceTest.java
git commit -m "feat(storyboard): buildFallbackSkeletons 使用叙事阶段节奏分配时长"
```

---

### Task 4: 精修 prompt 加入防翻车指南 + 允许 duration 微调

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java` (行 393-421, 501)

- [ ] **Step 1: 在 `buildRefineSystemPrompt()` 插入防翻车指南**

在对话约束块末尾（`"4. 当 dialogue 为空时..."` 之后）和爽剧模式判断之前，插入：

```java
sb.append("【快切防翻车指南 - 硬性约束】\n");
sb.append("一、动作设计：做减法\n");
sb.append("1. 一个镜头只做一个单一变化（位置、姿势、表情，三选一）\n");
sb.append("2. 禁止复合动作（如\"拔刀+冲刺+劈砍\"），必须拆成多个镜头\n");
sb.append("3. 切\"结果\"不切\"过程\"：写\"男主背对镜头，刀已入鞘\"而非\"男主转身收刀\"\n\n");
sb.append("二、镜头语言：贴脸\n");
sb.append("1. 快切镜头（1-2s）必须用特写或极特写，禁止全景\n");
sb.append("2. 用\"局部的动\"代替\"全局的动\"：脚踩碎地砖、刀刃划过鼻尖、手腕翻转\n");
sb.append("3. 动作结果用静止镜头表现：\"两人背对背站立，地面裂痕\"\n\n");
sb.append("三、运镜约束\n");
sb.append("1. 1-2s 镜头只允许：固定镜头、极特写、主观视角(POV)\n");
sb.append("2. 3-5s 镜头允许：缓慢推镜头、微平移\n");
sb.append("3. 禁止复杂运镜（推+环绕+拉远）\n\n");
sb.append("四、提示词写法\n");
sb.append("1. 锁死起始状态：\"原本侧面朝左，猛然转头看向右\"\n");
sb.append("2. 定格保底：\"挥拳瞬间画面定格，只有背景雨滴飞溅\"\n");
sb.append("3. 分离前景背景：\"背景完全静止，只有前景角色在动\"\n");
sb.append("4. 用\"升格拍摄（高帧率）\"代替\"慢动作\"\n\n");
sb.append("五、转场\n");
sb.append("1. transitionHint 必须写\"硬切\"\n");
sb.append("2. 禁止渐变、模糊、溶解过渡\n\n");
```

- [ ] **Step 2: 修改 system prompt 末尾的 duration 约束**

行 421，将：
```java
sb.append("输出纯 JSON 数组，不要 markdown 代码块。保持骨架的 shotNumber 和 duration 不变。");
```
改为：
```java
sb.append("输出纯 JSON 数组，不要 markdown 代码块。保持骨架的 shotNumber 不变，duration 可在骨架基础上 ±1s 微调（范围 1-5s）。");
```

- [ ] **Step 3: 修改 user prompt 末尾的 duration 约束**

行 501，将：
```java
sb.append("\n请输出精修后的完整 shot JSON 数组。保持 shotNumber 和 duration 不变。");
```
改为：
```java
sb.append("\n请输出精修后的完整 shot JSON 数组。保持 shotNumber 不变，duration 可在骨架基础上 ±1s 微调（范围 1-5s）。");
```

- [ ] **Step 4: 修复 `refineSkeletons()` 中的 duration clamp**

行 339 将 `Math.min(4, ...)` 改为 `Math.min(5, ...)`，否则 5s 镜头会被截断为 4s：

```java
// 行 339: 原代码
int d = Math.max(1, Math.min(4, ((Number) shot.getOrDefault("duration", 3)).intValue()));
// 改为
int d = Math.max(1, Math.min(5, ((Number) shot.getOrDefault("duration", 3)).intValue()));
```

- [ ] **Step 5: 运行全部测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false -q 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java
git commit -m "feat(storyboard): 精修 prompt 加入快切防翻车指南 + duration 允许 ±1s 微调 + 5s clamp 修复"
```

---

### Task 5: 端到端验证

**Files:** 无改动，只运行测试

- [ ] **Step 1: 运行全部单元测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -Dsurefire.useFile=false -q 2>&1 | tail -10`
Expected: PASS (all tests, ~16 tests)

- [ ] **Step 2: 运行集成测试**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn test -pl . -Dtest=StoryboardV2IntegrationTest -Dsurefire.useFile=false -q 2>&1 | tail -10`
Expected: PASS (3 tests)

- [ ] **Step 3: (可选) 运行真实 API 测试验证时长分布**

Run: `cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && DEEPSEEK_API_KEY=sk-47f1529041ca4926b21b8ee5820d75d0 mvn test -pl . -Dtest=StoryboardV2RealTest -Dsurefire.useFile=false 2>&1 | tail -10`

Expected: 测试输出文件 `storyboard-爽剧-180s.txt` 中镜头时长有变化（不再全是 3s），transitionHint 包含"硬切"。

- [ ] **Step 4: Commit (如有验证发现的修复)**
