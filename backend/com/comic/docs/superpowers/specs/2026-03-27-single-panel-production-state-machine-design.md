# 单 Panel 串行生产状态机设计（确认版 v2）

日期：2026-03-27
范围：项目级状态流转 + 分镜后生产阶段（PRODUCING）

##1.目标

统一并明确「状态即流转」规则，解决：

1. 各阶段确认后是否自动进入下一步不一致
2. 分镜阶段与生产阶段边界不清晰
3. 前端不知道何时该轮询、何时该等待人工审核、何时可自动推进

已确认约束：

- 确认即自动推进
- 分镜后进入单 Panel 串行生产
- 严格串行（不并行跨 Panel）
- 任一步失败即暂停（等待人工 retry）
- `PRODUCING` 不拆项目级子状态
- `COMPLETED`以「全部 Panel 视频完成」为准，不等待拼接
- 拼接剪辑作为下一步独立流程

---

##2. 项目级状态（保持简洁）

项目级 `ProjectStatus` 保持单层，不拆 `PRODUCING_*`：

`DRAFT -> ... -> PANEL_GENERATING -> PANEL_REVIEW -> PRODUCING -> COMPLETED`

###2.1 确认即自动推进：事件链定义（原子语义）

确认动作不是“停在确认态”，而是触发一条后续链路。后端要求：**同一 API 调用内完成首跳持久化**，后续异步任务通过事件回调推进。

1) 剧本确认：

- API 动作：`confirm_script`
-事件链：
 - `SCRIPT_REVIEW --confirm_script--> SCRIPT_CONFIRMED`
 - `SCRIPT_CONFIRMED --start_character_extraction--> CHARACTER_EXTRACTING`
-触发器：`PipelineService.triggerNextStage(CHARACTER_EXTRACTING)` 自动调用 `characterExtractService.extractCharacters`

2)角色确认：

- API 动作：`confirm_characters`
-事件链：
 - `CHARACTER_REVIEW --confirm_characters--> CHARACTER_CONFIRMED`
 - `CHARACTER_CONFIRMED --start_image_generation--> IMAGE_GENERATING`
-触发器：`PipelineService.triggerNextStage(IMAGE_GENERATING)` 自动调用批量角色图生成

3) 图片确认：

- API 动作：`confirm_images`
-事件链：
 - `IMAGE_REVIEW --confirm_images--> ASSET_LOCKED`
 - `ASSET_LOCKED --start_panels--> PANEL_GENERATING`
-触发器：`PipelineService.triggerNextStage(PANEL_GENERATING)` 自动启动分镜生成

4) 分镜确认（全局完成）：

- API 动作：`confirm_panels`
-归属：由 `PipelineService`统一判定“是否全部分镜已确认可进入生产”
-事件链：
 - `PANEL_REVIEW --all_panels_confirmed--> PRODUCING`
 - `PipelineService` 在状态落库后同步调用 `PanelProductionService.startOrResume(projectId)` 启动首个 `currentPanel`
-进入 `PRODUCING` 后不再等二次“开始生产”按钮

###2.2 幂等与重复点击

- 同一确认动作重复提交，后端按“已在目标态/已触发”返回成功（幂等成功）
- 非法状态触发返回业务错误（不修改状态）

---

##3. PRODUCING 内部子流程（Panel级，严格串行）

`PRODUCING` 内部不新增项目级状态，使用生产字段表达细节。

###3.1 串行编排器（Orchestrator）

新增一个唯一编排责任（可由 `PanelProductionService` 承担），负责：

1.选择当前 Panel（全项目唯一）：按 `(episodeOrder, panelOrder)` 排序，取第一个未 `video=completed` 且未被跳过的 Panel
2.互斥：项目级生产锁（projectId维度），同一时刻仅允许一个 Panel处于执行中
3. 驱动步骤：每一步成功后自动触发下一步
4. 阻塞：遇到 `pending_review` 或 `failed` 停止推进，等待人工动作后恢复

###3.2 Panel 固定步骤

对当前 Panel 执行：

1. `background`（auto）
2. `comic`（auto）
3. `pending_review`（human approve 阻塞）
4. `video`（auto）

当前 Panel 视频成功后，`currentPanelIndex++`，进入下一个 Panel。

###3.3失败策略（已确认）

任一步失败：

