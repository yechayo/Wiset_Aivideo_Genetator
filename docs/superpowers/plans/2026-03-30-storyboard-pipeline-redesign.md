# 分镜流水线重设计 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将单 Panel 漫画转视频流水线替换为多分镜故事板流水线，支持九宫格图片生成/审核、融合参考图、Vidu 多镜头视频生成。

**Architecture:** 新增 StoryboardService（DeepSeek 分镜脚本+贪心分组）和 GridImageService（Seedream 九宫格+切割+融合）。改造 PipelineService 状态机（30+处引用清理）、PanelProductionService 生产流程、PanelController API 接口。删除 ComicGenerationService 及相关旧服务。前端新增九宫格审核组件，重构 Step5 生产页面。

**Tech Stack:** Spring Boot 2.7.18 / Java 8 / MyBatis-Plus / JUnit 5 / Mockito (后端)；React 19 / TypeScript / Zustand / Less CSS Modules (前端)

**Spec 文件:** `docs/superpowers/specs/2026-03-30-storyboard-pipeline-redesign.md`

---

## 文件结构总览

### 后端 - 新增文件
| 文件 | 职责 |
|------|------|
| `service/storyboard/StoryboardService.java` | 分镜脚本生成 + 贪心分组 + Panel 创建 |
| `service/panel/GridImageService.java` | 九宫格生成 + 切割 + 融合参考图 |

### 后端 - 修改文件
| 文件 | 改动 |
|------|------|
| `common/ProjectStatus.java` | 新增/删除枚举值，更新转换表 |
| `service/pipeline/PipelineService.java` | 全面改造状态转换（30+处） |
| `service/job/JobQueueService.java` | PANEL_GENERATING → 新状态 |
| `ai/text/DeepSeekTextService.java` | 新增 generateEpisodeScript()、generateStoryboard() |
| `ai/PanelPromptBuilder.java` | 新增多镜头/九宫格提示词，删除旧方法 |
| `service/production/PanelProductionService.java` | 改为 grid→video 流程 |
| `service/panel/PanelGenerationService.java` | 清理旧状态引用（与新 StoryboardService 共存） |
| `controller/PanelController.java` | 新增/删除/改造 API |

### 后端 - 删除文件
| 文件 | 原因 |
|------|------|
| `service/production/ComicGenerationService.java` | 四宫格漫画被九宫格取代 |
| `dto/response/ComicStatusResponse.java` | 不再使用 |
| `dto/response/PanelBackgroundResponse.java` | 不再单独生成背景 |
| `dto/request/ComicReviseRequest.java` | comic/revise 接口删除 |
| `dto/response/PanelProductionStatusResponse.java` | 被 Map<String, Object> 替代 |

### 后端 - 测试文件
| 文件 | 改动 |
|------|------|
| `common/ProjectStatusTransitionTest.java` | 更新状态转换断言 |
| `service/pipeline/PipelineServiceAutoAdvanceTest.java` | 更新自动推进测试 |
| `e2e/ProjectStateMachineE2ETest.java` | 清理旧状态引用 |
| `controller/ProjectProductionSummaryApiTest.java` | 清理旧字段引用 |
| `service/storyboard/StoryboardServiceTest.java` | 新增 |
| `service/panel/GridImageServiceTest.java` | 新增 |

### 前端 - 新增文件
| 文件 | 职责 |
|------|------|
| `steps/components/StoryboardGrid.tsx` | 九宫格分镜图展示（多页） |
| `steps/components/ShotTimeline.tsx` | 分镜时间线 |
| `steps/components/ShotDetail.tsx` | 单分镜详情 |
| `steps/components/GridReviewPanel.tsx` | 审核面板（通过/拒绝/重新生成） |
| `steps/components/BatchReviewBar.tsx` | 批量审核工具栏 |
| `steps/components/StoryboardGrid.module.less` | 样式 |

### 前端 - 修改文件
| 文件 | 改动 |
|------|------|
| `services/types/episode.types.ts` | 新增 shot/grid/fusion 类型 |
| `services/types/project.types.ts` | 更新状态联合类型 |
| `services/episodeService.ts` | 新增九宫格 API，删除旧 API，确保 put 方法存在 |
| `services/apiClient.ts` | 确保 put 方法存在 |
| `pages/create/steps/Step5page.tsx` | 重构为九宫格审核流程 |
| `pages/create/steps/components/SegmentCard.tsx` | 重构为分镜卡片 |
| `pages/create/steps/components/EpisodeCard.tsx` | 清理 ComicPanel 引用 |
| `pages/create/steps/types.ts` | 更新 SegmentPipelineStep |
| `stores/createStore.ts` | 新增 storyboardShots/gridImages/gridStatus/fusionImageUrl |
| `pages/create/CreateLayout.tsx` | PANEL_GENERATING → 新状态 |

### 前端 - 删除文件
| 文件 | 原因 |
|------|------|
| `steps/components/ComicPanel.tsx` | 被 GridReviewPanel 取代 |

---

## Task 1: ProjectStatus 枚举更新

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/common/ProjectStatus.java`
- Test: `backend/com/comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java`

- [ ] **Step 1: 更新枚举值 — 新增 5 个状态，删除 4 个状态**

在 `ProjectStatus.java` 的枚举定义中：

**删除:**
```java
PANEL_GENERATING("PANEL_GENERATING", "分镜生成中", 5),
PANEL_REVIEW("PANEL_REVIEW", "分镜审核", 5),
PANEL_GENERATING_FAILED("PANEL_GENERATING_FAILED", "分镜生成失败", 5),
VIDEO_ASSEMBLING("VIDEO_ASSEMBLING", "拼接剪辑中", 6),
```

**新增:**
```java
EPISODE_SCRIPT_GENERATING("EPISODE_SCRIPT_GENERATING", "分集剧本生成中", 5),
EPISODE_SCRIPT_GENERATING_FAILED("EPISODE_SCRIPT_GENERATING_FAILED", "分集剧本生成失败", 5),
STORYBOARD_GENERATING("STORYBOARD_GENERATING", "分镜生成中", 5),
STORYBOARD_GENERATING_FAILED("STORYBOARD_GENERATING_FAILED", "分镜生成失败", 5),
STORYBOARD_REVIEW("STORYBOARD_REVIEW", "分镜审核", 5),
```

- [ ] **Step 2: 更新状态转换表 ALLOWED_TRANSITIONS**

**删除** 旧转换:
```java
put(map, ASSET_LOCKED, "start_panels", PANEL_GENERATING);
put(map, PANEL_GENERATING, "panels_generated", PANEL_REVIEW);
put(map, PANEL_GENERATING, "panels_failed", PANEL_GENERATING_FAILED);
put(map, PANEL_REVIEW, "confirm_panels", PANEL_REVIEW);
put(map, PANEL_REVIEW, "all_panels_confirmed", PRODUCING);
put(map, PANEL_REVIEW, "revise_panels", PANEL_GENERATING);
put(map, PANEL_GENERATING_FAILED, "retry", ASSET_LOCKED);
put(map, PANEL_GENERATING, "retry", ASSET_LOCKED);
put(map, PRODUCING, "production_completed", VIDEO_ASSEMBLING);
put(map, VIDEO_ASSEMBLING, "assembly_completed", COMPLETED);
```

**新增** 新转换:
```java
// 素材 → 分集剧本
put(map, ASSET_LOCKED, "start_episode_script", EPISODE_SCRIPT_GENERATING);

// 分集剧本阶段
put(map, EPISODE_SCRIPT_GENERATING, "episode_script_generated", STORYBOARD_GENERATING);
put(map, EPISODE_SCRIPT_GENERATING, "episode_script_failed", EPISODE_SCRIPT_GENERATING_FAILED);
// retry 回到自身阶段（保留已生成的剧本数据）
put(map, EPISODE_SCRIPT_GENERATING_FAILED, "retry", EPISODE_SCRIPT_GENERATING);
put(map, EPISODE_SCRIPT_GENERATING, "retry", ASSET_LOCKED);

