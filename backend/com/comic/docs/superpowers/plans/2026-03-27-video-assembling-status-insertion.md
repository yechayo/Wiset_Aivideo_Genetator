# VIDEO_ASSEMBLING 状态插入重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PRODUCING 和 COMPLETED 之间插入 VIDEO_ASSEMBLING 状态，使 PRODUCING 归入步骤5（分镜阶段），VIDEO_ASSEMBLING 成为步骤6（拼接剪辑阶段）。

**Architecture:** 修改 ProjectStatus 枚举新增 VIDEO_ASSEMBLING，调整 PRODUCING frontendStep=5，更新转换表使 `production_completed → VIDEO_ASSEMBLING → assembly_completed → COMPLETED`。PipelineService 同步更新 rollback/enrich/trigger 逻辑。PanelProductionService 无需改动（事件名不变）。

**Tech Stack:** Java 17, Spring Boot 2.7, JUnit5 + Mockito

---

## File Structure

| File | Responsibility | Action |
|------|---------------|--------|
| `comic/src/main/java/com/comic/common/ProjectStatus.java` | 状态枚举 + 转换表 + frontendStep | Modify |
| `comic/src/main/java/com/comic/service/pipeline/PipelineService.java` | Pipeline 编排（trigger/rollback/enrich） | Modify |
| `comic/src/main/java/com/comic/controller/PanelController.java` | 分镜守卫（阻止生产/拼接中变更） | Modify |
| `comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java` | 转换表正确性 | Modify |
| `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java` | 自动推进链 | Modify |
| `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java` | 编排器行为 | Modify |
| `comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java` | E2E 生命周期 | Modify |
| `comic/docs/frontend-api-guide.md` | 前端对接指南 | Modify |

---

### Task 1: ProjectStatus 枚举 — 新增 VIDEO_ASSEMBLING + 调整 frontendStep

**Files:**
- Modify: `comic/src/main/java/com/comic/common/ProjectStatus.java`
- Test: `comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java`

- [ ] **Step 1: Write failing test — production_completed 应转到 VIDEO_ASSEMBLING 而非 COMPLETED**

在 `ProjectStatusTransitionTest.java` 中，修改 `should_resolve_production_completed_to_completed` 测试：

```java
@Test
void should_resolve_production_completed_to_video_assembling() {
    assertEquals(
        ProjectStatus.VIDEO_ASSEMBLING,
        ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"),
        "Production completion should transition to VIDEO_ASSEMBLING state"
    );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd comic && mvn test -pl . -Dtest=ProjectStatusTransitionTest#should_resolve_production_completed_to_completed -q`
Expected: FAIL — 断言 COMPLETED != VIDEO_ASSEMBLING

- [ ] **Step 3: Write failing test — assembly_completed 应转到 COMPLETED**

在 `ProjectStatusTransitionTest.java` 中新增：

```java
@Test
void should_resolve_assembly_completed_to_completed() {
    assertEquals(
        ProjectStatus.COMPLETED,
        ProjectStatus.resolveTransition(ProjectStatus.VIDEO_ASSEMBLING, "assembly_completed"),
        "Assembly completion should transition to COMPLETED state"
    );
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd comic && mvn test -pl . -Dtest=ProjectStatusTransitionTest#should_resolve_assembly_completed_to_completed -q`
Expected: FAIL — VIDEO_ASSEMBLING 不存在

- [ ] **Step 5: Implement — ProjectStatus 枚举修改**

在 `ProjectStatus.java` 中做以下 5 处修改：

**(5a)** 在 `PANEL_GENERATING_FAILED` 行之后、`PRODUCING` 行之前插入新枚举值：

```java
// 拼接剪辑阶段
VIDEO_ASSEMBLING("VIDEO_ASSEMBLING", "拼接剪辑中", 6),
```

**(5b)** 修改 PRODUCING 的 frontendStep：

```java
// 生产阶段（逐 Panel 视频生产，属于分镜阶段的一部分）
PRODUCING("PRODUCING", "生产中", 5),
```

**(5c)** 在 static block 中，将 `production_completed` 的目标从 COMPLETED 改为 VIDEO_ASSEMBLING，并新增 VIDEO_ASSEMBLING 的转换：

```java
// 生产 → 拼接剪辑
put(map, PRODUCING, "production_completed", VIDEO_ASSEMBLING);

// 拼接剪辑 → 完成
put(map, VIDEO_ASSEMBLING, "assembly_completed", COMPLETED);
```

