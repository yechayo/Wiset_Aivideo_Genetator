# 4B 宫格图片生成增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增强 4B 阶段宫格图片生成，支持 2×2/3×3/4×4/5×5 四种规格、4K 分辨率、增强 prompt 结构确保角色场景风格一致性。

**Architecture:** 后端 GridImageService 扩展 calculateGridSize 支持四种宫格规格，分辨率从 1920×1080 升级到 3840×2160，分隔线从 4px 升到 8px。PanelPromptBuilder 重构为 prompt 结构：第1层全局风格锁 → 第2层叙事上下文 → 风格前缀（buildSceneStylePrefix，不编号） → 第3层角色锚定 → 第4层逐格指令 → 第5层大宫格约束（5×5 时启用）。前端 GridEpisodeCard 同步更新 getGridSize 逻辑。

**Note:** 2160 不是 64 的倍数，`SeedreamImageService.getSizeString()` 不会自动对齐（仅像素超限时才调整）。Task 8 编译通过后需用实际 API 调用验证 3840×2160 是否被接受，如被拒绝则将 `GRID_IMAGE_HEIGHT` 改为 2176。

**Tech Stack:** Java / Spring Boot, React / TypeScript, BufferedImage (AWT)

---

### Task 1: GridImageService — 常量与 calculateGridSize 扩展

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:42-46` (常量), `:425-428` (calculateGridSize)

- [ ] **Step 1: 修改常量定义**

将 `GRID_SEPARATOR_PIXELS` 从 4 改为 8，删除 `GRID_COLS`、`GRID_ROWS`、`SHOTS_PER_PAGE`。同时删除 `calculatePageCount` 方法（已无生产代码调用）。

```java
// 删除这四行:
// private static final int GRID_COLS = 3;
// private static final int GRID_ROWS = 3;
// private static final int SHOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
// 以及 calculatePageCount 方法 (行 417-419)

// 修改:
private static final int GRID_SEPARATOR_PIXELS = 8;
```

- [ ] **Step 2: 扩展 calculateGridSize**

```java
public static int[] calculateGridSize(int shotCount) {
    if (shotCount <= 4) return new int[]{2, 2};
    if (shotCount <= 9) return new int[]{3, 3};
    if (shotCount <= 16) return new int[]{4, 4};
    return new int[]{5, 5};
}
```

- [ ] **Step 3: 添加 4K 分辨率常量**

在 `FUSION_BG_COLOR` 后面添加：

```java
private static final int GRID_IMAGE_WIDTH = 3840;
private static final int GRID_IMAGE_HEIGHT = 2160;
```

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: 扩展宫格规格支持2×2/3×3/4×4/5×5 + 4K分辨率常量 + 8px分隔线"
```

---