// 分镜生成阶段
put(map, STORYBOARD_GENERATING, "storyboard_generated", STORYBOARD_REVIEW);
put(map, STORYBOARD_GENERATING, "storyboard_failed", STORYBOARD_GENERATING_FAILED);
// retry 回到自身阶段
put(map, STORYBOARD_GENERATING_FAILED, "retry", STORYBOARD_GENERATING);
put(map, STORYBOARD_GENERATING, "retry", ASSET_LOCKED);

// 分镜审核阶段
put(map, STORYBOARD_REVIEW, "all_grids_approved", PRODUCING);
put(map, STORYBOARD_REVIEW, "regenerate_storyboard", EPISODE_SCRIPT_GENERATING);

// 生产 → 完成（直接完成，不再需要拼接）
put(map, PRODUCING, "production_completed", COMPLETED);
```

- [ ] **Step 3: 更新 getCompletedSteps()**

```java
if (this == SCRIPT_CONFIRMED || this == CHARACTER_CONFIRMED || this == ASSET_LOCKED
        || this == COMPLETED || this == STORYBOARD_REVIEW) {
    steps.add(current);
}
```

- [ ] **Step 4: 更新 getAvailableActions()**

```java
case ASSET_LOCKED:
    return Arrays.asList();
case EPISODE_SCRIPT_GENERATING:
    return Arrays.asList();
case EPISODE_SCRIPT_GENERATING_FAILED:
    return Arrays.asList("retry");
case STORYBOARD_GENERATING:
    return Arrays.asList();
case STORYBOARD_GENERATING_FAILED:
    return Arrays.asList("retry");
case STORYBOARD_REVIEW:
    return Arrays.asList("approve_all_grids", "regenerate_storyboard");
case PRODUCING:
    return Arrays.asList();
case COMPLETED:
    return Arrays.asList("view_result");
```

删除旧的 `PANEL_GENERATING`、`PANEL_REVIEW`、`VIDEO_ASSEMBLING` case。失败状态 default 分支的 `retry` 已覆盖两个新 FAILED 状态。

- [ ] **Step 5: 更新 fromCode() 兼容映射**

```java
if ("PANEL_GENERATING".equals(code)) {
    return STORYBOARD_GENERATING;
}
if ("PANEL_REVIEW".equals(code)) {
    return STORYBOARD_REVIEW;
}
if ("PANEL_GENERATING_FAILED".equals(code)) {
    return STORYBOARD_GENERATING_FAILED;
}
if ("VIDEO_ASSEMBLING".equals(code)) {
    return PRODUCING;
}
```

- [ ] **Step 6: 更新 ProjectStatusTransitionTest**

删除旧的 3 个测试，新增：

```java
@Test
void should_resolve_episode_script_generated_to_storyboard_generating() {
    assertEquals(ProjectStatus.STORYBOARD_GENERATING,
        ProjectStatus.resolveTransition(ProjectStatus.EPISODE_SCRIPT_GENERATING, "episode_script_generated"));
}

@Test
void should_resolve_storyboard_generated_to_storyboard_review() {
    assertEquals(ProjectStatus.STORYBOARD_REVIEW,
        ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_GENERATING, "storyboard_generated"));
}

@Test
void should_resolve_all_grids_approved_to_producing() {
    assertEquals(ProjectStatus.PRODUCING,
        ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_REVIEW, "all_grids_approved"));
}

@Test
void should_resolve_production_completed_to_completed() {
    assertEquals(ProjectStatus.COMPLETED,
        ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"));
}

@Test
void should_resolve_episode_script_failed_to_failed_state() {
    assertEquals(ProjectStatus.EPISODE_SCRIPT_GENERATING_FAILED,
        ProjectStatus.resolveTransition(ProjectStatus.EPISODE_SCRIPT_GENERATING, "episode_script_failed"));
}

@Test
void should_resolve_storyboard_failed_to_failed_state() {
    assertEquals(ProjectStatus.STORYBOARD_GENERATING_FAILED,
        ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_GENERATING, "storyboard_failed"));
}

@Test
void should_resolve_storyboard_review_regenerate_to_episode_script() {
    assertEquals(ProjectStatus.EPISODE_SCRIPT_GENERATING,
        ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_REVIEW, "regenerate_storyboard"));
}

@Test
void should_resolve_episode_script_failed_retry_to_itself() {
    assertEquals(ProjectStatus.EPISODE_SCRIPT_GENERATING,
        ProjectStatus.resolveTransition(ProjectStatus.EPISODE_SCRIPT_GENERATING_FAILED, "retry"));
}

@Test
void should_resolve_storyboard_failed_retry_to_itself() {
    assertEquals(ProjectStatus.STORYBOARD_GENERATING,
        ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_GENERATING_FAILED, "retry"));
}
```

- [ ] **Step 7: 运行测试**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -pl . -Dtest=ProjectStatusTransitionTest -Dsurefire.useFile=false`
Expected: 所有测试 PASS

- [ ] **Step 8: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/common/ProjectStatus.java
git add backend/com/comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java
git commit -m "feat: update ProjectStatus enum with new storyboard pipeline states"
```

---

## Task 2: PipelineService 全面改造

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
- Test: `backend/com/comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`

> **重要**: PipelineService.java 有 30+ 处引用被删除的状态（PANEL_GENERATING, PANEL_REVIEW, PANEL_GENERATING_FAILED, VIDEO_ASSEMBLING, comicStatus, backgroundStatus）。实施者必须通读整个文件，逐一替换。

- [ ] **Step 1: 全局搜索替换旧状态引用**

在 `PipelineService.java` 中搜索并替换所有旧状态引用：

```
PANEL_GENERATING     → STORYBOARD_GENERATING
PANEL_REVIEW         → STORYBOARD_REVIEW
PANEL_GENERATING_FAILED → STORYBOARD_GENERATING_FAILED
VIDEO_ASSEMBLING     → PRODUCING（或根据上下文使用 COMPLETED）
"panels_generated"   → "storyboard_generated"
"panels_failed"      → "storyboard_failed"
"start_panels"       → "start_episode_script"
"all_panels_confirmed" → "all_grids_approved"
"production_completed" → "production_completed"（保持不变）
"assembly_completed"  → 删除此分支（PRODUCING → COMPLETED）
```

特别注意：
- `getRollbackTarget()` 中所有 PANEL_REVIEW/VIDEO_ASSEMBLING 的 case
- `cleanupAfterRollback()` 中对应的 case
- 状态映射/enrich 方法中的引用
- Panel 状态汇总逻辑中的 comicStatus/backgroundStatus 检查
- Panel 状态轮询/推进逻辑中的 PANEL_GENERATING

- [ ] **Step 2: 更新 collapseAutoAdvance()**

```java
// ASSET_LOCKED → EPISODE_SCRIPT_GENERATING → STORYBOARD_GENERATING（collapse）
if (next == ProjectStatus.EPISODE_SCRIPT_GENERATING) {
    return ProjectStatus.STORYBOARD_GENERATING;
}
```

- [ ] **Step 3: 更新 triggerNextStage() — 注入 StoryboardService**

新增依赖注入和分支：

```java
// 在构造函数/字段中注入
@Resource
private StoryboardService storyboardService;

// 在 triggerNextStage() 中
case EPISODE_SCRIPT_GENERATING:
    storyboardService.generateEpisodeScriptAndStoryboard(projectId);
    break;
```

注意：如果 StoryboardService 尚未创建，先创建一个空壳 `@Service` 类让编译通过。

- [ ] **Step 4: 更新 getRollbackTarget()**

```java
if (status == ProjectStatus.STORYBOARD_REVIEW) {
    return ProjectStatus.EPISODE_SCRIPT_GENERATING;
}
if (status == ProjectStatus.PRODUCING) {
    return ProjectStatus.STORYBOARD_REVIEW;
}
if (status == ProjectStatus.EPISODE_SCRIPT_GENERATING_FAILED) {
    return ProjectStatus.EPISODE_SCRIPT_GENERATING;
}
if (status == ProjectStatus.STORYBOARD_GENERATING_FAILED) {
    return ProjectStatus.STORYBOARD_GENERATING;
}
```

- [ ] **Step 5: 更新 cleanupAfterRollback()**

回滚到 EPISODE_SCRIPT_GENERATING 时清理所有 Panel 的 panelInfo 字段：
```java
if (target == ProjectStatus.EPISODE_SCRIPT_GENERATING) {
    cleanupStoryboardData(projectId);
}
```

回滚到 STORYBOARD_REVIEW 时清理视频数据：
```java
if (target == ProjectStatus.STORYBOARD_REVIEW) {
    cleanupVideoData(projectId);
}
```

- [ ] **Step 6: 删除 comicStatus/backgroundStatus 相关逻辑**

搜索并移除所有引用 `comicStatus`、`comicUrl`、`backgroundStatus`、`backgroundUrl` 的代码。这些字段在新流水线中不再使用。

- [ ] **Step 7: 更新 PipelineServiceAutoAdvanceTest**

```java
// 删除旧测试:
// confirm_images_should_eventually_enter_panel_generating
// production_completed_should_transition_to_video_assembling
// assembly_completed_should_transition_to_completed

