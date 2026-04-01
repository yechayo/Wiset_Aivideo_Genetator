# Step5 全链路漏洞修复设计

**日期:** 2026-03-31
**状态:** Draft
**范围:** Step5 视频生产工作台前端 + 后端全链路

---

## 问题概述

用户反馈"切割后分镜对不上"，经代码审查发现 18 个漏洞（P0-P2），涉及九宫格切割、Panel 分组、视频拼接、前端轮询、类型定义等多个环节。

---

## 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 九宫格生成方式 | 保留九宫格（非逐格） | 用户选择；逐格生成成本高且风格一致性差 |
| 分割策略 | 固定分隔线补偿 | 可预测、可调试，不依赖 OpenCV |
| 分隔线宽度 | 可配置常量（默认 4px） | 不同 AI 模型可能生成不同宽度的分隔线 |

---

## T0 修复

### T0: 融合图尺寸不固定为 16:9，导致 AI 视频生成尺寸不一致

**问题:** `createFusionImage` 的 canvas 尺寸随 shots 数量和角色侧栏动态变化，不保证 16:9 比例。而 `PanelProductionService.doGenerateVideoByPanelId` 将融合图作为参考图传给视频生成 AI（`generateAsync(prompt, duration, "16:9", fusionImageUrl, offPeak)`），AI 会参考图片的宽高比来决定视频尺寸。

**实际尺寸计算：**
```
cellW=640, cellH=360, pad=8, headerH=60, fCols=3, sidebarW=360(有角色时)

3 shots (1 row), 无角色:  1952 x 436 ≈ 4.48:1  ← 远非 16:9
6 shots (2 rows), 无角色: 1952 x 796 ≈ 2.45:1  ← 不是 16:9
9 shots (3 rows), 无角色: 1952 x 1156 ≈ 1.69:1 ← 接近但不是
3 shots (1 row), 有角色:  2320 x 436 ≈ 5.32:1  ← 极端变形
```

**方案:** 固定融合图输出为 1920x1080（16:9）。将分镜子图和角色参考图缩放到固定画布内：

1. 固定 canvas 为 1920x1080
2. 去掉 header（标题行占 60px 且对 AI 无用）
3. 根据实际 shots 数量动态计算 cellW/cellH，使内容自适应填满 1920x1080
4. 角色参考图改为底部横排条（而非右侧栏），保持画面宽度不被挤压

**设计要点：**
- 1920x1080 是 AI 视频模型最标准的学习尺寸，可确保视频生成 16:9
- 每个 cell 的尺寸 = `(1920 - padding) / cols` 和 `(1080 - padding) / rows`
- 角色参考图放在底部一行，高度固定为 200px，剩余高度给分镜格子

**文件:** `GridImageService.java` - `createFusionImage` 方法

---

## P0 修复

### P0-1: 九宫格分割未考虑分隔线像素

**问题:** `splitGridImage` 直接 3x3 等分切割，但 AI 生成的九宫格包含黑色分隔线，导致每个切片底部/右侧带黑边。

**方案:** 引入 `GRID_SEPARATOR_PIXELS` 常量（默认 4px），在计算切割区域时扣除分隔线：

```
图片总宽 W, 总高 H, 3列3行, 分隔线宽度 S

cellW = (W - (cols - 1) * S) / cols
cellH = (H - (rows - 1) * S) / rows

第 (col, row) 格的坐标:
  x = col * (cellW + S)
  y = row * (cellH + S)
  w = cellW
  h = cellH
```

**文件:** `GridImageService.java` - `splitGridImage` 方法

### P0-2: `Math.floor` 导致像素累积偏差

**问题:** `floor(W/3)` 可能导致右/下侧格子丢失像素。

**方案:** P0-1 的新切割算法使用整除分配，最后列/行取剩余像素：

```
前 cols-1 列宽度 = floor((W - (cols-1)*S) / cols)
最后一列宽度 = W - (cols-1) * (cellW + S)
```

