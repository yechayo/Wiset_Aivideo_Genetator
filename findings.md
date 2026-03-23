# Findings - 分镜系统（第5章）现状与缺口

## 调研范围
- `backend/com/comic/src/main/java/com/comic/service/story/StoryboardService.java`
- `backend/com/comic/src/main/java/com/comic/service/production/SceneAnalysisService.java`
- `backend/com/comic/src/main/java/com/comic/service/production/SceneGridGenService.java`
- `backend/com/comic/src/main/java/com/comic/service/production/EpisodeProductionService.java`
- `backend/com/comic/src/main/java/com/comic/ai/PromptBuilder.java`
- `backend/com/comic/src/main/java/com/comic/dto/model/SceneGroupModel.java`
- `backend/com/comic/src/test/java/com/comic/service/production/EpisodeProductionServiceTest.java`
- `backend/com/comic/src/main/java/com/comic/common/ProjectStatus.java`

## 需求映射结论（5.0~5.4）

| 需求 | 当前状态 | 证据 | 结论 |
|---|---|---|---|
| 5.0 分镜生成主流程 | 已部分实现 | `StoryboardService.generateStoryboard` + `generateWithRetry` 包含提示词构建、AI调用、JSON解析校验、保存、角色状态更新 | 基本能力已在位 |
| 5.0 前置：`storyboardJson` 为空才生成 | 未实现 | `generateStoryboard` 直接覆盖写入，未见非空拦截（仅 `buildRevisionUserPrompt` 使用已有分镜做修订） | 需要补幂等前置校验 |
| 5.0 触发时机：`ASSET_LOCKED` 后、`PRODUCING` 前 | 已实现（项目状态层） | `ProjectStatus` 允许 `ASSET_LOCKED -> start_storyboard -> STORYBOARD_* -> start_production -> PRODUCING` | 状态流满足要求 |
| 5.1 按场景分组输出 `SceneGroupModel` | 已实现（基础版） | `SceneAnalysisService.groupPanelsByScene` + `SceneGroupModel` 包含 start/end/sceneId/description/characters | 可用但解析字段偏旧 |
| 5.1 新分镜结构兼容（`background.scene_desc`、角色对象） | 未实现 | `SceneAnalysisService.parsePanels` 读取 `panel.scene` 和 `characters` 字符串数组 | 需要升级解析逻辑 |
| 5.2 九宫格生成并实时落库 | 已实现（单场景组单页） | `EpisodeProductionService.executeProductionFlow` 逐场景组调用 `sceneGridGenService.generateSceneGrid`，每张落库 `sceneGridUrls` | 已支持实时展示 |
| 5.2 六宫格（2x3）支持 | 未实现 | `SceneGridGenService` 固定 `GRID_COLUMNS=3, GRID_ROWS=3` | 需要布局参数化 |
| 5.2 单场景组超9分镜多页 | 未实现 | `generateDetailedGridFromStoryboard` 仅固定9格并 `validCount=min(9,panelCount)` | 需要分页生成 |
| 5.2 不足格占位填充 | 已实现（九宫格内） | `fillPlaceholderCell` 为不足格补占位 | 可复用到多页与六宫格 |
| 5.3 网格拆分并索引绑定 | 缺失（后端） | 未发现后端 `GridSplitService`；前端仅有 `canvasSplitter.ts` 工具 | 需要新增后端拆分服务 |
| 5.3 容错：单页失败继续 | 缺失（拆分阶段未实现） | 当前流程无“拆分页容错”节点 | 需在新拆分服务中实现 |
| 5.4 规则推荐增强 | 缺失 | 未发现分镜增强服务或规则引擎 | 需要新增 |
| 5.4 LLM增强 + 术语校验 + 回退 | 缺失 | 未发现对应服务与术语库 | 需要新增 |
| 5.4 批量0.5s节流 | 缺失 | 未发现增强批处理实现 | 需要新增 |

## 状态更新（2026-03-23 第二轮）
- 已完成 P1 的首批落地：在 API 层为分镜生成/重试接口增加“分镜非空拒绝”校验，并补充单元测试。
- 对应文件：
  - `backend/com/comic/src/main/java/com/comic/controller/StoryController.java`
  - `backend/com/comic/src/test/java/com/comic/controller/StoryControllerTest.java`
- 说明：该规则按用户决策仅放在 API 层，Service 层暂不增加硬拦截。

## 状态更新（2026-03-23 第三轮）
- 已完成 `StoryboardService` 的 UTF-8 重建与编译恢复，避免乱码/破损字符串导致的构建失败。
- 已修复 `PromptBuilder` 的 `+++` 语法错误，恢复编译链路。
- 已落地 `episode.content` 优先输入策略（`content` 非空时不再使用 `outlineNode`）。
- 新增并通过 `StoryboardServiceTest`，验证上述行为。

