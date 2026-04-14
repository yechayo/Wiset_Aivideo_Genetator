# 自适应宫格（九宫格→六宫格→四宫格）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根据每页分镜数量动态选择 2×2 / 3×2 / 3×3 宫格布局，解决少分镜时 AI 指令遵循能力下降的问题。

**Architecture:** 后端 `GridImageService` 新增 `calculateGridSize()` 静态方法，每页独立计算网格尺寸并写入 `gridConfigs`。两个 `PromptBuilder` 新增 `gridCols/gridRows` 参数重载，prompt 动态化。前端从 `gridConfigs` 读取布局参数替换硬编码。

**Tech Stack:** Java (Spring Boot), TypeScript (React)

---

## 文件地图

| 文件 | 职责 |
|------|------|
| `backend/.../service/panel/GridImageService.java` | 核心生成/切割逻辑 |
| `backend/.../ai/PanelPromptBuilder.java` | 标准模式 prompt 构建 |
| `backend/.../ai/ComicCommentaryPanelPromptBuilder.java` | 漫剧解说模式 prompt |
| `frontend/.../pages/create/steps/Step4Production.tsx` | `buildGridPromptText` 函数 + `DoneEpisodeCard` |
| `frontend/.../pages/create/steps/components/GridEpisodeCard.tsx` | 九宫格编辑卡片 |
| `frontend/.../pages/create/steps/types.ts` | `EpisodeState` 类型定义 |

以下文件 **无需改动**（已支持动态参数）：
- `frontend/.../utils/canvasSplitter.ts`
- `frontend/.../components/GridReviewPanel.tsx`
- `frontend/.../components/GridFusionEditor.tsx`（已有 `?? 3` 兜底）

---

### Task 1: GridImageService — 新增 calculateGridSize + 改造 generateGridsForPanel

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`

- [ ] **Step 1: 新增 calculateGridSize 静态方法**

在 `calculatePageCount` 方法（第 353 行）之后添加：

```java
/**
 * 根据分镜数量计算该页最优网格布局
 * @return int[]{cols, rows}
 */
public static int[] calculateGridSize(int shotCount) {
    if (shotCount <= 4) return new int[]{2, 2}; // 四宫格 2×2
    if (shotCount <= 6) return new int[]{3, 2}; // 六宫格 3×2
    return new int[]{3, 3}; // 九宫格 3×3
}

