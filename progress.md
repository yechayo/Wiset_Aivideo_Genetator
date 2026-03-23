# Progress Log

## Session: 2026-03-23

### 本次目标
基于用户给出的“第五章分镜系统（5.0~5.4）”需求，建立 `planning-with-files` 三件套并完成可执行规划落盘。

### 已完成
1. 读取并应用技能文档：`.agents/skills/planning-with-files/SKILL.md`。
2. 检查项目根目录规划文件状态：确认 `task_plan.md / findings.md / progress.md` 不存在，需要新建。
3. 扫描后端现状实现，覆盖：
   - `StoryboardService`
   - `SceneAnalysisService`
   - `SceneGridGenService`
   - `EpisodeProductionService`
   - `PromptBuilder`
   - `SceneGroupModel`
   - `EpisodeProductionServiceTest`
4. 新建并填写：
   - `task_plan.md`（阶段计划、验收标准、风险、假设）
   - `findings.md`（需求映射、缺口证据、优先级建议）
   - `progress.md`（本日志）

### 关键结论
1. 5.0 主流程已具备，但缺“`storyboardJson` 为空才生成”的硬前置。
2. 5.1 场景分析仍偏旧字段解析，和目标 JSON 结构存在不一致。
3. 5.2 当前为固定3x3且单场景组单页，缺 2x3 与超9分页能力。
4. 5.3 后端拆分服务缺失（目前更多依赖前端逐格提交）。
5. 5.4 分镜增强（规则/LLM）尚未进入主流程。

### 产物清单
- `task_plan.md`
- `findings.md`
- `progress.md`

### 下一步（执行实现时）
1. 先做 P1/P2：锁定新旧字段兼容规则并修复 5.0/5.1 契约。
2. 再做 P3/P4：实现分页网格 + 后端拆分绑定。
3. 最后做 P5：增强服务与术语白名单回退机制。

### 异常与处理
- 异常：规划文件初始不存在。
- 处理：按技能规范在项目根目录新建并初始化。

### 决策补充（用户确认）
1. 明确“分镜为空才能生成”即“非空禁止生成”，并确认采用该规则。
2. 前置校验按用户要求仅放在 API 层。
3. 字段策略按第5章目标结构执行，不保留旧字段兼容分支。
4. 场景分组采用连续优先（跨段合并作为可选优化）。
5. `sceneId` 使用可读ID+稳定UUID双轨。
6. 网格布局采用 `<=6 -> 2x3`，`>6 -> 3x3`。
7. 分页按场景组独立计算。
8. 占位格统一灰色且不可编辑。
9. 切图失败策略为“自动重试一次，失败则记录并跳过”。
10. 分镜增强插入点定为“场景分析后、网格生成前”。
11. LLM非法术语不回退默认值，直接失败并重试/人工修正。
12. 批量增强节流改为配置项（默认0.5秒）。

### P1 实施记录（API 层前置校验）
1. 新增 `StoryControllerTest`，先写失败用例验证“已有分镜应拒绝生成”（红灯通过）。
2. 在 `StoryController.generateStoryboard` 增加：
   - episode 存在性校验（不存在返回 404）
   - `storyboardJson` 非空拦截（返回 400，提示使用修改分镜接口）
3. 在 `StoryController.retryStoryboard` 同步增加上述两项校验。
4. 补充测试覆盖：
   - 分镜非空拒绝生成
   - 分镜为空允许生成并提交任务
   - 分镜非空拒绝重试生成
5. 运行 `mvn -Dtest=StoryControllerTest test`，3/3 通过。

### 本轮异常与处理
- 异常：沙箱内 Maven 本地仓库不可写（Access is denied）。
- 处理：按流程申请提权后重跑测试，验证通过。

### 继续实施记录（P1 第二轮）
1. 新增 `StoryboardServiceTest`，验证“构建用户提示词优先使用 `episode.content`”。
2. 发现并定位编译阻塞：
   - `StoryboardService.java` 编码/字符串破损（含 BOM 与破损文本）
   - `PromptBuilder.java` 第74/75行存在 `+++` 语法错误
3. 处理：
   - 将 `StoryboardService.java` 重建为干净 UTF-8 版本（无乱码）
   - 保留并整理原有业务方法（分镜生成、修订、确认、重试、进入生产）
   - 保持严格 JSON 校验逻辑
   - 在构建 user prompt 时改为 `content` 优先、`outlineNode` 回退
   - 修复 `PromptBuilder.java` 字符串拼接语法
4. 验证：
   - `mvn -Dtest=StoryboardServiceTest test` 通过
   - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,EpisodeProductionServiceTest" test` 通过（16/16）

