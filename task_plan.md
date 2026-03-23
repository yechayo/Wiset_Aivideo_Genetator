# Task Plan - 分镜系统（第5章）

## 目标
将“分镜系统”5.0~5.4落地为可执行开发计划，覆盖：
- 5.0 分镜生成（`StoryboardService`）
- 5.1 场景分析（`SceneAnalysisService`）
- 5.2 九宫格/六宫格分镜生成（`SceneGridGenService`）
- 5.3 网格拆分与元数据绑定（新服务）
- 5.4 分镜增强（规则推荐 + LLM增强）

## 范围
- 后端服务、DTO/实体字段、流程编排、容错策略、单元/集成测试。
- 与现有生产流程（`EpisodeProductionService`）兼容并可渐进迁移。

## 不在本次范围
- 前端视觉重构（仅保留必要接口对接）。
- 新增第三方云服务接入（优先复用现有 AI/对象存储能力）。

## 当前状态快照（2026-03-23）
- `StoryboardService` 已具备生成、JSON校验、写入 `episode.storyboardJson`、更新角色状态能力。
- `SceneAnalysisService` 已具备按场景分组能力，但字段读取仍偏旧结构（`scene`/字符串角色）。
- `SceneGridGenService` 已支持 3x3 固定九宫格与占位格，但当前单次仅生成一页，不支持 2x3 布局。
- `EpisodeProductionService` 已支持按场景组逐页生成网格并逐页保存融合结果（默认每页 9 格）。
- 尚无独立“网格拆分服务”将大图切成单格并绑定完整分镜元数据。
- 尚无“分镜增强服务”实现规则推荐与 LLM术语回填校验。

## 阶段计划

| Phase | 任务 | 关键改动 | 产出 | 状态 |
|---|---|---|---|---|
| P0 | 需求冻结与数据契约 | 冻结 `storyboardJson` 标准结构；定义向后兼容策略（旧字段到新字段映射） | JSON schema + 映射规则文档 | in_progress |
| P1 | 5.0 分镜生成强化 | 在 API 层增加“仅当 `storyboardJson` 为空才生成”前置校验；统一 `PromptBuilder.buildStoryboardSystemPrompt` 输出契约；补齐失败重试与解析日志 | 可复用的分镜生成入口（幂等） | completed |
| P2 | 5.1 场景分析升级 | `SceneAnalysisService` 支持新结构读取（`background.scene_desc`、角色对象数组）；输出 `SceneGroupModel` 完整字段（sceneId/description/characters/start/end） | 稳定场景分组结果 | completed |
| P3 | 5.2 网格生成分页与布局 | `SceneGridGenService` 支持 3x3 与 2x3；单场景组超限分页；不足页补纯色占位；每页实时落库 | 分页网格 URL 列表 | completed |
| P4 | 5.3 网格拆分服务 | 新增 `GridSplitService`：按行优先切图（非识别）；索引与分镜数组绑定；页失败可跳过继续 | 单格图片 + 绑定元数据 | completed |
| P5 | 5.4 分镜增强服务 | 新增 `StoryboardEnhancementService`：规则推荐 + LLM增强；术语白名单校验；非法术语直接失败；批量0.5s节流 | 增强后的镜头参数与理由 | completed |
| P6 | 生产流程编排集成 | 在生产流程中接入增强与拆分后的数据链路，明确阶段顺序和状态迁移 | 端到端可运行流程 | in_progress |
| P7 | 测试与回归 | 单元测试（服务级）+ 集成测试（流程级）+ 兼容性测试（旧分镜数据） | 测试用例与通过报告 | pending |

## 子任务拆解（按需求5.x）

### 5.0 分镜生成（StoryboardService）
- 输入：`episode.content`（兼容当前 `outlineNode`）、世界观、角色状态。
- 处理：
1. 读取 episode/world/characters。
2. 构建 system/user prompt。
3. 调用文本生成服务。
4. JSON 清洗 + 解析 + 结构校验。
5. 写 `episode.storyboardJson`。
6. 更新角色状态。
- 关键约束：
1. `episode.getStoryboardJson()` 非空时默认拒绝生成（反馈修订入口除外）。
2. 结构校验失败必须可观测（错误码+日志）。

### 5.1 场景分析
- 输入：`storyboardJson.panels`。
- 输出：`List<SceneGroupModel>`，含 `sceneId/description/characters/startPanelIndex/endPanelIndex`。
- 关键约束：
1. 分组依据优先 `background.scene_desc`，回退旧 `scene`。
2. 角色名提取支持 `characters[].char_id` 与旧字符串数组。

