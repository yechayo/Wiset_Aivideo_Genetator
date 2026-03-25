# Project Controller 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 ProjectController（拆分脚本接口到 ScriptController），三个实体统一 Map 存储，新增逻辑删除和状态回退。

**Architecture:** 4 阶段实施。阶段一处理实体和 Repository 基础设施；阶段二扩展 ProjectStatus 枚举和 DTO；阶段三重写所有 Service 层（核心，将字段访问改为 Map 访问）；阶段四重构 Controller。每阶段可独立编译验证。

**Tech Stack:** Spring Boot 2.7, MyBatis-Plus 3.5, Lombok, Swagger/OpenAPI, Jackson

**设计文档：** `docs/superpowers/specs/2026-03-25-project-controller-redesign.md`

---

## 文件结构

### 需修改的文件

| 文件 | 职责 |
|------|------|
| `comic/src/main/java/com/comic/entity/Project.java` | 新增 `deleted` 字段 |
| `comic/src/main/java/com/comic/repository/ProjectRepository.java` | 所有查询加 deleted=false，新增分页 |
| `comic/src/main/java/com/comic/common/ProjectStatus.java` | 新增导航方法 |
| `comic/src/main/java/com/comic/dto/response/ProjectStatusResponse.java` | 新增 5 个字段 |
| `comic/src/main/java/com/comic/service/pipeline/PipelineService.java` | Map 访问 + 回退 + 分页 |
| `comic/src/main/java/com/comic/service/script/ScriptService.java` | Map 访问 + 结构化大纲 + 自动分批 |
| `comic/src/main/java/com/comic/service/character/CharacterExtractService.java` | Map 访问 |
| `comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java` | Map 访问 |
| `comic/src/main/java/com/comic/service/story/StoryboardService.java` | Map 访问 |
| `comic/src/main/java/com/comic/controller/EpisodeController.java` | Map 访问（如果 Service 变更影响其传参） |
| `comic/src/main/java/com/comic/ai/PromptBuilder.java` | 大纲生成改为结构化 JSON 输出 |
| `comic/src/main/java/com/comic/controller/ProjectController.java` | 重构为 CRUD + 状态 |
| `comic/src/main/java/com/comic/controller/CharacterController.java` | Map 访问（Character 字段迁移） |

### 需新建的文件

| 文件 | 职责 |
|------|------|
| `comic/src/main/java/com/comic/common/ProjectInfoKeys.java` | projectInfo Map key 常量 |
| `comic/src/main/java/com/comic/common/EpisodeInfoKeys.java` | episodeInfo Map key 常量 |
| `comic/src/main/java/com/comic/common/CharacterInfoKeys.java` | characterInfo Map key 常量 |
| `comic/src/main/java/com/comic/dto/request/AdvanceRequest.java` | advance 接口请求体 |
| `comic/src/main/java/com/comic/dto/request/ProjectUpdateRequest.java` | PUT/PATCH 请求体 |
| `comic/src/main/java/com/comic/dto/request/ScriptReviseRequest.java` | 剧本修改请求体 |
| `comic/src/main/java/com/comic/dto/response/StepHistoryItem.java` | stepHistory 数组元素 |
| `comic/src/main/java/com/comic/dto/response/PaginatedResponse.java` | 分页响应包装 |
| `comic/src/main/java/com/comic/controller/ScriptController.java` | 剧本接口 |

---

## 阶段一：Entity + deleted + Repository

### Task 1: 新增 deleted 字段到 Project 实体

**Files:**
- Modify: `comic/src/main/java/com/comic/entity/Project.java`

- [ ] **Step 1: 在 Project 实体中新增 deleted 字段**

在 `status` 字段之后添加：

```java
@TableField(fill = FieldFill.INSERT)
private Boolean deleted = false;
```

- [ ] **Step 2: 编译验证**

Run: `cd comic && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add comic/src/main/java/com/comic/entity/Project.java
git commit -m "feat: add deleted field to Project entity"
```

### Task 2: 定义 Map Key 常量类

**Files:**
- Create: `comic/src/main/java/com/comic/common/ProjectInfoKeys.java`
- Create: `comic/src/main/java/com/comic/common/EpisodeInfoKeys.java`
- Create: `comic/src/main/java/com/comic/common/CharacterInfoKeys.java`

- [ ] **Step 1: 创建 ProjectInfoKeys**

```java
package com.comic.common;

public final class ProjectInfoKeys {
    public static final String STORY_PROMPT = "storyPrompt";
    public static final String GENRE = "genre";
    public static final String TARGET_AUDIENCE = "targetAudience";
    public static final String TOTAL_EPISODES = "totalEpisodes";
    public static final String EPISODE_DURATION = "episodeDuration";
    public static final String VISUAL_STYLE = "visualStyle";
    public static final String SCRIPT = "script";
    public static final String SCRIPT_OUTLINE = "outline";
    public static final String SCRIPT_CHARACTERS = "characters";
    public static final String SCRIPT_ITEMS = "items";
    public static final String SCRIPT_EPISODES = "episodes";
    public static final String EPISODES_PER_CHAPTER = "episodesPerChapter";
    public static final String SCRIPT_REVISION_NOTE = "scriptRevisionNote";
    public static final String SELECTED_CHAPTER = "selectedChapter";

    private ProjectInfoKeys() {}
}
```

- [ ] **Step 2: 创建 EpisodeInfoKeys**

```java
package com.comic.common;

public final class EpisodeInfoKeys {
    public static final String EPISODE_NUM = "episodeNum";
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String CHARACTERS = "characters";
    public static final String KEY_ITEMS = "keyItems";
    public static final String CONTINUITY_NOTE = "continuityNote";
    public static final String VISUAL_STYLE_NOTE = "visualStyleNote";
    public static final String SYNOPSIS = "synopsis";
    public static final String CHAPTER_TITLE = "chapterTitle";
    public static final String RETRY_COUNT = "retryCount";
    public static final String STORYBOARD_JSON = "storyboardJson";
    public static final String ERROR_MSG = "errorMsg";
    public static final String PRODUCTION_STATUS = "productionStatus";
    public static final String OUTLINE_NODE = "outlineNode";

    private EpisodeInfoKeys() {}
}
```

