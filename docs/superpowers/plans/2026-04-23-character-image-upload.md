# 角色图片手动上传功能 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在角色与素材页面配置阶段增加用户手动上传三视图/表情图功能，上传的图片存 OSS 并与 AI 生图走相同状态路径。

**Architecture:** 后端新增 2 个 upload 端点（CharacterController → CharacterImageGenerationService → OssService），前端新增 2 个 API 函数 + 配置表单中增加上传按钮。

**Tech Stack:** Spring Boot (MultipartFile), React + TypeScript, 阿里云 OSS

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `backend/com/comic/src/main/java/com/comic/controller/CharacterController.java` | Modify | 新增 2 个 upload 端点 |
| `backend/com/comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java` | Modify | 新增 `uploadUserThreeView` / `uploadUserExpression` 方法 |
| `frontend/wiset_aivideo_generator/src/services/characterService.ts` | Modify | 新增 `uploadThreeView` / `uploadExpression` 函数 |
| `frontend/wiset_aivideo_generator/src/pages/create/steps/Step3Merged.tsx` | Modify | 配置表单增加上传按钮 |
| `frontend/wiset_aivideo_generator/src/pages/create/steps/Step3Merged.module.less` | Modify | 上传按钮样式 |

---

### Task 1: 后端 — 新增 upload 方法到 CharacterImageGenerationService

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java`

- [ ] **Step 1: 在 CharacterImageGenerationService 中新增 uploadUserThreeView 方法**

在 `generateThreeViewSheet` 方法之后新增：

```java
@Transactional
public void uploadUserThreeView(String projectId, String charId, MultipartFile file) {
    Character character = characterRepository.findByCharId(charId);
    if (character == null) {
        throw new BusinessException("角色不存在: " + charId);
    }
    // 归属校验
    if (!projectId.equals(character.getProjectId())) {
        throw new BusinessException("角色不属于该项目");
    }
    // 锁定校验
    if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IMAGES_LOCKED))) {
        throw new BusinessException("角色图片已锁定，无法上传");
    }
    // 生成中校验
    if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_THREE_VIEW))) {
        throw new BusinessException("三视图正在生成中，请稍后");
    }
    // 文件校验
    validateImageFile(file);

    progressService.clearError(projectId);
    String ossUrl = ossService.uploadMultipartFile(file, "character");
    log.info("用户上传三视图: charId={}, url={}", charId, ossUrl);

    Map<String, Object> info = ensureCharInfo(character);
    info.put(CharacterInfoKeys.THREE_VIEW_GRID_URL, ossUrl);
    info.put(CharacterInfoKeys.THREE_VIEW_STATUS, "COMPLETED");
    info.put(CharacterInfoKeys.IS_GENERATING_THREE_VIEW, false);
    info.remove(CharacterInfoKeys.THREE_VIEW_ERROR);
    info.remove(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT);

    // 推进 charStatus
    if (isCharacterImageComplete(character)) {
        info.put(CharacterInfoKeys.CHAR_STATUS, "review");
        info.put(CharacterInfoKeys.CONFIRMED, true);
    }
    characterRepository.updateById(character);

    // 重建 compositeReferenceUrl（主角/反派）
    rebuildCompositeIfNeeded(character);

    checkAndAdvanceProjectState(character);
}
```

- [ ] **Step 2: 新增 uploadUserExpression 方法**

紧接上一步之后：

```java
@Transactional
public void uploadUserExpression(String projectId, String charId, MultipartFile file) {
    Character character = characterRepository.findByCharId(charId);
    if (character == null) {
        throw new BusinessException("角色不存在: " + charId);
    }
    if (!projectId.equals(character.getProjectId())) {
        throw new BusinessException("角色不属于该项目");
    }
    if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IMAGES_LOCKED))) {
        throw new BusinessException("角色图片已锁定，无法上传");
    }
    if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_EXPRESSION))) {
        throw new BusinessException("表情图正在生成中，请稍后");
    }
    // 配角拦截
    if ("配角".equals(getCharInfoStr(character, CharacterInfoKeys.ROLE))) {
        throw new BusinessException("配角不需要上传表情图");
    }
    validateImageFile(file);

    progressService.clearError(projectId);
    String ossUrl = ossService.uploadMultipartFile(file, "character");
    log.info("用户上传表情图: charId={}, url={}", charId, ossUrl);

    Map<String, Object> info = ensureCharInfo(character);
    info.put(CharacterInfoKeys.EXPRESSION_GRID_URL, ossUrl);
    info.put(CharacterInfoKeys.EXPRESSION_STATUS, "COMPLETED");
    info.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, false);
    info.remove(CharacterInfoKeys.EXPRESSION_ERROR);
    info.remove(CharacterInfoKeys.EXPRESSION_GRID_PROMPT);

    if (isCharacterImageComplete(character)) {
        info.put(CharacterInfoKeys.CHAR_STATUS, "review");
        info.put(CharacterInfoKeys.CONFIRMED, true);
    }
    characterRepository.updateById(character);

    rebuildCompositeIfNeeded(character);
    checkAndAdvanceProjectState(character);
}
```

- [ ] **Step 3: 新增 validateImageFile 和 rebuildCompositeIfNeeded 辅助方法**

```java
private static final long MAX_UPLOAD_SIZE = 10 * 1024 * 1024; // 10MB

