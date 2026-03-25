# CharacterController 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 CharacterController，适配 RESTful 嵌套路径 `/api/projects/{projectId}/characters`，新增逻辑删除、分页筛选、voice 字段。

**Architecture:** Controller → Service → Repository 三层架构不变。Character 实体新增 `deleted` 字段用于逻辑删除。分页使用 MyBatis Plus `IPage` + `PaginatedResponse`。路径从 `/api/characters` 迁移到 `/api/projects/{projectId}/characters`。

**Tech Stack:** Spring Boot, MyBatis Plus, Swagger/OpenAPI, Lombok

**Spec:** `docs/superpowers/specs/2026-03-25-character-controller-design.md`

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `entity/Character.java` | 修改 | 新增 `deleted` 字段 |
| `common/CharacterInfoKeys.java` | 修改 | 新增 `PERSONALITY` 常量，`VOICE` 已存在 |
| `dto/model/CharacterDraftModel.java` | 修改 | 新增 `voice` 字段 |
| `dto/response/CharacterListItemResponse.java` | 新建 | 列表查询返回的精简 DTO |
| `dto/request/CharacterUpdateRequest.java` | 新建 | 更新角色请求 DTO |
| `repository/CharacterRepository.java` | 修改 | 新增分页查询、逻辑删除、按条件筛选方法 |
| `service/character/CharacterExtractService.java` | 修改 | 适配 voice 字段、逻辑删除、projectId 路径参数 |
| `service/character/CharacterImageGenerationService.java` | 修改 | 移除 controller 层辅助方法 |
| `controller/CharacterController.java` | 重写 | 全部接口迁移到新路径 |

---

### Task 1: Character 实体新增 `deleted` 字段

**Files:**
- Modify: `src/main/java/com/comic/entity/Character.java`

- [ ] **Step 1: 在 Character 实体中新增 `deleted` 字段**

在 `status` 字段下方新增：

```java
@TableLogic
private Boolean deleted = false;
```

`@TableLogic` 是 MyBatis Plus 的逻辑删除注解，查询时自动过滤 `deleted = true` 的记录。

- [ ] **Step 2: 确认数据库表已添加 `deleted` 列**

检查 `character` 表是否有 `deleted` 列（TINYINT/BOOLEAN 类型，默认 0）。如果没有，需要执行：

```sql
ALTER TABLE `character` ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/comic/entity/Character.java
git commit -m "feat: Character 实体新增 deleted 逻辑删除字段"
```

---

### Task 2: CharacterInfoKeys 和 CharacterDraftModel 新增字段

**Files:**
- Modify: `src/main/java/com/comic/common/CharacterInfoKeys.java`
- Modify: `src/main/java/com/comic/dto/model/CharacterDraftModel.java`

- [ ] **Step 1: 在 CharacterInfoKeys 中新增 PERSONALITY 常量**

`VOICE` 已存在，新增 `PERSONALITY` 替代散落的 `"personality"` 字符串：

```java
public static final String PERSONALITY = "personality";
```

放在 `ROLE` 和 `VOICE` 之间。

- [ ] **Step 2: 在 CharacterDraftModel 中新增 voice 字段**

在 `personality` 字段下方新增：

```java
private String voice;              // 声音描述
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/comic/common/CharacterInfoKeys.java src/main/java/com/comic/dto/model/CharacterDraftModel.java
git commit -m "feat: 新增 PERSONALITY 常量和 voice 字段"
```

---

### Task 3: 新建 DTO 类

**Files:**
- Create: `src/main/java/com/comic/dto/response/CharacterListItemResponse.java`
- Create: `src/main/java/com/comic/dto/request/CharacterUpdateRequest.java`

- [ ] **Step 1: 创建 CharacterListItemResponse**

列表查询返回的精简 DTO，不包含生成状态等详细信息：

```java
package com.comic.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CharacterListItemResponse {
    private String charId;
    private String name;
    private String role;
    private String personality;
    private String voice;
    private String appearance;
    private String visualStyle;
    private String expressionStatus;
    private String threeViewStatus;
    private Boolean confirmed;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 创建 CharacterUpdateRequest**

更新角色的请求 DTO，支持部分更新：

```java
package com.comic.dto.request;

import lombok.Data;