- [ ] **Step 3: 创建 CharacterInfoKeys**

```java
package com.comic.common;

public final class CharacterInfoKeys {
    public static final String CHAR_ID = "charId";
    public static final String NAME = "name";
    public static final String ROLE = "role";
    public static final String VOICE = "voice";
    public static final String APPEARANCE = "appearance";
    public static final String BACKGROUND = "background";
    public static final String CONFIRMED = "confirmed";
    public static final String VISUAL_STYLE = "visualStyle";
    public static final String THREE_VIEWS_URL = "threeViewsUrl";
    public static final String EXPRESSION_IMAGE_URL = "expressionImageUrl";
    public static final String EXPRESSION_STATUS = "expressionStatus";
    public static final String THREE_VIEW_STATUS = "threeViewStatus";
    public static final String EXPRESSION_ERROR = "expressionError";
    public static final String THREE_VIEW_ERROR = "threeViewError";
    public static final String IS_GENERATING_EXPRESSION = "isGeneratingExpression";
    public static final String IS_GENERATING_THREE_VIEW = "isGeneratingThreeView";
    public static final String EXPRESSION_GRID_URL = "expressionGridUrl";
    public static final String THREE_VIEW_GRID_URL = "threeViewGridUrl";
    public static final String EXPRESSION_GRID_PROMPT = "expressionGridPrompt";
    public static final String THREE_VIEW_GRID_PROMPT = "threeViewGridPrompt";

    private CharacterInfoKeys() {}
}
```

- [ ] **Step 4: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/common/ProjectInfoKeys.java \
       comic/src/main/java/com/comic/common/EpisodeInfoKeys.java \
       comic/src/main/java/com/comic/common/CharacterInfoKeys.java
git commit -m "feat: add Map key constants for projectInfo/episodeInfo/characterInfo"
```

### Task 3: 更新 ProjectRepository — deleted 过滤 + 分页

**Files:**
- Modify: `comic/src/main/java/com/comic/repository/ProjectRepository.java`

- [ ] **Step 1: 所有查询方法加 deleted = false 条件**

```java
package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.entity.Project;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProjectRepository extends BaseMapper<Project> {

    default Project findByProjectId(String projectId) {
        return selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getProjectId, projectId)
            .eq(Project::getDeleted, false));
    }

    default Project findByUserIdAndStatus(String userId, String status) {
        return selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getStatus, status)
            .eq(Project::getDeleted, false)
            .orderByDesc(Project::getCreatedAt)
            .last("LIMIT 1"));
    }

    default List<Project> findAllByUserId(String userId) {
        return selectList(new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getDeleted, false)
            .orderByDesc(Project::getCreatedAt));
    }

    default IPage<Project> findPage(String userId, String status, String sortBy, String sortOrder,
                                      int page, int size) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getDeleted, false);

        if (status != null && !status.isEmpty()) {
            wrapper.eq(Project::getStatus, status);
        }

        if ("updatedAt".equals(sortBy)) {
            wrapper.orderBy(true, "asc".equals(sortOrder), Project::getUpdatedAt);
        } else {
            wrapper.orderBy(true, "asc".equals(sortOrder), Project::getCreatedAt);
        }

        return selectPage(new Page<>(page, size), wrapper);
    }
}
```

- [ ] **Step 2: 确保 MyBatis-Plus 分页插件已配置**

检查 `comic/src/main/java/com/comic/config/` 下是否有 MybatisPlusConfig 或 PaginationInterceptor 配置。如果没有，新建：

```java
package com.comic.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

- [ ] **Step 3: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/repository/ProjectRepository.java
git commit -m "feat: add deleted filter and pagination to ProjectRepository"
```

---

## 阶段二：ProjectStatus 枚举 + DTO 更新

### Task 4: 扩展 ProjectStatus 枚举

**Files:**
- Modify: `comic/src/main/java/com/comic/common/ProjectStatus.java`

- [ ] **Step 1: 新增导航方法**

在枚举中添加以下方法（在 `getAvailableActions()` 之后）：

```java
/**
 * 获取当前步骤对应的状态标签
 */
public String getLabel() {
    switch (this) {
        case DRAFT: return "草稿";
        case OUTLINE_GENERATING: return "大纲生成中";
        case OUTLINE_REVIEW: return "大纲审核";
        case EPISODE_GENERATING: return "剧集生成中";
        case SCRIPT_REVIEW: return "剧本审核";
        case SCRIPT_CONFIRMED: return "剧本确认";
        case CHARACTER_EXTRACTING: return "角色提取中";
        case CHARACTER_REVIEW: return "角色审核";
        case CHARACTER_CONFIRMED: return "角色已确认";
        case IMAGE_GENERATING: return "图像生成中";
        case IMAGE_REVIEW: return "图像审核";
        case ASSET_LOCKED: return "素材已锁定";
        case STORYBOARD_GENERATING: return "分镜生成中";
        case STORYBOARD_REVIEW: return "分镜审核";
        case PRODUCING: return "生产中";
        case COMPLETED: return "已完成";
        default: return this.description;
    }
}

/**
 * 获取上一个确认状态（用于回退导航）
 */
public ProjectStatus getPreviousStatus() {
    switch (this) {
        case OUTLINE_REVIEW:
        case EPISODE_GENERATING:
        case SCRIPT_REVIEW:
            return DRAFT;
        case SCRIPT_CONFIRMED:
            return SCRIPT_REVIEW;
        case CHARACTER_EXTRACTING:
            return SCRIPT_CONFIRMED;
        case CHARACTER_REVIEW:
            return CHARACTER_EXTRACTING;
        case CHARACTER_CONFIRMED:
            return CHARACTER_REVIEW;
        case IMAGE_GENERATING:
            return CHARACTER_CONFIRMED;
        case IMAGE_REVIEW:
            return IMAGE_GENERATING;
        case ASSET_LOCKED:
            return IMAGE_REVIEW;
        case STORYBOARD_GENERATING:
            return ASSET_LOCKED;
        case STORYBOARD_REVIEW:
            return STORYBOARD_GENERATING;
        case PRODUCING:
            return STORYBOARD_REVIEW;
        case COMPLETED:
            return PRODUCING;
        default:
            return null;
    }
}

