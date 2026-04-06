# 漫剧解说模式新增字段分支设计

## 概述

在现有项目创建与生成链路中新增 `productionMode` 字段，用于区分 `realtime_animation` 与 `comic_commentary` 两种制作模式。该字段作为统一入口写入 `projectInfo`，并在剧本生成阶段作为首个分流点，为后续漫剧解说模式接入独立 prompt builder 提供稳定边界。

本设计的核心原则是：**保护现有实时动画 prompt 结构不变**。因此首阶段只改“项目入口 + 剧本生成分流”，不直接侵入现有 `PanelPromptBuilder`、`PanelProductionService`、`GridImageService` 这条实时动画核心链路。

## 目标

- 为项目增加明确的制作模式字段 `productionMode`
- 让创建项目、更新项目、读取项目时都能携带该字段
- 在 `ScriptService` 处按模式分流不同的 script prompt builder
- 为漫剧解说模式新增独立 builder，而不是污染现有实时动画 builder
- 保证老项目与旧请求在没有该字段时仍按实时动画模式正常运行

## 非目标

- 本阶段不改现有 `PanelPromptBuilder` 的提示词结构
- 本阶段不改现有 `PanelProductionService` 的实时动画视频生成逻辑
- 本阶段不为漫剧解说模式实现独立 panel/video prompt
- 本阶段不调整数据库表结构，继续沿用 `projectInfo` JSON 存储扩展字段

## 当前代码事实

### 项目创建入口

后端创建请求当前仅包含故事、题材、风格、供应商等字段，还没有制作模式字段。`ProjectService.createProject()` 会将这些字段写入 `projectInfo`。

涉及文件：
- `backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java`
- `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java`
- `backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java`
- `backend/com/comic/src/main/java/com/comic/controller/ProjectController.java`

### 前端创建页

前端 Step1 当前负责输入故事创意和各类配置参数，并组装 `CreateProjectRequest` 提交给后端。现有前端类型也没有制作模式字段。

涉及文件：
- `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`
- `frontend/wiset_aivideo_generator/src/services/types/project.types.ts`

### 剧本生成层

`ScriptService` 在生成大纲和分集时，会从 `projectInfo` 读取 `storyPrompt`、`genre`、`targetAudience`、`visualStyle` 等字段，并调用 `ScriptPromptBuilder` 构建 prompt。这一层是最自然的模式分流点。

涉及文件：
- `backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java`
- `backend/com/comic/src/main/java/com/comic/ai/ScriptPromptBuilder.java`

### 实时动画核心链路

现有实时动画视频 prompt 主要由 `PanelPromptBuilder` 和 `PanelProductionService` 驱动，内容复杂且经过精调，不适合直接插入漫剧模式条件分支。

涉及文件：
- `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java`
- `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`
- `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`

## 方案选择

### 方案 1：新增 `productionMode` 字段（采用）

在项目创建与更新请求中新增 `productionMode`，并写入 `projectInfo.productionMode`。后续所有模式判断都从该字段读取。

优点：
- 边界清晰，模式信息从项目入口就明确
- 便于后续 script、panel、video 各层统一分流
- 与现有 `projectInfo` 扩展模式一致，改动最小
- 对老项目兼容简单，只需在读取时提供默认值

缺点：
- 需要前后端类型与创建页同步补齐字段

### 方案 2：复用现有字段隐式推断模式（不采用）

例如根据 `visualStyle` 或其他现有字段推断是否为漫剧解说模式。

不采用原因：
- 语义不准确，容易让风格字段承担模式职责
- 后续扩展更多模式时不可维护
- 会导致模式判断散落在多处业务逻辑中

### 方案 3：直接在现有 prompt builder 内部加条件（不采用）

在 `ScriptPromptBuilder` 或 `PanelPromptBuilder` 中直接通过 `if/else` 生成不同模式的提示词。

不采用原因：
- 容易污染现有实时动画精调链路
- 让 builder 同时维护多套提示词哲学，职责变重
- 后续漫剧解说扩展到 panel/video 时风险更高

## 设计方案

### 1. 新增字段定义

新增统一字段：
- 字段名：`productionMode`
- 存储位置：`projectInfo.productionMode`
- 值域：
  - `realtime_animation`
  - `comic_commentary`

默认值：
- 创建项目未传时，默认写入 `realtime_animation`
- 读取项目未发现该字段时，按 `realtime_animation` 处理

### 2. 前端入口调整

在创建页 Step1 增加“制作模式”选择项，默认选中 `realtime_animation`。

前端改动：
- `CreateProjectRequest` 增加 `productionMode?: ProductionMode`
- `ProjectInfoData` 增加 `productionMode?: ProductionMode`
- `Step1Content.tsx` 新增模式选择 UI，并在提交时带上 `productionMode`

推荐前端类型：
- `type ProductionMode = 'realtime_animation' | 'comic_commentary'`

