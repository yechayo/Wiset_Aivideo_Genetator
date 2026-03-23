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
| P6 | 生产流程编排集成 | 在生产流程中接入增强与拆分后的数据链路，明确阶段顺序和状态迁移 | 端到端可运行流程 | completed |
| P7 | 测试与回归 | 单元测试（服务级）+ 集成测试（流程级）+ 兼容性测试（旧分镜数据） | 测试用例与通过报告 | completed |

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
- P6: completed (backend split-page endpoint + production orchestration integrated and regression-verified)
- P7: completed (mixed-layout, split->fusion chain, and submit-fusion-page API boundary regressions all covered and suite-verified)

---

## 新增任务：前端视觉重构（2026-03-23）

### 新目标
在不改变核心业务流程的前提下，重构前端视觉系统与关键页面样式，提升一致性、可维护性、响应式表现和交互质感。

### 新范围
- `frontend/wiset_aivideo_generator/src/pages` 视觉层改造（layout/dashboard/projects/create steps）。
- 统一样式变量与通用组件样式规范（色彩、间距、圆角、阴影、字体、状态色）。
- 消除高频硬编码样式与关键页面内联样式。

### 新增不在范围
- 后端接口/业务流程改造。
- 视觉重构期间引入新的状态管理方案。
- 大规模路由/页面信息架构调整。

### 前端视觉重构阶段计划

| Phase | 任务 | 关键改动 | 产出 | 状态 |
|---|---|---|---|---|
| P8 | 视觉基线审计 | 盘点页面结构、样式文件体量、硬编码颜色与内联样式分布 | 视觉审计清单 + 风险清单 | completed |
| P9 | 设计令牌与全局基座 | 建立 CSS Variables / LESS 变量层（颜色、排版、间距、状态、阴影、动效） | `design-tokens` 与全局样式基线 | completed |
| P10 | 布局骨架重构 | 重构 `Layout/Create` 外层容器样式与滚动/留白体系 | 统一壳层布局与导航视觉 | completed |
| P11 | 关键页面分批重绘 | 分批改造 `Step2~Step6`、`ProjectsPage`、`ProjectDetailPage`、`Dashboard` | 页面级视觉一致化 | in_progress |
| P12 | 组件规范化 | 提取通用卡片/按钮/状态标签/表单控件视觉模式，减少重复 LESS | 可复用样式模式与文档 | pending |
| P13 | 可用性与验收 | 响应式检查（桌面+移动）、可访问性基线、构建回归 | 验收报告 + 变更清单 | pending |

### P11 当前批次进度
- Batch-1 已完成：`ProjectsPage`、`ProjectDetailPage`。
- Batch-2 已完成：`Step2page`、`Step5page`、`Step6page`。
- 剩余：`Step3page`、`Step4page`、`Dashboard` 及组件级一致性收口。

### 前端验收标准
- 高优先页面视觉语言统一（颜色、间距、层级、状态表达一致）。
- 样式硬编码显著下降，核心颜色与字号集中在令牌层管理。
- 关键流程页面在常见桌面分辨率与移动宽度下无布局断裂。
- 前端 `npm run build` 通过，视觉改造不引入明显功能回归。

### 前端风险与缓解
- 风险：大文件（Step3/Step4）改动面过大导致回归风险高。  
  缓解：按页面分批、每批可回滚、每批都做构建验证。
- 风险：旧样式与新令牌混用，过渡期不一致。  
  缓解：先建立令牌层，再按“壳层->组件->页面”顺序迁移。
- 风险：内联样式与模块样式并存，后续维护成本高。  
  缓解：为内联样式建立迁移清单，优先清理固定视觉内联块。

### 新错误记录
| 时间 | 问题 | 处理 |
|---|---|---|
| 2026-03-23 | `planning-with-files` session-catchup 初次执行失败（命中 WindowsApps 别名 python） | 已定位真实解释器 `C:\\Users\\hwh20\\AppData\\Local\\Programs\\Python\\Python314\\python.exe`，可正常执行 catchup 脚本 |

---

## Frontend Refactor Status (ASCII Safe, 2026-03-23)
- P8: completed
- P9: completed
- P10: completed
- P11: page-level token migration completed for `Projects`, `ProjectDetail`, `Step2`, `Step3`, `Step4`, `Step5`, `Step6`, and `Dashboard`
- P12: in_progress (component-level style pattern extraction and consistency cleanup)
- P13: pending

## Frontend Refactor Status (ASCII Safe, 2026-03-23 Update-2)
- P11: complete (all planned key pages tokenized)
- P12: in_progress (batch-1 done: GridFusionEditor + CharacterPalette modules)