/**
 * 获取下一个可推进的状态
 */
public ProjectStatus getNextStatus() {
    switch (this) {
        case DRAFT:
            return OUTLINE_GENERATING;
        case OUTLINE_REVIEW:
            return EPISODE_GENERATING;
        case SCRIPT_REVIEW:
            return SCRIPT_CONFIRMED;
        case SCRIPT_CONFIRMED:
            return CHARACTER_EXTRACTING;
        case CHARACTER_REVIEW:
            return CHARACTER_CONFIRMED;
        case CHARACTER_CONFIRMED:
            return IMAGE_GENERATING;
        case IMAGE_REVIEW:
            return ASSET_LOCKED;
        case ASSET_LOCKED:
            return STORYBOARD_GENERATING;
        case STORYBOARD_REVIEW:
            return PRODUCING;
        case PRODUCING:
            return COMPLETED;
        default:
            return null;
    }
}

public boolean canGoBack() {
    return getPreviousStatus() != null && this != DRAFT;
}

public boolean canAdvance() {
    return getNextStatus() != null && !this.isGenerating() && !this.isFailed();
}

/**
 * 构建步骤历史
 */
public List<java.util.Map<String, Object>> getStepHistory() {
    java.util.List<java.util.Map<String, Object>> history = new java.util.ArrayList<>();

    // Step 1: 剧本
    if (this.frontendStep >= 1) {
        boolean confirmed = this.frontendStep > 1
            || this == SCRIPT_CONFIRMED
            || this == CHARACTER_EXTRACTING
            || this == CHARACTER_REVIEW
            || this == CHARACTER_CONFIRMED
            || this == IMAGE_GENERATING
            || this == IMAGE_REVIEW
            || this == ASSET_LOCKED
            || this == STORYBOARD_GENERATING
            || this == STORYBOARD_REVIEW
            || this == PRODUCING
            || this == COMPLETED;
        java.util.Map<String, Object> step1 = new java.util.HashMap<>();
        step1.put("step", 1);
        step1.put("status", confirmed ? "OUTLINE_GENERATED" : this.code);
        step1.put("label", "剧本大纲");
        step1.put("confirmed", confirmed);
        history.add(step1);
    }

    // Step 2: 剧本确认
    if (this.frontendStep >= 2) {
        boolean confirmed = this.frontendStep > 2
            || this == SCRIPT_CONFIRMED
            || this == CHARACTER_EXTRACTING
            || this == CHARACTER_REVIEW
            || this == CHARACTER_CONFIRMED
            || this == IMAGE_GENERATING
            || this == IMAGE_REVIEW
            || this == ASSET_LOCKED
            || this == STORYBOARD_GENERATING
            || this == STORYBOARD_REVIEW
            || this == PRODUCING
            || this == COMPLETED;
        java.util.Map<String, Object> step2 = new java.util.HashMap<>();
        step2.put("step", 2);
        step2.put("status", confirmed ? "SCRIPT_CONFIRMED" : this.code);
        step2.put("label", "剧本确认");
        step2.put("confirmed", confirmed);
        history.add(step2);
    }

    // Step 3: 角色
    if (this.frontendStep >= 3) {
        boolean confirmed = this.frontendStep > 3
            || this == CHARACTER_CONFIRMED
            || this == IMAGE_GENERATING
            || this == IMAGE_REVIEW
            || this == ASSET_LOCKED
            || this == STORYBOARD_GENERATING
            || this == STORYBOARD_REVIEW
            || this == PRODUCING
            || this == COMPLETED;
        java.util.Map<String, Object> step3 = new java.util.HashMap<>();
        step3.put("step", 3);
        step3.put("status", confirmed ? "CHARACTER_CONFIRMED" : this.code);
        step3.put("label", "角色确认");
        step3.put("confirmed", confirmed);
        history.add(step3);
    }

    // Step 4: 图像/素材
    if (this.frontendStep >= 4) {
        boolean confirmed = this == ASSET_LOCKED
            || this == STORYBOARD_GENERATING
            || this == STORYBOARD_REVIEW
            || this == PRODUCING
            || this == COMPLETED;
        java.util.Map<String, Object> step4 = new java.util.HashMap<>();
        step4.put("step", 4);
        step4.put("status", confirmed ? "ASSET_LOCKED" : this.code);
        step4.put("label", "素材锁定");
        step4.put("confirmed", confirmed);
        history.add(step4);
    }

    // Step 5: 分镜
    if (this.frontendStep >= 5) {
        boolean confirmed = this == STORYBOARD_REVIEW && /* all confirmed */ true
            || this == PRODUCING
            || this == COMPLETED;
        java.util.Map<String, Object> step5 = new java.util.HashMap<>();
        step5.put("step", 5);
        step5.put("status", confirmed ? "STORYBOARD_CONFIRMED" : this.code);
        step5.put("label", "分镜确认");
        step5.put("confirmed", confirmed);
        history.add(step5);
    }

    // Step 6: 生产
    if (this.frontendStep >= 6) {
        boolean confirmed = this == COMPLETED;
        java.util.Map<String, Object> step6 = new java.util.HashMap<>();
        step6.put("step", 6);
        step6.put("status", confirmed ? "COMPLETED" : this.code);
        step6.put("label", "视频生产");
        step6.put("confirmed", confirmed);
        history.add(step6);
    }

    return history;
}
```

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/common/ProjectStatus.java
git commit -m "feat: add navigation methods to ProjectStatus enum"
```

### Task 5: 新建 DTO 类