### 继续实施记录（P1 第三轮）
1. 扩展 `StoryboardServiceTest`：
   - `generateStoryboardWithFeedback` 场景验证 `episode.content` 优先
   - `validateStoryboardJson` 非法样本验证（非法 `shot_type`、缺失 `background.scene_desc`、非法 `bubble_type`）
2. 结果：
   - `mvn -Dtest=StoryboardServiceTest test` 通过（5/5）
   - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,EpisodeProductionServiceTest" test` 通过（20/20）

### 继续实施记录（P2 第一轮：场景分析升级）
1. 新增 `SceneAnalysisServiceTest`（TDD）：
   - 连续分镜按 `background.scene_desc` 分组
   - 从 `characters[].char_id` 提取角色列表
   - 缺少 `background.scene_desc` 时失败
2. 升级 `SceneAnalysisService`：
   - 解析结构切换为第5章标准字段（不再读取旧 `panel.scene` / 字符串角色）
   - 场景组输出补齐 `description/timeOfDay/mood` 等字段
   - 缺失关键字段时抛出明确格式错误
3. 验证：
   - `mvn -Dtest=SceneAnalysisServiceTest test` 通过（2/2）
  - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,SceneAnalysisServiceTest,EpisodeProductionServiceTest" test` 通过（22/22）

### Continue Update (2026-03-23, P3 in progress)
1. Rebuilt `SceneGridGenService` to support:
   - `2x3` layout when scene-group panel count is `<= 6`
   - `3x3` layout when scene-group panel count is `> 6`
   - per-scene-group pagination (`generateSceneGridPages`)
2. Updated `EpisodeProductionService`:
   - stage-2 grid generation now persists page-by-page URLs from `generateSceneGridPages`
   - fusion URL injection now maps by panel global index -> page/cell
   - `submitFusionPage` now computes expected cell count per page dynamically (6 or 9)
   - `getGridInfo` now derives page-to-scene-group mapping and per-page rows/cols
3. Updated DTO/entity:
   - `GridInfoResponse.GridPageInfo` includes `gridRows` and `gridColumns`
   - `Character` now includes `standardImageUrl` for existing reference mapping usage
4. Regression verification:
   - `mvn -Dtest=EpisodeProductionServiceTest test` passed
   - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,SceneAnalysisServiceTest,EpisodeProductionServiceTest" test` passed (22/22)

### Continue Update (2026-03-23, P4 completed)
1. Added `GridSplitService` in backend:
   - row-major split (`index = row * cols + col`) with no image recognition dependency
   - supports both `3x3` and `2x3` via `rows/cols` task parameters
   - binds each split cell to panel metadata by the same index
   - uploads each split cell via `OssService.uploadFromInputStream`
2. Added page-level fault tolerance in batch mode:
   - first failure retries once
   - second failure marks only that page as skipped and continues remaining pages
3. Added TDD coverage with `GridSplitServiceTest`:
   - validates row-major split and metadata binding on 3x3
   - validates 2x3 support and placeholder binding for missing panel metadata
   - validates retry-once-then-skip behavior
4. Regression verification:
   - `mvn -Dtest=GridSplitServiceTest test` passed
   - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,SceneAnalysisServiceTest,EpisodeProductionServiceTest,GridSplitServiceTest" test` passed (25/25)

### Continue Update (2026-03-23, P5 completed)
1. Added `StoryboardEnhancementService`:
   - supports `RULE` mode (keyword-based camera parameter recommendation)
   - supports `LLM` mode (panel-by-panel selection from predefined terminology)
   - validates LLM terms against strict whitelist and fails fast on invalid output
   - adds configurable panel-to-panel throttle (`comic.storyboard.enhancement.delay-ms`, default `500ms`)
2. Inserted enhancement step in production flow:
   - called after scene analysis and before grid generation in `EpisodeProductionService.executeProductionFlow`
3. Added tests:
   - `StoryboardEnhancementServiceTest` (rule recommendation, invalid-term fail-fast, throttle, episode persistence)
   - `EpisodeProductionServiceTest` new case to verify enhancement invocation
4. Added config defaults:
   - `comic.storyboard.enhancement.enabled=true`
   - `comic.storyboard.enhancement.mode=RULE`
   - `comic.storyboard.enhancement.delay-ms=500`
5. Regression verification:
   - `mvn "-Dtest=StoryboardEnhancementServiceTest,EpisodeProductionServiceTest" test` passed (17/17)
   - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,SceneAnalysisServiceTest,EpisodeProductionServiceTest,GridSplitServiceTest,StoryboardEnhancementServiceTest" test` passed (30/30)

