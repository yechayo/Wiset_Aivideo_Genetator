# Findings & Decisions

## Requirements
- 用户要“漫剧解说模式”分支设计
- 不要改现有实时动画架构与提示词结构
- 必须先基于真实代码阅读后再给设计
- 允许新增独立的漫剧提示词分支
- 倾向统一入口下按模式调度不同 builder/template

## Research Findings
- 项目根目录已有 backend/com/comic 与 frontend/wiset_aivideo_generator 两套主工程
- 现有 docs/superpowers/specs 与 plans 已覆盖 storyboard、step5、realtime-generation-progress 等主题
- 创建项目请求包含 `storyPrompt`、`genre`、`targetAudience`、`totalEpisodes`、`episodeDuration`、`visualStyle`、`imageProvider`、`videoProvider`、`videoModel`，**并已增加** `productionMode`（可选，服务端默认 `realtime_animation`）
- `ProjectService.createProject()` / `updateProject()` 将 `productionMode` 写入 `projectInfo.productionMode`
- 前端 Step1 含制作模式选择，类型 `CreateProjectRequest` / `ProjectInfoData` 含 `productionMode`

## Implementation Status（2026-04-04）
- **已完成：**剧本层：`ScriptService` + `ComicCommentaryScriptPromptBuilder`
- **已完成：**panel / video：`GridImageService` + `PanelProductionService` 按 `productionMode` 使用 `ComicCommentaryPanelPromptBuilder`；`PanelPromptBuilder` 类内模板未改
- **辅助：**`ProjectProductionMode` 统一判断漫剧解说模式

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 先沿“项目创建 -> prompt builder -> 生产服务”链路继续读代码 | 这是最可能落 production mode 与漫剧分支的真实路径 |
| 漫剧能力优先考虑新增字段 + 新 builder/template 文件 | 用户要求实时链路零侵入 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| 用户不希望继续抽象讨论架构 | 立即切换为代码阅读驱动，先找真实落点再汇报 |

## Resources
- `backend/com/comic/src/main/java/com/comic/dto/request/ProjectCreateRequest.java`
- `backend/com/comic/src/main/java/com/comic/service/project/ProjectService.java`
- `frontend/wiset_aivideo_generator/src/pages/create/steps/Step1Content.tsx`
- `frontend/wiset_aivideo_generator/src/services/types/project.types.ts`

## Visual/Browser Findings
- 本轮无需浏览器可视化；当前问题更适合代码阅读与文本分析