同理处理行。

**文件:** `GridImageService.java` - `splitGridImage` 方法

### P0-3: 前端 `canvasSplitter.ts` 同步更新

**问题:** 前端的 `splitGridToPanels` 函数也做等分切割，需要同步分隔线补偿逻辑。

**方案:** 接受 `separatorPixels` 参数，计算与后端一致的切割坐标。

**文件:** `canvasSplitter.ts` - `splitGridToPanels` 函数

---

## P1 修复

### P1-1: 视频拼接顺序无保证（经核查已部分解决）

**问题:** `ProjectController.mergePanelVideos` 中 Panel 顺序不确定。

**现状核查:** `PanelRepository.findByEpisodeId` 已有 `orderByAsc(Panel::getId)`，`EpisodeRepository.findByProjectId` 已有 `orderByAsc(Episode::getId)`。由于 Panel 在 `approveEpisodeGrid` 中按分组顺序依次 `insert`，自增 ID 顺序即为分镜顺序。

**残留风险:** 如果 Episode 的自增 ID 与 `episodeNum` 不一致（理论上不太可能），最终拼接顺序可能错。

**方案:** 保持现状，不做修改。风险极低，避免过度工程。

**文件:** 无需修改

### P1-2: FFmpeg concat `-c copy` 无法处理不同编码

**问题:** Vidu 返回的视频可能编码参数不完全一致，`-c copy` 会失败。

**方案:** 改用 `-c:v libx264 -c:a aac` 重新编码拼接，确保兼容。在 `composeWithoutSubtitle` 中用编码模式替代流复制。

**文件:** `VideoCompositionService.java` - `composeWithoutSubtitle` 方法

### P1-3: 融合图编号与视频 prompt 编号映射断裂（严重 — "分镜对不上"的根因之一）

**问题:** 经数据链路深度追踪，发现三处编号体系不一致：

| 位置 | 编号方式 | Panel2 的实际值 |
|------|---------|---------------|
| `createFusionImage` 画标签（第312行） | 循环索引 `i`（0-based→圈码） | ①②③ |
| `buildMultiShotPrompt` 镜头名（第98行） | `shot.get("shotNumber")` | 【分镜4】【分镜5】【分镜6】 |
| `buildMultiShotPrompt` 映射声明（第120行） | **硬编码** | ①②③对应【分镜1】【分镜2】【分镜3】（**错误！**） |

**数据链路分析：**
```
Phase 1: shots = [shot1, shot2, ..., shot12]  (shotNumber: 1-12)
Phase 2: 九宫格 → splitShots  (顺序不变，shotNumber 保留) ✓
Phase 3: greedyGroup →
  Panel1: [shot1, shot2, shot3]  shotNumber=1,2,3
  Panel2: [shot4, shot5, shot6]  shotNumber=4,5,6  ← 关键
Phase 4:
  Panel2 融合图: ①=shot4, ②=shot5, ③=shot6  (用索引 0,1,2)
  Panel2 prompt: 【分镜4】【分镜5】【分镜6】   (用 shotNumber)
  Panel2 映射声明: "①②③对应【分镜1】【分镜2】【分镜3】"  ← 错误！
```

**从第 2 个 Panel 开始，AI 被告知错误的编号映射关系，导致视频生成时分镜内容对不上。**

**方案:**
1. `createFusionImage` 中用循环索引（0,1,2→①②③）标注 — 这个是正确的（代表 group 内位置）
2. `buildMultiShotPrompt` 中镜头名改为用循环索引（【分镜1】【分镜2】【分镜3】代表 group 内第 1/2/3 个镜头），而非原始 shotNumber
3. 末尾映射声明动态生成：`①②③对应【分镜1】【分镜2】【分镜3】`（用 NumberFormatter）
4. 提取圈码工具方法共享给 `createFusionImage` 和 `buildMultiShotPrompt`

