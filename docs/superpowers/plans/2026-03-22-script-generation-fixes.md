# 剧本生成系统差距修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复剧本生成系统的 5 项功能差距：OutlineEditor 编辑不生效、确认按钮 UX、visualStyleNote 字段丢失、缺少批量生成、episodesPerChapter 最小值。

**Architecture:** 后端遵循现有 Controller → Service → Repository 分层模式，数据库使用 Flyway 迁移。前端遵循 React + TypeScript + LESS 模块化样式模式。所有修改在现有文件上进行，不新增文件。

**Tech Stack:** Java Spring Boot, MyBatis Plus, SQLite (Flyway), React, TypeScript, LESS

---

### Task 1: 数据库迁移 — episode 表新增 visualStyleNote 字段

**Files:**
- Create: `backend/com/comic/src/main/resources/db/migration/V10__add_episode_visual_style_note.sql`
- Modify: `backend/com/comic/src/main/java/com/comic/entity/Episode.java:22-24`
- Modify: `backend/com/comic/src/main/resources/schema.sql:54`

- [ ] **Step 1: 创建 Flyway 迁移文件**

文件路径：`backend/com/comic/src/main/resources/db/migration/V10__add_episode_visual_style_note.sql`

```sql
ALTER TABLE episode ADD COLUMN visual_style_note TEXT;
```

- [ ] **Step 2: 更新 Episode 实体类**

在 `Episode.java` 的 `continuityNote` 和 `chapterTitle` 之间（约第 23 行）添加：

```java
private String visualStyleNote;     // 视觉风格备注
```

- [ ] **Step 3: 更新 schema.sql（保持同步）**

在 `schema.sql` 第 54 行 `continuity_note TEXT,` 之后添加：

```sql
    visual_style_note TEXT,
```

- [ ] **Step 4: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/resources/db/migration/V10__add_episode_visual_style_note.sql \
        backend/com/comic/src/main/java/com/comic/entity/Episode.java \
        backend/com/comic/src/main/resources/schema.sql
git commit -m "feat(db): add visual_style_note column to episode table"
```

---

### Task 2: 后端 — ScriptService 提取并保存 visualStyleNote

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java:566-575`

- [ ] **Step 1: 在 parseAndSaveEpisodes 中提取 visualStyleNote**

在 `ScriptService.java` 的 `parseAndSaveEpisodes` 方法中，在设置 `continuityNote` 之后（约第 574 行），添加：

```java
episode.setVisualStyleNote(getJsonText(episodeNode, "visualStyleNote", ""));
```

最终该代码块应为：
```java
episode.setTitle(getJsonText(episodeNode, "title", "第" + (episodeNum - 1) + "集"));
episode.setContent(getJsonText(episodeNode, "content", ""));
episode.setCharacters(getJsonText(episodeNode, "characters", ""));
episode.setKeyItems(getJsonText(episodeNode, "keyItems", ""));
episode.setContinuityNote(getJsonText(episodeNode, "continuityNote", ""));
episode.setVisualStyleNote(getJsonText(episodeNode, "visualStyleNote", ""));
episode.setChapterTitle(chapterTitle);
```

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java
git commit -m "feat(script): extract and persist visualStyleNote from episode JSON"
```

---

### Task 3: 后端 — 新增直接保存大纲接口

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/ProjectController.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java`

- [ ] **Step 1: 在 ScriptService 中新增 updateScriptOutline 方法**

在 `ScriptService.java` 的 `reviseOutline` 方法之前（约第 244 行），添加：

```java
/**
 * 直接保存用户编辑的大纲（不触发 AI 重新生成）
 * 保存后会删除所有已生成剧集（大纲变更导致剧集失效）
 */
@Transactional
public void updateScriptOutline(String projectId, String outline) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("项目不存在");
    }

    if (!STATUS_OUTLINE_REVIEW.equals(project.getStatus())) {
        throw new BusinessException("当前状态不能修改大纲");
    }

    project.setScriptOutline(outline);
    projectRepository.updateById(project);

    // 大纲变更，删除已生成的所有剧集
    episodeRepository.deleteByProjectId(projectId);

    log.info("大纲直接保存完成（已清除剧集）: projectId={}", projectId);
}
```