// 新增:
@Test
void confirm_images_should_eventually_enter_storyboard_generating() {
    Project project = createTestProject("test-project-3");
    project.setStatus(ProjectStatus.IMAGE_REVIEW.getCode());
    when(projectRepository.findByProjectId("test-project-3")).thenReturn(project);
    when(projectRepository.updateById(any(Project.class))).thenReturn(1);

    StoryboardService storyboardService = mock(StoryboardService.class);
    ReflectionTestUtils.setField(pipelineService, "storyboardService", storyboardService);

    pipelineService.advancePipeline("test-project-3", "confirm_images");
    Thread.sleep(200);

    verify(storyboardService, times(1)).generateEpisodeScriptAndStoryboard(eq("test-project-3"));
    assertEquals(ProjectStatus.STORYBOARD_GENERATING.getCode(), project.getStatus());
}

@Test
void production_completed_should_transition_to_completed() {
    Project project = createTestProject("test-project-4");
    project.setStatus(ProjectStatus.PRODUCING.getCode());
    when(projectRepository.findByProjectId("test-project-4")).thenReturn(project);
    when(projectRepository.updateById(any(Project.class))).thenReturn(1);

    pipelineService.advancePipeline("test-project-4", "production_completed");

    ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
    verify(projectRepository).updateById(captor.capture());
    assertEquals(ProjectStatus.COMPLETED.getCode(), captor.getValue().getStatus());
}
```

- [ ] **Step 8: 运行测试**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -pl . -Dtest=PipelineServiceAutoAdvanceTest -Dsurefire.useFile=false`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/pipeline/PipelineService.java
git add backend/com/comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java
git commit -m "refactor: comprehensive PipelineService update for new storyboard states"
```

---

## Task 3: JobQueueService + PanelGenerationService 旧状态清理

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/job/JobQueueService.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/PanelGenerationService.java`

- [ ] **Step 1: 在 JobQueueService.java 中搜索替换旧状态**

搜索 `"PANEL_GENERATING"` 引用（约4处），替换为新状态。具体行为取决于上下文：
- Panel 生成完成 → `STORYBOARD_GENERATING`
- Panel 生成失败 → `STORYBOARD_GENERATING_FAILED`
- 判断是否在生成中 → 检查 `STORYBOARD_GENERATING` 或 `EPISODE_SCRIPT_GENERATING`

- [ ] **Step 2: 在 PanelGenerationService.java 中搜索替换旧状态**

搜索 `"PANEL_GENERATING"` 引用（约8处），替换为新状态。注意：
- `PanelGenerationService` 在新流水线中仍负责 Panel 的 CRUD 操作
- 新增的 `StoryboardService` 负责分镜生成+分组+创建
- 两者通过 PanelRepository 共存，`PanelGenerationService` 的 `confirmPanels()`、`retryFailedPanels()` 等方法需要适配新状态

搜索并替换：
- `"PANEL_GENERATING"` → `"STORYBOARD_GENERATING"`
- `"PANEL_REVIEW"` → `"STORYBOARD_REVIEW"`
- `"PANEL_GENERATING_FAILED"` → `"STORYBOARD_GENERATING_FAILED"`
- `comicUrl`、`comicStatus`、`backgroundUrl`、`backgroundStatus` 引用 → 移除或替换为 `gridStatus`、`gridImages` 等

- [ ] **Step 3: 运行编译检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/job/JobQueueService.java
git add backend/com/comic/src/main/java/com/comic/service/panel/PanelGenerationService.java
git commit -m "refactor: update JobQueueService and PanelGenerationService for new states"
```

---

## Task 4: 删除旧服务和 DTO

**Files:**
- Delete: `backend/com/comic/src/main/java/com/comic/service/production/ComicGenerationService.java`
- Delete: `backend/com/comic/src/main/java/com/comic/dto/response/ComicStatusResponse.java`
- Delete: `backend/com/comic/src/main/java/com/comic/dto/response/PanelBackgroundResponse.java`
- Delete: `backend/com/comic/src/main/java/com/comic/dto/request/ComicReviseRequest.java`
- Delete: `backend/com/comic/src/main/java/com/comic/dto/response/PanelProductionStatusResponse.java`
- Modify: `backend/com/comic/src/main/java/com/comic/controller/PanelController.java` (移除 import 和注入)
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java` (删除旧方法)

- [ ] **Step 1: 删除旧文件**

```bash
rm backend/com/comic/src/main/java/com/comic/service/production/ComicGenerationService.java
rm backend/com/comic/src/main/java/com/comic/dto/response/ComicStatusResponse.java
rm backend/com/comic/src/main/java/com/comic/dto/response/PanelBackgroundResponse.java
rm backend/com/comic/src/main/java/com/comic/dto/request/ComicReviseRequest.java
rm backend/com/comic/src/main/java/com/comic/dto/response/PanelProductionStatusResponse.java
```

- [ ] **Step 2: 清理 PanelController 中的 ComicGenerationService 引用**

移除 `import com.comic.service.production.ComicGenerationService;` 和字段注入。

- [ ] **Step 3: 删除 PanelPromptBuilder 中的旧方法**

删除：
- `buildBackgroundPrompt()`
- `buildComicPrompt()`

- [ ] **Step 4: 全局编译验证**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn compile -pl . -q 2>&1`
Expected: BUILD SUCCESS。如果有编译错误，说明其他文件引用了被删除的类，逐一修复。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove ComicGenerationService and obsolete DTOs"
```

---

## Task 5: DeepSeekTextService — 分集剧本 + 分镜脚本生成

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java`

- [ ] **Step 1: 新增 generateEpisodeScript()**

```java
/**
 * 生成结构化分集剧本（JSON 格式）
 */
public List<Map<String, Object>> generateEpisodeScript(
        String outlineNode, String characters, int durationSeconds, String visualStyle) {
    String systemPrompt = "你是一位专业的影视编剧。请根据提供的大纲和角色信息，生成结构化分集剧本。\n"
        + "输出格式为纯 JSON 数组，不要包含 markdown 代码块标记。\n"
        + "每个元素包含以下字段：\n"
        + "- title: 集标题\n"
        + "- content: 剧本正文内容（约" + (durationSeconds / 60) + "分钟对应的字数，中文约200-250字/分钟）\n"
        + "- characters: 本集出场角色，逗号分隔\n"
        + "- keyItems: 本集关键道具/场景，逗号分隔\n"
        + "- continuityNote: 连贯性备注\n"
        + "注意：内容要紧凑，适合" + durationSeconds + "秒的短视频。";

    String userPrompt = "大纲节点：" + outlineNode + "\n"
        + "角色：" + characters + "\n"
        + "视觉风格：" + visualStyle + "\n"
        + "目标时长：" + durationSeconds + "秒\n"
        + "请生成结构化分集剧本 JSON。";

    String response = generate(systemPrompt, userPrompt);
    return parseJsonArray(response);
}

/**
 * 生成分镜脚本（DetailedStoryboardShot 数组）
 */
