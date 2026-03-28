# Single-Panel Production State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make project state transitions deterministic and production-safe: confirm actions auto-advance, PRODUCING runs strict serial per-panel orchestration, failures pause flow, and project completes when all panel videos are done.

**Architecture:** Keep DB `project.status` as source of truth and Redis as realtime event/lock layer. Extend `PipelineService` as transition owner, and let `PanelProductionService` act as project-level PRODUCING orchestrator (`startOrResume`) with a single current panel and explicit blocking states. Expose project-level production summary API for frontend synchronization.

**Tech Stack:** Spring Boot2.7, MyBatis-Plus, Redis (pub/sub + lock), SSE, JUnit5 + Spring Boot Test + MockMvc.

---

## Scope and decomposition

This spec is a single subsystem (state machine + producing orchestration + sync APIs), so one implementation plan is sufficient.

---

## File structure (planned changes)

### Core transition ownership
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
 - Add deterministic auto-advance chains for confirmed states.
 - On entering `PRODUCING`, call production orchestrator instead of logging-only behavior.
 - Persist `PRODUCING -> COMPLETED` via transition event (no response-only derived completed).
- Modify: `comic/src/main/java/com/comic/common/ProjectStatus.java`
 - Align available actions with confirmed auto-advance semantics.
 - Remove stale manual `start_production` action from review surface.

### Confirm entry points
- Modify: `comic/src/main/java/com/comic/service/script/ScriptService.java`
 - Keep idempotent `confirmScript`, rely on pipeline auto-chain.
- Modify: `comic/src/main/java/com/comic/service/character/CharacterExtractService.java`
 - Keep idempotent `confirmCharacters`, rely on pipeline auto-chain.
- Modify: `comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java`
 - Add project-level `confirmImages(projectId)` and validation.
- Modify: `comic/src/main/java/com/comic/controller/CharacterController.java`
 - Add image-confirm API endpoint.

### PRODUCING strict-serial orchestration
- Modify: `comic/src/main/java/com/comic/service/production/PanelProductionService.java`
 - Add `startOrResume(projectId)` orchestrator.
 - Add project-level current-panel selection across episodes.
 - Add lock-protected step driving: background -> comic -> pending_review -> video.
 - Pause on failure; resume on retry/approve.
 - On all videos complete, call pipeline `production_completed`.
- Modify: `comic/src/main/java/com/comic/service/production/ComicGenerationService.java`
 - On `approveComic`, trigger orchestrator resume for same project.
- Modify: `comic/src/main/java/com/comic/controller/PanelController.java`
 - After manual retry endpoints succeed, ensure orchestrator resume is triggered.
- Modify: `comic/src/main/java/com/comic/repository/PanelRepository.java`
 - Add helpers for project-wide ordered panel traversal (via episode list input).

### Frontend sync contract
- Create: `comic/src/main/java/com/comic/dto/response/ProjectProductionSummaryResponse.java`
 - Project-level PRODUCING snapshot for frontend.
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
 - Populate `productionSubStage` consistently in `/status` response.
- Modify: `comic/src/main/java/com/comic/controller/ProjectController.java`
 - Add `GET /api/projects/{projectId}/production/summary` endpoint.

### Tests
- Create: `comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java`
- Create: `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`
- Create: `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java`
- Create: `comic/src/test/java/com/comic/controller/ProjectProductionSummaryApiTest.java`
- Create: `comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java`

---

## Implementation phases (minimum-risk order)

1. Transition safety net tests
2. Confirm -> auto-advance chains
3. Image confirm endpoint + semantics cleanup
4. PRODUCING orchestrator (strict serial + pause/resume)
5. Completion persistence (`production_completed`)
6. Frontend summary API and response consistency
7. Integration + E2E validation + docs sanity
8. Boundary and recovery hardening (spec §6)

Each phase includes acceptance and rollback point.

---

### Task1: Build transition safety net before behavior changes

**Files:**
- Create: `comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java`
- Create: `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`