- [ ] **Step 2: 修改 reviseOutline 方法签名，新增 currentOutline 参数**

将 `reviseOutline` 方法签名从：
```java
public void reviseOutline(String projectId, String revisionNote) {
```
改为：
```java
public void reviseOutline(String projectId, String revisionNote, String currentOutline) {
```

并在方法体中，将 `buildScriptOutlineUserPrompt` 调用中的 `null`（world rules 参数）改为 `currentOutline`，这样 AI 会基于用户当前编辑的大纲版本重新生成：

```java
String userPrompt = promptBuilder.buildScriptOutlineUserPrompt(
    project.getStoryPrompt(),
    project.getGenre(),
    currentOutline,  // 使用用户当前编辑的大纲作为上下文
    project.getTotalEpisodes(),
    project.getEpisodeDuration() != null ? project.getEpisodeDuration() / 60 : 1,
    project.getVisualStyle() != null ? project.getVisualStyle() : "REAL"
);
```

- [ ] **Step 3: 在 ProjectController 中新增 PATCH 接口**

在 `reviseScript` 方法之后（约第 166 行），添加：

```java
/**
 * 直接保存用户编辑的大纲
 * PATCH /api/projects/{projectId}/script-outline
 */
@PatchMapping("/{projectId}/script-outline")
@Operation(summary = "保存大纲", description = "直接保存用户编辑的大纲内容，不触发 AI 重新生成。保存后会删除已生成的剧集。")
public Result<Void> updateScriptOutline(@PathVariable String projectId,
                                        @RequestBody Map<String, String> body) {
    String outline = body.get("outline");
    if (outline == null || outline.trim().isEmpty()) {
        return Result.fail("大纲内容不能为空");
    }
    scriptService.updateScriptOutline(projectId, outline.trim());
    return Result.ok();
}
```

- [ ] **Step 4: 修改 ProjectController 的 reviseScript 方法**

在 `reviseScript` 方法中，提取 `currentOutline` 参数并传递给 `reviseOutline`：

```java
String currentOutline = (String) body.get("currentOutline");
// ...
scriptService.reviseOutline(projectId, revisionNote, currentOutline);
```

- [ ] **Step 5: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/ProjectController.java \
        backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java
git commit -m "feat(script): add direct outline save API and pass currentOutline to revise"
```

---

### Task 4: 后端 — 新增批量生成全部剩余章节接口

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/ProjectController.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java`

- [ ] **Step 1: 在 ScriptService 中新增 generateAllEpisodes 方法**

在 `generateScriptEpisodes` 方法之后，添加：

```java
/**
 * 批量生成所有剩余章节的剧集
 * 按顺序生成，如果某章失败则停止
 */
@Transactional
public void generateAllEpisodes(String projectId) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("项目不存在");
    }

    if (!STATUS_OUTLINE_REVIEW.equals(project.getStatus()) &&
        !STATUS_SCRIPT_REVIEW.equals(project.getStatus())) {
        throw new BusinessException("当前状态不能生成分集，请先生成并确认大纲");
    }

    // 获取所有章节
    List<String> chapters = extractChaptersFromOutline(project.getScriptOutline());

    // 获取已生成的章节
    List<Episode> existingEpisodes = episodeRepository.findByProjectId(projectId);
    Set<String> generatedChapters = new HashSet<>();
    for (Episode ep : existingEpisodes) {
        if (ep.getChapterTitle() != null) {
            generatedChapters.add(ep.getChapterTitle());
        }
    }

    // 找出所有未生成的章节
    List<String> pendingChapters = new ArrayList<>();
    for (String chapter : chapters) {
        if (!generatedChapters.contains(chapter)) {
            pendingChapters.add(chapter);
        }
    }

    if (pendingChapters.isEmpty()) {
        throw new BusinessException("所有章节已生成，无需重复生成");
    }

    int episodeCount = project.getEpisodesPerChapter() != null ? project.getEpisodesPerChapter() : 4;

    // 按顺序生成每一章
    for (String chapter : pendingChapters) {
        try {
            generateScriptEpisodes(projectId, chapter, episodeCount, null);
        } catch (Exception e) {
            log.error("批量生成失败，停止在章节: {}", chapter, e);
            throw new BusinessException("批量生成在章节「" + chapter + "」处失败: " + e.getMessage());
        }
    }

    log.info("批量生成完成: projectId={}, 共生成 {} 章", projectId, pendingChapters.size());
}
```