## 状态更新（2026-03-23 第四轮）
- 已补齐 `StoryboardService` 严格 JSON 契约的单元测试覆盖：
  - 非法 `shot_type` 拒绝
  - 缺失 `background.scene_desc` 拒绝
  - 非法 `bubble_type` 拒绝
- 当前与分镜生成相关的核心测试集（StoryController/StoryboardService/EpisodeProductionService）均已通过。

## 状态更新（2026-03-23 第五轮）
- 已完成 5.1 场景分析升级：
  - `SceneAnalysisService` 按新结构解析 `background.scene_desc`
  - 从 `characters[].char_id` 提取角色
  - 缺关键字段时快速失败
  - 场景组输出补齐 `description/timeOfDay/mood`
- 新增 `SceneAnalysisServiceTest` 并通过，且与现有核心测试集联合回归通过。

## 关键代码观察

### A. 5.0 分镜生成链路已存在
- `StoryboardService.generateWithRetry` 已执行：
1. 获取世界观/角色状态
2. 调用提示词构建
3. 调用 `textGenerationService.generate`
4. JSON清洗、解析、`validateStoryboardJson`
5. `characterService.updateStatesFromStoryboard`
6. 返回并写入 `episode.storyboardJson`
- 风险：当前校验仅检查 `panels` 存在且非空，未校验新结构字段完整性。

### B. 5.1 场景分析与新结构存在契约不一致
- `SceneAnalysisService.parsePanels` 当前读取：
  - `panel.scene`
  - `panel.characters`（字符串数组）
  - `panel.description`、`panel.dialogue`
- 与目标结构差异：
  - 新结构场景位于 `background.scene_desc`
  - 新结构角色位于 `characters[].char_id`
  - 对白为数组对象 `dialogue[]`，不是纯字符串

### C. 5.2 当前生产流程按“场景组=一页九宫格”运行
- `EpisodeProductionService.executeProductionFlow` 已逐场景组生成网格并累计 `sceneGridUrls`。
- `SceneGridGenService.generateDetailedGridFromStoryboard` 每次仅生成固定9格内容。
- 结论：若单场景组超过9分镜，当前实现会截断，不会翻页。

### D. 5.3 逐格融合存在，但“切图+绑定”链路缺失
- 生产流程已有 `submitFusionPage` 逐页接收 9 个格子 URL。
- 但这依赖前端先提交格子图，后端未提供统一切图服务。
- 结论：需要新增后端标准拆分实现，统一数组索引绑定规则。

### E. 5.4 分镜增强尚未进入主流程
- 未发现 `StoryboardEnhancementService` 类似实现。
- 当前 `EpisodeProductionService` 从 `sceneAnalysis -> grid -> fusion -> prompt` 直接推进。
- 结论：增强步骤需在“场景分析后/提示词构建前”插入。

## 建议优先级
1. P1/P2 先打通数据契约（5.0+5.1）以保证后续步骤输入稳定。
2. P3/P4 实现分页网格与后端拆分，解决生产主链路缺口。
3. P5 再接入分镜增强，避免在不稳定数据结构上叠加复杂度。

## 实施依赖
- 需要为 `storyboardJson` 定义统一 schema（建议新增 JSON schema 校验工具类）。
- 需要明确对象存储上传接口在后端拆分阶段的调用方式（复用现有上传能力）。
- 需要确定 2x3 与 3x3 布局选择策略（配置驱动或场景组自适应）。

## Status Update (2026-03-23)
- P3 backend implementation is now in place:
  - Scene-group-level pagination for grid generation
  - Dynamic layout selection (`<=6 -> 2x3`, `>6 -> 3x3`)
  - `submitFusionPage` supports dynamic expected cells per page (6/9)
  - Prompt fusion-image mapping upgraded from "group-index based" to "panel-index -> page/cell based"
  - `getGridInfo` now carries page-to-scene-group mapping and per-page rows/cols
- Core regression suite (`StoryControllerTest`, `StoryboardServiceTest`, `SceneAnalysisServiceTest`, `EpisodeProductionServiceTest`) passed.

## Status Update (2026-03-23, P4)
- Backend `GridSplitService` is now implemented:
  - splits grid images in strict row-major order
  - supports both `3x3` and `2x3` layouts
  - binds split cell result to panel metadata by index
  - uploads each split cell to OSS
- Fault tolerance for multi-page split is in place:
  - page split failure retries once
  - if retry still fails, that page is skipped and next page continues