- 当前 Panel 标记失败（含错误信息）
- 项目保持 `PRODUCING`
- 流程暂停，不自动跳过
- 人工 `retry` 成功后继续串行推进

---

##4. 完成判定与一致性

###4.1 完成触发者

由生产编排器（`PanelProductionService.startOrResume` 链路）在每次 Panel 视频完成后做全量判定：

- 若全部 Panel `videoStatus = completed`
-触发事件：`production_completed`
- 执行：`PRODUCING -> COMPLETED`

###4.2 原子性与一致性要求

- 同一事务内完成：当前 Panel 视频完成落库 + 全量完成判定 + 项目状态置 `COMPLETED`
- 项目级生产锁（projectId维度）下执行完成判定，避免并发双触发
- 项目 DB 状态必须落库为 `COMPLETED`
- `/api/projects/{projectId}/status` 返回值与 DB 一致
- 禁止仅在响应层“派生 COMPLETED但 DB仍是 PRODUCING”

说明：拼接剪辑不阻塞本次完成判定，作为后续独立流程。

---

##5. 前端同步模型

采用「SSE 通知 + 拉取快照」。

###5.1 项目级同步

1. 初始化：`GET /api/projects/{projectId}/status`
2.订阅：`GET /api/projects/{projectId}/status/stream`（SSE）
3. 每次 `status-change`：立即重拉 `/status`

###5.2 PRODUCING 阶段同步

- 项目级生产摘要（必需）：
 - `GET /api/projects/{projectId}/production/summary`
 - 返回：`currentEpisodeId/currentPanelId/currentPanelIndex/totalPanelCount/completedPanelCount/productionSubStage/blockedReason`
-细节列表：
 - `GET /api/projects/{projectId}/episodes/{episodeId}/panels/production-statuses`
- 单 Panel：
 - `GET /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/production-status`
- Job细粒度：
 - `GET /api/jobs/{jobId}/progress`（SSE）

###5.3 DTO兼容映射

|目标字段 |现有字段 | 来源 |备注 |
|---|---|---|---|
| `productionSubStage` | `productionSubStage` | `ProjectStatusResponse` |需在 PRODUCING 中稳定赋值 |
| `currentPanelIndex` | 无 | 新增 summary API | 项目级串行必须项 |
| `totalPanelCount` | 可由聚合计算 | 新增 summary API | 避免前端遍历所有 episode |
| `completedPanelCount` | 可由聚合计算 | 新增 summary API |进度显示 |
| `blockedReason` | 无 | 新增 summary API | `awaiting_comic_approval`/`panel_failed` |

前端最小渲染规则：

- `statusCode != PRODUCING`：按主步骤页
- `statusCode == PRODUCING`：切换串行生产看板
- `productionSubStage = pending_review`：仅显示审核操作
- `productionSubStage = failed`：显示 retry 操作

---

##6. 边界场景（必须定义）

1. `PRODUCING` 时无 Panel：
 - **固定规则：阻止进入 `PRODUCING` 并返回可恢复错误**（需先完成分镜生成）

2. `PRODUCING` 中项目回滚：
 -释放生产锁，取消后续自动推进，按既有回滚清理策略执行

3. 服务重启后恢复：
 -先抢占 project生产锁，再重建 `currentPanel` 与 `productionSubStage`
 - 若检测到同一 Panel 存在未决任务，按幂等恢复，不重复启动同一步

4. 重复 approve/retry/confirm：
 - 幂等成功，不重复触发同一步骤任务

5.生产中编辑/删除当前 Panel：
 - 阻止操作并提示“当前生产中不可变更”，或先中断后重排（建议阻止）

---

##7.兼容与迁移

1. 保持主枚举层级不变（最小联动）
2. 在确认 API 中补齐自动事件链
3. 在 `PRODUCING` 增加编排器与项目级 summary读模型
4. 保持现有失败重试语义

---

##8. 验收标准

1. `SCRIPT/CHARACTER/IMAGE/PANEL` 确认后自动进入下一阶段
2.进入 `PRODUCING` 后全项目仅一个当前 Panel
3. 四宫格未审核通过前绝不推进下一个 Panel
4. 任一步失败暂停，retry 成功后继续
5. 全部 Panel 视频完成即 `COMPLETED` 且 DB 状态一致
6. 前端仅靠 `/status + /production/summary + production-statuses` 可稳定渲染全过程