**(5d)** 在 `getCompletedSteps()` 的确认态列表中添加 VIDEO_ASSEMBLING：

```java
if (this == SCRIPT_CONFIRMED || this == CHARACTER_CONFIRMED || this == ASSET_LOCKED
        || this == COMPLETED || this == PANEL_REVIEW || this == VIDEO_ASSEMBLING) {
    steps.add(current);
}
```

**(5e)** 在 `getAvailableActions()` 的 switch 中添加 VIDEO_ASSEMBLING case：

```java
case VIDEO_ASSEMBLING:
    return Arrays.asList();
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd comic && mvn test -pl . -Dtest=ProjectStatusTransitionTest -q`
Expected: ALL PASS (7 tests)

- [ ] **Step 7: Verify VIDEO_ASSEMBLING frontendStep and isGenerating**

Run: `cd comic && mvn test -pl . -Dtest=ProjectStatusTransitionTest -q`

确认以下断言手动验证通过（可临时在测试中添加）：
- `ProjectStatus.VIDEO_ASSEMBLING.getFrontendStep() == 6`
- `ProjectStatus.PRODUCING.getFrontendStep() == 5`
- `ProjectStatus.VIDEO_ASSEMBLING.isGenerating() == false`
- `ProjectStatus.VIDEO_ASSEMBLING.isReview() == false`
- `ProjectStatus.VIDEO_ASSEMBLING.isFailed() == false`

- [ ] **Step 8: Commit**

```bash
git add comic/src/main/java/com/comic/common/ProjectStatus.java comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java
git commit -m "feat(status): add VIDEO_ASSEMBLING status, move PRODUCING to step 5"
```

---

### Task 2: PipelineService — triggerNextStage / rollback / enrich 更新

**Files:**
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
- Test: `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`

- [ ] **Step 1: Write failing test — production_completed 应转到 VIDEO_ASSEMBLING**

在 `PipelineServiceAutoAdvanceTest.java` 中修改 `production_completed_should_transition_to_completed`：

```java
@Test
void production_completed_should_transition_to_video_assembling() {
    Project project = createTestProject("test-project-4");
    project.setStatus(ProjectStatus.PRODUCING.getCode());

    when(projectRepository.findByProjectId("test-project-4")).thenReturn(project);
    when(projectRepository.updateById(any(Project.class))).thenReturn(1);

    pipelineService.advancePipeline("test-project-4", "production_completed");

    ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
    verify(projectRepository, times(1)).updateById(projectCaptor.capture());
    Project updatedProject = projectCaptor.getValue();

    assertEquals(
        ProjectStatus.VIDEO_ASSEMBLING.getCode(),
        updatedProject.getStatus(),
        "After production completes, project should be in VIDEO_ASSEMBLING state"
    );
}
```

- [ ] **Step 2: Write failing test — assembly_completed 应转到 COMPLETED**

在 `PipelineServiceAutoAdvanceTest.java` 中新增：

```java
@Test
void assembly_completed_should_transition_to_completed() {
    Project project = createTestProject("test-project-6");
    project.setStatus(ProjectStatus.VIDEO_ASSEMBLING.getCode());

    when(projectRepository.findByProjectId("test-project-6")).thenReturn(project);
    when(projectRepository.updateById(any(Project.class))).thenReturn(1);

    pipelineService.advancePipeline("test-project-6", "assembly_completed");

    ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
    verify(projectRepository, times(1)).updateById(projectCaptor.capture());
    Project updatedProject = projectCaptor.getValue();

    assertEquals(
        ProjectStatus.COMPLETED.getCode(),
        updatedProject.getStatus(),
        "After assembly completes, project should be in COMPLETED state"
    );
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd comic && mvn test -pl . -Dtest=PipelineServiceAutoAdvanceTest -q`
Expected: FAIL — production_completed 仍期望 COMPLETED

- [ ] **Step 4: Implement PipelineService changes**

在 `PipelineService.java` 中做以下修改：

**(4a)** `triggerNextStage()` 方法中，在 `case PRODUCING:` 之后添加 VIDEO_ASSEMBLING case（约 line 791 后）：

```java
case VIDEO_ASSEMBLING:
    // 自动开始拼接编排（预留，后续实现拼接服务后接入）
    log.info("Video assembling started: projectId={}", projectId);
    break;
```