### 3. 后端请求与持久化调整

后端改动：
- `ProjectCreateRequest` 增加 `productionMode`
- `ProjectInfoKeys` 增加 `PRODUCTION_MODE`
- `ProjectService.createProject()` 写入 `projectInfo.productionMode`
- `ProjectService.updateProject()` 支持更新该字段
- `ProjectController.createProject()` 与更新接口继续复用现有 DTO，无需额外改路由

该字段继续存储在 `projectInfo` 中，不新增数据库列。

### 4. 剧本生成分流

首个分流点放在 `ScriptService`，而不是直接改现有 `PanelPromptBuilder`。

分流规则：
- `realtime_animation` → 使用现有 `ScriptPromptBuilder`
- `comic_commentary` → 使用新增 `ComicCommentaryScriptPromptBuilder`

建议做法：
- 在 `ScriptService.generateScriptOutline()` 和 `generateScriptEpisodes()` 中读取 `productionMode`
- 根据模式选择对应 builder 来构造 system/user prompt
- 不改现有实时动画 builder 的 prompt 文本结构

### 5. 新增漫剧专属 builder

新增文件：
- `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryScriptPromptBuilder.java`

职责：
- 构建漫剧解说模式的大纲 prompt
- 构建漫剧解说模式的分集 prompt

约束：
- 不复写或混入现有 `ScriptPromptBuilder` 的实时动画提示词结构
- 保持类职责单一，让不同模式各自维护自己的 prompt 模板

### 6. panel/video 阶段的渐进接入策略

本阶段先不改现有 `PanelPromptBuilder`、`PanelProductionService`、`GridImageService`。

后续若漫剧解说模式确实需要独立 panel/video prompt，再在更外层新增第二处分流：
- `realtime_animation` → 现有 `PanelPromptBuilder`
- `comic_commentary` → 新增 `ComicCommentaryPanelPromptBuilder` 或专属 router/service

这样可以保证当前阶段只打通“模式入口 + 剧本分流”，不影响实时动画生产链。

## 文件改动清单

### 修改
- `backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java`
- `backend/com/comic/src/main/java/com/comic/constant/ProjectInfoKeys.java`
- `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java`
- `backend/com/comic/src/main/java/com/comic/service/script/ScriptService.java`
- `frontend/wiset_aivideo_generator/src/services/types/project.types.ts`
- `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`

### 新增
- `backend/com/comic/src/main/java/com/comic/ai/ComicCommentaryScriptPromptBuilder.java`

### 明确不改
- `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java`
- `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`
- `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`

## 兼容性策略

### 老项目兼容

老项目的 `projectInfo` 中没有 `productionMode` 时：
- 后端读取默认返回 `realtime_animation`
- 行为保持与当前系统一致

### 旧前端/旧请求兼容

若旧请求体未传 `productionMode`：
- `ProjectService.createProject()` 自动补默认值 `realtime_animation`
- 不影响项目创建

### 列表/详情接口兼容

如果前端需要显示该字段，可在 `ProjectInfoData` 和详情页读取后直接使用；如果暂时不展示，也不会影响已有列表与状态接口。

## 错误处理

- 若传入未知 `productionMode`，后端应在边界层拒绝或回退为默认值，避免进入非法分支
- 若 `comic_commentary` 模式配置了但对应 builder 未接入，`ScriptService` 应抛出明确业务错误，不应静默回退到实时动画 prompt
- 不允许在 panel/video 阶段因为模式字段存在而误入尚未实现的漫剧链路

## 测试策略

### 后端
- 创建项目：传 `productionMode`，确认写入 `projectInfo`
- 创建项目：不传 `productionMode`，确认默认写入/读取为 `realtime_animation`
- 更新项目：修改 `productionMode`，确认 `projectInfo` 更新
- 剧本生成：`realtime_animation` 走现有 builder
- 剧本生成：`comic_commentary` 走新 builder
- 老项目：无 `productionMode` 时仍可正常生成大纲/分集

### 前端
- Step1 默认模式是否正确
- 切换模式后请求体是否包含正确值
- 拉取项目详情时是否能读到 `productionMode`
- 不选择模式时是否仍能按默认模式创建

## 分阶段实施建议

### 第一阶段
- 打通 `productionMode` 数据流
- 在 `ScriptService` 完成 script builder 分流
- 保持 panel/video 生产链完全不动

### 第二阶段
- 根据漫剧解说实际需求决定是否新增 panel/video 分支
- 如需要，再新增独立 `ComicCommentaryPanelPromptBuilder` 或更外层 router

## 最终结论

采用“新增 `productionMode` 字段 + 在 `ScriptService` 首次分流 + 新增独立漫剧 script builder”的方案。该方案在最小改动下提供了稳定的模式入口，同时最大程度保护现有实时动画 prompt 结构和生产链不被污染，是当前代码结构下最稳妥的接入方式。