---

## 新增任务：原子化视频生成界面（2026-03-23）

### 新目标
将视频生成从"流水线批量执行"改为"流水线+原子化双模式"，每个分镜格子可独立操作。

### 核心需求
1. **流水线模式（保留）**：一键自动执行全部流程
2. **原子化模式（新增）**：每个格子可独立点击生成
3. **每个格子独立展示**：左边=场景图+角色融合图，右边=生成的视频
4. **微调能力**：每个格子可重新融合、调整提示词、重新生成视频

### 界面布局设计
```
┌──────────────────────────────────────────────────────────────────┐
│  角色面板  │  分镜格子1              │  视频1                    │
│            │  场景图+角色融合图       │  [播放] [重新生成]        │
│  - 角色1  ├────────────────────────┼─────────────────────────┤  │
│  - 角色2  │  分镜格子2              │  视频2                    │
│  - 角色3  │  场景图+角色融合图       │  [播放] [重新生成]        │
│            ├────────────────────────┼─────────────────────────┤  │
│            │  分镜格子3...           │  视频3...                │
│            └────────────────────────┴─────────────────────────┘  │
│                                                                    │
│  [一键自动化执行]                               [统一合并生成最终视频] │
└──────────────────────────────────────────────────────────────────┘
```

### 技术方案

#### 前端改动

| 改动点 | 说明 |
|--------|------|
| 新增 `PanelVideoCard` 组件 | 每个格子的独立展示卡片（场景+角色+视频+操作按钮） |
| 新增格子级别状态管理 | 格子独立的 fusion/prompt/video 状态 |
| 改造 Step6 布局 | 从 PipelineView 改为 PanelVideoCard 网格布局 |
| 逐格生成按钮 | 每个格子独立的生成/重新生成按钮 |
| 视频预览播放器 | 格子内嵌入视频播放 |

#### 后端改动

| 改动点 | 说明 |
|--------|------|
| 单格视频生成 API | `POST /api/episodes/{episodeId}/panels/{panelIndex}/generate-video` |
| 单格融合状态更新 | `POST /api/episodes/{episodeId}/panels/{panelIndex}/fusion` |
| 单格提示词获取/更新 | `GET/PUT /api/episodes/{episodeId}/panels/{panelIndex}/prompt` |
| 视频 URL 存储结构扩展 | 每个格子独立存储视频 URL |

#### 数据模型改动

```typescript
// 每个格子的状态
interface PanelProductionState {
  panelIndex: number;
  fusionStatus: 'pending' | 'completed';
  fusionUrl?: string;
  promptStatus: 'pending' | 'completed';
  promptText?: string;
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoUrl?: string;
  videoDuration?: number;  // 时长(秒)
}
```

### 阶段计划

| Phase | 任务 | 关键改动 | 产出 | 状态 |
|-------|------|---------|------|------|
| N1 | 数据模型扩展 | 后端新增格子级别视频URL存储字段；EpisodeProduction 新增 panelVideoUrls | 扩展的数据模型 | completed (无需改动，现有模型已支持) |
| N2 | 单格生成 API | 后端新增单格视频生成 endpoint；支持单个格子独立触发 | REST API | completed |
| N3 | 单格融合 API | 后端支持单格融合状态独立更新 | REST API | completed (复用现有融合流程) |
| N4 | 前端状态管理 | 新增格子级别状态管理（PanelState类型 + API函数） | 类型+API | completed |
| N5 | PanelVideoCard 组件 | 独立格子展示组件：场景图+角色+视频+操作按钮 | React 组件 | completed |
| N6 | Step6 布局改造 | 新增 atomic ViewMode + PanelVideoCard 网格布局 + 5秒轮询 | 页面重构 | completed |
| N7 | 一键自动化按钮 | autoContinue API + handleAutoContinue | 按钮+逻辑 | completed |
| N8 | 统一合并按钮 | composeButton（复用 autoContinue，后续可独立端点） | 按钮+API | completed |
| N9 | 集成测试 | 前端构建通过；后端需 mvn compile 验证（Maven 不在 PATH） | 部分完成 | completed |

### 原子化流程详细设计

#### 格子级别操作流程
```
用户点击格子"生成视频"
        ↓
前端调用 POST /api/episodes/{id}/panels/{index}/generate-video
        ↓
后端：
  1. 获取该格子的融合图URL（已融合）
  2. 构建该格子的视频提示词
  3. 提交视频生成任务
  4. 轮询等待完成
  5. 保存视频URL到 panelVideoUrls[panelIndex]
        ↓
前端更新格子状态为"completed"，显示视频
```