public List<Map<String, Object>> generateStoryboard(
        String episodeContent, String characters, int totalDuration, String visualStyle) {
    int recommendedShots = Math.max(1, totalDuration * 10 / 25);

    String systemPrompt = "你是一位专业的影视分镜师。请根据提供的剧本内容，生成详细的分镜脚本。\n"
        + "关键约束：\n"
        + "- 每个分镜时长：1-4秒\n"
        + "- 所有分镜时长总和必须 >= " + totalDuration + "秒\n"
        + "- 推荐分镜数量：" + recommendedShots + " 个\n"
        + "- 输出纯 JSON 数组，不要包含 markdown 代码块标记\n\n"
        + "每个分镜包含以下字段：\n"
        + "- shotNumber: 镜头编号（从1开始）\n"
        + "- duration: 时长（秒，1-4）\n"
        + "- scene: 场景描述\n"
        + "- characters: 出场角色数组\n"
        + "- shotSize: 景别（大远景/远景/全景/中景/中近景/近景/特写/大特写）\n"
        + "- cameraAngle: 角度（视平/高位俯拍/低位仰拍/斜拍/越肩/鸟瞰）\n"
        + "- cameraMovement: 运镜（固定/横移/俯仰/横摇/升降/轨道推拉/变焦推拉/正跟随/倒跟随/环绕/滑轨横移）\n"
        + "- visualDescription: 画面描述\n"
        + "- dialogue: 对白（无则填"无"）\n"
        + "- visualEffects: 视觉特效（无则填"无"）\n"
        + "- audioEffects: 音效（无则填"无"）";

    String userPrompt = "剧本内容：\n" + episodeContent + "\n\n"
        + "角色：" + characters + "\n"
        + "视觉风格：" + visualStyle + "\n"
        + "目标总时长：" + totalDuration + "秒\n\n"
        + "请生成详细的分镜脚本 JSON 数组。";

    String response = generate(systemPrompt, userPrompt);
    List<Map<String, Object>> shots = parseJsonArray(response);

    if (shots == null || shots.isEmpty()) {
        throw new BusinessException("分镜生成结果为空，请重试");
    }

    // 钳制时长到 1-4 秒
    for (Map<String, Object> shot : shots) {
        int duration = ((Number) shot.get("duration")).intValue();
        duration = Math.max(1, Math.min(4, duration));
        shot.put("duration", duration);
    }

    return shots;
}

/** 解析 DeepSeek 返回的 JSON 数组 */
private List<Map<String, Object>> parseJsonArray(String jsonStr) {
    String cleaned = jsonStr.trim();
    if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
    else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
    if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
    cleaned = cleaned.trim();

    try {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
    } catch (JsonProcessingException e) {
        throw new BusinessException("JSON 解析失败: " + e.getMessage());
    }
}
```

需要在文件顶部添加 import：
```java
import com.comic.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
```

- [ ] **Step 2: 运行编译检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java
git commit -m "feat: add generateEpisodeScript() and generateStoryboard() to DeepSeekTextService"
```

---

## Task 6: PanelPromptBuilder — 多镜头和九宫格提示词

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java`

- [ ] **Step 1: 新增 buildGridPrompt()**

```java
/**
 * 构建九宫格图片生成提示词
 */
public String buildGridPrompt(VisualStyle visualStyle, List<Map<String, Object>> shots,
                               List<String> charReferences) {
    StringBuilder sb = new StringBuilder();
    sb.append(buildSceneStylePrefix(visualStyle));
    sb.append("\n\n生成一张 3x3 分镜九宫格图片。图片比例 16:9，黑色细边框分隔。保持角色外观一致性，图片中不包含任何文字。\n\n");

    if (charReferences != null && !charReferences.isEmpty()) {
        sb.append("角色参考：").append(String.join("、", charReferences)).append("\n\n");
    }

    for (Map<String, Object> shot : shots) {
        sb.append("面板 ").append(shot.get("shotNumber")).append(": ");
        sb.append("16:9 - ").append(shot.getOrDefault("visualDescription", ""));
        sb.append(" ").append(shot.getOrDefault("shotSize", ""));
        sb.append(" ").append(shot.getOrDefault("cameraAngle", ""));
        sb.append(" environment: ").append(shot.getOrDefault("scene", ""));
        sb.append("\n");
    }

    int emptySlots = 9 - shots.size();
    if (emptySlots > 0) {
        sb.append("剩余 ").append(emptySlots).append(" 个面板: (empty panel - storyboard end)");
    }
    return sb.toString();
}
```

- [ ] **Step 2: 新增 buildMultiShotPrompt()**

```java
/**
 * 构建多镜头视频生成提示词
 */
@SuppressWarnings("unchecked")
public String buildMultiShotPrompt(VisualStyle visualStyle, Map<String, Object> panelInfo) {
    StringBuilder sb = new StringBuilder();
    sb.append(buildSceneStylePrefix(visualStyle));
    sb.append(" 专业电影级画面。\n\n");

    List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
    int n = shots != null ? shots.size() : 0;
    sb.append("多镜头连续拍摄指令，以下 ").append(n).append(" 个镜头必须在同一视频中连续呈现：\n\n");

    if (shots != null) {
        for (Map<String, Object> shot : shots) {
            int shotNum = ((Number) shot.get("shotNumber")).intValue();
            sb.append("Shot ").append(shotNum).append(":\n");
            sb.append("duration: ").append(shot.get("duration")).append("s\n");
            sb.append("Scene: ").append(shot.getOrDefault("shotSize", ""))
              .append(", ").append(shot.getOrDefault("cameraAngle", ""))
              .append(", ").append(shot.getOrDefault("cameraMovement", ""))
              .append(", ").append(shot.getOrDefault("visualDescription", "")).append("\n");

            String dialogue = (String) shot.get("dialogue");
            if (dialogue != null && !"无".equals(dialogue)) {
                sb.append("对白: ").append(dialogue).append("\n");
            }
            String audioEffects = (String) shot.get("audioEffects");
            if (audioEffects != null && !"无".equals(audioEffects)) {
                sb.append("音效: [").append(audioEffects).append("]\n");
            }
            sb.append("\n");
        }
    }

    sb.append("## 画面衔接\n视频应从参考图自然展开，多镜头间平滑过渡。\n");
    sb.append("保持角色位置和动作的连贯性。\n");
    sb.append("参考图中编号①②③对应 Shot 1/2/3 的画面内容。");
    return sb.toString();
}
```

注意：`VisualStyle` 使用 `com.comic.ai.CharacterPromptManager.VisualStyle`（现有代码中的内部枚举）。

- [ ] **Step 3: 运行编译检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git commit -m "feat: add buildGridPrompt() and buildMultiShotPrompt() to PanelPromptBuilder"
```

---