**关键设计决策:** 统一使用"Panel 内位置编号"（1,2,3...）替代"全局 shotNumber"。因为融合图只包含当前 Panel 的 shots，用全局编号没有意义。

**文件:** `PanelPromptBuilder.java:98,120`, `GridImageService.java:312-314`, 新建 `NumberFormatter.java`

### P1-3b: `greedyGroup` 浅拷贝注释误导

**问题:** 注释说"深拷贝"但 `new HashMap<>(shot)` 只是浅拷贝，嵌套对象（`characters`、`characterRefs`）共享引用。当前不会触发 bug，但有隐患。

**方案:** 修正注释为"浅拷贝"，不做深拷贝改造（YAGNI）。

**文件:** `StoryboardService.java:157`

### P1-4: `@Async` 并发导致状态覆盖

**问题:** `generateGridsForEpisode` 是 `@Async` 方法，传入的 `shots` 是引用。如果用户在执行期间重新生成，旧线程完成会覆盖新状态。

**方案:** 在 `generateGridsForEpisode` 写入数据库前，重新读取 `episodeInfo` 检查 `gridStatus`。如果已被其他操作重置为 `generating`（说明有更新的操作），则放弃写入。

**文件:** `GridImageService.java:176-182`

### P1-5: `shots` 与 `splitShots` 数据不同步

**问题:** `regenerateEpisodeGrid` 重置 `splitShots` 但保留原始 `shots`，两者可能不一致。

**方案:** 在 `regenerateEpisodeGrid` 中不依赖传入的 `shots`，而是从数据库最新 `episodeInfo` 中读取 `shots`。

**文件:** `EpisodeController.java:225-254`

### P1-6: `rejected` 状态可直接审核通过

**问题:** 用户可能误操作通过被拒绝的九宫格。

**方案:** 移除 `rejected` 状态的通过权限。被拒绝的九宫格必须先重新生成。

**文件:** `EpisodeController.java:140`

---

## P2 修复

### P2-1: 前端轮询无限循环无退出上限

**问题:** 多处 `while(true)` 轮询没有最大重试次数、没有 `AbortController`、组件卸载不清理。

**方案:**
1. 所有轮询添加最大重试次数（grid: 360次/30min, video: 720次/1h）
2. 使用 `useRef<AbortController>` 在组件卸载时中止轮询
3. 轮询退出时清理 `generatingGridPanelId` / `generatingVideoPanelId` 状态

**文件:** `Step5page.tsx` - `refreshEpisodeGridStatus`, `handleRegenerateGrid`, `handleRegenerateEpisodeGrid`, `handleGenerateVideo`

### P2-2: 接口声明重复

**问题:** `Step5pageProps` 被声明两次，第二个覆盖第一个，导致 `onNextStep` 类型检查丢失。

**方案:** 删除重复声明，合并为单一接口。

**文件:** `Step5page.tsx:30-37`

### P2-3: 融合图编号超过 ⑯ 显示纯数字

**问题:** 超过 16 个分镜时编号变成纯数字，与 prompt 中的圈码不一致。

**方案:** 提取 `circledNumbers` 为工具方法，支持 1-99 的圈码生成（使用 Unicode 圈码 ①-㊿ 范围）。

**文件:** `GridImageService.java:312-314`, `PanelPromptBuilder.java`（共享工具方法）

### P2-4: `@Transactional` 审核方法中融合图异常处理

**问题:** `approveEpisodeGrid` 中 `createFusionImage` 失败被静默吞掉，事务可能不一致。

**方案:** 融合图生成失败时记录到 `panelInfo.revisionFeedback`，但允许流程继续（当前行为合理）。添加 `log.error` 替代 `log.warn`。

**文件:** `EpisodeController.java:191-193`

### P2-5: `generateGridPrompt` 中 shotNumber 位置假设

**问题:** 使用 AI 生成的 `shotNumber` 作为面板编号，不保证连续。

**方案:** 在 `buildGridPrompt` 中使用循环索引（0-based）+1 作为面板编号，而非 `shot.get("shotNumber")`。