/** 根据网格尺寸计算该页最大容量 */
public static int shotsPerPage(int cols, int rows) {
    return cols * rows;
}
```

- [ ] **Step 2: 改造 generateGridsForPanel（第 65-133 行）**

将固定 `SHOTS_PER_PAGE` 分页改为动态分页。核心改动：

**2a.** 替换第 82 行的 `calculatePageCount` 调用和分页循环。将整个 `try` 块内的生成+切割逻辑替换为：

```java
try {
    panelInfo.put("gridStatus", "generating");
    updatePanelInfo(panel, panelInfo);

    List<String> characterRefUrls = getCharacterReferenceUrls(panel.getEpisodeId());
    List<CharRef> charRefsWithNames = getCharacterReferencesWithNames(panel.getEpisodeId());

    // 动态分页：按最优网格尺寸分组
    List<List<Map<String, Object>>> pages = new ArrayList<>();
    List<int[]> pageGridSizes = new ArrayList<>();
    int remaining = shots.size();
    int offset = 0;
    while (remaining > 0) {
        int[] gridSize = calculateGridSize(remaining);
        int capacity = shotsPerPage(gridSize[0], gridSize[1]);
        int take = Math.min(capacity, remaining);
        pages.add(shots.subList(offset, offset + take));
        pageGridSizes.add(gridSize);
        offset += take;
        remaining -= take;
    }
    int pageCount = pages.size();
    List<String> gridImageUrls = new ArrayList<>();
    List<Map<String, Object>> gridConfigs = new ArrayList<>();

    for (int page = 0; page < pageCount; page++) {
        List<Map<String, Object>> pageShots = pages.get(page);
        int[] gridSize = pageGridSizes.get(page);
        int gridCols = gridSize[0];
        int gridRows = gridSize[1];

        String prompt = buildGridPromptForProject(panel.getEpisodeId(), visualStyleStr, pageShots, charRefsWithNames, gridCols, gridRows);
        prompt = appendUserHintToPrompt(prompt, customHint);
        String imageUrl;
        if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
            imageUrl = imageService.generateWithMultipleReferences(
                prompt, characterRefUrls, 1920, 1080);
        } else {
            imageUrl = imageService.generate(prompt, 1920, 1080, visualStyleStr);
        }
        gridImageUrls.add(imageUrl);

        Map<String, Object> config = new HashMap<>();
        config.put("page", page);
        config.put("gridCols", gridCols);
        config.put("gridRows", gridRows);
        config.put("shotCount", pageShots.size());
        gridConfigs.add(config);
    }

    // 切割九宫格
    int shotOffset = 0;
    for (int page = 0; page < gridImageUrls.size(); page++) {
        BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
        int[] gridSize = pageGridSizes.get(page);
        List<BufferedImage> subImages = splitGridImage(gridImage, gridSize[0], gridSize[1]);
        for (int i = 0; i < subImages.size() && (shotOffset + i) < shots.size(); i++) {
            String ossUrl = uploadToOss(subImages.get(i), panelId, shotOffset + i);
            @SuppressWarnings("unchecked")
            Map<String, Object> shotCopy = new HashMap<>(shots.get(shotOffset + i));
            shotCopy.put("splitImageUrl", ossUrl);
            shots.set(shotOffset + i, shotCopy);
        }
        shotOffset += pages.get(page).size();
    }

    // 融合参考图
    BufferedImage fusionImage = createFusionImage(shots, charRefsWithNames);
    panelInfo.put("fusionImageUrl", uploadToOss(fusionImage, panelId, "fusion"));
    panelInfo.put("gridImages", gridImageUrls);
    panelInfo.put("gridStatus", "generated");
    panelInfo.put("gridPageCount", pageCount);
    panelInfo.put("gridConfigs", gridConfigs);
    updatePanelInfo(panel, panelInfo);

    log.info("Panel {} 九宫格完成, {} 页, {} 分镜", panelId, pageCount, shots.size());

} catch (Exception e) {
    log.error("Panel {} 九宫格失败", panelId, e);
    panelInfo.put("gridStatus", "failed");
    panelInfo.put("errorMessage", e.getMessage());
    updatePanelInfo(panel, panelInfo);
}
```

- [ ] **Step 3: 改造 buildGridPromptForProject 私有方法（第 146-153 行）**

新增 `gridCols/gridRows` 参数并传递给两个 prompt builder：

```java
private String buildGridPromptForProject(Long episodeId, String visualStyle,
                                         List<Map<String, Object>> pageShots,
                                         List<CharRef> charRefsWithNames,
                                         int gridCols, int gridRows) {
    Project project = resolveProjectByEpisodeId(episodeId);
    if (ProjectProductionMode.isComicCommentary(project)) {
        return comicCommentaryPanelPromptBuilder.buildGridPrompt(visualStyle, pageShots, charRefsWithNames, gridCols, gridRows);
    }
    return panelPromptBuilder.buildGridPrompt(visualStyle, pageShots, charRefsWithNames, gridCols, gridRows);
}
```

- [ ] **Step 4: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: GridImageService 动态网格尺寸 + generateGridsForPanel 自适应分页"
```

---