### 5.2 九宫格/六宫格生成
- 输入：场景组（场景描述+关联角色）与分镜分页窗口。
- 输出：每页网格 URL。
- 关键约束：
1. 支持 3x3（9）与 2x3（6）两种布局。
2. 场景组内不足页补纯色占位格。
3. 超过上限自动翻页（按场景组独立计页）。
4. 每生成一页立即落库，便于前端轮询。

### 5.3 网格拆分
- 输入：网格大图 + 同页分镜数组。
- 输出：单格图及其绑定元数据。
- 关键约束：
1. 严格行优先索引绑定（`index = row * cols + col`）。
2. 不使用图像识别。
3. 单页失败不终止全流程（跳过并记录）。

### 5.4 分镜增强
- 模式A：规则推荐（无AI）。
- 模式B：LLM增强（术语库选择 + 理由）。
- 关键约束：
1. 术语不在白名单时直接失败，进入重试/人工修正流程。
2. 批量增强每镜头延迟 0.5s 防限流。

## 验收标准
- 能在 `ASSET_LOCKED -> STORYBOARD_* -> PRODUCING` 流程中稳定运行。
- 单场景组 1~N 分镜均可正确分页生成与拆分。
- 3x3 与 2x3 布局切图索引一致，元数据绑定无错位。
- 规则推荐与 LLM增强输出均可被校验并具备默认回退。
- 关键路径具备自动化测试覆盖（成功/失败/回退/跳过）。

## 风险与缓解
- 风险：新旧分镜字段并存导致解析歧义。
  - 缓解：统一“新字段优先，旧字段回退”的解析策略并加测试夹具。
- 风险：多页融合与分页索引不一致。
  - 缓解：统一页内索引规则，增加跨页边界测试（7/9/12/18等样本）。
- 风险：LLM增强结果漂移。
  - 缓解：白名单校验 + 默认值回退 + reason记录。

## 假设（如不变更将按此执行）
- 现有 `EpisodeProductionService` 仍以“场景组”为生产分页单位。
- 网格占位格使用统一纯色策略（默认灰色）并视为废弃格。
- 5.0 前置“分镜为空才能生成”不影响“反馈修订”专用入口。

## 实施决策（已确认）
| 议题 | 决策 |
|---|---|
| 5.0 生成前置校验 | 仅在 `storyboardJson` 为空时允许生成（非空禁止） |
| 校验放置层级 | 仅 API 层拦截（Service 层不加硬拦截） |
| 字段兼容策略 | 以第5章目标结构为唯一标准，不再保留旧字段兼容分支 |
| 场景分组策略 | 连续分镜优先，跨段合并作为可选优化 |
| `sceneId` 方案 | 可读ID + 稳定UUID 双轨 |
| 网格布局 | `<=6` 使用 2x3，`>6` 使用 3x3 |
| 分页口径 | 按场景组独立分页 |
| 占位格策略 | 统一灰色，且不可编辑 |
| 切图失败处理 | 单页失败自动重试一次；仍失败则记录并跳过后续页继续 |
| 分镜增强插入点 | 场景分析后、网格生成前 |
| LLM 非法术语处理 | 直接失败并触发重试/人工修正（不使用默认回退） |
| 批量节流 | 配置项控制，默认 0.5s |

## 错误记录
| 时间 | 问题 | 处理 |
|---|---|---|
| 2026-03-23 | 项目根目录无 `task_plan.md/findings.md/progress.md` | 按技能规范新建三份文件 |
| 2026-03-23 | `StoryboardService.java` 出现编码与字符串破损，导致编译失败 | 重建为干净 UTF-8 文件并保持现有业务能力 |
| 2026-03-23 | `PromptBuilder.java` 存在 `+++` 语法错误 | 最小修复为标准字符串拼接 |
| 2026-03-23 | 需要覆盖严格 JSON 契约校验，避免回归 | 新增 `StoryboardServiceTest` 非法枚举/缺字段用例并通过 |
| 2026-03-23 | 场景分析仍按旧字段解析，无法匹配第5章数据结构 | 升级 `SceneAnalysisService` 至 `background.scene_desc` + `characters[].char_id` 并补测试 |

## Latest Status (2026-03-23)
- P1: completed
- P2: completed
- P3: implemented in backend (grid pagination + 2x3/3x3 + dynamic fusion page cell count), test-verified
- P4: completed (new backend `GridSplitService` with row-major split, 2x3/3x3 support, index binding, and per-page retry-once-then-skip tolerance), test-verified
- P5: completed (new backend `StoryboardEnhancementService` with rule/LLM modes, whitelist validation, configurable 0.5s throttle, and flow insertion before grid generation), test-verified
- P6: in_progress (added backend split-page endpoint and production-service task orchestration for fusion, test-verified)
- P7: in_progress (added mixed-layout pagination fusion regression and extended suite verification)