## Task 7: StoryboardService — 分镜脚本生成 + 贪心分组

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java`
- Create: `backend/com/comic/src/test/java/com/comic/service/storyboard/StoryboardServiceTest.java`

- [ ] **Step 1: 编写贪心分组测试**

```java
package com.comic.service.storyboard;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StoryboardServiceTest {

    @Test
    void greedy_grouping_should_not_exceed_16s_per_group() {
        List<Map<String, Object>> shots = createShots(new int[]{3, 2, 4, 3, 2, 1, 4});
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        assertEquals(2, groups.size());
        for (List<Map<String, Object>> group : groups) {
            int total = group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();
            assertTrue(total <= 16, "每组时长不应超过16秒, 实际: " + total);
        }
    }

    @Test
    void greedy_grouping_should_handle_single_group() {
        List<Map<String, Object>> shots = createShots(new int[]{3, 2, 4});
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        assertEquals(1, groups.size());
        assertEquals(3, groups.get(0).size());
    }

    @Test
    void greedy_grouping_should_handle_empty_shots() {
        assertEquals(0, StoryboardService.greedyGroup(new ArrayList<>(), 16).size());
    }

    @Test
    void greedy_grouping_should_clamp_oversized_shot() {
        List<Map<String, Object>> shots = createShots(new int[]{20});
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        assertEquals(1, groups.size());
    }

    @Test
    void greedy_grouping_should_produce_many_groups() {
        int[] durations = new int[24];
        Arrays.fill(durations, 3);
        List<Map<String, Object>> shots = createShots(durations);
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        for (List<Map<String, Object>> group : groups) {
            int total = group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();
            assertTrue(total <= 16);
        }
    }

    private List<Map<String, Object>> createShots(int[] durations) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", durations[i]);
            shots.add(shot);
        }
        return shots;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -pl . -Dtest=StoryboardServiceTest -Dsurefire.useFile=false`
Expected: FAIL

- [ ] **Step 3: 实现 StoryboardService**

```java
package com.comic.service.storyboard;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.character.CharacterService;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.pipeline.ProjectStatusBroadcaster;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StoryboardService {

    private static final int MAX_PANEL_DURATION = 16;

    @Resource
    private DeepSeekTextService deepSeekTextService;
    @Resource
    private EpisodeRepository episodeRepository;
    @Resource
    private PanelRepository panelRepository;
    @Resource
    private ProjectRepository projectRepository;
    @Resource
    private PipelineService pipelineService;
    @Resource
    private ProjectStatusBroadcaster broadcaster;

    /**
     * 主入口：生成结构化分集剧本 → 分镜脚本 → 贪心分组 → 创建 Panel → 九宫格
     * 此方法被 PipelineService 异步调用
     */
    @Transactional
    public void generateEpisodeScriptAndStoryboard(String projectId) {
        try {
            Project project = projectRepository.findByProjectId(projectId);
            Map<String, Object> projectInfo = project.getProjectInfo();
            String visualStyle = (String) projectInfo.getOrDefault("visualStyle", "ANIME");
            int targetDuration = getIntFromMap(projectInfo, "episodeDuration", 60);
            String outline = (String) projectInfo.getOrDefault("scriptOutline", "");

            // 获取角色描述
            String charactersDesc = getCharacterDescriptions(projectId);

            // 1. 生成结构化分集剧本
            List<Map<String, Object>> scripts = deepSeekTextService.generateEpisodeScript(
                outline, charactersDesc, targetDuration, visualStyle);

            // 2. 逐集生成分镜并创建 Panel
            for (Map<String, Object> script : scripts) {
                String content = (String) script.get("content");
                String characters = (String) script.getOrDefault("characters", "");

                List<Map<String, Object>> shots = deepSeekTextService.generateStoryboard(
                    content, characters, targetDuration, visualStyle);

                List<List<Map<String, Object>>> groups = greedyGroup(shots, MAX_PANEL_DURATION);
                if (groups.isEmpty()) continue;

                Long episodeId = findOrCreateEpisode(projectId, script);
                deleteExistingPanels(episodeId);
                createPanels(episodeId, groups, visualStyle);
            }

            // 3. 推进状态到 STORYBOARD_REVIEW
            // 注意：九宫格图生成由后续流程异步触发
            pipelineService.advancePipeline(projectId, "storyboard_generated");
            broadcaster.broadcastStatus(projectId);

        } catch (BusinessException e) {
            log.error("分镜生成失败: projectId={}, error={}", projectId, e.getMessage());
            pipelineService.advancePipeline(projectId, "storyboard_failed");
            broadcaster.broadcastStatus(projectId);
        } catch (Exception e) {
            log.error("分镜生成异常: projectId={}", projectId, e);
            pipelineService.advancePipeline(projectId, "storyboard_failed");
            broadcaster.broadcastStatus(projectId);
        }
    }

    /**
     * 贪心分组算法（纯函数，可独立测试）
     */
    public static List<List<Map<String, Object>>> greedyGroup(
            List<Map<String, Object>> shots, int maxDuration) {
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<Map<String, Object>> currentGroup = new ArrayList<>();
        int currentDuration = 0;

        for (Map<String, Object> shot : shots) {
            int duration = ((Number) shot.get("duration")).intValue();
            if (duration > maxDuration) {
                duration = maxDuration;
                shot.put("duration", duration);
            }

            if (currentDuration + duration > maxDuration && !currentGroup.isEmpty()) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentDuration = 0;
            }
            currentGroup.add(shot);
            currentDuration += duration;
        }
        if (!currentGroup.isEmpty()) groups.add(currentGroup);
        return groups;
    }

    // --- 私有方法 ---

    private String getCharacterDescriptions(String projectId) {
        // 从 CharacterService 获取角色描述文本
        // 需根据实际 CharacterService 接口实现
        // TODO: 实现 CharacterService.getCharacterDescriptions(projectId)
        return "";
    }

    private Long findOrCreateEpisode(String projectId, Map<String, Object> script) {
        String title = (String) script.get("title");
        List<Episode> episodes = episodeRepository.selectList(
            new LambdaQueryWrapper<Episode>()
                .eq(Episode::getProjectId, projectId)
                .eq(Episode::getDeleted, false));
        for (Episode ep : episodes) {
            Map<String, Object> info = ep.getEpisodeInfo();
            if (info != null && title.equals(info.get("title"))) {
                info.putAll(script);
                ep.setEpisodeInfo(info);
                episodeRepository.updateById(ep);
                return ep.getId();
            }
        }
        Episode episode = new Episode();
        episode.setProjectId(projectId);
        episode.setStatus("pending");
        episode.setDeleted(false);
        episode.setEpisodeInfo(new HashMap<>(script));
        episodeRepository.insert(episode);
        return episode.getId();
    }

    private void deleteExistingPanels(Long episodeId) {
        List<Panel> existing = panelRepository.selectList(
            new LambdaQueryWrapper<Panel>()
                .eq(Panel::getEpisodeId, episodeId)
                .eq(Panel::getDeleted, false));
        for (Panel p : existing) {
            p.setDeleted(true);
            panelRepository.updateById(p);
        }
    }

    private void createPanels(Long episodeId, List<List<Map<String, Object>>> groups, String visualStyle) {
        for (List<Map<String, Object>> group : groups) {
            Panel panel = new Panel();
            panel.setEpisodeId(episodeId);
            panel.setStatus("pending");
            panel.setDeleted(false);
            Map<String, Object> panelInfo = new HashMap<>();
            panelInfo.put("shots", group);
            panelInfo.put("totalShots", group.size());
            panelInfo.put("totalDuration",
                group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum());
            panelInfo.put("gridStatus", "pending");
            panelInfo.put("gridPageCount", (int) Math.ceil(group.size() / 9.0));
            panelInfo.put("gridImages", new ArrayList<String>());
            panelInfo.put("videoStatus", "pending");
            panelInfo.put("visualStyle", visualStyle);
            panel.setPanelInfo(panelInfo);
            panelRepository.insert(panel);
        }
    }

    private int getIntFromMap(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -pl . -Dtest=StoryboardServiceTest -Dsurefire.useFile=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java
git add backend/com/comic/src/test/java/com/comic/service/storyboard/StoryboardServiceTest.java
git commit -m "feat: add StoryboardService with greedy grouping algorithm"
```

---

## Task 8: GridImageService — 九宫格生成 + 切割 + 融合

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`
- Create: `backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java`

- [ ] **Step 1: 编写切割逻辑测试**

```java
package com.comic.service.panel;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GridImageServiceTest {

    @Test
    void splitGrid_should_produce_9_sub_images() {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
        assertEquals(9, result.size());
        assertEquals(100, result.get(0).getWidth());
        assertEquals(100, result.get(0).getHeight());
    }

    @Test
    void splitGrid_should_handle_non_divisible_size() {
        BufferedImage img = new BufferedImage(301, 301, BufferedImage.TYPE_INT_RGB);
        List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
        assertEquals(9, result.size());
        assertEquals(100, result.get(0).getWidth());
    }

    @Test
    void calculatePagination_should_return_correct_pages() {
        assertEquals(1, GridImageService.calculatePageCount(7, 9));
        assertEquals(1, GridImageService.calculatePageCount(9, 9));
        assertEquals(2, GridImageService.calculatePageCount(10, 9));
        assertEquals(3, GridImageService.calculatePageCount(19, 9));
    }

    @Test
    void calculatePagination_should_handle_zero_shots() {
        assertEquals(0, GridImageService.calculatePageCount(0, 9));
    }
}
```

- [ ] **Step 2: 实现 GridImageService**

```java
package com.comic.service.panel;

import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.SeedreamImageService;
import com.comic.common.BusinessException;
import com.comic.ai.CharacterPromptManager.VisualStyle;
import com.comic.entity.Panel;
import com.comic.repository.PanelRepository;
import com.comic.service.oss.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GridImageService {

    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 3;
    private static final int SHOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
    private static final Color FUSION_BG_COLOR = new Color(0x1a, 0x1a, 0x1c);

    @Resource private SeedreamImageService seedreamImageService;
    @Resource private PanelPromptBuilder panelPromptBuilder;
    @Resource private PanelRepository panelRepository;
    @Resource private OssService ossService;

    /**
     * 为指定 Panel 生成九宫格图 → 切割 → 融合参考图
     */
    public void generateGridsForPanel(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在: " + panelId);

        Map<String, Object> panelInfo = panel.getPanelInfo();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
        String visualStyleStr = (String) panelInfo.getOrDefault("visualStyle", "ANIME");
        VisualStyle visualStyle = VisualStyle.valueOf(visualStyleStr);

        try {
            panelInfo.put("gridStatus", "generating");
            updatePanelInfo(panel, panelInfo);

            List<String> characterRefUrls = getCharacterReferenceUrls(panel.getEpisodeId());
            int pageCount = calculatePageCount(shots.size(), SHOTS_PER_PAGE);
            List<String> gridImageUrls = new ArrayList<>();

            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * SHOTS_PER_PAGE;
                int toIdx = Math.min(fromIdx + SHOTS_PER_PAGE, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(fromIdx, toIdx);

                String prompt = panelPromptBuilder.buildGridPrompt(visualStyle, pageShots, characterRefUrls);
                String imageUrl = seedreamImageService.generateWithMultipleReferences(
                    prompt, characterRefUrls, 1920, 1080);
                gridImageUrls.add(imageUrl);
            }

            // 切割九宫格
            for (int page = 0; page < gridImageUrls.size(); page++) {
                BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
                List<BufferedImage> subImages = splitGridImage(gridImage, GRID_COLS, GRID_ROWS);
                int fromIdx = page * SHOTS_PER_PAGE;
                for (int i = 0; i < subImages.size() && (fromIdx + i) < shots.size(); i++) {
                    String ossUrl = uploadToOss(subImages.get(i), panelId, fromIdx + i);
                    shots.get(fromIdx + i).put("splitImageUrl", ossUrl);
                }
            }

            // 融合参考图
            BufferedImage fusionImage = createFusionImage(shots, characterRefUrls);
            panelInfo.put("fusionImageUrl", uploadToOss(fusionImage, panelId, "fusion"));
            panelInfo.put("gridImages", gridImageUrls);
            panelInfo.put("gridStatus", "generated");
            panelInfo.put("gridPageCount", pageCount);
            updatePanelInfo(panel, panelInfo);

            log.info("Panel {} 九宫格完成, {} 页, {} 分镜", panelId, pageCount, shots.size());

        } catch (Exception e) {
            log.error("Panel {} 九宫格失败", panelId, e);
            panelInfo.put("gridStatus", "failed");
            panelInfo.put("errorMessage", e.getMessage());
            updatePanelInfo(panel, panelInfo);
        }
    }

    /** 切割九宫格（纯函数） */
    public static List<BufferedImage> splitGridImage(BufferedImage img, int cols, int rows) {
        List<BufferedImage> subImages = new ArrayList<>();
        int pw = (int) Math.floor((double) img.getWidth() / cols);
        int ph = (int) Math.floor((double) img.getHeight() / rows);
        for (int i = 0; i < rows * cols; i++) {
            subImages.add(img.getSubimage((i % cols) * pw, (i / cols) * ph, pw, ph));
        }
        return subImages;
    }

    /** 分页数（纯函数） */
    public static int calculatePageCount(int totalShots, int shotsPerPage) {
        return totalShots <= 0 ? 0 : (int) Math.ceil((double) totalShots / shotsPerPage);
    }

    private BufferedImage createFusionImage(List<Map<String, Object>> shots, List<String> charRefUrls) {
        int fCols = 3, cellW = 640, cellH = 360, pad = 8, headerH = 60;
        int fRows = (int) Math.ceil((double) shots.size() / fCols);
        int cw = fCols * (cellW + pad) + pad;
        int ch = headerH + fRows * (cellH + pad) + pad;

        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(FUSION_BG_COLOR);
        g.fillRect(0, 0, cw, ch);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("分镜融合图 - 共" + shots.size() + "个镜头", pad, headerH - 15);

        for (int i = 0; i < shots.size(); i++) {
            int x = pad + (i % fCols) * (cellW + pad);
            int y = headerH + pad + (i / fCols) * (cellH + pad);
            String splitUrl = (String) shots.get(i).get("splitImageUrl");
            if (splitUrl != null) {
                try {
                    BufferedImage sub = downloadImage(splitUrl);
                    double scale = Math.min((double) cellW / sub.getWidth(), (double) cellH / sub.getHeight());
                    g.drawImage(sub, x + (cellW - (int)(sub.getWidth()*scale))/2,
                        y + (cellH - (int)(sub.getHeight()*scale))/2,
                        (int)(sub.getWidth()*scale), (int)(sub.getHeight()*scale), null);
                } catch (Exception e) { log.warn("融合图加载失败: shot {}", i); }
            }
            g.setColor(Color.BLACK);
            g.fillRect(x+4, y+4, 70, 22);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("Shot " + (i+1), x+10, y+19);
        }
        g.dispose();
        return canvas;
    }

    private List<String> getCharacterReferenceUrls(Long episodeId) {
        // TODO: 从 CharacterService 获取角色参考图 URL
        return new ArrayList<>();
    }

    private BufferedImage downloadImage(String url) {
        try (InputStream is = new URL(url).openStream()) { return ImageIO.read(is); }
        catch (Exception e) { throw new BusinessException("图片下载失败: " + url, e); }
    }

    private String uploadToOss(BufferedImage img, Long panelId, Object suffix) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return ossService.upload("panels/" + panelId + "/shot-" + suffix + ".png", baos.toByteArray());
        } catch (Exception e) { throw new BusinessException("图片上传失败", e); }
    }

    private void updatePanelInfo(Panel panel, Map<String, Object> info) {
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -pl . -Dtest=GridImageServiceTest -Dsurefire.useFile=false`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git add backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java
git commit -m "feat: add GridImageService for grid generation, splitting, and fusion"
```

---

## Task 9: PanelProductionService 重构

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

- [ ] **Step 1: 删除背景图和四宫格漫画相关方法**

删除 `generateBackgroundByPanelId()`、`getBackgroundStatusByPanelId()` 及所有 background/comic 相关私有方法。

- [ ] **Step 2: 注入 GridImageService**

```java
@Resource
private GridImageService gridImageService;
```

- [ ] **Step 3: 改造 getProductionStatus()**

```java
public Map<String, Object> getProductionStatus(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) throw new BusinessException("Panel 不存在");
    Map<String, Object> panelInfo = panel.getPanelInfo();
    Map<String, Object> status = new HashMap<>();
    status.put("panelId", panel.getId());
    status.put("gridStatus", panelInfo.getOrDefault("gridStatus", "pending"));
    status.put("gridImages", panelInfo.getOrDefault("gridImages", new ArrayList<>()));
    status.put("fusionImageUrl", panelInfo.get("fusionImageUrl"));
    status.put("shots", panelInfo.get("shots"));
    status.put("totalShots", panelInfo.getOrDefault("totalShots", 0));
    status.put("totalDuration", panelInfo.getOrDefault("totalDuration", 0));
    status.put("gridPageCount", panelInfo.getOrDefault("gridPageCount", 0));
    status.put("gridRejectionFeedback", panelInfo.get("gridRejectionFeedback"));
    status.put("videoStatus", panelInfo.getOrDefault("videoStatus", "pending"));
    status.put("videoUrl", panelInfo.get("videoUrl"));
    status.put("videoTaskId", panelInfo.get("videoTaskId"));
    status.put("offPeak", panelInfo.getOrDefault("offPeak", false));
    return status;
}
```

- [ ] **Step 4: 改造 generateVideoByPanelId() — 融合图 + 多镜头提示词**

```java
public void generateVideoByPanelId(Long panelId, boolean offPeak) {
    Panel panel = panelRepository.selectById(panelId);
    Map<String, Object> panelInfo = panel.getPanelInfo();

    if (!"approved".equals(panelInfo.getOrDefault("gridStatus", "pending"))) {
        throw new BusinessException("九宫格未审核通过");
    }
    String fusionImageUrl = (String) panelInfo.get("fusionImageUrl");
    if (fusionImageUrl == null || fusionImageUrl.isEmpty()) {
        throw new BusinessException("融合参考图不存在");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
    int totalDuration = shots.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();

    String visualStyleStr = (String) panelInfo.getOrDefault("visualStyle", "ANIME");
    VisualStyle visualStyle = VisualStyle.valueOf(visualStyleStr);
    String prompt = panelPromptBuilder.buildMultiShotPrompt(visualStyle, panelInfo);

    panelInfo.put("videoStatus", "generating");
    panelInfo.put("offPeak", offPeak);
    updatePanelInfo(panel, panelInfo);

    try {
        String taskId = viduVideoService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak);
        panelInfo.put("videoTaskId", taskId);
        updatePanelInfo(panel, panelInfo);
    } catch (Exception e) {
        panelInfo.put("videoStatus", "failed");
        panelInfo.put("errorMessage", e.getMessage());
        updatePanelInfo(panel, panelInfo);
        throw e;
    }
}
```

注意：`VisualStyle` 使用 `com.comic.ai.CharacterPromptManager.VisualStyle`。

- [ ] **Step 5: 新增九宫格审核方法**

```java
public void approveGrid(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    Map<String, Object> info = panel.getPanelInfo();
    info.put("gridStatus", "approved");
    info.put("gridRejectionFeedback", null);
    updatePanelInfo(panel, info);
}