- [ ] **Step1: Write failing transition tests**

```java
// ProjectStatusTransitionTest
@Test
void should_resolve_confirm_images_to_asset_locked() {
 assertEquals(ProjectStatus.ASSET_LOCKED,
 ProjectStatus.resolveTransition(ProjectStatus.IMAGE_REVIEW, "confirm_images"));
}

@Test
void should_resolve_production_completed_to_completed() {
 assertEquals(ProjectStatus.COMPLETED,
 ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"));
}
```

```java
// PipelineServiceAutoAdvanceTest (mock repositories/services)
@Test
void confirm_script_should_eventually_enter_character_extracting() { }

@Test
void confirm_characters_should_eventually_enter_image_generating() { }
```

- [ ] **Step2: Run tests to confirm baseline behavior gaps**

Run: `mvn -q -Dtest=ProjectStatusTransitionTest,PipelineServiceAutoAdvanceTest test`
Expected: At least one FAIL for auto-chain expectations.

- [ ] **Step3: Commit baseline failing tests**

```bash
git add comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java
git commit -m "test: add state transition safety net before auto-advance changes"
```

**Acceptance:** Tests document intended auto-chain behavior and fail on current implementation.

**Rollback point:** Revert only these two test files if needed.

---

### Task2: Implement confirm -> auto-advance chains in pipeline owner

**Files:**
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
- Modify: `comic/src/main/java/com/comic/common/ProjectStatus.java`
- Test: `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`

- [ ] **Step1: Make `triggerNextStage` handle confirmed states**

Implementation target:
- `SCRIPT_CONFIRMED` => `advancePipeline(projectId, "start_character_extraction")`
- `CHARACTER_CONFIRMED` => `advancePipeline(projectId, "start_image_generation")`
- `ASSET_LOCKED` => `advancePipeline(projectId, "start_panels")`

- [ ] **Step2: Keep transitions idempotent-safe**

Ensure repeated confirm calls do not break:
- if already in target-or-later state, no duplicate illegal transition.
- keep existing business exceptions for truly invalid states.

- [ ] **Step3: Update available actions to match new semantics**

In `ProjectStatus.getAvailableActions()`:
- remove stale action hints that imply a second manual step after confirm.
- ensure frontend action list matches auto-advance model.

- [ ] **Step4: Re-run focused tests**

Run: `mvn -q -Dtest=PipelineServiceAutoAdvanceTest,ProjectStatusTransitionTest test`
Expected: PASS.

- [ ] **Step5: Commit**

```bash
git add comic/src/main/java/com/comic/service/pipeline/PipelineService.java comic/src/main/java/com/comic/common/ProjectStatus.java comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java
git commit -m "feat: auto-advance confirmed states via pipeline-owned transition chain"
```

**Acceptance:** Confirm actions move to next generating stage without second manual start.

**Rollback point:** Revert pipeline + enum files only.

---

### Task3: Add explicit image-confirm API and wire to transition chain

**Files:**
- Modify: `comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java`
- Modify: `comic/src/main/java/com/comic/controller/CharacterController.java`
- Test: `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`

- [ ] **Step1: Add service method `confirmImages(String projectId)`**

Behavior:
- validate project exists and status is `IMAGE_REVIEW`
- validate all characters satisfy image-complete rules
- call `pipelineService.advancePipeline(projectId, "confirm_images")`

- [ ] **Step2: Add controller endpoint**

Suggested endpoint:
- `POST /api/projects/{projectId}/characters/images/confirm`

- [ ] **Step3: Add test for invalid image confirm**

Case:
- image not complete => `BusinessException`.

- [ ] **Step4: Run tests**

Run: `mvn -q -Dtest=PipelineServiceAutoAdvanceTest test`
Expected: PASS.

- [ ] **Step5: Commit**

```bash
git add comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java comic/src/main/java/com/comic/controller/CharacterController.java comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java
git commit -m "feat: add image confirm endpoint and pipeline transition"
```

