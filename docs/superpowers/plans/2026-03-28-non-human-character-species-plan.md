# 非人类角色物种支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为角色系统增加物种类型（species）字段，使非人类角色（拟人化动物、奇幻种族、真实动物）的参考图 prompt 能正确适配。

**Architecture:** 在现有 characterInfo Map 中新增 species 字段，AI 提取时自动识别，用户可在 Step3 修改，CharacterPromptManager 根据 species 调整三视图和表情图 prompt。

**Tech Stack:** Java (Spring Boot), TypeScript (React), Seedream Image API

**Spec:** `docs/superpowers/specs/2026-03-28-non-human-character-species-design.md`

---

### Task 1: 后端数据模型 — CharacterInfoKeys + CharacterDraftModel

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/common/CharacterInfoKeys.java:24`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/model/CharacterDraftModel.java:17`

- [ ] **Step 1: CharacterInfoKeys 新增 SPECIES 常量**

在 `CharacterInfoKeys.java` 的 `THREE_VIEW_GRID_PROMPT` 行之后添加：

```java
public static final String SPECIES = "species";
```

- [ ] **Step 2: CharacterDraftModel 新增 species 字段**

在 `CharacterDraftModel.java` 的 `private Boolean confirmed;` 之后添加：

```java
private String species;           // 物种类型: HUMAN/ANTHRO_ANIMAL/CREATURE/ANIMAL
```

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/common/CharacterInfoKeys.java backend/com/comic/src/main/java/com/comic/dto/model/CharacterDraftModel.java
git commit -m "feat: add species field to CharacterInfoKeys and CharacterDraftModel"
```

---

### Task 2: 后端 DTO — CharacterUpdateRequest + CharacterListItemResponse + CharacterStatusResponse

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/dto/request/CharacterUpdateRequest.java:11`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/response/CharacterListItemResponse.java:23`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/response/CharacterStatusResponse.java:26`

- [ ] **Step 1: CharacterUpdateRequest 新增 species 字段**

在 `private String background;` 之后添加：

```java
private String species;           // 物种类型: HUMAN/ANTHRO_ANIMAL/CREATURE/ANIMAL
```

- [ ] **Step 2: CharacterListItemResponse 新增 species 字段**

在 `private LocalDateTime createdAt;` 之前添加：

```java
private String species;           // 物种类型
```

- [ ] **Step 3: CharacterStatusResponse 新增 species 字段**

在 `private String threeViewGridUrl;` 之前添加：

```java
private String species;              // 物种类型: HUMAN/ANTHRO_ANIMAL/CREATURE/ANIMAL
```

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/dto/request/CharacterUpdateRequest.java backend/com/comic/src/main/java/com/comic/dto/response/CharacterListItemResponse.java backend/com/comic/src/main/java/com/comic/dto/response/CharacterStatusResponse.java
git commit -m "feat: add species field to character DTOs"
```

---

### Task 3: 后端 — CharacterExtractService 提取 prompt + 解析 + 持久化 + 映射

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/character/CharacterExtractService.java`

- [ ] **Step 1: 修改角色提取 prompt，增加 species 字段要求**

将 `extractCharacters()` 方法中（约第 70-83 行）的 `userPrompt` 替换为：

```java
String userPrompt = "请从以下故事大纲中提取角色信息：\n\n"
        + "【故事创意】\n" + storyPrompt + "\n\n"
        + "【故事大纲】\n" + outline + "\n\n"
        + "要求：\n"
        + "1. 只返回纯JSON数组，不要有任何其他文字说明\n"
        + "2. 不要使用markdown代码块标记\n"
        + "3. 每个角色必须包含：name(姓名), role(角色定位), species(物种类型), personality(性格), appearance(外貌), background(背景), voice(声音特点)\n"
        + "4. 所有字段都必须有值，不能为null\n"
        + "5. role只能是：主角、反派、配角\n"
        + "6. species只能是以下值之一：HUMAN（人类）、ANTHRO_ANIMAL（拟人化动物，有人形身体但保留动物特征如猫耳狐尾）、CREATURE（奇幻/科幻种族，如精灵、机器人、恶魔）、ANIMAL（真实动物形态，如宠物、坐骑、灵兽，无人类形态）\n"
        + "7. 判断species的规则：如果角色描述中提到动物特征+人形身体（如猫耳、狐尾、龙鳞），则为ANTHRO_ANIMAL；如果提到非人类种族（精灵、机器人、恶魔、外星人），则为CREATURE；如果是纯动物形态无人类特征（灵兽、宠物、坐骑），则为ANIMAL；其余为HUMAN\n"
        + "8. 返回格式示例：[{\"name\":\"张三\",\"species\":\"HUMAN\",\"role\":\"主角\",\"personality\":\"勇敢\",\"appearance\":\"英俊\",\"background\":\"孤儿\",\"voice\":\"沉稳男声\"}]\n\n"
        + "请直接返回JSON数组：";
```

- [ ] **Step 2: 修改 parseCharacters()，读取 species 字段**