public void rejectGrid(Long panelId, String reason) {
    Panel panel = panelRepository.selectById(panelId);
    Map<String, Object> info = panel.getPanelInfo();
    info.put("gridStatus", "rejected");
    info.put("gridRejectionFeedback", reason);
    updatePanelInfo(panel, info);
}

public void regenerateGrid(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    Map<String, Object> info = panel.getPanelInfo();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
    if (shots != null) {
        for (Map<String, Object> shot : shots) shot.remove("splitImageUrl");
    }
    info.put("gridImages", new ArrayList<>());
    info.put("gridStatus", "generating");
    info.put("fusionImageUrl", null);
    info.put("errorMessage", null);
    updatePanelInfo(panel, info);
    gridImageService.generateGridsForPanel(panelId);
}
```

- [ ] **Step 6: 运行编译检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "refactor: PanelProductionService to grid-based video flow"
```

---

## Task 10: Controller 层更新

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/PanelController.java`
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java`

- [ ] **Step 1: 在 PanelController 中新增九宫格审核 API**

```java
@PutMapping("/{panelId}/grid/approve")
public Result<Void> approveGrid(@PathVariable String projectId, @PathVariable Long episodeId, @PathVariable Long panelId) {
    panelProductionService.approveGrid(panelId);
    return Result.success(null);
}

@PutMapping("/{panelId}/grid/reject")
public Result<Void> rejectGrid(@PathVariable String projectId, @PathVariable Long episodeId, @PathVariable Long panelId, @RequestBody Map<String, String> body) {
    panelProductionService.rejectGrid(panelId, body.getOrDefault("reason", ""));
    return Result.success(null);
}

@PostMapping("/{panelId}/grid/regenerate")
public Result<Void> regenerateGrid(@PathVariable String projectId, @PathVariable Long episodeId, @PathVariable Long panelId) {
    panelProductionService.regenerateGrid(panelId);
    return Result.success(null);
}

@PutMapping("/grid/approve-all")
public Result<Void> approveAllGrids(@PathVariable String projectId, @PathVariable Long episodeId) {
    List<Panel> panels = panelService.getPanelsByEpisodeId(episodeId);
    for (Panel p : panels) {
        Map<String, Object> info = p.getPanelInfo();
        if (info != null && "generated".equals(info.get("gridStatus"))) {
            panelProductionService.approveGrid(p.getId());
        }
    }
    return Result.success(null);
}
```