**Acceptance:** Frontend has first-class confirm-images API that immediately enters panel generation chain.

**Rollback point:** Revert new endpoint + service method only.

---

### Task4: Implement PRODUCING strict-serial orchestrator core

**Files:**
- Modify: `comic/src/main/java/com/comic/service/production/PanelProductionService.java`
- Modify: `comic/src/main/java/com/comic/service/production/ComicGenerationService.java`
- Modify: `comic/src/main/java/com/comic/repository/PanelRepository.java`
- Test: `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java`

- [ ] **Step1: Write failing orchestrator tests**

```java
@Test
void should_pick_single_current_panel_in_project_order() { }

@Test
void should_pause_when_comic_pending_review() { }

@Test
void should_pause_when_step_failed() { }

@Test
void should_not_start_next_panel_before_current_video_completed() { }

@Test
void confirm_panels_with_zero_panels_should_keep_panel_review() { }
```

- [ ] **Step2: Add project-level `startOrResume(projectId)` in `PanelProductionService`**

Required behavior:
- acquire project lock (Redis key, short TTL + renew or conservative long TTL)
- if project has zero panels, reject start with recoverable business error (do not transition to COMPLETED)
- pick first panel not video-completed in deterministic order
- drive stage transitions for only that panel
- release lock in finally block.

- [ ] **Step3: Hook resume points**

- after `approveComic(panelId)` => resume orchestrator for project.
- after retry success paths => resume orchestrator.

- [ ] **Step4: Implement `confirm_panels` gate ownership in `PipelineService`**

Before `PANEL_REVIEW -> PRODUCING`:
- `PipelineService` must verify project has producible panels (count >0 and eligible ordering metadata exists).
- on zero panels: throw recoverable `BusinessException` and keep status at `PANEL_REVIEW`.
- on success: apply `all_panels_confirmed` transition and then auto-start orchestrator.

- [ ] **Step5: Wire PRODUCING entry auto-start in pipeline**

In `PipelineService.triggerNextStage`, when status enters `PRODUCING`:
- call `panelProductionService.startOrResume(projectId)` immediately.
- keep idempotent guard so repeated calls do not duplicate work.

- [ ] **Step6: Implement failure pause semantics**

If current stage failed:
- do not advance to next panel.
- set blocking reason (`panel_failed` / `awaiting_comic_approval`).

- [ ] **Step7: Run orchestrator tests**

Run: `mvn -q -Dtest=PanelProductionOrchestratorTest test`
Expected: PASS.

- [ ] **Step8: Commit**

```bash
git add comic/src/main/java/com/comic/service/production/PanelProductionService.java comic/src/main/java/com/comic/service/production/ComicGenerationService.java comic/src/main/java/com/comic/repository/PanelRepository.java comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java
git commit -m "feat: add strict-serial producing orchestrator with pause-and-resume semantics"
```

**Acceptance:** At most one panel advances at a time; pending review/failed states block progression.

**Rollback point:** Revert orchestrator methods while keeping existing per-panel APIs functional.

---

### Task5: Persist project completion via pipeline transition (no derived-only completed)

**Files:**
- Modify: `comic/src/main/java/com/comic/service/production/PanelProductionService.java`
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
- Test: `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java`

- [ ] **Step1: Write failing completion test**

```java
@Test
void all_panel_videos_completed_should_emit_production_completed_and_persist_status() { }
```

- [ ] **Step2: Trigger pipeline event from orchestrator under lock/transaction**

When all panels `videoStatus=completed`:
- execute in one transactional unit under project lock: update current panel completion, recompute all-done, emit `production_completed` once.
- call `pipelineService.advancePipeline(projectId, "production_completed")` only when DB status is still `PRODUCING`.
- guard idempotency if already completed.

- [ ] **Step3: Align `/status` enrichment with persisted state**

Avoid returning derived `COMPLETED` while DB still `PRODUCING`.

- [ ] **Step4: Add concurrency regression test**