**文件:** `PanelPromptBuilder.java:68`

---

## 不在此次范围

- AI 生成质量优化（prompt 工程）—— 这是独立的大课题
- 逐格生成方案（用户选择保留九宫格）
- 视频拼接去掉前5帧的设计（`removeFirstFrames`）—— 这是有意为之的设计决策
- 前端 Step5page 组件架构重构

---

## 数据链路完整性验证

### shots → splitShots → panelInfo.shots 映射追踪

```
Phase 1: AI 生成 shots (StoryboardService)
  episodeInfo.shots = [{shotNumber:1, duration:3, ...}, {shotNumber:2, ...}, ..., {shotNumber:12, ...}]

Phase 2: 九宫格 → 切割 (GridImageService.generateGridsForEpisode)
  Page1 grid → split → subImages[0..8] 对应 shots[0..8]
  Page2 grid → split → subImages[0..2] 对应 shots[9..11]
  splitShots[i] = shallow_copy(shots[i]) + {splitImageUrl: ossUrl}
  顺序一致 ✓, shotNumber 保留 ✓

Phase 3: 审核通过 → 分组 (EpisodeController.approveEpisodeGrid)
  greedyGroup(splitShots, 16) →
    Panel1: splitShots[0..2]  (shotNumber 1,2,3)
    Panel2: splitShots[3..5]  (shotNumber 4,5,6)  ← 从这里开始编号错位
    Panel3: splitShots[6..8]  (shotNumber 7,8,9)
    Panel4: splitShots[9..11] (shotNumber 10,11,12)

Phase 4: 融合图 + 视频 prompt
  Panel2 createFusionImage: 标注 ①②③ (基于 group 内索引 0,1,2)
  Panel2 buildMultiShotPrompt: 【分镜4】【分镜5】【分镜6】(基于 shotNumber)
  Panel2 映射声明: "①②③对应【分镜1】【分镜2】【分镜3】"(硬编码) ← BUG!

  → AI 视频模型收到错误的映射关系 → 生成的视频与分镜内容不匹配
```

### 统一编号方案（修复后）

修复后，所有编号统一使用"Panel 内位置编号"：
- `createFusionImage`: ①②③ (group 内索引，不变)
- `buildMultiShotPrompt`: 【镜头1】【镜头2】【镜头3】(group 内索引)
- 映射声明: 动态生成 "①②③对应【镜头1】【镜头2】【镜头3】"

---

## 文件变更清单

| 文件 | 变更类型 | 涉及问题 |
|------|----------|----------|
| `GridImageService.java` | 修改 | T0, P0-1, P0-2, P1-4, P2-3 |
| `canvasSplitter.ts` | 修改 | P0-3 |
| `VideoCompositionService.java` | 修改 | P1-2 |
| `PanelPromptBuilder.java` | 修改 | **P1-3**, P2-5 |
| `NumberFormatter.java` | 新建 | P1-3, P2-3 |
| `GridImageServiceTest.java` | 修改 | T0, P0-1, P0-2 测试 |
| `EpisodeController.java` | 修改 | P1-5, P1-6, P2-4 |
| `StoryboardService.java` | 修改 | P1-3b (注释修正) |
| `Step5page.tsx` | 修改 | P2-1, P2-2 |

---

## 测试策略

- **T0**: 融合图输出尺寸验证 — 确保所有 Panel 的融合图都是 1920x1080
- `splitGridImage` 新算法的单元测试：验证分隔线补偿、边界像素不丢失
- `composeWithoutSubtitle` 改用编码模式的集成测试
- 前端轮询清理的 E2E 验证（手动测试组件卸载后轮询停止）
- **编号映射验证**: 模拟 Panel2（包含 shotNumber 4,5,6），验证 prompt 中编号为 ①②③→【镜头1】【镜头2】【镜头3】
- **视频尺寸一致性验证**: 生成多个 Panel 的视频，检查宽高比是否都是 16:9