private void validateImageFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
        throw new BusinessException("上传文件不能为空");
    }
    if (file.getSize() > MAX_UPLOAD_SIZE) {
        throw new BusinessException("文件大小不能超过 10MB");
    }
    try {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        if (img == null) {
            throw new BusinessException("文件不是有效的图片格式（仅支持 JPG/PNG/WebP）");
        }
    } catch (BusinessException e) {
        throw e;
    } catch (Exception e) {
        throw new BusinessException("文件不是有效的图片格式（仅支持 JPG/PNG/WebP）");
    }
}

private void rebuildCompositeIfNeeded(Character character) {
    String role = getCharInfoStr(character, CharacterInfoKeys.ROLE);
    if ("配角".equals(role)) return;

    String threeViewUrl = getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_GRID_URL);
    String expressionUrl = getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_GRID_URL);
    if (threeViewUrl == null || threeViewUrl.isEmpty() || expressionUrl == null || expressionUrl.isEmpty()) return;

    try {
        String compositeUrl = ossService.combineImagesVertical(threeViewUrl, expressionUrl);
        Map<String, Object> info = ensureCharInfo(character);
        info.put(CharacterInfoKeys.COMPOSITE_REFERENCE_URL, compositeUrl);
        characterRepository.updateById(character);
        log.info("重建 compositeReferenceUrl: charId={}", getCharInfoStr(character, CharacterInfoKeys.CHAR_ID));
    } catch (Exception e) {
        log.warn("重建 compositeReferenceUrl 失败，忽略: {}", e.getMessage());
    }
}
```

- [ ] **Step 4: 在 CharacterImageGenerationService 中注入 OssService**

该类使用 `@RequiredArgsConstructor`（final 字段构造器注入）+ `@Lazy @Autowired`（懒加载字段）模式。`OssService` 目前未注入，需要新增：

```java
// 在现有 final 字段之后添加（第 42 行 ApplicationContext 之后）
private final OssService ossService;
```

同时在文件顶部添加 import：
```java
import com.comic.service.oss.OssService;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
```

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java
git commit -m "feat(character): add upload methods for three-view and expression images"
```

---

### Task 2: 后端 — 新增 Controller 端点

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/CharacterController.java`

- [ ] **Step 1: 在 CharacterController 中新增 2 个 upload 端点**

在现有的 `retryGeneration` 端点之后添加：

```java
@PostMapping("/{charId}/upload/three-view")
@Operation(summary = "上传三视图图片", description = "用户手动上传三视图图片替代 AI 生成")
public Result<Void> uploadThreeView(
        @PathVariable String projectId,
        @PathVariable String charId,
        @RequestParam("file") MultipartFile file) {
    characterImageGenerationService.uploadUserThreeView(projectId, charId, file);
    return Result.ok();
}

@PostMapping("/{charId}/upload/expression")
@Operation(summary = "上传表情图图片", description = "用户手动上传九宫格表情图替代 AI 生成")
public Result<Void> uploadExpression(
        @PathVariable String projectId,
        @PathVariable String charId,
        @RequestParam("file") MultipartFile file) {
    characterImageGenerationService.uploadUserExpression(projectId, charId, file);
    return Result.ok();
}
```

确保文件顶部有 `import org.springframework.web.multipart.MultipartFile;`

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/CharacterController.java
git commit -m "feat(character): add upload endpoints for three-view and expression images"
```

---

### Task 3: 前端 — 新增 API 函数

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/characterService.ts`

- [ ] **Step 1: 在 characterService.ts 中新增 uploadThreeView 和 uploadExpression 函数**

在文件末尾（`generateImage` 函数之后）添加：

```typescript
/**
 * 上传三视图图片（替代 AI 生成）
 */
