# 宫格图占位内容填充 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将宫格图中多余格子的「纯黑填充」替换为占位场景内容填充，提升 AI 生成合格率

**Architecture:** 仅修改 `PanelPromptBuilder.java`，新增占位场景池常量和 `pickFillerScene` 方法，修改 `buildGridPrompt` 中空格填充逻辑。`GridImageService.splitGridImage` 不需要修改——所有调用方已有 `shots.size()` 边界保护，会自动丢弃多余格子。

**Tech Stack:** Java, Spring Boot, JUnit 5

---

### Task 1: 新增占位场景池和 pickFillerScene 方法

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:34` (在 FLASHBACK_TEXT_KEYS 之后插入常量)

- [ ] **Step 1: 在 PanelPromptBuilder 中新增 FILLER_SCENES 常量和 pickFillerScene 方法**

在 `FLASHBACK_TEXT_KEYS` 常量之后（第34行后），新增：

```java
/**
 * 占位场景池 — 当分镜数 < 网格容量时，为多余格子填充无关紧要的过渡场景
 * 每个风格提供8个场景，避免AI画黑格失败的问题
 */
private static final List<String> FILLER_SCENES = java.util.List.of(
    "远景 — 夕阳余晖洒在城市天际线上，暖色调，无角色",
    "中景 — 天空中云彩缓慢飘动，光线柔和，无角色",
    "特写 — 树叶在微风中轻轻摇曳，自然光影，无角色",
    "远景 — 宁静的湖面倒映着远山，柔和的色调，无角色",
    "中景 — 空旷的街道延伸到远方，傍晚的氛围，无角色",
    "特写 — 光线穿过窗帘的缝隙，尘埃在光束中漂浮，无角色",
    "远景 — 飞鸟划过天空的剪影，辽阔的视野，无角色",
    "中景 — 雨后地面倒映着霓虹灯光，柔和模糊，无角色"
);

private String pickFillerScene(int index) {
    return FILLER_SCENES.get(index % FILLER_SCENES.size());
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(panel): 新增占位场景池和 pickFillerScene 方法"
```

---

### Task 2: 修改 buildGridPrompt 的空格填充逻辑

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:285-294`

- [ ] **Step 1: 替换空格填充逻辑**

将第285-294行：

```java
        int emptySlots = totalSlots - shots.size();
        if (emptySlots > 0) {
            sb.append("\n【以下格子必须留空 - 纯黑色填充，不绘制任何内容】\n");
            for (int i = shots.size(); i < totalSlots; i++) {
                int row = i / gridCols + 1;
                int col = i % gridCols + 1;
                sb.append("第").append(row).append("行第").append(col).append("列: 纯黑色填充，不绘制任何内容。\n");
            }
            sb.append("\n");
        }
```

替换为：

```java
        int emptySlots = totalSlots - shots.size();
        if (emptySlots > 0) {
            sb.append("\n");
            for (int i = shots.size(); i < totalSlots; i++) {
                int row = i / gridCols + 1;
                int col = i % gridCols + 1;
                sb.append("第").append(row).append("行第").append(col).append("列: ").append(pickFillerScene(i - shots.size())).append("。\n");
            }
            sb.append("\n");
        }
```

关键变化：
- 删除了「【以下格子必须留空 - 纯黑色填充，不绘制任何内容】」标题
- 每个「纯黑色填充，不绘制任何内容」替换为 `pickFillerScene()` 返回的占位场景描述
- 占位场景和正常分镜使用完全相同的格式（第X行第Y列: 场景描述），AI 无法区分

- [ ] **Step 2: 编译验证**

```bash
cd backend/com/comic && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat(panel): 空格填充改为占位场景内容填充"
```

---

### Task 3: 编写单元测试验证占位场景逻辑

**Files:**
- Create: `backend/com/comic/src/test/java/com/comic/ai/PanelPromptBuilderTest.java`

- [ ] **Step 1: 编写测试**

```java
package com.comic.ai;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PanelPromptBuilderTest {

    private final PanelPromptBuilder builder = new PanelPromptBuilder();

    @Test
    void buildGridPrompt_should_contain_filler_scenes_for_empty_slots() {
        // 7个分镜 + 3x3网格 = 2个空格，应填充占位场景而非黑格
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", "测试场景" + (i + 1));
            shots.add(shot);
        }

        String prompt = builder.buildGridPrompt("ANIME", shots, List.of(), 3, 3);

        // 不应包含"纯黑色填充"
        assertFalse(prompt.contains("纯黑色填充"), "不应包含黑格填充指令");

        // 应包含占位场景关键词
        assertTrue(prompt.contains("无角色"), "占位场景应包含'无角色'标记");

        // 应有第3行第1列和第3行第2列
        assertTrue(prompt.contains("第3行第1列"), "应有第3行第1列占位");
        assertTrue(prompt.contains("第3行第2列"), "应有第3行第2列占位");
    }

    @Test
    void buildGridPrompt_full_grid_should_have_no_filler() {
        // 恰好9个分镜填满3x3，不应有任何占位
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", "测试场景" + (i + 1));
            shots.add(shot);
        }

        String prompt = builder.buildGridPrompt("ANIME", shots, List.of(), 3, 3);

        // 不应包含"纯黑色填充"
        assertFalse(prompt.contains("纯黑色填充"));
        // 应有完整的9个格子
        assertTrue(prompt.contains("第3行第3列"));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd backend/com/comic && mvn test -pl . -Dtest=PanelPromptBuilderTest -Dsurefire.useFile=false
```

Expected: 2 tests PASS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/test/java/com/comic/ai/PanelPromptBuilderTest.java
git commit -m "test(panel): 添加占位场景填充的单元测试"
```