Simulate double resume/double completion trigger and assert:
- exactly one `production_completed` transition occurs
- final DB status is `COMPLETED` with no oscillation.

- [ ] **Step5: Run tests**

Run: `mvn -q -Dtest=PanelProductionOrchestratorTest,PipelineServiceAutoAdvanceTest test`
Expected: PASS.

- [ ] **Step6: Commit**

```bash
git add comic/src/main/java/com/comic/service/production/PanelProductionService.java comic/src/main/java/com/comic/service/pipeline/PipelineService.java comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java
git commit -m "fix: persist producing completion through pipeline event"
```

**Acceptance:** COMPLETED is stored in DB and broadcast via Redis/SSE.

**Rollback point:** Revert completion trigger change only.

---

### Task6: Add project-level production summary API for frontend sync

**Files:**
- Create: `comic/src/main/java/com/comic/dto/response/ProjectProductionSummaryResponse.java`
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
- Modify: `comic/src/main/java/com/comic/controller/ProjectController.java`
- Test: `comic/src/test/java/com/comic/controller/ProjectProductionSummaryApiTest.java`

- [ ] **Step1: Write failing API test**

```java
@Test
void get_production_summary_should_return_current_panel_and_blocked_reason() { }
```

- [ ] **Step2: Implement summary DTO and service aggregation**

Fields:
- `currentEpisodeId`
- `currentPanelId`
- `currentPanelIndex`
- `totalPanelCount`
- `completedPanelCount`
- `productionSubStage`
- `blockedReason`

- [ ] **Step3: Add endpoint**

`GET /api/projects/{projectId}/production/summary`

- [ ] **Step4: Keep `/status`, `/status/stream`, `/production-statuses` compatibility**

Ensure existing frontends still work while new summary endpoint provides canonical producing view.

- [ ] **Step5: Run API tests**

Run: `mvn -q -Dtest=ProjectProductionSummaryApiTest test`
Expected: PASS.

- [ ] **Step6: Commit**

```bash
git add comic/src/main/java/com/comic/dto/response/ProjectProductionSummaryResponse.java comic/src/main/java/com/comic/service/pipeline/PipelineService.java comic/src/main/java/com/comic/controller/ProjectController.java comic/src/test/java/com/comic/controller/ProjectProductionSummaryApiTest.java
git commit -m "feat: add project-level production summary api for frontend sync"
```

**Acceptance:** Frontend can render strict-serial producing board without scanning all episodes.

**Rollback point:** Remove new endpoint + DTO while preserving core orchestration.

---

### Task7: End-to-end verification of state machine contracts

**Files:**
- Create: `comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java`
- Modify: `comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java`
- Modify: `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java`

- [ ] **Step1: Add E2E scenarios**

Scenarios:
1. `SCRIPT_REVIEW confirm -> CHARACTER_EXTRACTING`
2. `CHARACTER_REVIEW confirm -> IMAGE_GENERATING`
3. `IMAGE_REVIEW confirm -> PANEL_GENERATING`
4. `PANEL_REVIEW all_confirmed -> PRODUCING`
5. Producing strict serial blocks on `pending_review`
6. Failure pause + retry resume
7. All video complete -> persisted `COMPLETED`

- [ ] **Step2: Run full targeted suite**

Run: `mvn -q -Dtest=ProjectStatusTransitionTest,PipelineServiceAutoAdvanceTest,PanelProductionOrchestratorTest,ProjectProductionSummaryApiTest,ProjectStateMachineE2ETest test`
Expected: PASS.

- [ ] **Step3: Run broader regression**

Run: `mvn test`
Expected: PASS, no unrelated failures.

- [ ] **Step4: Commit**

```bash
git add comic/src/test/java/com/comic/common/ProjectStatusTransitionTest.java comic/src/test/java/com/comic/service/pipeline/PipelineServiceAutoAdvanceTest.java comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java comic/src/test/java/com/comic/controller/ProjectProductionSummaryApiTest.java comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java
git commit -m "test: add end-to-end coverage for unified state machine and producing flow"
```

