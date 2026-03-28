# Panel Duration Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add duration awareness to the panel generation pipeline so total video duration approximately matches the user-specified episode duration.

**Architecture:** Budget-based duration planning — AI receives total duration budget in both planning and detailing prompts, allocates per-panel durations; code calibrates via proportional scaling if total falls outside ±5% tolerance.

**Tech Stack:** Java 17, Spring Boot, Jackson (ObjectMapper/ArrayNode/ObjectNode), existing LLM text generation service.

**Spec:** `docs/superpowers/specs/2026-03-26-panel-duration-planning-design.md`

---

## File Structure

| File | Responsibility |
|------|----------------|
| `PanelPromptBuilder.java` | Prompt construction for Step1 (plan) and Step2 (detail) — adds duration constraints to prompts |
| `PanelGenerationService.java` | Orchestrates panel generation — reads episodeDuration, passes to prompts, tracks budget in Step2 loop, runs calibration |

No new files needed. No entity/DTO changes needed (duration lives in panelInfo JSON).

---

### Task 1: Update `PanelPromptBuilder.buildPlanSystemPrompt` — add duration constraint to system prompt

**Files:**
- Modify: `comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:18-57`

- [ ] **Step 1: Add `episodeDuration` parameter and duration planning section**

Change method signature from:
```java
public String buildPlanSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates)
```
to:
```java
public String buildPlanSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates, int episodeDuration)
```

Add `duration` field to the JSON output format block (line 47, before the closing `}`):
```java
sb.append("      \"duration\": 5\n");
```

Replace the requirements section (lines 52-55) with duration-aware requirements:
```java
int minDuration = (int) Math.round(episodeDuration * 0.95);
int maxDuration = (int) Math.round(episodeDuration * 1.05);

sb.append("## 时长规划\n");
sb.append("目标总时长：").append(episodeDuration).append(" 秒（可接受范围：").append(minDuration).append("~").append(maxDuration).append(" 秒）\n");
sb.append("- 每个 panel 必须包含 duration 字段（整数秒，范围 1~16）\n");
sb.append("- 所有 panel 的 duration 之和必须在 ").append(minDuration).append("~").append(maxDuration).append(" 秒之间\n");
sb.append("- 根据内容重要性合理分配时长，重要场景分配更多时间\n");
sb.append("- 规划完成后，请自行验证总和是否在范围内\n");
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(prompt): add duration planning constraints to panel plan system prompt"
```

---

### Task 2: Update `PanelPromptBuilder.buildPlanUserPrompt` — add duration constraints to user prompt

**Files:**
- Modify: `comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:59-65`

- [ ] **Step 1: Add `episodeDuration` parameter and duration constraints section**

Change method signature from:
```java
public String buildPlanUserPrompt(int episodeNum, String episodeContent, String recentMemory)
```
to:
```java
public String buildPlanUserPrompt(int episodeNum, String episodeContent, String recentMemory, int episodeDuration)
```

Add duration constraints block before episode content (after the first `sb.append` line):
```java
int minDuration = (int) Math.round(episodeDuration * 0.95);
int maxDuration = (int) Math.round(episodeDuration * 1.05);

sb.append("## 时长约束\n");
sb.append("- 目标总时长：").append(episodeDuration).append(" 秒\n");
sb.append("- 单 panel 时长范围：1~16 秒\n");
sb.append("- 所有 panel 时长之和必须在 ").append(minDuration).append("~").append(maxDuration).append(" 秒之间\n\n");
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(prompt): add duration constraints to panel plan user prompt"
```

---

### Task 3: Update `PanelPromptBuilder.buildPanelDetailSystemPrompt` — add `duration` to output format

**Files:**
- Modify: `comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:69-121`

**Why:** The Step2 system prompt's output format (lines 88-114) must include `duration` as a valid field. Without this, the AI will likely omit `duration` from its output because line 119 says "不要额外字段" (no extra fields), which directly contradicts the user prompt asking for duration.

- [ ] **Step 1: Add `duration` field to Step2 output format**

After line 94 (`sb.append("  \"pacing\": \"slow|normal|fast\",\n");`), add:
```java
sb.append("  \"duration\": 5,\n");
```

Update the "关键要求" section (line 119) to mention duration is expected:
```java
sb.append("- 仅返回单个 panel 的 JSON，不要数组\n");
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(prompt): add duration field to panel detail system prompt output format"
```

---

### Task 4: Update `PanelPromptBuilder.buildPanelDetailUserPrompt` — pass duration info for Step2

**Files:**
- Modify: `comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:123-137`

- [ ] **Step 1: Add duration parameters and duration info section**

Change method signature from:
```java
public String buildPanelDetailUserPrompt(int episodeNum, int panelIndex, String panelPlan,
                                          String episodeContent, String previousPanelSummary)
```
to:
```java
public String buildPanelDetailUserPrompt(int episodeNum, int panelIndex, String panelPlan,
                                          String episodeContent, String previousPanelSummary,
                                          int plannedDuration, int remainingBudget, int remainingPanels)
```