**Files:**
- Create: `comic/src/main/java/com/comic/dto/request/AdvanceRequest.java`
- Create: `comic/src/main/java/com/comic/dto/request/ProjectUpdateRequest.java`
- Create: `comic/src/main/java/com/comic/dto/request/ScriptReviseRequest.java`
- Create: `comic/src/main/java/com/comic/dto/response/StepHistoryItem.java`
- Create: `comic/src/main/java/com/comic/dto/response/PaginatedResponse.java`
- Modify: `comic/src/main/java/com/comic/dto/response/ProjectStatusResponse.java`

- [ ] **Step 1: 创建 AdvanceRequest**

```java
package com.comic.dto.request;

import lombok.Data;

@Data
public class AdvanceRequest {
    private String direction;  // "forward" or "backward"
    private String event;      // forward 时必填
}
```

- [ ] **Step 2: 创建 ProjectUpdateRequest**

```java
package com.comic.dto.request;

import lombok.Data;

@Data
public class ProjectUpdateRequest {
    private String storyPrompt;
    private String genre;
    private String targetAudience;
    private Integer totalEpisodes;
    private Integer episodeDuration;
    private String visualStyle;
}
```

- [ ] **Step 3: 创建 ScriptReviseRequest**

```java
package com.comic.dto.request;

import lombok.Data;

@Data
public class ScriptReviseRequest {
    private String revisionNote;
    private String currentOutline;  // 可选
}
```

- [ ] **Step 4: 创建 StepHistoryItem**

```java
package com.comic.dto.response;

import lombok.Data;

@Data
public class StepHistoryItem {
    private int step;
    private String status;
    private String label;
    private boolean confirmed;
}
```

- [ ] **Step 5: 创建 PaginatedResponse**

```java
package com.comic.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class PaginatedResponse<T> {
    private List<T> items;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public static <T> PaginatedResponse<T> of(List<T> items, long total, int page, int size) {
        PaginatedResponse<T> response = new PaginatedResponse<>();
        response.setItems(items);
        response.setTotal(total);
        response.setPage(page);
        response.setSize(size);
        response.setTotalPages((int) Math.ceil((double) total / size));
        return response;
    }
}
```

- [ ] **Step 6: 更新 ProjectStatusResponse 新增字段**

在 `ProjectStatusResponse.java` 中添加：

```java
private String previousStatus;
private String nextStatus;

@JsonProperty("canGoBack")
private boolean canGoBack;

@JsonProperty("canAdvance")
private boolean canAdvance;

private List<StepHistoryItem> stepHistory;
```

- [ ] **Step 7: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/dto/
git commit -m "feat: add new DTOs for advance, update, script revise, pagination"
```

---

## 阶段三：Service 层重写（核心）

> 此阶段是最大的工作量。每个 Service 需要将所有 `entity.getXxx()` / `entity.setXxx()` 调用替换为 `entity.getXxxInfo().get(KEY)` / `entity.getXxxInfo().put(KEY, value)` 模式。
>
> **Map 访问辅助方法模式：** 在每个 Service 中使用工具方法减少重复代码：
> ```java
> private String getInfoStr(Map<String, Object> info, String key) {
>     Object v = info != null ? info.get(key) : null;
>     return v != null ? v.toString() : null;
> }
> private Integer getInfoInt(Map<String, Object> info, String key) {
>     Object v = info != null ? info.get(key) : null;
>     return v != null ? ((Number) v).intValue() : null;
> }
> ```

### Task 6: 重写 PipelineService

**Files:**
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`

**变更要点：**
- `createProject`: `project.setStoryPrompt(...)` → `projectInfo.put(ProjectInfoKeys.STORY_PROMPT, ...)`
- `getProjectStatusDetail`: 新增 `previousStatus`, `nextStatus`, `canGoBack`, `canAdvance`, `stepHistory` 字段填充
- `advancePipeline`: 支持 `direction: "backward"`，回退时精确清理数据
- `getProjectsByUserId`: 改为调用分页方法 `findPage`
- `toListItemDTO`: 从 `projectInfo` Map 读取字段

- [ ] **Step 1: 重写 createProject 方法**

将所有字段设置改为 Map 操作：

```java
@Transactional
public String createProject(String userId, String storyPrompt, String genre,
                            String targetAudience, Integer totalEpisodes,
                            Integer episodeDuration, String visualStyle) {
    Project project = new Project();
    project.setProjectId(generateProjectId());
    project.setUserId(userId);
    project.setDeleted(false);
    project.setStatus(ProjectStatus.DRAFT.getCode());

    Map<String, Object> info = new HashMap<>();
    info.put(ProjectInfoKeys.STORY_PROMPT, storyPrompt);
    info.put(ProjectInfoKeys.GENRE, genre);
    info.put(ProjectInfoKeys.TARGET_AUDIENCE, targetAudience);
    info.put(ProjectInfoKeys.TOTAL_EPISODES, totalEpisodes);
    info.put(ProjectInfoKeys.EPISODE_DURATION, episodeDuration);
    info.put(ProjectInfoKeys.VISUAL_STYLE, visualStyle);
    project.setProjectInfo(info);

    projectRepository.insert(project);
    log.info("Project created: projectId={}, userId={}", project.getProjectId(), userId);
    return project.getProjectId();
}
```

- [ ] **Step 2: 更新 advancePipeline 支持 backward**

在现有 `advancePipeline` 方法中添加 backward 方向处理。当 `direction` 为 `backward` 时：
1. 调用 `calculatePreviousStatus` 获取回退目标
2. 执行精确清理（删除后续阶段数据）
3. 设置新状态

