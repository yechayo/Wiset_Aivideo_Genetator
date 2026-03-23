# Step5 剧集卡片重写 — 继续开发指南

> **目标读者**: 在另一台电脑上使用 Superpowers 插件恢复并继续此任务的开发者（即你自己）。
> **前置条件**: 安装了 Claude Code CLI + Superpowers 插件，项目已从 GitHub 克隆。

---

## 一、任务概述

**要做什么**: 重写 Step5 页面，从"分镜审查页"改为"剧集卡片工作台"。

**核心设计**:
- 一张卡片 = 一个剧集（Episode）
- 卡片内部完成全流程：分镜审查 → 场景生成 → 融合 → 视频生成
- 不再有页面级阶段切换，所有流程在卡片内部管理

**为什么重写**: 上一轮实现（sub-agent）犯了 3 个根本性架构错误：
1. 卡片粒度错了 — 把一张卡片做成一个分镜格子，应该是 = 一个剧集
2. 有页面级阶段切换 — 不应该存在，所有流程应在卡片内部
3. 数据源映射全错 — 用了 `storyboardData.panels` 而非 `PanelState[]`，sceneGridUrls 按页映射到按格

---

## 二、已完成的代码（当前分支上）

| 文件 | 说明 |
|------|------|
| `EpisodeController.java` | 新增 `regeneratePanelScene` 端点（后端保留，前端暂不用） |
| `VideoProductionTaskRepository.java` | 新增 `findByEpisodeIdAndPanelIndex` 查询方法 |
| `EpisodeProductionService.java` | 新增 `regeneratePanelScene` 方法 |
| `PanelStateResponse.java` | 新增 DTO（PanelState 响应） |
| `Step5page.tsx` | 上一轮错误实现（需要重写） |
| `Step6page.tsx` | 上一轮改动（保留） |
| `GridFusionEditor.tsx` | 新增 `mode="modal"` + `onClose` 支持 |
| `episodeService.ts` | 新增多个 API 函数 |
| `episode.types.ts` | 新增类型（部分需清理） |
| `Step5Card.tsx/.module.less` | 上一轮错误实现（需要删除） |
| `PanelVideoCard.tsx/.module.less` | 新增组件 |

---

## 三、实现计划位置

**计划文件**: `docs/superpowers/plans/2026-03-24-step5-episode-card-rewrite.md`

**设计规格**: `docs/superpowers/specs/2026-03-23-atomic-storyboard-design.md`

---

## 四、恢复任务的步骤

### 4.1 在 Claude Code 中打开项目

```bash
cd /path/to/Wiset_Aivideo_Genetator-main
claude
```

### 4.2 使用 Superpowers 插件执行计划

在 Claude Code 中输入：

```
/executing-plans docs/superpowers/plans/2026-03-24-step5-episode-card-rewrite.md
```

这会加载计划并按任务顺序执行。共 8 个 Task：

| Task | 内容 | 预计耗时 |
|------|------|---------|
| Task 1 | 清理旧实现（删除 Step5Card、清理无用类型） | 2 分钟 |
| Task 2 | 新增 `EpisodeCardData` 类型定义 | 2 分钟 |
| Task 3 | Step5page.tsx 主页面重写 + 样式 | 5 分钟 |
| Task 4 | EpisodeCard.tsx 剧集卡片组件 + 样式 | 5 分钟 |
| Task 5 | PanelCell.tsx 面板格子组件 + 样式 | 3 分钟 |
| Task 6 | 修复导入和类型导出 | 2 分钟 |
| Task 7 | 前端构建验证 | 2 分钟 |
| Task 8 | 后端编译验证 | 2 分钟 |

---

## 五、关键架构决策（已确认）

### 5.1 数据层级

```
项目 (Project) → 章节 (Chapter) → 剧集 (Episode) → 分镜 (Panel)
                                                    ↓
                                              九宫格 (Scene Grid Page)
                                                3×3 = 9 个格子的场景
```

### 5.2 卡片 = 剧集

- 一张卡片代表一个剧集
- 卡片内部有两种模式：
  - **审核模式**: 剧集 status 为 DRAFT/GENERATING → 展示分镜文字 + 确认/修订
  - **生产模式**: 剧集 status 为 DONE 且有 productionStatus → 展示面板网格

### 5.3 面板网格

- 每个面板格子独立管理：场景图 → 融合图 → 视频
- 场景图是按页生成的整张九宫格（一张图包含 9 个格子），展示在卡片顶部作为参考
- 融合图和视频是按格子独立的

### 5.4 关键 API

| API | 用途 |
|-----|------|
| `getEpisodes(projectId)` | 获取所有剧集列表 |
| `getPanelStates(episodeId)` | 获取单集所有面板状态 |
| `getProductionPipeline(projectId)` | 获取 sceneGridUrls |
| `generateSinglePanelVideo(episodeId, panelIndex)` | 单格生成视频 |
| `autoContinue(episodeId)` | 一键自动化 |

### 5.5 不需要新增后端 API

所有功能复用已有 API。

---

## 六、Maven 环境配置

后端编译需要手动指定 Maven 路径：

```bash
"C:/Users/HP/apache-maven-3.9.9/bin/mvn.cmd" compile -q -f backend/com/comic/pom.xml
```

环境变量（需在系统中配置）：
- `MAVEN_HOME = C:\Users\HP\apache-maven-3.9.9`
- `JAVA_HOME = C:\Program Files\BellSoft\LibericaJDK-8`

---

## 七、验收标准

- [ ] 分镜确认后仍在卡片内操作，不切换页面
- [ ] 每张卡片代表一个剧集
- [ ] 卡片内部展示面板网格（3x3）
- [ ] 每个面板独立显示融合状态和视频状态
- [ ] 场景网格图展示在卡片顶部作为参考
- [ ] 一键自动化按钮可用
- [ ] 前端构建通过
- [ ] 后端编译通过