### Task 2: GridImageService — 改造 generateGridsForEpisode

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`

- [ ] **Step 1: 改造 doGenerateGridsForEpisode（第 196-319 行）**

与 Task 1 同理，将固定 `SHOTS_PER_PAGE` 分页改为动态分页。核心改动在 `doGenerateGridsForEpisode` 方法内：

**1a.** 替换第 217 行 `int pageCount = calculatePageCount(...)` 到第 252 行 `gridImageUrls.add(imageUrl)` 的整段生成循环为：

```java
// 动态分页：按最优网格尺寸分组
List<List<Map<String, Object>>> pages = new ArrayList<>();
List<int[]> pageGridSizes = new ArrayList<>();
int remaining = shots.size();
int offset = 0;
while (remaining > 0) {
    int[] gridSize = calculateGridSize(remaining);
    int capacity = shotsPerPage(gridSize[0], gridSize[1]);
    int take = Math.min(capacity, remaining);
    pages.add(shots.subList(offset, offset + take));
    pageGridSizes.add(gridSize);
    offset += take;
    remaining -= take;
}
int pageCount = pages.size();
List<String> gridImageUrls = new ArrayList<>();
List<String> allPagePrompts = new ArrayList<>();
List<Map<String, Object>> gridConfigs = new ArrayList<>();

String promptOverride = episodeInfo.containsKey("gridPromptOverride")
    ? (String) episodeInfo.get("gridPromptOverride") : null;

// 逐页生成
for (int page = 0; page < pageCount; page++) {
    List<Map<String, Object>> pageShots = pages.get(page);
    int[] gridSize = pageGridSizes.get(page);
    int gridCols = gridSize[0];
    int gridRows = gridSize[1];

    String prompt;
    if (gridPrompts != null && page < gridPrompts.size() && gridPrompts.get(page) != null && !gridPrompts.get(page).isEmpty()) {
        prompt = gridPrompts.get(page);
    } else if (promptOverride != null && page == 0) {
        prompt = promptOverride;
    } else {
        prompt = buildGridPromptForProject(episodeId, visualStyle, pageShots, charRefsWithNames, gridCols, gridRows);
        prompt = appendUserHintToPrompt(prompt, customHint);
    }
    allPagePrompts.add(prompt);
    String imageUrl;
    if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
        imageUrl = imageService.generateWithMultipleReferences(
            prompt, characterRefUrls, 1920, 1080);
    } else {
        imageUrl = imageService.generate(prompt, 1920, 1080, visualStyle);
    }
    gridImageUrls.add(imageUrl);

    Map<String, Object> config = new HashMap<>();
    config.put("page", page);
    config.put("gridCols", gridCols);
    config.put("gridRows", gridRows);
    config.put("shotCount", pageShots.size());
    gridConfigs.add(config);
}
```

**1b.** 替换第 256-267 行的切割循环为：

```java
List<Map<String, Object>> splitShots = new ArrayList<>();
int shotOffset = 0;
for (int page = 0; page < gridImageUrls.size(); page++) {
    BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
    int[] gridSize = pageGridSizes.get(page);
    List<BufferedImage> subImages = splitGridImage(gridImage, gridSize[0], gridSize[1]);
    for (int i = 0; i < subImages.size() && (shotOffset + i) < shots.size(); i++) {
        Map<String, Object> shot = shots.get(shotOffset + i);
        String ossUrl = uploadToOssEpisode(subImages.get(i), episodeId, shotOffset + i);
        Map<String, Object> splitShot = new HashMap<>(shot);
        splitShot.put("splitImageUrl", ossUrl);
        splitShots.add(splitShot);
    }
    shotOffset += pages.get(page).size();
}
```

**1c.** 在第 283 行 `episodeInfo.put("gridPageCount", pageCount);` 之后添加：

```java
episodeInfo.put("gridConfigs", gridConfigs);
```

- [ ] **Step 2: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: doGenerateGridsForEpisode 动态网格尺寸 + gridConfigs 持久化"
```

---