- [ ] **Step 2: 删除 PanelController 中的旧端点**

删除 background 和 comic 相关端点（约7个）。

- [ ] **Step 3: 在 EpisodeController 中新增分集剧本和分镜 API**

```java
@PostMapping("/{episodeId}/script")
public Result<Void> generateEpisodeScript(@PathVariable String projectId, @PathVariable Long episodeId) {
    storyboardService.generateEpisodeScriptForEpisode(projectId, episodeId);
    return Result.success(null);
}

@GetMapping("/{episodeId}/script")
public Result<Map<String, Object>> getEpisodeScript(@PathVariable String projectId, @PathVariable Long episodeId) {
    Episode episode = episodeService.getEpisode(projectId, episodeId);
    return Result.success(episode.getEpisodeInfo());
}

@PostMapping("/{episodeId}/storyboard")
public Result<Void> generateStoryboard(@PathVariable String projectId, @PathVariable Long episodeId) {
    storyboardService.generateStoryboardForEpisode(projectId, episodeId);
    return Result.success(null);
}
```

- [ ] **Step 4: 运行编译检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/PanelController.java
git add backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java
git commit -m "feat: update controllers with grid review APIs and episode storyboard endpoints"
```

---

## Task 11: 后端测试清理

**Files:**
- Modify: `backend/com/comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java`
- Modify: `backend/com/comic/src/test/java/com/comic/controller/ProjectProductionSummaryApiTest.java`

- [ ] **Step 1: 更新 ProjectStateMachineE2ETest**

搜索并替换所有旧状态引用：
- `PANEL_GENERATING` → `STORYBOARD_GENERATING`
- `PANEL_REVIEW` → `STORYBOARD_REVIEW`
- `PANEL_GENERATING_FAILED` → `STORYBOARD_GENERATING_FAILED`
- `VIDEO_ASSEMBLING` → `COMPLETED`（或删除对应测试）
- `"panels_generated"` → `"storyboard_generated"`
- `"start_panels"` → `"start_episode_script"`
- `"all_panels_confirmed"` → `"all_grids_approved"`
- `"assembly_completed"` → 删除

- [ ] **Step 2: 更新 ProjectProductionSummaryApiTest**

搜索并替换：
- `backgroundUrl`、`backgroundStatus`、`comicUrl`、`comicStatus` → 新字段 `gridStatus`、`gridImages`、`fusionImageUrl`

- [ ] **Step 3: 运行全部后端测试**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -Dsurefire.useFile=false`
Expected: 所有测试 PASS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java
git add backend/com/comic/src/test/java/com/comic/controller/ProjectProductionSummaryApiTest.java
git commit -m "test: update E2E and API tests for new storyboard pipeline states"
```

---

## Task 12: 前端类型定义和 API 服务更新

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/services/types/episode.types.ts`
- Modify: `frontend/wiset_aivideo_generator/src/services/types/project.types.ts`
- Modify: `frontend/wiset_aivideo_generator/src/services/episodeService.ts`
- Modify: `frontend/wiset_aivideo_generator/src/services/apiClient.ts`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts`
- Modify: `frontend/wiset_aivideo_generator/src/stores/createStore.ts`

- [ ] **Step 1: 新增分镜和九宫格类型到 episode.types.ts**

```typescript
export interface StoryboardShot {
  shotNumber: number;
  duration: number;
  scene: string;
  characters: string[];
  shotSize: string;
  cameraAngle: string;
  cameraMovement: string;
  visualDescription: string;
  dialogue: string;
  visualEffects: string;
  audioEffects: string;
  splitImageUrl?: string;
}

export type GridStatus = 'pending' | 'generating' | 'generated' | 'approved' | 'rejected' | 'failed';
export type VideoStatus = 'pending' | 'generating' | 'completed' | 'failed';