在 `parseCharacters()` 方法（约第 197 行）的 `dto.setVoice(...)` 之后添加：

```java
String species = getStringValue(data, "species");
dto.setSpecies(isValidSpecies(species) ? species : "HUMAN");
```

在 `CharacterExtractService` 类中添加辅助方法：

```java
private boolean isValidSpecies(String species) {
    return species != null && java.util.Set.of("HUMAN", "ANTHRO_ANIMAL", "CREATURE", "ANIMAL").contains(species);
}
```

- [ ] **Step 3: 修改 saveCharacters()，写入 species 到 characterInfo**

在 `saveCharacters()` 方法（约第 268 行）的 `info.put(CharacterInfoKeys.VISUAL_STYLE, visualStyle);` 之后添加：

```java
info.put(CharacterInfoKeys.SPECIES, dto.getSpecies() != null ? dto.getSpecies() : "HUMAN");
```

- [ ] **Step 4: 修改 updateCharacter()，支持 species 更新**

在 `updateCharacter()` 方法（约第 149 行）的 `if (dto.getBackground() != null)` 块之后添加：

```java
if (dto.getSpecies() != null) {
    if (isValidSpecies(dto.getSpecies())) {
        info.put(CharacterInfoKeys.SPECIES, dto.getSpecies());
    }
}
```

- [ ] **Step 5: 修改 toListItemResponse()，映射 species 到响应**

在 `toListItemResponse()` 方法（约第 292 行）的 `resp.setConfirmed(...)` 之前添加：

```java
resp.setSpecies(getInfoStr(info, CharacterInfoKeys.SPECIES));
```

- [ ] **Step 6: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/character/CharacterExtractService.java
git commit -m "feat: add species to character extraction, parsing, persistence, and update"
```

---

### Task 4: 后端 — CharacterImageGenerationService 映射 species 到响应

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java:255`

- [ ] **Step 1: 在 getGenerationStatus() 中映射 species**

在 `dto.setVisualStyle(...)` 之后、`dto.setExpressionGridUrl(...)` 之前添加：

```java
dto.setSpecies(getCharInfoStr(character, CharacterInfoKeys.SPECIES));
```

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java
git commit -m "feat: map species field in CharacterStatusResponse"
```

---

### Task 5: 后端 — CharacterPromptManager prompt 适配

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/CharacterPromptManager.java:77-125`

- [ ] **Step 1: 添加 species 读取辅助方法**

在 `getCharInfoStr()` 方法之后添加：

```java
private static final java.util.Set<String> NON_HUMAN_SPECIES = java.util.Set.of("ANTHRO_ANIMAL", "CREATURE", "ANIMAL");

private boolean isNonHuman(Character character) {
    String species = getCharInfoStr(character, "species");
    return species != null && NON_HUMAN_SPECIES.contains(species);
}

private String getSpeciesAwareBodyDescription(Character character) {
    String species = getCharInfoStr(character, "species");
    if (species == null) species = "HUMAN";
    return switch (species) {
        case "ANTHRO_ANIMAL" -> "正确的拟人化身体结构，保留动物特征（耳朵、尾巴、爪等）";
        case "CREATURE" -> "正确的种族身体结构，展现该种族特有的体型和特征";
        case "ANIMAL" -> "自然的动物形态，四足或飞行姿态，无衣物配饰";
        default -> "正确的人体结构";
    };
}

private String getSpeciesAwareExpressionFraming(Character character) {
    String species = getCharInfoStr(character, "species");
    if (species == null) species = "HUMAN";
    return switch (species) {
        case "ANTHRO_ANIMAL" -> "特写肖像镜头（头部和肩部），表情通过面部、耳朵、尾巴动态体现";
        case "CREATURE" -> "特写肖像镜头，表情通过该种族特征部位体现";
        case "ANIMAL" -> "特写镜头（头部），表情通过耳朵、眼睛、尾巴姿态体现，不要肩部以下";
        default -> "仅限特写肖像镜头（头部和肩部）";
    };
}
```

- [ ] **Step 2: 修改 buildThreeViewGridPrompt()**

将第 103-125 行的三视图 prompt 方法替换为：

```java
public String buildThreeViewGridPrompt(Character character, VisualStyle style) {
    String appearance = getCharInfoStr(character, "appearance");
    String personality = getCharInfoStr(character, "personality");
    String stylePrefix = buildCharacterStylePrefix(style);
    String negativePrompt = buildThreeViewNegativePrompt(style);
    String bodyDesc = getSpeciesAwareBodyDescription(character);

    String postureReq = isNonHuman(character) && "ANIMAL".equals(getCharInfoStr(character, "species"))
            ? "- 自然姿态（站立、四足或飞行），表情平静\n"
            : "- 全身站立姿势，表情平静\n";

    return stylePrefix +
            "角色三视图参考图，生成角色三视图（正面、侧面、背面视图）。\n\n" +
            "角色面部描述：" + appearance + "\n" +
            "属性特征：" + personality + "\n\n" +
            "构图要求：\n" +
            "- 纵向布局，包含3个视图：正面视图、侧面视图（侧面轮廓）、背面视图\n" +
            postureReq +
            "- 纯色平铺背景（白色、浅灰色或黑色），不要图案、渐变、环境元素\n" +
            "- 每个视图应清晰展示角色在指定角度下的外观\n\n" +
            "关键要求：\n" +
            "1. 角色设计一致——三个视图必须展示同一个角色\n" +
            "2. 不要文字、不要标签——纯图像\n" +
            "3. **" + bodyDesc + "**——身体比例正确、姿态自然\n" +
            "4. 平静表情——所有视图使用中性面部表情\n" +
            "5. 清晰对齐——正面、侧面和背面视图纵向对齐，比例一致\n\n" +
            "负面提示词：" + negativePrompt;
}
```