```java
@Transactional
public void advancePipeline(String projectId, String direction, String event) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("Project not found");
    }

    if ("backward".equals(direction)) {
        rollbackPipeline(project);
    } else {
        String currentStatus = project.getStatus();
        String nextStatus = calculateNextStatus(currentStatus, event);
        if (nextStatus == null) {
            throw new BusinessException("Cannot transition from status " + currentStatus + " via event " + event);
        }
        project.setStatus(nextStatus);
        projectRepository.updateById(project);
        log.info("Pipeline advanced: projectId={}, {} -> {}", projectId, currentStatus, nextStatus);
        triggerNextStageAsync(projectId, nextStatus);
    }
}

private void rollbackPipeline(Project project) {
    ProjectStatus current = ProjectStatus.fromCode(project.getStatus());
    ProjectStatus previous = current.getPreviousStatus();
    if (previous == null) {
        throw new BusinessException("Cannot go back from status " + current.getCode());
    }

    String projectId = project.getProjectId();
    cleanupAfterRollback(projectId, current);

    project.setStatus(previous.getCode());
    projectRepository.updateById(project);
    log.info("Pipeline rolled back: projectId={}, {} -> {}", projectId, current.getCode(), previous.getCode());
}

private void cleanupAfterRollback(String projectId, ProjectStatus from) {
    // 回退清理规则：清理 from 阶段及之后产生的数据
    switch (from) {
        case OUTLINE_REVIEW:
        case EPISODE_GENERATING:
        case SCRIPT_REVIEW:
            episodeRepository.deleteByProjectId(projectId);
            break;
        case SCRIPT_CONFIRMED:
            characterRepository.deleteByProjectId(projectId);
            episodeRepository.deleteByProjectId(projectId);
            break;
        case CHARACTER_EXTRACTING:
        case CHARACTER_REVIEW:
        case CHARACTER_CONFIRMED:
            characterRepository.deleteByProjectId(projectId);
            break;
        case IMAGE_GENERATING:
        case IMAGE_REVIEW:
            // 清除角色图片（通过更新 characterInfo）
            List<Character> characters = characterRepository.findByProjectId(projectId);
            for (Character c : characters) {
                Map<String, Object> info = c.getCharacterInfo();
                if (info != null) {
                    info.remove(CharacterInfoKeys.THREE_VIEWS_URL);
                    info.remove(CharacterInfoKeys.EXPRESSION_IMAGE_URL);
                    info.remove(CharacterInfoKeys.EXPRESSION_STATUS);
                    info.remove(CharacterInfoKeys.THREE_VIEW_STATUS);
                    info.remove(CharacterInfoKeys.EXPRESSION_ERROR);
                    info.remove(CharacterInfoKeys.THREE_VIEW_ERROR);
                    info.remove(CharacterInfoKeys.IS_GENERATING_EXPRESSION);
                    info.remove(CharacterInfoKeys.IS_GENERATING_THREE_VIEW);
                    info.remove(CharacterInfoKeys.EXPRESSION_GRID_URL);
                    info.remove(CharacterInfoKeys.THREE_VIEW_GRID_URL);
                    info.remove(CharacterInfoKeys.EXPRESSION_GRID_PROMPT);
                    info.remove(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT);
                    c.setCharacterInfo(info);
                    characterRepository.updateById(c);
                }
            }
            break;
        case ASSET_LOCKED:
        case STORYBOARD_GENERATING:
        case STORYBOARD_REVIEW:
        case PRODUCING:
            // 清除分镜和生产数据
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            for (Episode ep : episodes) {
                Map<String, Object> info = ep.getEpisodeInfo();
                if (info != null) {
                    info.remove(EpisodeInfoKeys.STORYBOARD_JSON);
                    info.remove(EpisodeInfoKeys.PRODUCTION_STATUS);
                    ep.setEpisodeInfo(info);
                }
                ep.setStatus("DRAFT");
                episodeRepository.updateById(ep);
            }
            break;
        default:
            break;
    }
}
```

- [ ] **Step 3: 更新 getProjectStatusDetail 填充新字段**

在现有 `getProjectStatusDetail` 方法中，`dto` 设置完成后添加：

```java
dto.setPreviousStatus(status.getPreviousStatus() != null ? status.getPreviousStatus().getCode() : null);
dto.setNextStatus(status.getNextStatus() != null ? status.getNextStatus().getCode() : null);
dto.setCanGoBack(status.canGoBack());
dto.setCanAdvance(status.canAdvance());

// 构建 stepHistory
List<StepHistoryItem> stepHistory = new ArrayList<>();
for (Map<String, Object> step : status.getStepHistory()) {
    StepHistoryItem item = new StepHistoryItem();
    item.setStep((int) step.get("step"));
    item.setStatus((String) step.get("status"));
    item.setLabel((String) step.get("label"));
    item.setConfirmed((boolean) step.get("confirmed"));
    stepHistory.add(item);
}
dto.setStepHistory(stepHistory);
```

- [ ] **Step 4: 更新 toListItemDTO 和 getProjectsByUserId**

`toListItemDTO` 改为从 `projectInfo` Map 读取字段：
```java
private ProjectListItemResponse toListItemDTO(Project project) {
    ProjectStatus status = ProjectStatus.fromCode(project.getStatus());
    Map<String, Object> info = project.getProjectInfo();

    ProjectListItemResponse dto = new ProjectListItemResponse();
    dto.setProjectId(project.getProjectId());
    dto.setStoryPrompt(getInfoStr(info, ProjectInfoKeys.STORY_PROMPT));
    dto.setGenre(getInfoStr(info, ProjectInfoKeys.GENRE));
    dto.setTargetAudience(getInfoStr(info, ProjectInfoKeys.TARGET_AUDIENCE));
    dto.setTotalEpisodes(getInfoInt(info, ProjectInfoKeys.TOTAL_EPISODES));
    dto.setEpisodeDuration(getInfoInt(info, ProjectInfoKeys.EPISODE_DURATION));
    dto.setVisualStyle(getInfoStr(info, ProjectInfoKeys.VISUAL_STYLE));
    dto.setStatusCode(status.getCode());
    dto.setStatusDescription(status.getDescription());
    dto.setCurrentStep(status.getFrontendStep());
    dto.setGenerating(status.isGenerating());
    dto.setFailed(status.isFailed());
    dto.setReview(status.isReview());
    dto.setCompletedSteps(status.getCompletedSteps());
    dto.setCreatedAt(project.getCreatedAt());
    dto.setUpdatedAt(project.getUpdatedAt());
    return dto;
}
```

- [ ] **Step 5: 新增 updateProject 和 logicalDelete 方法**