export async function uploadThreeView(
  projectId: string,
  charId: string,
  file: File
): Promise<ApiResponse<void>> {
  const formData = new FormData();
  formData.append('file', file);
  return post(`/api/projects/${projectId}/characters/${charId}/upload/three-view`, formData);
}

/**
 * 上传表情图（替代 AI 生成）
 */
export async function uploadExpression(
  projectId: string,
  charId: string,
  file: File
): Promise<ApiResponse<void>> {
  const formData = new FormData();
  formData.append('file', file);
  return post(`/api/projects/${projectId}/characters/${charId}/upload/expression`, formData);
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/characterService.ts
git commit -m "feat(character): add upload API functions for three-view and expression"
```

---

### Task 4: 前端 — Step3Merged UI 改动

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step3Merged.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step3Merged.module.less`

- [ ] **Step 1: 在 Step3Merged.tsx 中导入 uploadThreeView 和 uploadExpression**

在文件顶部 import 区域，找到 `from '../../../services/characterService'`，在导入列表中添加 `uploadThreeView` 和 `uploadExpression`。

- [ ] **Step 2: 新增 handleUpload 函数**

在 `handleRetryChar` 方法之后添加：

```typescript
const handleUpload = async (charId: string, type: 'threeView' | 'expression') => {
  if (!projectId) return;

  // 创建隐藏 file input 触发文件选择
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/png,image/jpeg,image/webp';
  input.onchange = async (e) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) {
      alert('文件大小不能超过 10MB');
      return;
    }
    setGeneratingIds(prev => new Set(prev).add(charId));
    try {
      if (type === 'threeView') {
        await uploadThreeView(projectId, charId, file);
      } else {
        await uploadExpression(projectId, charId, file);
      }
      loadCharacters();
    } catch (err: any) {
      alert(err.message || '上传失败');
      setGeneratingIds(prev => { const next = new Set(prev); next.delete(charId); return next; });
      loadCharacters();
    }
  };
  input.click();
};
```

- [ ] **Step 3: 修改 renderConfigForm — 在「生成素材」按钮旁添加上传按钮**

找到 `renderConfigForm` 中的按钮区域（`<button className={styles.startButton}` 之后），在"生成素材"按钮后面添加两个上传按钮：

```tsx
<button className={styles.uploadBtn} onClick={() => handleUpload(char.charId, 'threeView')} disabled={generatingIds.has(char.charId)}>
  上传三视图
</button>
{char.role !== '配角' && (
  <button className={styles.uploadBtn} onClick={() => handleUpload(char.charId, 'expression')} disabled={generatingIds.has(char.charId)}>
    上传表情图
  </button>
)}
```

- [ ] **Step 4: 在 Step3Merged.module.less 中添加上传按钮样式**

```less
.uploadBtn {
  padding: var(--space-2) var(--space-4);
  border: 1px dashed rgba(114, 183, 255, 0.5);
  border-radius: var(--radius-sm);
  background: rgba(114, 183, 255, 0.08);
  color: var(--color-accent);
  font-size: var(--font-size-sm);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--duration-fast) var(--easing-standard);
  white-space: nowrap;

  &:hover:not(:disabled) {
    background: rgba(114, 183, 255, 0.15);
    border-color: var(--color-accent);
  }

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}
```

- [ ] **Step 5: TypeScript 编译验证**

```bash
cd frontend/wiset_aivideo_generator && npx tsc --noEmit --pretty
```

- [ ] **Step 6: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step3Merged.tsx frontend/wiset_aivideo_generator/src/pages/create/steps/Step3Merged.module.less
git commit -m "feat(character): add upload buttons to character config form in Step3"
```

---

### Task 5: 集成验证

- [ ] **Step 1: 后端编译验证**

```bash
cd backend/com && mvn compile -pl comic -q
```

- [ ] **Step 2: 前端编译验证**

```bash
cd frontend/wiset_aivideo_generator && npx tsc --noEmit --pretty
```

- [ ] **Step 3: 端到端流程验证（手动）**

1. 启动后端 + 前端
2. 进入项目的 Step3 角色与素材页面
3. 展开一个 configuring 状态的角色
4. 验证：应看到「生成素材」「上传三视图」「上传表情图」三个按钮
5. 点击「上传三视图」→ 选择一张图片 → 等待上传完成
6. 验证：角色状态应变为 review，显示上传的图片
7. 点击「驳回」→ 回到 configuring
8. 验证：又可以重新选择生成或上传
9. 测试配角：只显示「上传三视图」，不显示「上传表情图」
10. 测试锁定后上传：应被拒绝并提示"角色图片已锁定"