**(4b)** `getRollbackTarget()` 方法中，修改 COMPLETED 的回退目标和新增 VIDEO_ASSEMBLING 的回退（约 line 269-270）：

```java
case PRODUCING:
    return ProjectStatus.PANEL_REVIEW;
case VIDEO_ASSEMBLING:
    return ProjectStatus.PRODUCING;
case COMPLETED:
    return ProjectStatus.VIDEO_ASSEMBLING;
```

**(4c)** `cleanupAfterRollback()` 方法中，在 `case PRODUCING:` 的 case 标签中添加 VIDEO_ASSEMBLING（使其共用 PRODUCING 的清理逻辑）（约 line 319）：

```java
case PRODUCING:
case VIDEO_ASSEMBLING:
    // 回滚 PRODUCING/VIDEO_ASSEMBLING 时释放生产锁
    if ((from == ProjectStatus.PRODUCING || from == ProjectStatus.VIDEO_ASSEMBLING) && stringRedisTemplate != null) {
```

**(4d)** `getProjectStatusDetail()` 方法中，在 `if (status == ProjectStatus.PRODUCING)` 之后添加 VIDEO_ASSEMBLING 分支（约 line 389 后）：

```java
} else if (status == ProjectStatus.VIDEO_ASSEMBLING) {
    dto.setStatusCode("VIDEO_ASSEMBLING");
    dto.setStatusDescription("视频拼接剪辑中");
    dto.setGenerating(true);
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd comic && mvn test -pl . -Dtest=PipelineServiceAutoAdvanceTest -q`
Expected: ALL PASS (6 tests)

- [ ] **Step 6: Commit**

```bash
git add comic/src/main/java/com/comic/service/pipeline/PipelineService.java comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java
git commit -m "feat(pipeline): add VIDEO_ASSEMBLING to trigger/rollback/enrich, update transition targets"
```

---

### Task 3: PanelController 守卫 + E2E 测试 + Orchestrator 测试更新

**Files:**
- Modify: `comic/src/main/java/com/comic/controller/PanelController.java`
- Modify: `comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java`
- Modify: `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java`

- [ ] **Step 1: Update PanelController guard**

在 `PanelController.java` 的 `guardPanelNotInProduction()` 方法（约 line 322-326）中扩展检查：

```java
private void guardPanelNotInProduction(String projectId) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project != null && (ProjectStatus.PRODUCING.getCode().equals(project.getStatus())
            || ProjectStatus.VIDEO_ASSEMBLING.getCode().equals(project.getStatus()))) {
        throw new BusinessException("当前项目正在生产或拼接中，无法变更分镜");
    }
}
```

- [ ] **Step 2: Update E2E test — production_completed → VIDEO_ASSEMBLING**

在 `ProjectStateMachineE2ETest.java` 中修改 `full_lifecycle_producing_to_completed` 测试：

```java
@Test
@DisplayName("PRODUCING -> production_completed -> VIDEO_ASSEMBLING (persisted)")
void full_lifecycle_producing_to_video_assembling() {
    Project project = createProject("e2e-4", ProjectStatus.PRODUCING);
    when(projectRepository.findByProjectId("e2e-4")).thenReturn(project);
    when(projectRepository.updateById(any(Project.class))).thenReturn(1);

    pipelineService.advancePipeline("e2e-4", "production_completed");

    assertEquals(ProjectStatus.VIDEO_ASSEMBLING.getCode(), project.getStatus());
}
```

- [ ] **Step 3: Add E2E test — VIDEO_ASSEMBLING → COMPLETED**

在 `ProjectStateMachineE2ETest.java` 中新增：

```java
@Test
@DisplayName("VIDEO_ASSEMBLING -> assembly_completed -> COMPLETED (persisted)")
void full_lifecycle_video_assembling_to_completed() {
    Project project = createProject("e2e-4b", ProjectStatus.VIDEO_ASSEMBLING);
    when(projectRepository.findByProjectId("e2e-4b")).thenReturn(project);
    when(projectRepository.updateById(any(Project.class))).thenReturn(1);

    pipelineService.advancePipeline("e2e-4b", "assembly_completed");

    assertEquals(ProjectStatus.COMPLETED.getCode(), project.getStatus());
}
```

- [ ] **Step 4: Update E2E transition_table tests**

在 `ProjectStateMachineE2ETest.java` 中：