- [ ] **Step 3: 修改 buildExpressionGridPrompt()**

将第 77-98 行的表情图 prompt 方法替换为：

```java
public String buildExpressionGridPrompt(Character character, VisualStyle style) {
    String appearance = getCharInfoStr(character, "appearance");
    String personality = getCharInfoStr(character, "personality");
    String stylePrefix = buildCharacterStylePrefix(style);
    String negativePrompt = buildExpressionNegativePrompt(style);
    String expressionFraming = getSpeciesAwareExpressionFraming(character);

    return stylePrefix +
            "角色面部表情参考图，3行3列网格布局，展示9种不同的面部表情" +
            "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、平静、思考、疲惫）。" +
            "严格为3行3列网格布局，共9个格子，每个格子包含一种独立的表情。\n\n" +
            "肖像构图：极致特写，仅头部和肩部，聚焦面部表情。\n\n" +
            "角色面部描述：" + appearance + "\n" +
            "性格特征：" + personality + "\n\n" +
            "关键约束：\n" +
            "- **" + expressionFraming + "**\n" +
            "- 不要全身，不要下半身，不要腿部\n" +
            "- 聚焦面部特征、表情和头部\n" +
            "- 纯色平铺背景（白色、浅灰色或黑色），不要图案、渐变、环境元素\n" +
            "- 所有9个表情中保持角色设计一致\n" +
            "- 严格为3行3列网格构图\n\n" +
            "负面提示词：" + negativePrompt;
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/CharacterPromptManager.java
git commit -m "feat: adapt character prompts based on species for non-human characters"
```

---

### Task 6: 前端 — TypeScript 类型定义

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/project.types.ts:248-297`

- [ ] **Step 1: CharacterListItem 接口添加 species**

在 `CharacterListItem` 接口（约第 248 行）的 `confirmed: boolean;` 之后添加：

```typescript
species?: string;            // 物种类型: HUMAN/ANTHRO_ANIMAL/CREATURE/ANIMAL
```

- [ ] **Step 2: CharacterDraft 接口添加 species**

在 `CharacterDraft` 接口（约第 266 行）的 `confirmed: boolean;` 之后添加：

```typescript
species?: string;            // 物种类型
```

- [ ] **Step 3: CharacterStatus 接口添加 species**

在 `CharacterStatus` 接口（约第 279 行）的 `visualStyle?: string;` 之后添加：

```typescript
species?: string;            // 物种类型: HUMAN/ANTHRO_ANIMAL/CREATURE/ANIMAL
```

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/types/project.types.ts
git commit -m "feat: add species field to character TypeScript interfaces"
```

---

### Task 7: 前端 — Step3page 物种下拉框

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step3page.tsx`

- [ ] **Step 1: 添加物种选项常量**

在文件顶部的 `VISUAL_STYLE_OPTIONS` 之后添加：

```typescript
// 物种类型选项
const SPECIES_OPTIONS = [
  { value: 'HUMAN', label: '人类' },
  { value: 'ANTHRO_ANIMAL', label: '拟人化动物' },
  { value: 'CREATURE', label: '奇幻/科幻种族' },
  { value: 'ANIMAL', label: '真实动物' },
];
```

- [ ] **Step 2: editForm 初始化中加入 species**

在 `setEditForm({...})` 初始化（约第 166-173 行）中，在 `background: char.char.background,` 之后添加：

```typescript
species: char.char.species,
```

- [ ] **Step 3: 在角色编辑卡片中添加物种下拉框**

在 `Step3page.tsx` 中，找到性格描述表单组（约第 403 行）的 `<div className={styles.formGroup}>` 之前，添加物种下拉框：

```tsx
<div className={styles.formGroup}>
  <label className={styles.formLabel} htmlFor={`species-${char.char.charId}`}>物种类型</label>
  <select
    id={`species-${char.char.charId}`}
    className={styles.formSelect}
    value={editForm.species || 'HUMAN'}
    onChange={(e) => handleFormChange('species', e.target.value)}
  >
    {SPECIES_OPTIONS.map(opt => (
      <option key={opt.value} value={opt.value}>{opt.label}</option>
    ))}
  </select>
</div>
```

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step3page.tsx
git commit -m "feat: add species dropdown to Step3 character editing card"
```