- Test evidence:
  - new `GridSplitServiceTest` added and passed
  - regression suite (`StoryControllerTest`, `StoryboardServiceTest`, `SceneAnalysisServiceTest`, `EpisodeProductionServiceTest`, `GridSplitServiceTest`) passed (25/25)

## Status Update (2026-03-23, P5)
- Backend `StoryboardEnhancementService` is now implemented:
  - rule-based recommendation mode (no LLM cost)
  - LLM mode with strict terminology whitelist validation
  - invalid LLM terms now fail fast (no silent fallback), aligned with confirmed decision
  - configurable panel throttle (`500ms` default) for batch LLM enhancement
- Integration is in place at the required insertion point:
  - enhancement runs after scene analysis and before grid generation
- Test evidence:
  - new `StoryboardEnhancementServiceTest` passed
  - `EpisodeProductionServiceTest` includes enhancement invocation assertion and passed
  - full regression suite passed (30/30)

## Status Update (2026-03-23, P6 in progress)
- Backend split integration in orchestration is now in place:
  - `EpisodeProductionService` adds `splitGridPageForFusion(episodeId, pageIndex)`
  - service builds page-level split task with row-major metadata binding (`rows/cols/startPanelIndex/panels`)
  - supports scene-group pagination via `GridPageDescriptor.pageInGroup`
- API surface added:
  - `POST /api/episodes/{episodeId}/split-grid-page`
  - optional `pageIndex` parsing and validation at controller level
- Test evidence:
  - `EpisodeProductionServiceTest` adds task-build assertion and out-of-range guard case
  - `mvn -Dtest=EpisodeProductionServiceTest test` passed (15/15)
  - full regression suite passed (32/32)

## Status Update (2026-03-23, P6 endpoint test coverage)
- Added new `EpisodeControllerTest` for `/split-grid-page`:
  - default pageIndex behavior (`body` absent)
  - numeric-string parse path
  - invalid format rejection
  - negative value rejection
- Evidence:
  - `mvn -Dtest=EpisodeControllerTest test` passed (4/4)
  - extended regression suite passed (37/37)

## Status Update (2026-03-23, P6 pagination offset regression)
- Added service-level regression for `splitGridPageForFusion`:
  - same scene group with `12` panels and two grid pages
  - page-2 task is verified with `startPanelIndex=9` and first bound panel `ep1_p10`
- Evidence:
  - `mvn -Dtest=EpisodeProductionServiceTest test` passed (16/16)

## Status Update (2026-03-23, P7 mixed-layout fusion regression)
- Added regression coverage for mixed page-cell expectations in `submitFusionPage`:
  - page-0 expects `6` fused cells (2x3)
  - page-1 and page-2 each expect `9` fused cells (3x3)
- Behavioral assertions:
  - not-all-done state does not trigger resume
  - all-done state (`24` total fused cells) triggers `tryMarkFusionResumed` once
- Evidence:
  - `mvn -Dtest=EpisodeProductionServiceTest test` passed (18/18)
  - extended regression suite passed (39/39)

## Status Update (2026-03-23, P7 split-to-fusion chain regression)
- Added one chain-level regression in `EpisodeProductionServiceTest`:
  - first calls `splitGridPageForFusion` to get split cell image URLs
  - then directly calls `submitFusionPage` with those URLs
- Verifies:
  - 2x3 split metadata consistency
  - fused matrix persistence (1 page x 6 urls)
  - fusion total count and resume-check behavior
- Evidence:
  - `mvn -Dtest=EpisodeProductionServiceTest test` passed (19/19)
  - extended regression suite passed (40/40)

## Status Update (2026-03-23, P7 submit-fusion-page API boundaries)
- Added `EpisodeControllerTest` coverage for `submitFusionPage`:
  - `body == null` guard path
  - default page index path
  - numeric-string page index parse path
  - invalid / negative page index rejection
  - missing / empty fused URL list rejection
- Applied a small controller hardening:
  - explicit `body == null` check in `EpisodeController.submitFusionPage`
- Evidence:
  - `mvn -Dtest=EpisodeControllerTest test` passed (10/10)
  - extended regression suite passed (46/46)

## Final Assessment (2026-03-23)
- P6 status: completed
  - production orchestration and backend split endpoint integration are in place and test-verified
- P7 status: completed
  - core regression matrix now includes:
    - split task build correctness (including multi-page offset)
    - mixed-layout fusion completion rules (6/9 cell expectations)
    - split-to-fusion chain continuity
    - submit-fusion-page API boundary guards
- Residual note:
  - current coverage is service/controller-level unit regression; no external-system E2E (real OSS/network image split) has been executed in this cycle.