Add duration info section after the previousPanelSummary block (before the `要求：` section):
```java
int minAdjusted = Math.max(1, plannedDuration - 2);
int maxAdjusted = Math.min(16, plannedDuration + 2);

sb.append("## 时长信息\n");
sb.append("- 规划阶段预定时长：").append(plannedDuration).append(" 秒\n");
sb.append("- 允许调整范围：").append(minAdjusted).append("~").append(maxAdjusted).append(" 秒\n");
sb.append("- 该集剩余预算：").append(remainingBudget).append(" 秒（还有 ").append(remainingPanels).append(" 个 panel 待细化）\n");
sb.append("- 在输出中包含 duration 字段，填写调整后的实际时长\n\n");
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(prompt): add duration info to panel detail user prompt"
```

---

### Task 4: Update `PanelGenerationService.generatePanels` — read episodeDuration, pass to prompts, track budget

**Files:**
- Modify: `comic/src/main/java/com/comic/service/panel/PanelGenerationService.java:105-197`

- [ ] **Step 1: Add `getProjectInfoInt` helper and `DEFAULT_EPISODE_DURATION` constant**

Add constant at line ~68 (after other constants):
```java
private static final int DEFAULT_EPISODE_DURATION = 60;
```

Add helper method near the existing `getEpInfoInt` method (~line 94):
```java
private int getProjectInfoInt(Project project, String key) {
    Map<String, Object> info = project.getProjectInfo();
    Object v = info != null ? info.get(key) : null;
    return v != null ? ((Number) v).intValue() : DEFAULT_EPISODE_DURATION;
}
```

- [ ] **Step 2: Add `ProjectInfoKeys` import**

Add to the imports section (line ~8):
```java
import com.comic.common.ProjectInfoKeys;
```

- [ ] **Step 3: Read episodeDuration from project in `generatePanels`**

After line 122 (`String recentMemory = ...`), add:
```java
Project project = projectRepository.findByProjectId(episode.getProjectId());
int episodeDuration = project != null
        ? getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION)
        : DEFAULT_EPISODE_DURATION;
```

- [ ] **Step 4: Pass episodeDuration to Step1 prompt builders**

Change lines 126-127 from:
```java
String planSystemPrompt = panelPromptBuilder.buildPlanSystemPrompt(world, charStates);
String planUserPrompt = panelPromptBuilder.buildPlanUserPrompt(safeEpNum, content, recentMemory);
```
to:
```java
String planSystemPrompt = panelPromptBuilder.buildPlanSystemPrompt(world, charStates, episodeDuration);
String planUserPrompt = panelPromptBuilder.buildPlanUserPrompt(safeEpNum, content, recentMemory, episodeDuration);
```

- [ ] **Step 5: Validate and fill missing duration fields after Step1**

After line 133 (`epInfo(episode).put("panelPlan", ...)`), before the Step2 section, add:
```java
// Validate and fill missing duration fields from Step1
for (int i = 0; i < panelsPlan.size(); i++) {
    JsonNode panelPlan = panelsPlan.get(i);
    if (!panelPlan.has("duration") || !panelPlan.get("duration").isInt()) {
        int defaultDuration = Math.min(16, Math.max(1,
                (int) Math.round((double) episodeDuration / panelsPlan.size())));
        ((ObjectNode) panelPlan).put("duration", defaultDuration);
        log.warn("Panel {} missing duration, assigned default: {}", i, defaultDuration);
    }
}
```

- [ ] **Step 6: Track budget and pass duration info in Step2 loop**

Before the Step2 loop (line 148), add budget tracking variables:
```java
int confirmedDurationTotal = 0;
```

Inside the Step2 loop, before the `buildPanelDetailUserPrompt` call (line 154), calculate remaining budget:
```java
int plannedDuration = panelPlan.has("duration") ? panelPlan.get("duration").asInt() : 5;
int remainingPanels = panelsPlan.size() - i;
int remainingBudget = episodeDuration - confirmedDurationTotal;
```

Change the `buildPanelDetailUserPrompt` call from:
```java
String detailUserPrompt = panelPromptBuilder.buildPanelDetailUserPrompt(
        safeEpNum, i + 1, panelPlanStr, content, previousPanelSummary);
```
to:
```java
String detailUserPrompt = panelPromptBuilder.buildPanelDetailUserPrompt(
        safeEpNum, i + 1, panelPlanStr, content, previousPanelSummary,
        plannedDuration, remainingBudget, remainingPanels);
```

After `normalizePanelDetail` and `validatePanelDetail` (after line 163), add budget confirmation:
```java
// Track actual duration from Step2 output
int actualDuration = detailJson.has("duration") && detailJson.get("duration").isInt()
        ? detailJson.get("duration").asInt() : plannedDuration;
confirmedDurationTotal += actualDuration;
```

- [ ] **Step 7: Commit**

```bash
git add comic/src/main/java/com/comic/service/panel/PanelGenerationService.java
git commit -m "feat(service): pass episodeDuration to prompts and track budget in Step2"
```

---

### Task 6: Add `calibrateDurations` method to `PanelGenerationService`

**Files:**
- Modify: `comic/src/main/java/com/comic/service/panel/PanelGenerationService.java`

- [ ] **Step 1: Add the `calibrateDurations` private method**