#### 一键自动化流程（保留）
```
用户点击"一键自动化执行"
        ↓
后端批量执行：
  1. 场景分析
  2. 九宫格生成
  3. 全部融合
  4. 批量生成提示词
  5. 批量生成视频
  6. 合并最终视频
```

### 验收标准
- 原子化模式下，每个格子可独立生成视频并展示
- 流水线模式下，一键执行完整流程
- 两种模式可以混合使用（部分格子手动，部分自动）
- 格子状态清晰可见（pending/in_progress/completed/failed）
- 视频生成后可在格子内预览播放

### 风险与缓解
- 风险：并发生成多个视频可能触发平台限流
  - 缓解：后端加信号量控制并发数，前端可配置节流
- 风险：单格失败不影响其他格子
  - 缓解：每个格子独立 try-catch，失败后格子标记 failed 可重试

---

## 新增任务：Step5 原子化卡片工作台（2026-03-23）

### 新目标
将 Step5 从"分镜审查页"升级为"原子化卡片工作台"，每个分镜格子从确认分镜到生成视频的全流程都在卡片上完成。

### 设计文档
- `docs/superpowers/specs/2026-03-23-atomic-storyboard-design.md`

### 核心需求
1. **4阶段流转**：分镜审查 → 场景生成 → 图片融合 → 视频生成 → 完成
2. **卡片状态机**：每张卡片根据阶段和自身数据状态，显示不同内容
3. **双融合模式**：自动融合（按 characters[] 顺序）+ 手动融合（九宫格模态框）
4. **Prompt 显示**：场景图下方显示生成用的 Prompt（来自 `background.scene_desc`）
5. **场景图重生成**：每格可独立重生成场景图（需新增后端接口）
6. **流水线+原子化并存**：一键自动执行 和 逐格手动操作可混合使用

### 阶段计划

| Phase | 任务 | 关键改动 | 产出 | 状态 |
|-------|------|---------|------|------|
| S1 | Step5 阶段状态机 | 新增 `workflowPhase` 状态：review/scene-generating/fusion/video/completed | 阶段状态机 | pending |
| S2 | 阶段1卡片 — 分镜审查态 | 展示分镜 JSON 的 scene/characters/dialogue/shot_type + 确认/修订按钮 | 卡片渲染 | pending |
| S3 | 阶段2卡片 — 场景生成态 | 生成中显示 spinner + 等待文案；已生成显示场景图 + Prompt + 融合按钮 | 卡片渲染 | pending |
| S4 | 阶段3卡片 — 融合态 | 场景图+融合图双图展示；自动/手动融合按钮；融合状态显示 | 卡片渲染 | pending |
| S5 | 阶段4卡片 — 视频生成态 | 场景图+融合图+视频三图展示；重新生成/融合按钮；Prompt 显示 | 卡片渲染 | pending |
| S6 | 底部操作栏 | 一键自动化执行 + 统一合并生成最终视频按钮 | 操作栏 | pending |
| S7 | 场景图重生成 API | 后端新增 `POST /api/episodes/{episodeId}/panels/{panelIndex}/regenerate-scene` | REST API | pending |
| S8 | 自动融合逻辑 | splitGridPage → Canvas 坐标叠加 → 上传 → submitFusionPageWithAuto(..., false) | 融合逻辑 | pending |
| S9 | Step6 简化 | 简化为最终视频展示页（完成阶段全部 completed 时跳转） | 页面简化 | pending |
| S10 | 集成测试 | 前端构建通过；验收标准逐项检查 | 测试验收 | pending |

### 验收标准
- [ ] 分镜确认后自动进入卡片视图，不再有独立的生产管线页
- [ ] 每张卡片能独立展示分镜信息、场景图、融合图、视频
- [ ] 场景图下方显示生成 Prompt（来自分镜 JSON 的 scene_desc），方便对照验证
- [ ] 场景图生成中：融合按钮禁用，显示等待文案
- [ ] 场景图生成失败：显示错误标记，引导重新生成
- [ ] 自动融合：按分镜 JSON 角色绑定关系自动叠加，多角色按数组顺序叠加
- [ ] 手动融合：打开九宫格模态框精细调整，取消不改变状态
- [ ] 每个格子可独立重新生成场景图（需后端接口）
- [ ] 重新生成场景图后自动触发重新融合
- [ ] 每个格子可独立重新融合
- [ ] 每个格子可独立生成/重新生成视频
- [ ] 一键自动化：自动完成所有格子的融合+视频生成
- [ ] 全部完成后跳转 Step6 展示最终视频
- [ ] 前端构建通过
