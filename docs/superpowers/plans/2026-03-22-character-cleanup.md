# 角色管理模块代码清理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清理角色管理模块中的死代码，简化融合编辑器为只使用三视图

**Architecture:** 三项独立改动——前端融合编辑器简化、standardImageUrl 字段清理、未使用英文 prompt 清理。每项可独立提交。

**Tech Stack:** Java / Spring Boot, React / TypeScript, Zustand, MySQL (Flyway migrations)

---

### Task 1: 前端融合编辑器只使用三视图

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/stores/fusionStore.ts`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelToolbar.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/CharacterPalette.tsx`

- [ ] **Step 1: 简化 fusionStore.ts**

删除 `OverlaySourceType` 类型（第 4 行）、`overlaySourceType` 状态字段（第 26 行）、`setOverlaySourceType` 方法（第 76 行）。

`PanelOverlay.sourceType` 改为 `string`（固定值 `'threeView'`）。

`assignCharacterToPanel`（第 80-113 行）简化为：
```typescript
assignCharacterToPanel: (panelIndex, charIndex) => {
    const { gridInfo } = get();
    if (!gridInfo) return;
    const char = gridInfo.characterReferences[charIndex];
    if (!char) return;

    const url = char.threeViewGridUrl || char.expressionGridUrl || '';
    if (!url) return;

    const overlay: PanelOverlay = {
      characterName: char.characterName,
      sourceType: 'threeView',
      imageUrl: url,
      x: 0,
      y: 0,
      width: gridInfo.panelWidth,
      height: gridInfo.panelHeight,
      opacity: 0.7,
      scale: 0.3,
    };

    set((state) => {
      const newOverlays = new Map(state.panelOverlays);
      newOverlays.set(panelIndex, overlay);
      return { panelOverlays: newOverlays };
    });
  },
```

`reset` 中删除 `overlaySourceType: 'standard'`（第 53、142 行）。

`autoAssignCharacters`（第 164-183 行）简化：
```typescript
const url = char.threeViewGridUrl || char.expressionGridUrl || '';
if (!url) return;

overlays.set(panelIndex, {
  characterName: char.characterName,
  sourceType: 'threeView',
  imageUrl: url,
  ...
});
```

删除 `setOverlaySourceType` 从 interface 声明（第 39 行）。

- [ ] **Step 2: 简化 PanelToolbar.tsx**

删除图片源选择器区块（第 44-73 行：`{overlay && (` 中包含 `sourceSelector` 的整个 div）。

删除 `overlaySourceType` 和 `setOverlaySourceType` 从 store 解构（第 16 行）。

- [ ] **Step 3: 简化 CharacterPalette.tsx**

第 26 行的 `hasImages` 判断改为只用三视图：
```typescript
const hasImages = char.threeViewGridUrl;
```

第 34-54 行的图片展示区域简化为只展示三视图：
```tsx
{char.threeViewGridUrl ? (
  <div className={styles.characterImages}>
    <div className={styles.characterImageItem}>
      <img src={char.threeViewGridUrl} alt="三视图" />
      <span className={styles.imageLabel}>三视图</span>
    </div>
  </div>
) : (
  <p className={styles.emptyHint}>无参考图</p>
)}
```

- [ ] **Step 4: 验证前端编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 5: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/stores/fusionStore.ts frontend/wiset_aivideo_generator/src/pages/create/steps/components/PanelToolbar.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/components/CharacterPalette.tsx
git commit -m "refactor: 融合编辑器简化为只使用三视图作为角色参考图"
```

---

### Task 2: 清理 standardImageUrl 死字段

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/entity/Character.java:23`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/response/CharacterStatusResponse.java:19`
- Modify: `backend/com/comic/src/main/java/com/comic/dto/response/GridInfoResponse.java:63`
- Modify: `backend/com/comic/src/main/java/com/comic/controller/CharacterController.java:161`
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/EpisodeProductionService.java:542`
- Modify: `backend/com/comic/src/main/resources/schema.sql:81`
- Create: `backend/com/comic/src/main/resources/db/migration/V11__remove_standard_image_url.sql`
- Modify: `frontend/wiset_aivideo_generator/src/services/types/episode.types.ts:39`
- Modify: `frontend/wiset_aivideo_generator/src/services/types/project.types.ts:238`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step4page.tsx:391`

- [ ] **Step 1: 后端删除 standardImageUrl**

`Character.java` 第 23 行：删除 `private String standardImageUrl;  // 标准形象图URL`

`CharacterStatusResponse.java` 第 19 行：删除 `private String standardImageUrl;         // 标准形象图URL`

`GridInfoResponse.java` 第 63 行：删除 `private String standardImageUrl;`

`CharacterController.java` 第 161 行：删除 `dto.setStandardImageUrl(character.getStandardImageUrl());`

`EpisodeProductionService.java` 第 542 行：删除 `info.setStandardImageUrl(c.getStandardImageUrl());`

- [ ] **Step 2: 创建数据库迁移脚本**

Create `backend/com/comic/src/main/resources/db/migration/V11__remove_standard_image_url.sql`:
```sql
ALTER TABLE t_character DROP COLUMN IF EXISTS standard_image_url;
```

- [ ] **Step 3: 更新 schema.sql**

`schema.sql` 第 81 行：删除 `standard_image_url TEXT,`

- [ ] **Step 4: 前端删除 standardImageUrl**

`episode.types.ts` 第 39 行：删除 `standardImageUrl: string | null;`

`project.types.ts` 第 238 行 `CharacterStatus`：删除 `standardImageUrl: string;`

`Step4page.tsx` 第 391 行：将 avatar 显示改为 fallback 逻辑：
```tsx
{st?.threeViewGridUrl ? <img src={st.threeViewGridUrl} alt={char.draft.name} /> : <span>{char.draft.name.charAt(0)}</span>}
```

- [ ] **Step 5: 验证后端编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: 验证前端编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 7: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/entity/Character.java backend/com/comic/src/main/java/com/comic/dto/response/CharacterStatusResponse.java backend/com/comic/src/main/java/com/comic/dto/response/GridInfoResponse.java backend/com/comic/src/main/java/com/comic/controller/CharacterController.java backend/com/comic/src/main/java/com/comic/service/production/EpisodeProductionService.java backend/com/comic/src/main/resources/schema.sql backend/com/comic/src/main/resources/db/migration/V11__remove_standard_image_url.sql frontend/wiset_aivideo_generator/src/services/types/episode.types.ts frontend/wiset_aivideo_generator/src/services/types/project.types.ts frontend/wiset_aivideo_generator/src/pages/create/steps/Step4page.tsx
git commit -m "refactor: 清理 standardImageUrl 死字段（后端+前端+DB迁移）"
```

---

### Task 3: 清理未使用的英文 prompt 方法

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PromptBuilder.java:527-583`

- [ ] **Step 1: 删除 PromptBuilder.java 中的英文方法**

删除第 527-583 行（整个"角色图片生成提示词方法"区块），包括：
- 注释 `// ================= 角色图片生成提示词方法 =================`（第 527 行）
- `buildExpressionPrompt` 方法（第 529-547 行）
- `buildThreeViewPrompt` 方法（第 549-582 行）
- 空行（第 583 行）

- [ ] **Step 2: 验证无其他引用**

Run: `grep -rn "buildExpressionPrompt\|buildThreeViewPrompt" backend/`
Expected: 只在 `promptManager.md`（文档）中有提及，无代码引用

- [ ] **Step 3: 验证后端编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PromptBuilder.java
git commit -m "refactor: 清理 PromptBuilder 中未使用的英文 prompt 方法"
```