**Acceptance:** All required behaviors are covered by deterministic automated tests.

**Rollback point:** Keep code, revert only flaky E2E tests if needed and reintroduce stable subset.

---

### Task8: Boundary and recovery behavior hardening (spec §6)

**Files:**
- Modify: `comic/src/main/java/com/comic/service/production/PanelProductionService.java`
- Modify: `comic/src/main/java/com/comic/service/pipeline/PipelineService.java`
- Modify: `comic/src/main/java/com/comic/controller/PanelController.java`
- Test: `comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java`
- Test: `comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java`

- [ ] **Step1: Add failing boundary tests**

```java
@Test
void producing_without_panels_should_fail_recoverably() { }

@Test
void rollback_during_producing_should_release_lock_and_stop_resume() { }

@Test
void restart_resume_should_not_double_start_same_panel() { }

@Test
void editing_or_deleting_current_panel_should_be_blocked() { }
```

- [ ] **Step2: Implement rollback/restart protections**

- on PRODUCING rollback: release project lock and stop auto-resume chain.
- on service restart resume: acquire lock first, then reconstruct current panel idempotently.

- [ ] **Step3: Block mutating current producing panel**

In panel update/delete entry points, reject mutation when target is active current panel in producing flow.

- [ ] **Step4: Run boundary suite**

Run: `mvn -q -Dtest=PanelProductionOrchestratorTest,ProjectStateMachineE2ETest test`
Expected: PASS.

- [ ] **Step5: Commit**

```bash
git add comic/src/main/java/com/comic/service/production/PanelProductionService.java comic/src/main/java/com/comic/service/pipeline/PipelineService.java comic/src/main/java/com/comic/controller/PanelController.java comic/src/test/java/com/comic/service/production/PanelProductionOrchestratorTest.java comic/src/test/java/com/comic/e2e/ProjectStateMachineE2ETest.java
git commit -m "fix: harden producing boundary and recovery behaviors"
```

**Acceptance:** Spec §6 boundary behaviors are explicitly implemented and tested.

**Rollback point:** Revert boundary hardening commit while keeping core orchestration intact.

---

## Test plan (consolidated)

### Unit tests
- `ProjectStatusTransitionTest`
 - transition table correctness
 - available action exposure correctness
- `PipelineServiceAutoAdvanceTest`
 - confirm auto-chain behavior
 - idempotent repeated confirm calls

### Integration tests
- `PanelProductionOrchestratorTest`
 - strict serial selection
 - pending_review and failed blocking
 - retry/approve resume
 - completion event emission
- `ProjectProductionSummaryApiTest`
 - summary contract fields and values
 - compatibility with existing `/status`

### E2E tests
- `ProjectStateMachineE2ETest`
 - phase-by-phase full lifecycle
 - Redis broadcast + status persistence consistency
 - completion persistence correctness

---

## Phase-level acceptance checklist

- [ ] Confirm actions no longer require second manual start click.
- [ ] PRODUCING processes exactly one current panel globally.
- [ ] Pending comic review blocks next panel.
- [ ] Failed step pauses flow; retry resumes same panel.
- [ ] COMPLETED is persisted through pipeline event when all panel videos complete.
- [ ] Frontend can use `/status`, `/status/stream`, `/production/summary` for consistent sync.

---

## Operational rollback strategy

If production issue occurs after deploy:
1. Disable auto-resume entry in `PipelineService` (feature rollback commit).
2. Keep DB status transitions intact (do not remove source-of-truth persistence).
3. Keep Redis broadcaster enabled for observability.
4. Revert orchestrator-only commits first; preserve API schema when possible.

---

## Execution notes

- Keep commits small and phase-bounded.
- Never skip failing test -> minimal implementation -> passing test loop.
- Prefer explicit idempotency checks over implicit catch-and-ignore.
- Do not introduce extra states beyond approved spec.