```java
@Transactional
public void updateProject(String projectId, ProjectUpdateRequest request) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("Project not found");
    }
    Map<String, Object> info = project.getProjectInfo();
    if (info == null) {
        info = new HashMap<>();
    }
    if (request.getStoryPrompt() != null) info.put(ProjectInfoKeys.STORY_PROMPT, request.getStoryPrompt());
    if (request.getGenre() != null) info.put(ProjectInfoKeys.GENRE, request.getGenre());
    if (request.getTargetAudience() != null) info.put(ProjectInfoKeys.TARGET_AUDIENCE, request.getTargetAudience());
    if (request.getTotalEpisodes() != null) info.put(ProjectInfoKeys.TOTAL_EPISODES, request.getTotalEpisodes());
    if (request.getEpisodeDuration() != null) info.put(ProjectInfoKeys.EPISODE_DURATION, request.getEpisodeDuration());
    if (request.getVisualStyle() != null) info.put(ProjectInfoKeys.VISUAL_STYLE, request.getVisualStyle());
    project.setProjectInfo(info);
    projectRepository.updateById(project);
}

@Transactional
public void logicalDeleteProject(String projectId) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("Project not found");
    }
    project.setDeleted(true);
    projectRepository.updateById(project);
}
```

- [ ] **Step 6: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/service/pipeline/PipelineService.java
git commit -m "feat: rewrite PipelineService with Map access, rollback, pagination"
```

### Task 7: 重写 ScriptService

**Files:**
- Modify: `comic/src/main/java/com/comic/service/script/ScriptService.java`

**变更要点：**
- 所有 `project.getScriptOutline()` → 从 `projectInfo["script"]["outline"]` 读取
- 所有 `project.getStoryPrompt()` → 从 `projectInfo["storyPrompt"]` 读取
- 所有 `project.getTotalEpisodes()` → 从 `projectInfo["totalEpisodes"]` 读取
- 所有 `episode.getXxx()` → 从 `episodeInfo` Map 读取
- `generateScriptOutline`: AI 输出改为结构化 JSON，存入 `projectInfo["script"]`
- `confirmScript`: 改为自动分批生成所有剧集
- 移除 `generateAllEpisodes` 和 `generateScriptEpisodes`（被自动分批替代）
- 新增 `generateAllEpisodesAsync`: 后台异步分批生成

- [ ] **Step 1: 添加 Map 辅助方法**

在 ScriptService 类中添加：

```java
private String getProjectInfoStr(Project project, String key) {
    Map<String, Object> info = project.getProjectInfo();
    Object v = info != null ? info.get(key) : null;
    return v != null ? v.toString() : null;
}

private Integer getProjectInfoInt(Project project, String key) {
    Map<String, Object> info = project.getProjectInfo();
    Object v = info != null ? info.get(key) : null;
    return v != null ? ((Number) v).intValue() : null;
}

private Map<String, Object> getScriptMap(Project project) {
    Map<String, Object> info = project.getProjectInfo();
    if (info == null) return null;
    Object script = info.get(ProjectInfoKeys.SCRIPT);
    return script instanceof Map ? (Map<String, Object>) script : null;
}

private String getEpisodeInfoStr(Episode episode, String key) {
    Map<String, Object> info = episode.getEpisodeInfo();
    Object v = info != null ? info.get(key) : null;
    return v != null ? v.toString() : null;
}
```

- [ ] **Step 2: 重写 generateScriptOutline 输出结构化 JSON**

修改 PromptBuilder 调用，让 AI 输出结构化 JSON（含 characters、items、episodes），然后解析存入 `projectInfo["script"]`。详见设计文档中的 script JSON 结构。

- [ ] **Step 3: 重写 confirmScript 自动分批生成**

```java
@Transactional
public void confirmScript(String projectId) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("项目不存在");
    }

    String status = project.getStatus();
    if (!ProjectStatus.OUTLINE_REVIEW.getCode().equals(status)
        && !ProjectStatus.SCRIPT_REVIEW.getCode().equals(status)) {
        throw new BusinessException("当前状态不能确认剧本");
    }

    Map<String, Object> scriptMap = getScriptMap(project);
    if (scriptMap == null) {
        throw new BusinessException("大纲数据不存在");
    }

    // 检查 episodes 列表是否存在
    Object episodesObj = scriptMap.get(ProjectInfoKeys.SCRIPT_EPISODES);
    if (episodesObj == null) {
        throw new BusinessException("大纲中缺少剧集信息");
    }

    project.setStatus(ProjectStatus.EPISODE_GENERATING.getCode());
    projectRepository.updateById(project);

    // 异步分批生成
    CompletableFuture.runAsync(() -> {
        try {
            generateAllEpisodesInBatches(project);
            project.setStatus(ProjectStatus.SCRIPT_CONFIRMED.getCode());
            projectRepository.updateById(project);
            pipelineService.advancePipeline(projectId, "start_character_extraction");
        } catch (Exception e) {
            log.error("自动生成剧集失败: projectId={}", projectId, e);
            project.setStatus(ProjectStatus.EPISODE_GENERATING_FAILED.getCode());
            projectRepository.updateById(project);
        }
    });
}
```

- [ ] **Step 4: 新增 generateAllEpisodesInBatches 方法**

按设计文档中的分批规则实现。每批取 2-4 个 episode synopsis，调用 AI 生成完整剧本，存入 Episode 表。

- [ ] **Step 5: 更新其余方法使用 Map 访问**

`getScriptContent`、`updateScriptOutline`、`reviseOutline` 等方法中所有 `project.getXxx()` 和 `episode.getXxx()` 调用替换为 Map 访问。

- [ ] **Step 6: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/service/script/ScriptService.java
git commit -m "feat: rewrite ScriptService with Map access, structured outline, auto-batch"
```

### Task 8: 重写 CharacterExtractService

**Files:**
- Modify: `comic/src/main/java/com/comic/service/character/CharacterExtractService.java`