**(4a)** 修改 `transition_table_confirm_transitions` 中的 production_completed 断言：

```java
assertEquals(ProjectStatus.VIDEO_ASSEMBLING,
    ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"));
```

**(4b)** 在 `transition_table_auto_advance_transitions` 中新增：

```java
assertEquals(ProjectStatus.COMPLETED,
    ProjectStatus.resolveTransition(ProjectStatus.VIDEO_ASSEMBLING, "assembly_completed"));
```

- [ ] **Step 5: Update Orchestrator test — double_resume status check**

在 `PanelProductionOrchestratorTest.java` 的 `double_resume_should_not_double_trigger_completion` 测试中（约 line 177-178），将：

```java
project.setStatus(ProjectStatus.COMPLETED.getCode());
```

改为：

```java
project.setStatus(ProjectStatus.VIDEO_ASSEMBLING.getCode());
```

> 原因：production_completed 现在转到 VIDEO_ASSEMBLING 而非 COMPLETED，所以二次调用时 project status 应该是 VIDEO_ASSEMBLING 才能使 startOrResume 提前返回（因为它检查 `PRODUCING` 状态）。

- [ ] **Step 6: Run all tests**

Run: `cd comic && mvn test -pl . -q`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add comic/src/main/java/com/comic/controller/PanelController.java \
        comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java \
        comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java
git commit -m "feat: update guard, E2E tests, orchestrator test for VIDEO_ASSEMBLING"
```

---

### Task 4: 更新前端 API 对接指南

**Files:**
- Modify: `comic/docs/frontend-api-guide.md`

- [ ] **Step 1: Update 状态机总览**

将步骤流程图从：

```
步骤1 DRAFT ──→ 步骤2 剧本阶段 ──→ 步骤3 角色阶段 ──→ 步骤4 图像阶段 ──→ 步骤5 分镜阶段 ──→ 步骤6 生产阶段
```

改为：

```
步骤1 DRAFT ──→ 步骤2 剧本阶段 ──→ 步骤3 角色阶段 ──→ 步骤4 图像阶段 ──→ 步骤5 分镜阶段 ──→ 步骤6 拼接剪辑阶段
```

将详细流转中的 `PRODUCING → COMPLETED` 改为：

```
   → PRODUCING → VIDEO_ASSEMBLING → COMPLETED
   (自动)      (所有Panel视频完成后自动)  (拼接完成后自动)
```

- [ ] **Step 2: Update 状态分类表和 availableActions 表**

- 在 `availableActions` 表中添加 `VIDEO_ASSEMBLING` 行：无可用操作，后端自动拼接
- 在状态分类表中注明 `VIDEO_ASSEMBLING` 的 `isGenerating = true`

- [ ] **Step 3: Update 步骤6说明**

将"### 步骤 6：生产阶段（PRODUCING）"改为"### 步骤 5：生产阶段（PRODUCING，分镜阶段内）"，并在步骤 5 的分镜阶段章节中说明 PRODUCING 是分镜阶段内的子状态。

新增"### 步骤 6：拼接剪辑阶段（VIDEO_ASSEMBLING）"章节，说明这是所有 Panel 视频完成后的自动拼接阶段。

- [ ] **Step 4: Update 渲染规则速查**

在渲染规则表中：
- `statusCode == PRODUCING` 改为属于步骤5
- 新增 `statusCode == VIDEO_ASSEMBLING` 切换拼接进度视图

- [ ] **Step 5: Commit**

```bash
git add comic/docs/frontend-api-guide.md
git commit -m "docs: update frontend API guide for VIDEO_ASSEMBLING status"
```

---

## Verification

- [ ] **Full test suite:** `cd comic && mvn test -q` — ALL PASS
- [ ] **Compile:** `cd comic && mvn compile -q` — NO ERRORS
- [ ] **Manual verification checklist:**
  - `PRODUCING.getFrontendStep() == 5`
  - `VIDEO_ASSEMBLING.getFrontendStep() == 6`
  - `resolveTransition(PRODUCING, "production_completed") == VIDEO_ASSEMBLING`
  - `resolveTransition(VIDEO_ASSEMBLING, "assembly_completed") == COMPLETED`
  - `getRollbackTarget(COMPLETED) == VIDEO_ASSEMBLING`
  - `getRollbackTarget(VIDEO_ASSEMBLING) == PRODUCING`