### Task 3: PanelPromptBuilder — 动态化 buildGridPrompt

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java`

- [ ] **Step 1: 新增带 gridCols/gridRows 参数的重载**

在现有 `buildGridPrompt(visualStyle, shots, charRefs)` 方法（第 82 行）之前，添加新的 5 参数版本：

```java
/**
 * 构建宫格图片生成提示词（动态网格尺寸）
 * @param gridCols 网格列数（2 或 3）
 * @param gridRows 网格行数（2 或 3）
 */
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                               List<?> charRefs, int gridCols, int gridRows) {
    StringBuilder sb = new StringBuilder();
    sb.append(buildSceneStylePrefix(visualStyle));
    sb.append("专业动画关键帧级别，电影级画面构图，精致光影与色彩。\n\n");

    // ===== 布局要求（动态） =====
    String gridLabel = gridCols + "×" + gridRows;
    int totalCells = gridCols * gridRows;
    sb.append("【布局要求 - 必须严格遵守】\n");
    sb.append("输出一张严格 ").append(gridLabel).append(" 宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。\n");
    sb.append("图片必须被 ").append(gridCols - 1).append(" 条黑色竖线（约 4px 宽）和 ")
      .append(gridRows - 1).append(" 条黑色横线（约 4px 宽）均匀分割为 ")
      .append(gridRows).append(" 行 ").append(gridCols).append(" 列，共 ")
      .append(totalCells).append(" 个等大的格子。\n");
    sb.append("每个格子是一个完全独立的分镜画面，场景、人物、时间可以不同。\n");
    sb.append("绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。\n");
    sb.append("图片中不包含任何文字、数字、标号或水印。\n\n");
    sb.append("【时间态标识】若分镜属于回忆/闪回（字段标记或描述语义显示为回忆），该格必须使用柔和虚化边框/暗角区分时间线；非回忆格禁止使用该效果。\n\n");

    // ===== 角色锚定（与原方法完全一致） =====
    if (charRefs != null && !charRefs.isEmpty()) {
        sb.append("【角色设定 - 必须严格遵守】\n");
        sb.append("只允许绘制以下角色，绝对不要出现列表之外的角色、路人或背景人物。\n");
        sb.append("每个角色在不同格子中必须保持外貌、体型比例、服装、发型完全一致。\n\n");

        for (Object refObj : charRefs) {
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
            if (species != null && !species.isEmpty()) {
                sb.append("：物种=").append(species);
                if (species.contains("拟人") || species.contains("ANTHRO")) {
                    sb.append("，始终为拟人化形态（直立行走、人形身体比例、兽耳兽尾等特征，非四足野兽形态）");
                }
            }
            if (appearance != null && !appearance.isEmpty()) {
                sb.append("，外貌特征: ").append(appearance);
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    // ===== 分镜内容（动态列数） =====
    sb.append("【分镜内容 - 按从左到右、从上到下填入").append(gridLabel).append("宫格，每个格子必须是精致的关键帧画面】\n");
    sb.append("每个分镜必须包含：完整的场景环境细节（光影、色调、空间纵深）、角色的精确外貌与服装、");
    sb.append("细腻的面部表情和肢体语言、精心设计的构图与景深关系。画面要有电影级质感。\n\n");
    for (int i = 0; i < shots.size(); i++) {
        Map<String, Object> shot = shots.get(i);
        int row = i / gridCols + 1;
        int col = i % gridCols + 1;
        sb.append("第").append(row).append("行第").append(col).append("列: ");
        String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
        sb.append(visualDescription != null ? visualDescription : "");
        String shotSize = getShotValue(shot, "shotSize", "shot_size");
        if (shotSize != null && !shotSize.isEmpty()) {
            sb.append("，").append(shotSize);
        }
        String cameraAngle = getShotValue(shot, "cameraAngle", "camera_angle");
        if (cameraAngle != null && !cameraAngle.isEmpty()) {
            sb.append("，").append(cameraAngle);
        }
        String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
        if (cameraMovement != null && !cameraMovement.isEmpty()) {
            sb.append("，").append(cameraMovement);
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
        sb.append("\n");
    }

    // ===== 空格子（动态总数） =====
    int emptySlots = totalCells - shots.size();
    if (emptySlots > 0) {
        sb.append("剩余 ").append(emptySlots).append(" 个格子留空（纯黑色填充，不绘制任何内容）。\n\n");
    }

    // ===== 负面提示词 =====
    sb.append("负面提示词：文字、水印、标签、签名、人体结构错误、肢体融合、多余手指、多余肢体、");
    sb.append("面部变形、眼睛异常、模糊、低质量、色块 artefact、粗糙线条、草稿感。");
    return sb.toString();
}
```

- [ ] **Step 2: 将原 3 参数方法改为委托调用**

将现有 `buildGridPrompt(visualStyle, shots, charRefs)` 方法体替换为：

```java
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                               List<?> charRefs) {
    return buildGridPrompt(visualStyle, shots, charRefs, 3, 3);
}
```

- [ ] **Step 3: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat: PanelPromptBuilder 动态网格尺寸 buildGridPrompt"
```

---

### Task 4: ComicCommentaryPanelPromptBuilder — 同步动态化

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java`

- [ ] **Step 1: 新增带 gridCols/gridRows 的重载**

在现有 `buildGridPrompt(visualStyle, shots, charRefs)` 方法（第 41 行）之前，添加 5 参数版本。逻辑与 Task 3 完全对应，仅漫剧解说风格不同：

```java
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                               List<?> charRefs, int gridCols, int gridRows) {
    StringBuilder sb = new StringBuilder();
    sb.append(panelPromptBuilder.buildSceneStylePrefix(visualStyle));
    sb.append("漫剧解说风格关键帧：每格为独立「漫画分镜式」画面，适合旁白解说与字幕叠加，构图清晰、主体突出。\n\n");

    String gridLabel = gridCols + "×" + gridRows;
    int totalCells = gridCols * gridRows;

    sb.append("【布局要求 - 必须严格遵守】\n");
    sb.append("输出一张严格 ").append(gridLabel).append(" 宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。\n");
    sb.append("图片必须被 ").append(gridCols - 1).append(" 条黑色竖线（约 4px 宽）和 ")
      .append(gridRows - 1).append(" 条黑色横线（约 4px 宽）均匀分割为 ")
      .append(gridRows).append(" 行 ").append(gridCols).append(" 列，共 ")
      .append(totalCells).append(" 个等大的格子。\n");
    sb.append("每个格子是一个完全独立的画面，可表现不同时间或场景；整体像动态漫/条漫分格，便于后期加解说与花字。\n");
    sb.append("绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。\n");
    sb.append("图片中不包含任何文字、数字、标号或水印（解说与字幕由后期添加）。\n\n");
    sb.append("【时间态标识】若分镜属于回忆/闪回（字段标记或描述语义显示为回忆），该格必须使用柔和虚化边框/暗角区分时间线；非回忆格禁止使用该效果。\n\n");

    // 角色锚定（与原方法完全一致，照抄第 54-90 行）
    if (charRefs != null && !charRefs.isEmpty()) {
        sb.append("【角色设定 - 必须严格遵守】\n");
        sb.append("只允许绘制以下角色，绝对不要出现列表之外的角色、路人或背景人物。\n");
        sb.append("每个角色在不同格子中必须保持外貌、体型比例、服装、发型完全一致。\n\n");

        for (Object refObj : charRefs) {
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
            if (species != null && !species.isEmpty()) {
                sb.append("：物种=").append(species);
                if (species.contains("拟人") || species.contains("ANTHRO")) {
                    sb.append("，始终为拟人化形态（直立行走、人形身体比例、兽耳兽尾等特征，非四足野兽形态）");
                }
            }
            if (appearance != null && !appearance.isEmpty()) {
                sb.append("，外貌特征: ").append(appearance);
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    sb.append("【景别约束】解说模式以中景、近景、特写为主；远景/大远景仅用于开场或转场，总数不超过 2 格。\n");
    sb.append("【字幕安全区】构图需留出上方约 1/4 区域，避免关键内容被花字遮挡。\n\n");

    sb.append("【分镜内容 - 从左到右、从上到下填入").append(gridLabel).append("宫格；每格信息密度适中，利于口播节奏】\n");
    sb.append("每格需交代清楚场景氛围与角色状态，情绪对比可略夸张以增强解说张力。\n\n");
    for (int i = 0; i < shots.size(); i++) {
        Map<String, Object> shot = shots.get(i);
        int row = i / gridCols + 1;
        int col = i % gridCols + 1;
        sb.append("第").append(row).append("行第").append(col).append("列: ");
        String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
        sb.append(visualDescription != null ? visualDescription : "");
        String shotSize = getShotValue(shot, "shotSize", "shot_size");
        if (shotSize != null && !shotSize.isEmpty()) {
            sb.append("，").append(shotSize);
        }
        String cameraAngle = getShotValue(shot, "cameraAngle", "camera_angle");
        if (cameraAngle != null && !cameraAngle.isEmpty()) {
            sb.append("，").append(cameraAngle);
        }
        String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
        if (cameraMovement != null && !cameraMovement.isEmpty()) {
            sb.append("，").append(cameraMovement);
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
        sb.append("\n");
    }

    int emptySlots = totalCells - shots.size();
    if (emptySlots > 0) {
        sb.append("剩余 ").append(emptySlots).append(" 个格子留空（纯黑色填充，不绘制任何内容）。\n\n");
    }

    sb.append("负面提示词：文字、水印、标签、签名、人体结构错误、肢体融合、多余手指、多余肢体、");
    sb.append("面部变形、眼睛异常、模糊、低质量、色块 artefact、粗糙线条、草稿感、");
    sb.append("字幕、旁白文字、屏幕上的任何文字。\n");
    sb.append("禁止快速运动、剧烈动作、突然变向。");
    return sb.toString();
}
```

- [ ] **Step 2: 原 3 参数方法委托**

```java
public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots, List<?> charRefs) {
    return buildGridPrompt(visualStyle, shots, charRefs, 3, 3);
}
```

- [ ] **Step 3: 提交**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryPanelPromptBuilder.java
git commit -m "feat: ComicCommentaryPanelPromptBuilder 动态网格尺寸"
```

---

### Task 5: 前端 types.ts — 新增 GridConfig 类型

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts`

- [ ] **Step 1: 添加 GridConfig 接口和 EpisodeState 字段**

在 `EpisodeState` 接口的 `gridStatus` 字段附近添加：

```typescript
/** 单页宫格布局配置 */
export interface GridConfig {
  page: number;
  gridCols: number;
  gridRows: number;
  shotCount: number;
}
```

在 `EpisodeState` 中，`gridPrompt` 字段附近添加：

```typescript
  /** 每页宫格布局配置（自适应：2×2 / 3×2 / 3×3） */
  gridConfigs?: GridConfig[];
```

- [ ] **Step 2: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
git commit -m "feat: 前端 GridConfig 类型定义"
```

---

### Task 6: 前端 Step4Production — 动态化 buildGridPromptText

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx`

- [ ] **Step 1: 改造 buildGridPromptText 函数（第 87 行）**

修改函数签名，新增 `gridCols/gridRows` 可选参数：

```typescript
const buildGridPromptText = (visualStyle: string, shots: any[], isComicCommentary?: boolean, gridCols?: number, gridRows?: number): string => {
  const cols = gridCols ?? 3;
  const rows = gridRows ?? 3;
  const totalCells = cols * rows;
  const stylePrefix = STYLE_PREFIX_MAP[visualStyle] || '高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。';
  const lines: string[] = [];

  lines.push(stylePrefix + '专业动画关键帧级别，电影级画面构图，精致光影与色彩。');
  lines.push('');

  lines.push('【布局要求 - 必须严格遵守】');
  lines.push(`输出一张严格 ${cols}×${rows} 宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。`);
  lines.push(`图片必须被 ${cols - 1} 条黑色竖线（约 4px 宽）和 ${rows - 1} 条黑色横线（约 4px 宽）均匀分割为 ${rows} 行 ${cols} 列，共 ${totalCells} 个等大的格子。`);
  lines.push('每个格子是一个完全独立的分镜画面，场景、人物、时间可以不同。');
  lines.push('绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。');
  lines.push('图片中不包含任何文字、数字、标号或水印。');
  lines.push('');

  // ... 中间角色锚定、景别约束部分不变 ...

  lines.push(`【分镜内容 - 按从左到右、从上到下填入${cols}×${rows}宫格，每个格子必须是精致的关键帧画面】`);
  lines.push('每个分镜必须包含：完整的场景环境细节（光影、色调、空间纵深）、角色的精确外貌与服装、细腻的面部表情和肢体语言、精心设计的构图与景深关系。画面要有电影级质感。');
  lines.push('');

  shots.forEach((shot, i) => {
    const row = Math.floor(i / cols) + 1;
    const col = i % cols + 1;
    // ... 其余不变 ...
  });

  const emptySlots = totalCells - shots.length;
  if (emptySlots > 0) {
    lines.push(`剩余 ${emptySlots} 个格子留空（纯黑色填充，不绘制任何内容）。`);
    lines.push('');
  }

  // ... 负面提示词不变 ...
```

关键改动点：
- 第 95-96 行：硬编码 3×3 → `${cols}×${rows}` 动态
- 第 122 行：`"九宫格"` → `${cols}×${rows}宫格`
- 第 127-128 行：`Math.floor(i / 3)` → `Math.floor(i / cols)`，`i % 3` → `i % cols`
- 第 141 行：`9 - shots.length` → `totalCells - shots.length`

- [ ] **Step 2: 改造 DoneEpisodeCard 中 getSavedPrompt 的 fallback（约第 2312-2320 行）**

将 fallback 中的 `SHOTS_PER_PAGE = 9` 改为从 `gridConfigs` 读取：

```typescript
const getSavedPrompt = (pageIndex: number): string => {
  if (episode.gridPrompts && episode.gridPrompts.length > pageIndex) {
    return episode.gridPrompts[pageIndex];
  }
  if (pageIndex === 0 && episode.gridPrompt) {
    return episode.gridPrompt;
  }
  // fallback: 从 gridConfigs 获取该页容量
  if (buildGridPromptText && allShots.length > 0) {
    const config = (episode as any).gridConfigs?.[pageIndex];
    const cols = config?.gridCols ?? 3;
    const rows = config?.gridRows ?? 3;
    const capacity = cols * rows;
    const fromIdx = pageIndex > 0
      ? (episode as any).gridConfigs?.slice(0, pageIndex).reduce((sum: number, c: any) => sum + (c.shotCount ?? c.gridCols * c.gridRows), 0) ?? pageIndex * 9
      : 0;
    const toIdx = Math.min(fromIdx + capacity, allShots.length);
    const pageShots = allShots.slice(fromIdx, toIdx);
    return buildGridPromptText(visualStyle, pageShots, isComicCommentary, cols, rows);
  }
  return '';
};
```

- [ ] **Step 3: 加载 episode 时同步 gridConfigs（约第 488 行）**

在 `segments` 赋值附近添加 `gridConfigs`：

```typescript
gridConfigs: ep.episodeInfo?.gridConfigs,
```

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step4Production.tsx
git commit -m "feat: 前端 buildGridPromptText + DoneEpisodeCard 动态网格尺寸"
```

---

### Task 7: 前端 GridEpisodeCard — 动态化分页

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridEpisodeCard.tsx`

- [ ] **Step 1: 新增动态分页工具函数，替换硬编码常量**

删除第 32-34 行的硬编码常量：
```typescript
// 删除这 3 行:
// const GRID_COLS = 3;
// const GRID_ROWS = 3;
// const SHOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
```

添加一个动态分页函数（放在组件外部，常量位置）：

```typescript
/** 根据分镜数量计算网格布局（与后端 GridImageService.calculateGridSize 一致） */
function getGridSize(shotCount: number): { cols: number; rows: number } {
  if (shotCount <= 4) return { cols: 2, rows: 2 };
  if (shotCount <= 6) return { cols: 3, rows: 2 };
  return { cols: 3, rows: 3 };
}

/**
 * 将总 shots 数量按自适应网格尺寸分页，返回每页的 { fromIdx, toIdx, cols, rows }
 */
function buildAdaptivePages(totalShots: number): Array<{ fromIdx: number; toIdx: number; cols: number; rows: number }> {
  const pages: Array<{ fromIdx: number; toIdx: number; cols: number; rows: number }> = [];
  let offset = 0;
  let remaining = totalShots;
  while (remaining > 0) {
    const { cols, rows } = getGridSize(remaining);
    const capacity = cols * rows;
    const take = Math.min(capacity, remaining);
    pages.push({ fromIdx: offset, toIdx: offset + take, cols, rows });
    offset += take;
    remaining -= take;
  }
  return pages;
}
```

- [ ] **Step 2: 改造 buildPagePrompt（第 86-94 行）**

```typescript
const buildPagePrompt = useCallback((pageIndex: number): string => {
  if (!buildGridPromptText) return '';
  const allShots = getAllShots();
  const visualStyle = getVisualStyle();
  // 优先从 gridConfigs 获取该页配置
  const config = (episode as any).gridConfigs?.[pageIndex];
  const cols = config?.gridCols ?? 3;
  const rows = config?.gridRows ?? 3;
  const capacity = cols * rows;
  // 计算该页的 fromIdx：累加之前各页的 shotCount
  let fromIdx = 0;
  const configs = (episode as any).gridConfigs;
  if (configs && configs.length > pageIndex) {
    for (let i = 0; i < pageIndex; i++) {
      fromIdx += configs[i].shotCount ?? (configs[i].gridCols * configs[i].gridRows);
    }
  } else {
    // 无 gridConfigs 时用前端计算的分页
    const adaptivePages = buildAdaptivePages(allShots.length);
    if (adaptivePages[pageIndex]) {
      fromIdx = adaptivePages[pageIndex].fromIdx;
    } else {
      fromIdx = pageIndex * 9;
    }
  }
  const toIdx = Math.min(fromIdx + capacity, allShots.length);
  const pageShots = allShots.slice(fromIdx, toIdx);
  return buildGridPromptText(visualStyle, pageShots, false, cols, rows);
}, [buildGridPromptText, getAllShots, getVisualStyle, episode]);
```

- [ ] **Step 3: 改造 pageCount 计算（第 100 行和第 133 行）**

两处 `Math.ceil(allShots.length / SHOTS_PER_PAGE)` 替换为：

```typescript
const adaptivePages = buildAdaptivePages(allShots.length);
const pageCount = adaptivePages.length || 1;
```

注意第 100 行的 `useEffect` 里和第 133 行的 `useEffect` 里各有一处，都需要替换。

- [ ] **Step 4: 提交**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridEpisodeCard.tsx
git commit -m "feat: GridEpisodeCard 动态网格分页"
```

---

### Task 8: 编译验证

- [ ] **Step 1: 后端编译**

```bash
cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 前端编译**

```bash
cd D:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit
```

Expected: 无错误

- [ ] **Step 3: 最终提交（如有修复）**

```bash
git add -A && git commit -m "fix: 编译修复"
```