**变更要点：**
- `project.getScriptOutline()` → 从 `projectInfo["script"]["outline"]` 读取
- `project.getStoryPrompt()` → 从 `projectInfo["storyPrompt"]` 读取
- `project.getVisualStyle()` → 从 `projectInfo["visualStyle"]` 读取
- `character.setXxx()` → `characterInfo.put(CharacterInfoKeys.XXX, value)`
- `character.getXxx()` → `characterInfo.get(CharacterInfoKeys.XXX)`
- `character.setConfirmed()` → `characterInfo.put(CharacterInfoKeys.CONFIRMED, true)`

- [ ] **Step 1: 替换所有字段访问为 Map 操作**

主要变更在 `extractCharacters`、`saveCharacters`、`updateCharacter`、`getProjectCharacters`、`confirmCharacters` 方法中。

例如 `saveCharacters`：
```java
private void saveCharacters(String projectId, List<CharacterDraftModel> characters) {
    Project project = projectRepository.findByProjectId(projectId);
    String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);
    if (visualStyle == null) visualStyle = "3D";

    List<Character> oldCharacters = characterRepository.findByProjectId(projectId);
    for (Character old : oldCharacters) {
        characterRepository.deleteById(old.getId());
    }

    for (CharacterDraftModel dto : characters) {
        Character character = new Character();
        character.setProjectId(projectId);

        Map<String, Object> info = new HashMap<>();
        info.put(CharacterInfoKeys.CHAR_ID, dto.getCharId());
        info.put(CharacterInfoKeys.NAME, dto.getName());
        info.put(CharacterInfoKeys.ROLE, dto.getRole());
        info.put(CharacterInfoKeys.VOICE, dto.getVoice());
        info.put(CharacterInfoKeys.APPEARANCE, dto.getAppearance());
        info.put(CharacterInfoKeys.BACKGROUND, dto.getBackground());
        info.put(CharacterInfoKeys.CONFIRMED, false);
        info.put(CharacterInfoKeys.VISUAL_STYLE, visualStyle);
        character.setCharacterInfo(info);

        characterRepository.insert(character);
    }
}
```

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/service/character/CharacterExtractService.java
git commit -m "feat: rewrite CharacterExtractService with Map access"
```

### Task 9: 重写 CharacterImageGenerationService

**Files:**
- Modify: `comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java`

**变更要点：**
- `character.getRole()` → `characterInfo.get(CharacterInfoKeys.ROLE)`
- `character.getName()` → `characterInfo.get(CharacterInfoKeys.NAME)`
- `character.getVisualStyle()` → `characterInfo.get(CharacterInfoKeys.VISUAL_STYLE)`
- `character.setExpressionStatus(...)` → `characterInfo.put(CharacterInfoKeys.EXPRESSION_STATUS, ...)`
- `character.getThreeViewGridUrl()` → `characterInfo.get(CharacterInfoKeys.THREE_VIEW_GRID_URL)`
- `character.setIsGeneratingExpression(...)` → `characterInfo.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, ...)`
- 所有 `character.setXxx(...)` → `characterInfo.put(CharacterInfoKeys.XXX, ...)` + `characterRepository.updateById(character)`

- [ ] **Step 1: 添加 Map 辅助方法并替换所有字段访问**

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java
git commit -m "feat: rewrite CharacterImageGenerationService with Map access"
```

### Task 10: 重写 StoryboardService

**Files:**
- Modify: `comic/src/main/java/com/comic/service/story/StoryboardService.java`

**变更要点：**
- `episode.setStatus(...)` → 保持不变（直接字段）
- `episode.setRetryCount(...)` → `episodeInfo.put(EpisodeInfoKeys.RETRY_COUNT, ...)`
- `episode.setErrorMsg(...)` → `episodeInfo.put(EpisodeInfoKeys.ERROR_MSG, ...)`
- `episode.setStoryboardJson(...)` → `episodeInfo.put(EpisodeInfoKeys.STORYBOARD_JSON, ...)`
- `episode.getEpisodeNum()` → `episodeInfo.get(EpisodeInfoKeys.EPISODE_NUM)`
- `episode.getContent()` → `episodeInfo.get(EpisodeInfoKeys.CONTENT)`
- `episode.getTitle()` → `episodeInfo.get(EpisodeInfoKeys.TITLE)`
- `episode.getStoryboardJson()` → `episodeInfo.get(EpisodeInfoKeys.STORYBOARD_JSON)`
- `episode.getOutlineNode()` → `episodeInfo.get(EpisodeInfoKeys.OUTLINE_NODE)`
- `project.setStatus(...)` → 保持不变

- [ ] **Step 1: 添加 Episode Map 辅助方法**

```java
private Map<String, Object> epInfo(Episode episode) {
    Map<String, Object> info = episode.getEpisodeInfo();
    if (info == null) {
        info = new HashMap<>();
        episode.setEpisodeInfo(info);
    }
    return info;
}
```

- [ ] **Step 2: 替换所有 episode.setXxx() 和 episode.getXxx() 调用**

所有 `episode.setStatus(...)` 保持不变。
所有 `episode.setRetryCount(0)` → `epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);`
所有 `episode.setErrorMsg(null)` → `epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);`
所有 `episode.setStoryboardJson(result)` → `epInfo(episode).put(EpisodeInfoKeys.STORYBOARD_JSON, result);`
所有 `episode.getEpisodeNum()` → `(Integer) episode.getEpisodeInfo().get(EpisodeInfoKeys.EPISODE_NUM)`
所有 `episode.getContent()` → `(String) episode.getEpisodeInfo().get(EpisodeInfoKeys.CONTENT)`

- [ ] **Step 3: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/service/story/StoryboardService.java
git commit -m "feat: rewrite StoryboardService with Map access"
```

### Task 11: 重写 CharacterController

**Files:**
- Modify: `comic/src/main/java/com/comic/controller/CharacterController.java`

**变更要点：**
- `getGenerationStatus` 方法中 `character.getXxx()` → `characterInfo.get(CharacterInfoKeys.XXX)`

- [ ] **Step 1: 替换 getGenerationStatus 中的字段访问**

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/controller/CharacterController.java
git commit -m "feat: update CharacterController to use Map access"
```

### Task 12: 更新 PromptBuilder — 大纲生成改为结构化 JSON