Add this method in the private methods section (e.g., after `buildPanelSummary` ~line 989):
```java
/**
 * 按比例校准所有 panel 时长，确保总时长在目标 ±5% 范围内
 */
private void calibrateDurations(int targetDuration, ArrayNode detailedPanels) {
    if (detailedPanels == null || detailedPanels.size() == 0) {
        return;
    }

    int minTarget = (int) Math.round(targetDuration * 0.95);
    int maxTarget = (int) Math.round(targetDuration * 1.05);

    // Calculate total
    int totalActual = 0;
    for (int i = 0; i < detailedPanels.size(); i++) {
        JsonNode panel = detailedPanels.get(i);
        if (panel.has("duration") && panel.get("duration").isInt()) {
            totalActual += panel.get("duration").asInt();
        }
    }

    // Already within range
    if (totalActual >= minTarget && totalActual <= maxTarget) {
        log.info("Duration calibration not needed: total={} target={}[{},{}]", totalActual, targetDuration, minTarget, maxTarget);
        return;
    }

    log.info("Calibrating durations: total={} target={}[{},{}]", totalActual, targetDuration, minTarget, maxTarget);
    double ratio = (double) targetDuration / totalActual;

    // Proportional scaling + clamp to [1, 16]
    for (int i = 0; i < detailedPanels.size(); i++) {
        JsonNode panel = detailedPanels.get(i);
        if (panel.isObject() && panel.has("duration")) {
            int original = panel.get("duration").asInt();
            int calibrated = (int) Math.round(original * ratio);
            calibrated = Math.max(1, Math.min(16, calibrated));
            ((ObjectNode) panel).put("duration", calibrated);
        }
    }

    // Recalculate and fine-tune if still out of range
    int newTotal = 0;
    int[] durations = new int[detailedPanels.size()];
    for (int i = 0; i < detailedPanels.size(); i++) {
        durations[i] = detailedPanels.get(i).get("duration").asInt();
        newTotal += durations[i];
    }

    int maxIterations = detailedPanels.size() * 2;
    int iteration = 0;
    while ((newTotal < minTarget || newTotal > maxTarget) && iteration < maxIterations) {
        // Find panel with largest deviation from its ideal proportion
        int maxDeviation = -1;
        int maxDeviationIndex = 0;
        double idealPerPanel = (double) targetDuration / detailedPanels.size();

        for (int i = 0; i < durations.length; i++) {
            int deviation = Math.abs(durations[i] - (int) Math.round(idealPerPanel));
            if (deviation > maxDeviation) {
                // Only adjust if not already at boundary
                if (newTotal < minTarget && durations[i] < 16) {
                    maxDeviation = deviation;
                    maxDeviationIndex = i;
                } else if (newTotal > maxTarget && durations[i] > 1) {
                    maxDeviation = deviation;
                    maxDeviationIndex = i;
                }
            }
        }

        if (newTotal < minTarget && durations[maxDeviationIndex] < 16) {
            durations[maxDeviationIndex]++;
            newTotal++;
        } else if (newTotal > maxTarget && durations[maxDeviationIndex] > 1) {
            durations[maxDeviationIndex]--;
            newTotal--;
        } else {
            break;
        }
        iteration++;
    }

    // Write back calibrated durations
    for (int i = 0; i < detailedPanels.size(); i++) {
        ((ObjectNode) detailedPanels.get(i)).put("duration", durations[i]);
    }

    if (newTotal < minTarget || newTotal > maxTarget) {
        log.warn("Duration calibration did not fully converge: total={} target=[{},{}]", newTotal, minTarget, maxTarget);
    } else {
        log.info("Duration calibration complete: total={} target={}[{},{}]", newTotal, targetDuration, minTarget, maxTarget);
    }
}
```

- [ ] **Step 2: Call `calibrateDurations` in `generatePanels` after Step2 loop**

After the Step2 for-loop ends (after line 169, before line 171 `// 构建最终结果`), add:
```java
// Calibrate durations to ensure total is within ±5% of target
calibrateDurations(episodeDuration, detailedPanels);
```

- [ ] **Step 3: Commit**

```bash
git add comic/src/main/java/com/comic/service/panel/PanelGenerationService.java
git commit -m "feat(service): add calibrateDurations for ±5% duration tolerance"
```

---

### Task 7: Verify compilation

**Files:**
- None (verification only)

- [ ] **Step 1: Run Maven compile**

```bash
cd D:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn compile -pl comic -am -q
```

Expected: BUILD SUCCESS (no compilation errors)

- [ ] **Step 2: Fix any compilation errors if present**

Address any issues found — most likely:
- Missing `ProjectInfoKeys` import in `PanelGenerationService`
- Method signature mismatches if callers of `buildPlanSystemPrompt`/`buildPlanUserPrompt`/`buildPanelDetailUserPrompt` weren't updated (check `generateWithRetryAndFeedback` and `buildRevisionUserPrompt` paths — these do NOT call the modified methods, so no changes needed there)

- [ ] **Step 3: Final commit if fixes were needed**

```bash
git add -A && git commit -m "fix: resolve compilation errors from duration planning changes"
```