export interface PanelGridStatusResponse {
  panelId: number;
  gridStatus: GridStatus;
  gridImages: string[];
  fusionImageUrl: string | null;
  shots: StoryboardShot[];
  totalShots: number;
  totalDuration: number;
  gridPageCount: number;
  gridRejectionFeedback: string | null;
  videoStatus: VideoStatus;
  videoUrl: string | null;
  videoTaskId: string | null;
  offPeak: boolean;
}
```

- [ ] **Step 2: 更新 project.types.ts 中的状态联合类型**

将 `PANEL_GENERATING`、`PANEL_REVIEW`、`PANEL_GENERATING_FAILED`、`VIDEO_ASSEMBLING` 替换为 `EPISODE_SCRIPT_GENERATING`、`EPISODE_SCRIPT_GENERATING_FAILED`、`STORYBOARD_GENERATING`、`STORYBOARD_GENERATING_FAILED`、`STORYBOARD_REVIEW`。

- [ ] **Step 3: 更新 steps/types.ts 中的 SegmentPipelineStep**

```typescript
export type SegmentPipelineStep =
  | 'pending'
  | 'grid_generating'
  | 'grid_review'
  | 'grid_approved'
  | 'video_generating'
  | 'video_completed'
  | 'video_failed';
```

- [ ] **Step 4: 确保 apiClient.ts 有 put 方法**

检查是否存在 `put` 导出函数。如果不存在，添加：
```typescript
export async function put<T>(url: string, data?: any, config?: any): Promise<T> {
  return request<T>({ method: 'PUT', url, data, ...config });
}
```

- [ ] **Step 5: 更新 episodeService.ts — 新增九宫格 API，删除旧 API**

新增：
```typescript
export async function approveGrid(projectId: string, episodeId: number, panelId: number): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/grid/approve`);
}

export async function rejectGrid(projectId: string, episodeId: number, panelId: number, reason: string): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/grid/reject`, { reason });
}

export async function regenerateGrid(projectId: string, episodeId: number, panelId: number): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/grid/regenerate`);
}

export async function approveAllGrids(projectId: string, episodeId: number): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/grid/approve-all`);
}
```

删除：`getBackgroundStatus`、`generateBackground`、`regenerateBackground`、`getComicStatus`、`generateComic`、`approveComic`、`reviseComic`。

- [ ] **Step 6: 更新 createStore.ts**

新增 store 字段：
```typescript
storyboardShots: [] as StoryboardShot[],
gridImages: [] as string[],
gridStatus: 'pending' as GridStatus,
fusionImageUrl: null as string | null,
```

- [ ] **Step 7: 更新 CreateLayout.tsx 中的旧状态引用**

搜索 `PANEL_GENERATING` 等旧状态字符串，替换为新状态。

- [ ] **Step 8: 运行类型检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit 2>&1 | head -30`
Expected: 可能有类型错误（旧组件引用），记录下来在后续 Task 修复

- [ ] **Step 9: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/services/types/episode.types.ts
git add frontend/wiset_aivideo_generator/src/services/types/project.types.ts
git add frontend/wiset_aivideo_generator/src/services/episodeService.ts
git add frontend/wiset_aivideo_generator/src/services/apiClient.ts
git add frontend/wiset_aivideo_generator/src/pages/create/steps/types.ts
git add frontend/wiset_aivideo_generator/src/stores/createStore.ts
git add frontend/wiset_aivideo_generator/src/pages/create/CreateLayout.tsx
git commit -m "feat: update frontend types, APIs, and stores for storyboard pipeline"
```

---

## Task 13: 前端新组件 — StoryboardGrid + GridReviewPanel + BatchReviewBar

**Files:**
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/StoryboardGrid.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/StoryboardGrid.module.less`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridReviewPanel.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/BatchReviewBar.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotTimeline.tsx`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotTimeline.module.less`
- Create: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotDetail.tsx`

- [ ] **Step 1: 实现 StoryboardGrid 组件**

九宫格图片展示组件，支持多页切换。显示当前页的分镜摘要列表。组件接口：

```tsx
interface StoryboardGridProps {
  gridImages: string[];
  shots: StoryboardShot[];
  gridStatus: string;
  onPageChange?: (pageIndex: number) => void;
}
```

- [ ] **Step 2: 实现 ShotTimeline 组件**

横向分镜时间线，显示每个 shot 的时长/景别/运镜/编号。

```tsx
interface ShotTimelineProps {
  shots: StoryboardShot[];
  selectedShotIndex: number | null;
  onSelectShot: (index: number) => void;
}
```

- [ ] **Step 3: 实现 ShotDetail 组件**

单个分镜详情展示，包含切割小图、画面描述、对白、景别、运镜等字段。

```tsx
interface ShotDetailProps {
  shot: StoryboardShot;
  onClose: () => void;
}
```

- [ ] **Step 4: 实现 GridReviewPanel 组件**

审核面板：包含 StoryboardGrid + 通过/拒绝/重新生成按钮 + 拒绝原因表单。

```tsx
interface GridReviewPanelProps {
  panelId: number;
  gridImages: string[];
  shots: StoryboardShot[];
  gridStatus: GridStatus;
  gridRejectionFeedback: string | null;
  onApprove: () => void;
  onReject: (reason: string) => void;
  onRegenerate: () => void;
}
```

- [ ] **Step 5: 实现 BatchReviewBar 组件**

批量审核工具栏，显示审核进度 + 一键全通过按钮。

```tsx
interface BatchReviewBarProps {
  totalPanels: number;
  approvedCount: number;
  pendingCount: number;
  onApproveAll: () => void;
}
```

- [ ] **Step 6: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/StoryboardGrid.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/StoryboardGrid.module.less
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/GridReviewPanel.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/BatchReviewBar.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotTimeline.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotTimeline.module.less
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/ShotDetail.tsx
git commit -m "feat: add StoryboardGrid, GridReviewPanel, BatchReviewBar, ShotTimeline, ShotDetail components"
```

---

## Task 14: 前端 Step5page 重构

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx`
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx`
- Delete: `frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.tsx`

- [ ] **Step 1: 重构 SegmentCard**

- 删除背景图和四宫格漫画 JSX
- 替换为 `<GridReviewPanel />` 组件
- 保留视频播放区域
- 更新 pipeline step 逻辑：
  - `grid_generating` → 显示加载状态
  - `grid_review` → 显示 GridReviewPanel
  - `grid_approved` → 自动触发视频生成
  - `video_generating` / `video_completed` / `video_failed` → 保持不变

- [ ] **Step 2: 重构 EpisodeCard**

- 移除 `generatingComicPanelId` 和 `ComicPanel` 引用
- 替换为九宫格相关状态

- [ ] **Step 3: 重构 Step5page**

- 删除 `ComicPanel`、背景图生成相关 import
- 引入 `GridReviewPanel`、`BatchReviewBar`、`StoryboardGrid`
- 顶部添加 `<BatchReviewBar />`
- 调用新 API（approveGrid / rejectGrid / regenerateGrid / approveAllGrids）

- [ ] **Step 4: 删除 ComicPanel.tsx**

确认无引用后删除文件。

- [ ] **Step 5: 运行类型检查**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npx tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 6: 运行构建**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npm run build 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/SegmentCard.tsx
git add frontend/wiset_aivideo_generator/src/pages/create/steps/components/EpisodeCard.tsx
git rm frontend/wiset_aivideo_generator/src/pages/create/steps/components/ComicPanel.tsx
git commit -m "refactor: Step5page to use grid-based storyboard review flow"
```

---

## Task 15: 全量测试和清理

- [ ] **Step 1: 运行全部后端测试**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\backend\com\comic && mvn test -Dsurefire.useFile=false`
Expected: 所有测试 PASS

- [ ] **Step 2: 修复所有失败的测试**

- [ ] **Step 3: 前端构建验证**

Run: `cd D:\wiset\Wiset_Aivideo_Genetator\frontend\wiset_aivideo_generator && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: 全局搜索确认无遗留旧引用**

在后端搜索：`PANEL_GENERATING`、`PANEL_REVIEW`、`PANEL_GENERATING_FAILED`、`VIDEO_ASSEMBLING`、`comicStatus`、`comicUrl`、`backgroundUrl`、`backgroundStatus`、`ComicGenerationService`

在前端搜索：`PANEL_GENERATING`、`PANEL_REVIEW`、`VIDEO_ASSEMBLING`、`ComicPanel`、`comicStatus`

- [ ] **Step 5: 最终 Commit**

```bash
git add -A
git commit -m "chore: final cleanup and test fixes for storyboard pipeline"
```