**Files:**
- Modify: `comic/src/main/java/com/comic/ai/PromptBuilder.java`

**变更要点：**
- `buildScriptOutlineSystemPrompt`: 修改 prompt 要求 AI 输出结构化 JSON
- `buildScriptOutlineUserPrompt`: 添加结构化 JSON 输出格式说明

- [ ] **Step 1: 修改 buildScriptOutlineSystemPrompt**

要求 AI 输出包含 `characters`、`items`、`episodes` 数组的 JSON：

```java
public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience) {
    return "你是一名专业的漫画剧本编剧。\n"
        + "请根据用户提供的信息生成结构化的剧本大纲。\n"
        + "题材类型：" + genre + "\n"
        + "目标受众：" + targetAudience + "\n"
        + "总集数：" + totalEpisodes + "\n\n"
        + "输出格式：仅返回 JSON，不要 markdown 代码块标记。\n"
        + "JSON 结构：\n"
        + "{\n"
        + "  \"outline\": \"Markdown 格式的完整大纲文本\",\n"
        + "  \"characters\": [{\"name\":\"...\",\"role\":\"主角/反派/配角\",\"personality\":\"...\",\"appearance\":\"...\",\"background\":\"...\"}],\n"
        + "  \"items\": [{\"name\":\"...\",\"description\":\"...\"}],\n"
        + "  \"episodes\": [{\"ep\":1,\"title\":\"...\",\"synopsis\":\"...\",\"characters\":[\"角色名\"],\"keyItems\":[\"物品名\"]}]\n"
        + "}\n\n"
        + "要求：\n"
        + "1. outline 包含完整的世界观、角色小传、关键物品设定、章节剧情线\n"
        + "2. episodes 数组长度必须等于总集数 " + totalEpisodes + "\n"
        + "3. 每集 synopsis 100-200 字\n"
        + "4. characters 和 items 尽可能详细";
}
```

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/ai/PromptBuilder.java
git commit -m "feat: update PromptBuilder to output structured JSON outline"
```

### Task 13: 更新 CharacterRepository — 新增 deleteByProjectId

**Files:**
- Modify: `comic/src/main/java/com/comic/repository/CharacterRepository.java`

- [ ] **Step 1: 新增 deleteByProjectId 方法**

```java
default void deleteByProjectId(String projectId) {
    delete(new LambdaQueryWrapper<Character>()
        .eq(Character::getProjectId, projectId));
}
```

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/repository/CharacterRepository.java
git commit -m "feat: add deleteByProjectId to CharacterRepository"
```

---

## 阶段四：Controller 重构

### Task 14: 重构 ProjectController

**Files:**
- Modify: `comic/src/main/java/com/comic/controller/ProjectController.java`

**变更要点：**
- 移除所有脚本相关接口（generateScript、confirmScript、reviseScript、updateScriptOutline、generateEpisodes、generateAllEpisodes）
- 保留：createProject、getProjectStatus、getProjectStatusDetail、getProjects
- 新增：updateProject (PUT)、partialUpdateProject (PATCH)、deleteProject (DELETE)
- 修改：advancePipeline 接受 AdvanceRequest（含 direction）
- 修改：getProjects 支持分页/筛选/排序

- [ ] **Step 1: 重写 ProjectController**

完整重构后的 ProjectController 应包含以下接口：

```
POST   /api/projects                    — 创建项目
GET    /api/projects                    — 项目列表（分页）
GET    /api/projects/{projectId}        — 项目详情
PUT    /api/projects/{projectId}        — 全量更新
PATCH  /api/projects/{projectId}        — 部分更新
DELETE /api/projects/{projectId}        — 逻辑删除
GET    /api/projects/{projectId}/status — 状态详情
POST   /api/projects/{projectId}/status/advance — 推进/回退
```

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/controller/ProjectController.java
git commit -m "feat: refactor ProjectController with CRUD, pagination, status, rollback"
```

### Task 15: 新建 ScriptController

**Files:**
- Create: `comic/src/main/java/com/comic/controller/ScriptController.java`

- [ ] **Step 1: 创建 ScriptController**

```java
package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.ScriptReviseRequest;
import com.comic.service.script.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/script")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ScriptController {

    private final ScriptService scriptService;

    @GetMapping
    @Operation(summary = "获取剧本内容")
    public Result<?> getScriptContent(@PathVariable String projectId) {
        return Result.ok(scriptService.getScriptContent(projectId));
    }

    @PostMapping("/generate")
    @Operation(summary = "生成大纲")
    public Result<Void> generateOutline(@PathVariable String projectId) {
        scriptService.generateScriptOutline(projectId);
        return Result.ok();
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认剧本（自动分批生成剧集）")
    public Result<Void> confirmScript(@PathVariable String projectId) {
        scriptService.confirmScript(projectId);
        return Result.ok();
    }

    @PostMapping("/revise")
    @Operation(summary = "修改剧本")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody ScriptReviseRequest request) {
        scriptService.reviseOutline(projectId, request.getRevisionNote(), request.getCurrentOutline());
        return Result.ok();
    }

    @PatchMapping("/outline")
    @Operation(summary = "手动保存大纲")
    public Result<Void> saveOutline(@PathVariable String projectId,
                                    @RequestBody Map<String, String> body) {
        String outline = body.get("outline");
        if (outline == null || outline.trim().isEmpty()) {
            return Result.fail("大纲内容不能为空");
        }
        scriptService.updateScriptOutline(projectId, outline.trim());
        return Result.ok();
    }
}
```

- [ ] **Step 2: 编译验证并提交**

```bash
git add comic/src/main/java/com/comic/controller/ScriptController.java
git commit -m "feat: create ScriptController with 5 script endpoints"
```

### Task 16: 全量编译 + 启动验证

- [ ] **Step 1: 全量编译**

Run: `cd comic && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: 启动应用验证**

Run: `cd comic && mvn spring-boot:run`
Expected: 应用启动成功，无报错

- [ ] **Step 3: 最终提交**

```bash
git add -A
git commit -m "chore: Project Controller redesign complete"
```