- [ ] **Step 2: 在 ProjectController 中新增批量生成接口**

在新增的 PATCH 接口之后，添加：

```java
/**
 * 批量生成所有剩余章节的剧集
 * POST /api/projects/{projectId}/generate-all-episodes
 */
@PostMapping("/{projectId}/generate-all-episodes")
@Operation(summary = "批量生成全部章节", description = "按顺序生成所有剩余章节的剧集。如果某章生成失败则停止。")
public Result<Void> generateAllEpisodes(@PathVariable String projectId) {
    scriptService.generateAllEpisodes(projectId);
    return Result.ok();
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/ProjectController.java \
        backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java
git commit -m "feat(script): add batch episode generation for all remaining chapters"
```

---

### Task 5: 后端 — 修复 episodesPerChapter 最小值

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PromptBuilder.java:197-204`

- [ ] **Step 1: 修改 calculateScriptParameters 方法**

将现有的条件分支：
```java
if (totalEpisodes <= 3) {
    episodesPerChapter = 1;
} else if (totalEpisodes <= 10) {
```

改为：
```java
if (totalEpisodes <= 6) {
    episodesPerChapter = 2;
} else if (totalEpisodes <= 10) {
```

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PromptBuilder.java
git commit -m "fix(script): enforce minimum 2 episodes per chapter"
```

---

### Task 6: 前端 — 更新类型定义

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/project.types.ts:108-122`
- Modify: `frontend/wiset_aivideo_generator/src/services/projectService.ts`

- [ ] **Step 1: 在 Episode 接口中添加 visualStyleNote**

在 `project.types.ts` 的 `Episode` 接口中，`continuityNote` 之后（第 115 行），添加：

```typescript
visualStyleNote?: string;
```

- [ ] **Step 2: 在 projectService.ts 中新增两个 API 函数**

在 `confirmScript` 函数之后，添加：

```typescript
/**
 * 直接保存用户编辑的大纲
 * @param projectId 项目ID
 * @param outline 大纲内容
 */
export async function updateScriptOutline(
  projectId: string,
  outline: string
): Promise<ApiResponse<void>> {
  return patch<ApiResponse<void>>(`/api/projects/${projectId}/script-outline`, { outline });
}

/**
 * 批量生成所有剩余章节的剧集
 * @param projectId 项目ID
 */
export async function generateAllEpisodes(
  projectId: string
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/generate-all-episodes`);
}
```

注意：需要确认 `apiClient.ts` 中是否已导出 `patch` 方法。如果没有，需要在 `apiClient.ts` 中新增：

```typescript
export async function patch<T>(url: string, data?: any): Promise<T> {
  return request<T>(url, { method: 'PATCH', data });
}
```

并在 `projectService.ts` 的 import 中添加 `patch`。

- [ ] **Step 3: 编译验证**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/types/project.types.ts \
        frontend/wiset_aivideo_generator/src/services/projectService.ts
git commit -m "feat(frontend): add visualStyleNote type and new API functions"
```

---

### Task 7: 前端 — OutlineEditor 改为双按钮模式

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/OutlineEditor.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/OutlineEditor.module.less`

- [ ] **Step 1: 修改 OutlineEditor Props 接口**

将现有的 `onSave` 回调拆分为两个：

```typescript
interface OutlineEditorProps {
  outline: string;
  onSaveDirect?: (content: string) => void;    // 直接保存
  onSaveWithAI?: (content: string, revisionNote: string) => void;  // AI 重新生成
  readOnly?: boolean;
}
```

- [ ] **Step 2: 重写编辑模式的按钮区域**

将编辑模式（`isEditing === true`）的按钮从单个"保存修改"改为两个按钮：

```tsx
{isEditing ? (
  <>
    <button
      className={styles.cancelButton}
      onClick={handleCancel}
    >
      取消
    </button>
    <button
      className={styles.aiButton}
      onClick={handleAIRegenerate}
    >
      AI 重新生成
    </button>
    <button
      className={styles.saveButton}
      onClick={handleDirectSave}
    >
      保存修改
    </button>
  </>
) : (
```

- [ ] **Step 3: 重写保存处理函数**

将 `handleSave` 拆分为两个函数：

```typescript
const handleDirectSave = () => {
  onSaveDirect?.(content);
  setIsEditing(false);
};

const handleAIRegenerate = () => {
  const revisionNote = window.prompt('请输入修改意见（可选，留空则基于当前内容重新生成）：');
  if (revisionNote === null) return;  // 用户取消
  onSaveWithAI?.(content, revisionNote.trim());
  setIsEditing(false);
};
```

- [ ] **Step 4: 在 OutlineEditor.module.less 中新增 AI 按钮样式**

在 `.saveButton` 样式之后，添加 `.aiButton` 样式（与 `.saveButton` 类似但使用不同色调）：

```less
.aiButton {
  composes: editButton;
  background: rgba(168, 85, 247, 0.15);
  border: 1px solid rgba(168, 85, 247, 0.3);
  color: #c084fc;
  &:hover {
    background: rgba(168, 85, 247, 0.25);
  }
}
```

- [ ] **Step 5: 编译验证**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 6: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/OutlineEditor.tsx \
        frontend/wiset_aivideo_generator/src/pages/create/steps/components/OutlineEditor.module.less
git commit -m "feat(frontend): OutlineEditor dual-mode - direct save + AI regenerate"
```

---

### Task 8: 前端 — Step2page 集成新功能

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step2page.tsx`

- [ ] **Step 1: 更新 import 语句**

在 `projectService` 的 import 中添加 `updateScriptOutline` 和 `generateAllEpisodes`。

- [ ] **Step 2: 拆分 handleOutlineSave 为两个函数**

将现有的 `handleOutlineSave` 替换为：

```typescript
// 直接保存用户编辑的大纲
const handleOutlineSaveDirect = async (content: string) => {
  const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
  if (!projectId) {
    setError('无法获取项目 ID');
    return;
  }
  setIsLoading(true);
  try {
    await updateScriptOutline(projectId, content);
    const result = await getScript(projectId);
    if (isApiSuccess(result) && result.data) {
      setScriptData(result.data);
    }
  } catch (err) {
    console.error('保存大纲失败:', err);
    setError('保存大纲失败，请稍后重试');
  } finally {
    setIsLoading(false);
  }
};

// AI 重新生成大纲
const handleOutlineSaveWithAI = async (content: string, revisionNote: string) => {
  const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
  if (!projectId) {
    setError('无法获取项目 ID');
    return;
  }
  setIsLoading(true);
  try {
    await reviseScript(projectId, {
      revisionNote,
      currentOutline: content  // 传递用户当前编辑的大纲作为上下文
    });
    const result = await getScript(projectId);
    if (isApiSuccess(result) && result.data) {
      setScriptData(result.data);
    }
  } catch (err) {
    console.error('AI 重新生成失败:', err);
    setError('AI 重新生成失败，请稍后重试');
  } finally {
    setIsLoading(false);
  }
};
```

- [ ] **Step 3: 新增批量生成处理函数**

```typescript
const [isBatchGenerating, setIsBatchGenerating] = useState(false);

const handleGenerateAll = async () => {
  if (!scriptData || scriptData.pendingChapters.length === 0) return;
  const confirmed = window.confirm(
    `即将生成全部 ${scriptData.pendingChapters.length} 个剩余章节，这可能需要几分钟时间。是否继续？`
  );
  if (!confirmed) return;

  const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
  if (!projectId) {
    setError('无法获取项目 ID');
    return;
  }
  setIsBatchGenerating(true);
  setIsLoading(true);
  try {
    await generateAllEpisodes(projectId);
    const result = await getScript(projectId);
    if (isApiSuccess(result) && result.data) {
      setScriptData(result.data);
    }
  } catch (err) {
    console.error('批量生成失败:', err);
    setError('批量生成失败，请稍后重试或尝试逐章生成。');
  } finally {
    setIsBatchGenerating(false);
    setIsLoading(false);
  }
};
```

- [ ] **Step 4: 更新 OutlineEditor 组件的 props**

```tsx
<OutlineEditor
  outline={scriptData.outline}
  onSaveDirect={handleOutlineSaveDirect}
  onSaveWithAI={handleOutlineSaveWithAI}
/>
```

- [ ] **Step 5: 在 ChapterList 上方新增批量生成按钮**

在 `<ChapterList>` 组件之前，添加批量生成按钮：

```tsx
{scriptData.pendingChapters.length > 0 && (
  <div className={styles.batchAction}>
    <button
      className={styles.batchGenerateButton}
      onClick={handleGenerateAll}
      disabled={isBatchGenerating}
    >
      {isBatchGenerating ? '批量生成中...' : `一键生成全部剩余章节 (${scriptData.pendingChapters.length} 章)`}
    </button>
  </div>
)}
```

- [ ] **Step 6: 修改确认按钮逻辑**

将现有的确认按钮区域替换为：

```tsx
<div className={styles.buttonContainer}>
  <button
    className={styles.confirmButton}
    onClick={handleConfirm}
    disabled={isLoading || !scriptData || scriptData.pendingChapters.length > 0}
  >
    {!scriptData || scriptData.pendingChapters.length > 0
      ? `全部章节已生成后可确认 (剩余 ${scriptData?.pendingChapters.length || 0} 章)`
      : '确认剧本，进入下一步'}
  </button>
</div>
```

同时移除 `handleConfirm` 中的 `window.confirm` 未完成章节警告（第 191-194 行），因为按钮已禁用。

- [ ] **Step 7: 在 Step2page.module.less 中新增批量生成按钮样式**

```less
.batchAction {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.batchGenerateButton {
  padding: 10px 20px;
  background: rgba(59, 130, 246, 0.15);
  border: 1px solid rgba(59, 130, 246, 0.3);
  border-radius: 8px;
  color: #60a5fa;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover:not(:disabled) {
    background: rgba(59, 130, 246, 0.25);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}
```

- [ ] **Step 8: 编译验证**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 9: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step2page.tsx \
        frontend/wiset_aivideo_generator/src/pages/create/steps/Step2page.module.less
git commit -m "feat(frontend): integrate direct save, batch generate, and confirm button fix"
```

---

### Task 9: 前端 — EpisodeCard 展示 visualStyleNote

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ChapterList.tsx:180-193`

- [ ] **Step 1: 在 EpisodeCard 的 meta 区域中添加 visualStyleNote 展示**

在 `ChapterList.tsx` 的 `EpisodeCard` 组件中，在 `keyItems` meta item 之后（约第 192 行），添加：

```tsx
{episode.visualStyleNote && (
  <div className={styles.metaItem}>
    <span className={styles.metaLabel}>视觉风格：</span>
    <span className={styles.metaValue}>{episode.visualStyleNote}</span>
  </div>
)}
```

- [ ] **Step 2: 编译验证**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ChapterList.tsx
git commit -m "feat(frontend): display visualStyleNote in EpisodeCard"
```

---

### Task 10: 全量编译验证

- [ ] **Step 1: 后端编译**

Run: `cd backend/com/comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 前端编译**

Run: `cd frontend/wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 前端构建**

Run: `cd frontend/wiset_aivideo_generator && npm run build`
Expected: 构建成功