@Data
public class CharacterUpdateRequest {
    private String name;
    private String personality;
    private String voice;
    private String appearance;
    private String background;
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/comic/dto/response/CharacterListItemResponse.java src/main/java/com/comic/dto/request/CharacterUpdateRequest.java
git commit -m "feat: 新增 CharacterListItemResponse 和 CharacterUpdateRequest DTO"
```

---

### Task 4: CharacterRepository 新增分页和删除方法

**Files:**
- Modify: `src/main/java/com/comic/repository/CharacterRepository.java`

- [ ] **Step 1: 新增分页查询方法**

```java
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

default IPage<Character> findPageByProjectId(String projectId, String role, String name, IPage<Character> page) {
    LambdaQueryWrapper<Character> wrapper = new LambdaQueryWrapper<Character>()
        .eq(Character::getProjectId, projectId);
    if (role != null && !role.isEmpty()) {
        wrapper.apply("JSON_EXTRACT(character_info, '$.role') = {0}", role);
    }
    if (name != null && !name.isEmpty()) {
        wrapper.like("character_info", name);  // JSON 内模糊搜索
    }
    return selectPage(page, wrapper);
}
```

注意：`@TableLogic` 会自动在查询条件中加上 `AND deleted = 0`，无需手动过滤。

- [ ] **Step 2: 新增逻辑删除方法**

由于使用了 `@TableLogic`，直接调用 MyBatis Plus 内置的 `deleteById` 即可实现逻辑删除。无需额外方法。

但为了显式语义，保留 `deleteByProjectId`（逻辑删除项目下所有角色）：

```java
default void logicalDeleteByProjectId(String projectId) {
    delete(new LambdaQueryWrapper<Character>()
        .eq(Character::getProjectId, projectId));
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/comic/repository/CharacterRepository.java
git commit -m "feat: CharacterRepository 新增分页查询和逻辑删除方法"
```

---

### Task 5: CharacterExtractService 适配

**Files:**
- Modify: `src/main/java/com/comic/service/character/CharacterExtractService.java`

- [ ] **Step 1: AI 提取 prompt 中新增 voice 字段**

修改 `extractCharacters` 方法中的 `userPrompt`，将 voice 加入提取要求：

```java
// 修改提示词中的字段要求
+ "3. 每个角色必须包含：name(姓名), role(角色定位), personality(性格), voice(声音特点), appearance(外貌), background(背景)\n"
+ "4. 所有字段都必须有值，不能为null\n"
+ "5. role只能是：主角、反派、配角\n"
+ "6. 返回格式示例：[{\"name\":\"张三\",\"role\":\"主角\",\"personality\":\"勇敢\",\"voice\":\"沉稳男声\",\"appearance\":\"英俊\",\"background\":\"孤儿\"}]\n\n"
```

- [ ] **Step 2: parseCharacters 方法中提取 voice 字段**

```java
dto.setVoice(getStringValue(data, "voice"));
```

- [ ] **Step 3: saveCharacters 方法中保存 voice 字段**

```java
info.put(CharacterInfoKeys.VOICE, dto.getVoice());
```

同时将 `"personality"` 替换为 `CharacterInfoKeys.PERSONALITY`。

- [ ] **Step 4: updateCharacter 方法适配**

使用 `CharacterUpdateRequest` 替代 `CharacterDraftModel`，支持部分更新：

```java
@Transactional
public void updateCharacter(String charId, CharacterUpdateRequest dto) {
    Character character = characterRepository.findByCharId(charId);
    if (character == null) {
        throw new BusinessException("角色不存在");
    }
    // 校验项目状态
    Project project = projectRepository.findByProjectId(character.getProjectId());
    if (project == null || !ProjectStatus.CHARACTER_REVIEW.getCode().equals(project.getStatus())) {
        throw new BusinessException("当前状态不能编辑角色");
    }

    Map<String, Object> info = character.getCharacterInfo();
    if (info == null) info = new HashMap<>();
    if (dto.getName() != null) info.put(CharacterInfoKeys.NAME, dto.getName());
    if (dto.getPersonality() != null) info.put(CharacterInfoKeys.PERSONALITY, dto.getPersonality());
    if (dto.getVoice() != null) info.put(CharacterInfoKeys.VOICE, dto.getVoice());
    if (dto.getAppearance() != null) info.put(CharacterInfoKeys.APPEARANCE, dto.getAppearance());
    if (dto.getBackground() != null) info.put(CharacterInfoKeys.BACKGROUND, dto.getBackground());
    character.setCharacterInfo(info);
    characterRepository.updateById(character);
    log.info("角色已更新: charId={}", charId);
}
```

- [ ] **Step 5: getProjectCharacters 改为分页查询**

新增方法返回分页结果：

```java
public PaginatedResponse<CharacterListItemResponse> getProjectCharactersPage(
        String projectId, String role, String name, int page, int size) {
    IPage<Character> charPage = characterRepository.findPageByProjectId(
        projectId, role, name, new Page<>(page, size));
    List<CharacterListItemResponse> items = new ArrayList<>();
    for (Character character : charPage.getRecords()) {
        items.add(toListItemResponse(character));
    }
    return PaginatedResponse.of(items, charPage.getTotal(), (int) charPage.getCurrent(), (int) charPage.getSize());
}

private CharacterListItemResponse toListItemResponse(Character character) {
    Map<String, Object> info = character.getCharacterInfo();
    CharacterListItemResponse dto = new CharacterListItemResponse();
    dto.setCharId(getInfoStr(info, CharacterInfoKeys.CHAR_ID));
    dto.setName(getInfoStr(info, CharacterInfoKeys.NAME));
    dto.setRole(getInfoStr(info, CharacterInfoKeys.ROLE));
    dto.setPersonality(getInfoStr(info, CharacterInfoKeys.PERSONALITY));
    dto.setVoice(getInfoStr(info, CharacterInfoKeys.VOICE));
    dto.setAppearance(getInfoStr(info, CharacterInfoKeys.APPEARANCE));
    dto.setVisualStyle(getInfoStr(info, CharacterInfoKeys.VISUAL_STYLE));
    dto.setExpressionStatus(getInfoStr(info, CharacterInfoKeys.EXPRESSION_STATUS));
    dto.setThreeViewStatus(getInfoStr(info, CharacterInfoKeys.THREE_VIEW_STATUS));
    dto.setConfirmed(getInfoBool(info, CharacterInfoKeys.CONFIRMED));
    dto.setCreatedAt(character.getCreatedAt());
    return dto;
}
```

- [ ] **Step 6: 新增逻辑删除方法**

```java
@Transactional
public void deleteCharacter(String charId) {
    Character character = characterRepository.findByCharId(charId);
    if (character == null) {
        throw new BusinessException("角色不存在");
    }
    Project project = projectRepository.findByProjectId(character.getProjectId());
    if (project == null || !ProjectStatus.CHARACTER_REVIEW.getCode().equals(project.getStatus())) {
        throw new BusinessException("当前状态不能删除角色");
    }
    characterRepository.deleteById(character.getId());
    log.info("角色已删除: charId={}", charId);
}
```

- [ ] **Step 7: 全局替换 `"personality"` 为 `CharacterInfoKeys.PERSONALITY`**

在 `CharacterExtractService.java` 中所有 `"personality"` 字符串替换为 `CharacterInfoKeys.PERSONALITY`。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/comic/service/character/CharacterExtractService.java
git commit -m "feat: CharacterExtractService 适配 voice 字段、分页查询、逻辑删除"
```

---

### Task 6: CharacterImageGenerationService 适配

**Files:**
- Modify: `src/main/java/com/comic/service/character/CharacterImageGenerationService.java`

- [ ] **Step 1: 将 getGenerationStatus 逻辑从 Controller 迁移到 Service**

新增方法，将 Controller 中 `getGenerationStatus` 的 Map → DTO 转换逻辑移入 Service：

```java
public CharacterStatusResponse getGenerationStatus(String charId) {
    Character character = characterRepository.findByCharId(charId);
    if (character == null) {
        throw new BusinessException("角色不存在");
    }
    Map<String, Object> info = character.getCharacterInfo();
    CharacterStatusResponse dto = new CharacterStatusResponse();
    dto.setCharId(getCharInfoStr(character, CharacterInfoKeys.CHAR_ID));
    dto.setName(getCharInfoStr(character, CharacterInfoKeys.NAME));
    dto.setRole(getCharInfoStr(character, CharacterInfoKeys.ROLE));
    dto.setExpressionStatus(getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_STATUS));
    dto.setThreeViewStatus(getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_STATUS));
    dto.setExpressionError(getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_ERROR));
    dto.setThreeViewError(getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_ERROR));
    dto.setIsGeneratingExpression(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_EXPRESSION));
    dto.setIsGeneratingThreeView(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_THREE_VIEW));
    dto.setVisualStyle(getCharInfoStr(character, CharacterInfoKeys.VISUAL_STYLE));
    dto.setExpressionGridUrl(getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_GRID_URL));
    dto.setThreeViewGridUrl(getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_GRID_URL));
    return dto;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/comic/service/character/CharacterImageGenerationService.java
git commit -m "feat: CharacterImageGenerationService 新增 getGenerationStatus 方法"
```

---

### Task 7: 重写 CharacterController

**Files:**
- Rewrite: `src/main/java/com/comic/controller/CharacterController.java`

- [ ] **Step 1: 重写 CharacterController**

完整替换为以下内容：

```java
package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.model.CharacterDraftModel;
import com.comic.dto.request.CharacterUpdateRequest;
import com.comic.dto.response.CharacterListItemResponse;
import com.comic.dto.response.CharacterStatusResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/characters")
@RequiredArgsConstructor
@Tag(name = "角色管理")
@SecurityRequirement(name = "bearerAuth")
public class CharacterController {

    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;

    // ================= CRUD 接口 =================

    @GetMapping
    @Operation(summary = "获取项目角色列表（分页）")
    public Result<PaginatedResponse<CharacterListItemResponse>> getProjectCharacters(
            @PathVariable String projectId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "角色类型筛选") @RequestParam(required = false) String role,
            @Parameter(description = "角色名称模糊搜索") @RequestParam(required = false) String name) {
        return Result.ok(characterExtractService.getProjectCharactersPage(projectId, role, name, page, size));
    }

    @GetMapping("/{charId}")
    @Operation(summary = "获取角色详情")
    public Result<CharacterStatusResponse> getCharacterDetail(
            @PathVariable String projectId,
            @PathVariable String charId) {
        return Result.ok(characterImageGenerationService.getGenerationStatus(charId));
    }

    @PostMapping("/extract")
    @Operation(summary = "提取角色", description = "从剧本中自动提取角色信息")
    public Result<List<CharacterDraftModel>> extractCharacters(@PathVariable String projectId) {
        List<CharacterDraftModel> characters = characterExtractService.extractCharacters(projectId);
        return Result.ok(characters);
    }

    @PutMapping("/{charId}")
    @Operation(summary = "更新角色信息")
    public Result<Void> updateCharacter(
            @PathVariable String projectId,
            @PathVariable String charId,
            @RequestBody CharacterUpdateRequest dto) {
        characterExtractService.updateCharacter(charId, dto);
        return Result.ok();
    }

    @DeleteMapping("/{charId}")
    @Operation(summary = "删除角色（逻辑删除）")
    public Result<Void> deleteCharacter(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterExtractService.deleteCharacter(charId);
        return Result.ok();
    }

    // ================= 确认接口 =================

    @PostMapping("/confirm")
    @Operation(summary = "确认角色", description = "确认项目的所有角色，锁定角色数据")
    public Result<Void> confirmCharacters(@PathVariable String projectId) {
        characterExtractService.confirmCharacters(projectId);
        return Result.ok();
    }

    // ================= 图片生成接口 =================

    @PostMapping("/{charId}/generate/expression")
    @Operation(summary = "生成九宫格表情")
    public Result<Void> generateExpression(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterImageGenerationService.generateExpressionSheet(charId);
        return Result.ok();
    }

    @PostMapping("/{charId}/generate/three-view")
    @Operation(summary = "生成三视图")
    public Result<Void> generateThreeView(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterImageGenerationService.generateThreeViewSheet(charId);
        return Result.ok();
    }

    @PostMapping("/{charId}/generate/all")
    @Operation(summary = "一键生成（表情+三视图）")
    public Result<Void> generateAll(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterImageGenerationService.generateAll(charId);
        return Result.ok();
    }

    @PutMapping("/{charId}/visual-style")
    @Operation(summary = "设置视觉风格")
    public Result<Void> setVisualStyle(
            @PathVariable String projectId,
            @PathVariable String charId,
            @RequestBody Map<String, String> body) {
        String visualStyle = body.get("visualStyle");
        characterImageGenerationService.setVisualStyle(charId, visualStyle);
        return Result.ok();
    }

    @PostMapping("/{charId}/retry/{type}")
    @Operation(summary = "重试生成")
    public Result<Void> retryGeneration(
            @PathVariable String projectId,
            @PathVariable String charId,
            @PathVariable String type) {
        characterImageGenerationService.retryGeneration(charId, type);
        return Result.ok();
    }

    @GetMapping("/{charId}/status")
    @Operation(summary = "获取生成状态")
    public Result<CharacterStatusResponse> getGenerationStatus(
            @PathVariable String projectId,
            @PathVariable String charId) {
        return Result.ok(characterImageGenerationService.getGenerationStatus(charId));
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/comic/controller/CharacterController.java
git commit -m "feat: 重写 CharacterController — RESTful 嵌套路径、新增删除/分页接口"
```

---

### Task 8: 检查其他引用 CharacterExtractService 的代码

**Files:**
- 搜索全局引用 `CharacterExtractService.getProjectCharacters` 和 `CharacterExtractService.updateCharacter(String, CharacterDraftModel)` 的位置

- [ ] **Step 1: 全局搜索旧方法签名**

```bash
cd D:/wiset/Wiset_Aivideo_Genetator/backend/com/comic
grep -rn "getProjectCharacters\|updateCharacter" src/ --include="*.java"
```

检查是否有其他 Service/Controller 调用了旧方法签名，如有则同步更新。

- [ ] **Step 2: 修复所有引用后编译验证**

```bash
./mvnw compile -q
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "fix: 修复 CharacterExtractService 方法签名变更的引用"
```