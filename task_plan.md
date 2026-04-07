# Task Plan: 漫剧提示词分支设计

## Goal
基于现有代码结构梳理「漫剧解说模式」如何以独立提示词分支接入，并形成不改动实时动画提示词结构的设计方案；按设计完成第一阶段实施。

## Current Phase
**实施 · 第二阶段已完成** — panel / video 提示词已按 `productionMode` 分流；后续仅为产品与提示词调优迭代。

## Phases（调研与设计）

### Phase 1: 代码结构梳理
- [x] 读取项目创建、项目信息、前端创建页与类型定义
- [x] 识别现有模式/风格字段与提示词入口
- [x] 记录关键发现到 findings.md
- **Status:** completed

### Phase 2: 生成链路定位
- [x] 读取剧本、分镜、面板、视频提示词相关服务
- [x] 找出现有实时动画提示词的边界与调用关系
- [x] 确认适合新增漫剧分支的插入点（首选 `ScriptService` + 独立 script builder）
- **Status:** completed

### Phase 3: 设计输出
- [x] 基于实际代码提出接入方案
- [x] 说明前后端最小改动点
- [x] 分阶段向用户汇报并确认
- **Status:** completed

### Phase 4: 文档沉淀
- [x] 写设计文档到 docs/superpowers/specs（`2026-04-04-comic-commentary-production-mode-design.md`）
- [ ] review loop 材料：按需补充 PR / 测试清单（自动化测试未写）
- **Status:** mostly_completed

## Phases（实施 — 对齐设计文档「分阶段实施建议」）

### 实施第一阶段：`productionMode` + 剧本分流
- [x] `projectInfo.productionMode`（`realtime_animation` | `comic_commentary`），创建默认、更新可改、非法值拒绝
- [x] `ComicCommentaryScriptPromptBuilder` + `ScriptService` 大纲/分集/改大纲路径按模式分流
- [x] 前端 Step1 制作模式选择与请求体字段
- [x] 不修改 `PanelPromptBuilder` / `PanelProductionService` / `GridImageService`
- **Status:** completed

### 实施第二阶段：panel / video 漫剧分支
- [x] 新增 `ComicCommentaryPanelPromptBuilder`（九宫格 + 多镜头视频 prompt）
- [x] `GridImageService` / `PanelProductionService` 按 `projectInfo.productionMode` 分流；`PanelPromptBuilder` 源文件未改
- [x] 抽取 `ProjectProductionMode`；`ScriptService` 与之对齐
- **Status:** completed

## Key Questions（已收敛）
| # | 问题 | 结论 |
|---|------|------|
| 1 | 哪个字段承载制作模式？ | `projectInfo.productionMode` |
| 2 | 实时动画提示词链位置？ | `ScriptPromptBuilder`、`PanelPromptBuilder`、`PanelProductionService` 等（设计文档已列） |
| 3 | 漫剧如何接入？ | 独立 builder；剧本层已接；panel/video 第二阶段再接 |

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 先读代码再收敛设计 | 用户明确要求先基于真实代码 |
| 保护现有实时动画提示词链 | 实时链路精调，禁止混写污染 |
| 新增 `productionMode` + 独立漫剧 script builder | 边界清晰、老项目缺省当 realtime |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Java 8 无 `String.isBlank()` | 编译失败 | 使用 `trim().isEmpty()` |

## Notes
- 自动化测试见设计文档「测试策略」；当前以手工/编译验证为主
- findings.md 与 progress.md 与本文件同步维护