### Continue Update (2026-03-23, P6 in progress)
1. Integrated backend split orchestration into production service:
   - added `EpisodeProductionService.splitGridPageForFusion(episodeId, pageIndex)`
   - validates status (`GRID_FUSION_PENDING`), page range, and storyboard schema
   - builds row-major `GridSplitService.PageSplitTask` with exact page metadata (`rows/cols/startPanelIndex/panels`)
2. Added API endpoint:
   - `POST /api/episodes/{episodeId}/split-grid-page`
   - supports optional `pageIndex` input and delegates to production service
3. Updated page-descriptor model:
   - `GridPageDescriptor` now includes `pageInGroup`, enabling correct per-scene-group page offset binding
4. Added test coverage:
   - `EpisodeProductionServiceTest` verifies task-building details (`pageIndex`, `rows`, `cols`, `startPanelIndex`, `panels`, object-key prefix)
   - includes out-of-range `pageIndex` exception case
5. Regression verification:
   - `mvn -Dtest=EpisodeProductionServiceTest test` passed (15/15)
   - `mvn "-Dtest=StoryControllerTest,StoryboardServiceTest,SceneAnalysisServiceTest,EpisodeProductionServiceTest,GridSplitServiceTest,StoryboardEnhancementServiceTest" test` passed (32/32)

### Continue Update (2026-03-23, P6 controller test coverage)
1. Added `EpisodeControllerTest` for new backend split endpoint:
   - `splitGridPage` with `body=null` defaults to `pageIndex=0`
   - numeric string `pageIndex` is parsed correctly
   - invalid `pageIndex` format throws `IllegalArgumentException`
   - negative `pageIndex` throws `IllegalArgumentException`
2. Verification:
   - `mvn -Dtest=EpisodeControllerTest test` passed (4/4)
   - extended regression suite including `EpisodeControllerTest` passed (37/37)

### Continue Update (2026-03-23, P6 multi-page offset guard)
1. Added one more production-service regression case:
   - validates same-scene-group multi-page split (`12 panels`) computes `startPanelIndex=9` on `pageIndex=1`
   - validates page panel binding starts from `ep1_p10`
2. Verification:
   - `mvn -Dtest=EpisodeProductionServiceTest test` passed (16/16)
   - extended regression suite passed (37/37)

### Continue Update (2026-03-23, P7 regression hardening)
1. Added mixed-layout pagination fusion tests in `EpisodeProductionServiceTest`:
   - scene-group A (`5 panels`) expects `2x3` page (`6` cells)
   - scene-group B (`10 panels`) expects two `3x3` pages (`9 + 9` cells)
2. Added verification scenarios:
   - partial completion must not trigger fusion resume
   - all pages complete (`6 + 9 + 9 = 24`) triggers resume check exactly once
3. Verification:
   - `mvn -Dtest=EpisodeProductionServiceTest test` passed (18/18)
   - extended regression suite passed (39/39)

### Continue Update (2026-03-23, P7 split->fusion chain regression)
1. Added chain regression in `EpisodeProductionServiceTest`:
   - executes `splitGridPageForFusion(pageIndex=0)` and reuses split cell image URLs
   - submits those URLs to `submitFusionPage(pageIndex=0)` in the same test flow
2. Assertions:
   - split returns expected 2x3 page metadata and 6 split cells
   - submit stage counts `totalFused=6`
   - fused URL matrix persistence is correct
   - resume check (`tryMarkFusionResumed`) is triggered once
3. Verification:
   - `mvn -Dtest=EpisodeProductionServiceTest test` passed (19/19)
   - extended regression suite passed (40/40)

### Continue Update (2026-03-23, P7 submit-fusion-page API boundary coverage)
1. Added API-level edge tests in `EpisodeControllerTest` for `submitFusionPage`:
   - null body -> clear parameter exception (`请求体不能为空`)
   - default `pageIndex=0` path
   - numeric-string `pageIndex` parse path
   - invalid/negative `pageIndex` rejection
   - missing or empty `panelFusedUrls` rejection
2. Production hardening:
   - `EpisodeController.submitFusionPage` now guards `body == null` before reading fields
3. Verification:
   - `mvn -Dtest=EpisodeControllerTest test` passed (10/10)
   - extended regression suite passed (46/46)

### Final Checkpoint (2026-03-23)
1. P6 completed:
   - backend split-page endpoint (`/split-grid-page`) is integrated with production orchestration
   - scene-group pagination address (`pageInGroup`) and panel binding offsets are regression-covered
2. P7 completed:
   - mixed-layout fusion pagination regressions covered
   - split->fusion chain regression covered
   - submit-fusion-page API boundary regressions covered
3. Latest verification snapshot:
   - `mvn -Dtest=EpisodeControllerTest test` passed (10/10)
   - `mvn -Dtest=EpisodeProductionServiceTest test` passed (19/19)
   - extended regression suite passed (46/46)