### Task 2: GridImageService — 生成调用改为 4K 分辨率

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:117,119` (generateGridsForPanel), `:297,299` (doGenerateGridsForEpisode)

- [ ] **Step 1: 修改 generateGridsForPanel 中的分辨率**

两处 `1920, 1080` 改为使用常量：

```java
// 行 116-119，将:
//   prompt, characterRefUrls, 1920, 1080);
//   prompt, 1920, 1080, visualStyleStr);
// 改为:
                        prompt, characterRefUrls, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT);
                    } else {
                    imageUrl = imageService.generate(prompt, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT, visualStyleStr);
```

- [ ] **Step 2: 修改 doGenerateGridsForEpisode 中的分辨率**

同样将行 296-299 的两处 `1920, 1080` 改为 `GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT`：

```java
                    imageUrl = imageService.generateWithMultipleReferences(
                        prompt, characterRefUrls, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT);
                } else {
                    imageUrl = imageService.generate(prompt, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT, visualStyle);
```

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: 宫格图片生成分辨率从1080p升级到4K"
```

---

### Task 3: GridImageService — createFusionImage 动态布局

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:517` (fCols 硬编码)

- [ ] **Step 1: 将 fCols 从硬编码 3 改为动态计算**

将 `createFusionImage` 方法中的：

```java
int fCols = 3;
```

改为：

```java
int[] fusionGrid = calculateGridSize(shots.size());
int fCols = fusionGrid[0];
```

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: fusion图布局动态适配宫格规格"
```

---

### Task 4: PanelPromptBuilder — 新增辅助方法

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java`

- [ ] **Step 1: 添加 buildNarrativeContext 方法**

在 `buildGridPrompt` 方法之前添加：

```java
/**
 * 从分镜列表中提取叙事上下文（1-2句话概括整页剧情 + 统一场景）
 */
private String buildNarrativeContext(List<Map<String, Object>> shots) {
    if (shots == null || shots.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    // 提取场景关键词（取出现最多的场景）
    Map<String, Integer> sceneCount = new java.util.LinkedHashMap<>();
    for (Map<String, Object> shot : shots) {
        String scene = getShotValue(shot, "scene");
        if (scene != null && !scene.isEmpty()) {
            sceneCount.merge(scene, 1, Integer::sum);
        }
    }
    // 故事摘要：取前2个和最后1个分镜的描述拼接
    sb.append("本页讲述的是：");
    int summaryCount = Math.min(shots.size(), 3);
    for (int i = 0; i < summaryCount; i++) {
        Map<String, Object> shot = shots.get(i);
        String desc = getShotValue(shot, "sceneDescription", "scene_description");
        if (desc == null || desc.isEmpty()) {
            desc = getShotValue(shot, "visualDescription", "visual_description");
        }
        if (desc != null && !desc.isEmpty()) {
            // 截取前40字避免过长
            if (desc.length() > 40) desc = desc.substring(0, 40) + "…";
            sb.append(desc);
            if (i < summaryCount - 1) sb.append("，");
        }
    }
    if (shots.size() > 3) sb.append("等");
    sb.append("。\n");
    // 统一场景
    if (!sceneCount.isEmpty()) {
        String mainScene = sceneCount.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse("");
        if (!mainScene.isEmpty()) {
            sb.append("故事发生在：").append(mainScene).append("。\n");
        }
    }
    return sb.toString();
}
```

- [ ] **Step 2: 添加 buildTransitionTag 方法**

```java
/**
 * 根据相邻分镜生成过渡标签
 */
private String buildTransitionTag(Map<String, Object> prevShot, Map<String, Object> currentShot) {
    if (prevShot == null) return "[新场景开场]";
    String prevScene = getShotValue(prevShot, "scene");
    String currScene = getShotValue(currentShot, "scene");
    // 场景切换
    if (prevScene != null && currScene != null && !prevScene.equals(currScene)) {
        return "[场景切换至：" + currScene + "]";
    }
    // 景别变化
    String prevSize = getShotValue(prevShot, "shotSize", "shot_size");
    String currSize = getShotValue(currentShot, "shotSize", "shot_size");
    if (prevSize != null && currSize != null && !prevSize.equals(currSize)) {
        if (currSize.contains("特写") || currSize.contains("近景")) return "[镜头拉近]";
        if (currSize.contains("远景") || currSize.contains("全景")) return "[镜头拉远]";
    }
    return "[与前一场景连续]";
}
```

- [ ] **Step 3: 添加 buildLargeGridWarning 方法**

```java
/**
 * 5×5 大宫格专属约束
 */
private String buildLargeGridWarning() {
    return "【大宫格约束 - 极其重要】\n" +
        "本图包含25个格子，必须严格遵守以下规则：\n" +
        "- 每个格子的内容必须严格限制在其边界内，禁止内容溢出到相邻格子\n" +
        "- 相邻格子之间的分隔线必须清晰可见，不可模糊或缺失\n" +
        "- 第4列和第5列的格子容易被忽略，请确保每一列每一行都有完整内容\n" +
        "- 25个格子都必须绘制对应内容，不可省略或合并\n\n";
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat: PanelPromptBuilder新增叙事上下文、过渡标签、大宫格约束方法"
```

---

### Task 5: PanelPromptBuilder — 重写 buildGridPrompt

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:84-198`

- [ ] **Step 1: 重写 buildGridPrompt(gridCols, gridRows) 方法体**

用 4 层约束结构替换现有 prompt 构建。保留现有的角色锚定逻辑、分镜内容迭代、空格填充、负面提示词。新增：叙事上下文层、过渡标签、大宫格警告。分隔线描述从 "4px" 改为 "8px"。

将 `buildGridPrompt(String visualStyle, List<Map<String, Object>> shots, List<?> charRefs, int gridCols, int gridRows)` 方法体替换为：

```java
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                               List<?> charRefs, int gridCols, int gridRows) {
    int totalSlots = gridCols * gridRows;
    StringBuilder sb = new StringBuilder();

    // ===== 第1层：全局风格锁 =====
    sb.append("【全局风格锁 - 最高优先级】\n");
    sb.append("整张图必须严格保持统一的视觉风格，色调、光影、线条粗细、");
    sb.append("色彩饱和度在所有格子中必须完全一致，禁止任何格子偏离此风格。\n\n");

    // ===== 第2层：叙事上下文 =====
    String narrativeContext = buildNarrativeContext(shots);
    if (!narrativeContext.isEmpty()) {
        sb.append("【叙事上下文】\n");
        sb.append(narrativeContext);
        sb.append("\n");
    }

    // ===== 风格前缀 =====
    sb.append(buildSceneStylePrefix(visualStyle));
    sb.append("专业动画关键帧级别，电影级画面构图，精致光影与色彩。\n\n");

    // ===== 第3层：角色锚定 =====
    if (charRefs != null && !charRefs.isEmpty()) {
        sb.append("【角色设定 - 最高优先级，必须严格遵守】\n");
        sb.append("本图附带角色参考图（reference images），这些参考图是角色外貌的唯一权威标准。\n");
        sb.append("你必须严格参照参考图来绘制每个角色，角色的五官、发型、发色、瞳色、体型比例、服装、配饰等所有外貌细节必须与参考图完全一致。\n");
        sb.append("严禁凭想象修改角色的任何外貌特征，即使文字描述与参考图有冲突，也必须以参考图为准。\n");
        sb.append("只允许绘制以下角色，绝对不要出现列表之外的角色、路人或背景人物。\n");
        sb.append("每个角色在不同格子中必须保持与参考图完全一致的外貌，不允许出现同一角色在不同格子中长得不一样的情况。\n\n");

        for (int refIdx = 0; refIdx < charRefs.size(); refIdx++) {
            Object refObj = charRefs.get(refIdx);
            String name = null;
            String species = null;
            String appearance = null;
            String role = null;
            if (refObj instanceof com.comic.service.panel.GridImageService.CharRef) {
                com.comic.service.panel.GridImageService.CharRef cr =
                        (com.comic.service.panel.GridImageService.CharRef) refObj;
                name = cr.name;
                species = cr.species;
                appearance = cr.appearance;
                role = cr.role;
            }
            if (name == null || name.isEmpty()) continue;

            sb.append("- ").append(name);
            if (role != null && !role.isEmpty()) {
                sb.append("（").append(role).append("）");
            }
            sb.append("：必须严格按照对应的参考图绘制");
            if (species != null && !species.isEmpty()) {
                sb.append("，物种=").append(species);
                if (species.contains("拟人") || species.contains("ANTHRO")) {
                    sb.append("，始终为拟人化形态（直立行走、人形身体比例、兽耳兽尾等特征，非四足野兽形态）");
                }
            }
            if (appearance != null && !appearance.isEmpty()) {
                sb.append("，外貌特征: ").append(appearance);
            }
            sb.append("。参考图中展示的五官、发型、发色、服装、体型等细节即为该角色的最终标准，务必完全复刻。\n");
        }
        sb.append("\n【角色一致性约束】每个格子中出现的角色都必须与上述参考图保持完全一致的外貌，包括但不限于：脸型、五官比例、发型与发色、瞳孔颜色、身高体型、服装款式与颜色。这是最重要的要求，违反即为失败。\n\n");
    }

    // ===== 第4层：逐格指令 =====
    sb.append("【布局要求 - 必须严格遵守】\n");
    sb.append("【重要：以下所有说明均为中文，请使用中文理解并执行】\n");
    sb.append("输出一张严格 ").append(gridCols).append("×").append(gridRows).append(" 分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。\n");
    sb.append("图片必须被 ").append(gridCols - 1).append(" 条黑色竖线（约 8px 宽）和 ").append(gridRows - 1).append(" 条黑色横线（约 8px 宽）均匀分割为 ").append(gridRows).append(" 行 ").append(gridCols).append(" 列，共 ").append(totalSlots).append(" 个等大的格子。\n");
    sb.append("每个格子是一个完全独立的分镜画面，场景、人物、时间可以不同。\n");
    sb.append("绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。\n");
    sb.append("图片中不包含任何文字、数字、标号或水印。\n\n");
    sb.append("【时间态标识】若分镜属于回忆/闪回（字段标记或描述语义显示为回忆），该格必须使用柔和虚化边框/暗角区分时间线；非回忆格禁止使用该效果。\n\n");

    sb.append("【分镜内容 - 按从左到右、从上到下填入格子，每个格子必须是精致的关键帧画面】\n");
    sb.append("每个分镜必须包含：完整的场景环境细节（光影、色调、空间纵深）、角色的精确外貌与服装、");
    sb.append("细腻的面部表情和肢体语言、精心设计的构图与景深关系。画面要有电影级质感。\n\n");

    Map<String, Object> prevShot = null;
    for (int i = 0; i < shots.size(); i++) {
        Map<String, Object> shot = shots.get(i);
        int row = i / gridCols + 1;
        int col = i % gridCols + 1;
        sb.append("第").append(row).append("行第").append(col).append("列: ");

        String sceneDescription = getShotValue(shot, "sceneDescription", "scene_description");
        if (sceneDescription != null && !sceneDescription.isEmpty()) {
            sb.append(sceneDescription);
        } else {
            String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
            sb.append(visualDescription != null ? visualDescription : "");
            String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
            if (cameraMovement != null && !cameraMovement.isEmpty()) {
                sb.append("，").append(cameraMovement);
            }
        }
        String shotSize = getShotValue(shot, "shotSize", "shot_size");
        if (shotSize != null && !shotSize.isEmpty()) {
            sb.append("，").append(shotSize);
        }
        String cameraAngle = getShotValue(shot, "cameraAngle", "camera_angle");
        if (cameraAngle != null && !cameraAngle.isEmpty()) {
            sb.append("，").append(cameraAngle);
        }
        String scene = getShotValue(shot, "scene");
        if (scene != null && !scene.isEmpty()) {
            sb.append("，场景: ").append(scene);
        }
        if (isFlashbackShot(shot)) {
            sb.append("，回忆镜头（需添加柔和虚化边框作为时间标识）");
        }
        String imageHint = getShotValue(shot, "image_prompt_hint");
        if (imageHint != null && !imageHint.isEmpty()) {
            sb.append("，画面补充提示: ").append(imageHint);
        }
        // 过渡标签
        sb.append(" ").append(buildTransitionTag(prevShot, shot));
        sb.append("\n");
        prevShot = shot;
    }

    int emptySlots = totalSlots - shots.size();
    if (emptySlots > 0) {
        sb.append("剩余 ").append(emptySlots).append(" 个格子留空（纯黑色填充，不绘制任何内容）。\n\n");
    }

    // ===== 第5层：大宫格约束（5×5 时启用） =====
    if (gridCols >= 5 && gridRows >= 5) {
        sb.append(buildLargeGridWarning());
    }

    // ===== 负面提示词 =====
    sb.append("负面提示词：文字、水印、标签、签名、人体结构错误、肢体融合、多余手指、多余肢体、");
    sb.append("面部变形、眼睛异常、模糊、低质量、色块 artefact、粗糙线条、草稿感。");
    return sb.toString();
}
```

- [ ] **Step 2: 修改默认重载方法**

将行 206-209 的默认重载改为根据 shots 数量自动计算：

```java
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                               List<?> charRefs) {
    int[] gridSize = com.comic.service.panel.GridImageService.calculateGridSize(shots.size());
    return buildGridPrompt(visualStyle, shots, charRefs, gridSize[0], gridSize[1]);
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat: 重写buildGridPrompt为4层约束结构 + 过渡标签 + 大宫格警告"
```

---

### Task 6: ComicCommentaryPanelPromptBuilder — 同步适配

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java:49-156` (buildGridPrompt 主方法), `:161-163` (默认重载)

- [ ] **Step 1: 添加相同的辅助方法**

从 PanelPromptBuilder 复制三个辅助方法（或提取为公共工具方法）。考虑到两个类是独立的，直接在 ComicCommentaryPanelPromptBuilder 中添加：

- `buildNarrativeContext(List<Map<String, Object>> shots)` — 与 Task 4 Step 1 相同
- `buildTransitionTag(Map<String, Object> prevShot, Map<String, Object> currentShot)` — 与 Task 4 Step 2 相同
- `buildLargeGridWarning()` — 与 Task 4 Step 3 相同

- [ ] **Step 2: 修改 buildGridPrompt 方法体**

在 `buildGridPrompt(gridCols, gridRows)` 方法中做以下修改：

1. 分隔线描述从 `"约 4px 宽"` 改为 `"约 8px 宽"`（行 59 的两处）
2. 在角色锚定段之前添加叙事上下文段：
   ```java
   String narrativeContext = buildNarrativeContext(shots);
   if (!narrativeContext.isEmpty()) {
       sb.append("【叙事上下文】\n");
       sb.append(narrativeContext);
       sb.append("\n");
   }
   ```
3. 在分镜内容迭代中添加过渡标签（参照 Task 5 Step 1 的方式，在每个分镜描述后添加 `buildTransitionTag(prevShot, shot)`）
4. 在空格填充之后、负面提示词之前添加大宫格约束：
   ```java
   if (gridCols >= 5 && gridRows >= 5) {
       sb.append(buildLargeGridWarning());
   }
   ```

- [ ] **Step 3: 修改默认重载方法**

将行 161-163 改为自动计算：

```java
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots, List<?> charRefs) {
    int[] gridSize = com.comic.service.panel.GridImageService.calculateGridSize(shots.size());
    return buildGridPrompt(visualStyle, shots, charRefs, gridSize[0], gridSize[1]);
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java
git commit -m "feat: ComicCommentaryPromptBuilder适配新prompt结构 + 8px分隔线 + 叙事上下文"
```

---

### Task 7: GridEpisodeCard.tsx — 扩展前端网格逻辑

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridEpisodeCard.tsx:41-59`

- [ ] **Step 1: 扩展 getGridSize 函数**

```typescript
function getGridSize(shotCount: number): { cols: number; rows: number } {
  if (shotCount <= 4) return { cols: 2, rows: 2 };
  if (shotCount <= 9) return { cols: 3, rows: 3 };
  if (shotCount <= 16) return { cols: 4, rows: 4 };
  return { cols: 5, rows: 5 };
}
```

- [ ] **Step 2: 验证 buildAdaptivePages 无需修改**

`buildAdaptivePages` 调用 `getGridSize(remaining)`，扩展 `getGridSize` 后它自动支持新规格。无需额外修改。

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridEpisodeCard.tsx
git commit -m "feat: 前端getGridSize扩展支持4×4/5×5宫格规格"
```

---

### Task 8: 编译验证

**Files:**
- No new files

- [ ] **Step 1: 编译后端**

```bash
cd backend/com/comic && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 编译前端**

```bash
cd frontend/wiset_aivideo_generator && npx tsc --noEmit
```

Expected: 无类型错误

- [ ] **Step 3: 如果编译失败，修复后 commit**

```bash
git add -u
git commit -m "fix: 编译修复"
```

---

### Task 9: 更新单元测试

**Files:**
- Modify: `backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java`

- [ ] **Step 1: 更新分隔线相关测试**

找到测试中引用 `sep = 4` 的地方，改为 `sep = 8`，更新对应的注释计算。例如：

```java
// 旧: 宽 = 3 * 640 + 2 * 4 = 1928
// 新: 宽 = 3 * 640 + 2 * 8 = 1936
```

- [ ] **Step 2: 添加 calculateGridSize 新规格测试**

```java
@Test
void calculateGridSize_should_support_4x4() {
    int[] size = GridImageService.calculateGridSize(10);
    assertArrayEquals(new int[]{4, 4}, size);
    size = GridImageService.calculateGridSize(16);
    assertArrayEquals(new int[]{4, 4}, size);
}

@Test
void calculateGridSize_should_support_5x5() {
    int[] size = GridImageService.calculateGridSize(17);
    assertArrayEquals(new int[]{5, 5}, size);
    size = GridImageService.calculateGridSize(25);
    assertArrayEquals(new int[]{5, 5}, size);
}
```

- [ ] **Step 3: 删除 calculatePageCount 相关测试**

如果存在调用已删除的 `calculatePageCount` 方法的测试，删除或注释掉。

- [ ] **Step 4: 运行测试验证**

```bash
cd backend/com/comic && mvn test -Dtest=GridImageServiceTest -q
```

Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java
git commit -m "test: 更新GridImageService测试适配新宫格规格和8px分隔线"
```
