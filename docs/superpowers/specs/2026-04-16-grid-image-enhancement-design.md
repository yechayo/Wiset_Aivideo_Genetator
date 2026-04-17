# 4B 宫格图片生成增强设计

## 背景

当前 4B 阶段生成宫格图片时，存在图片逻辑问题：角色不一致、场景跳跃、风格不统一。主要原因是：
- 仅支持 2×2 和 3×3 两种宫格规格
- 分辨率固定 1920×1080，大宫格下每格像素不足
- Prompt 约束不够强，AI 模型对风格一致性和内容边界遵循度差

## 方案选择：Prompt 工程增强

经过对比三种方案（Prompt 增强 / 分步生成拼图 / 分区域生成），选择 **Prompt 工程增强**：
- 改动最小，风险可控
- 兼容现有流程
- 通过精细化 prompt + 4K 分辨率提升即可显著改善

## 1. 宫格规格与分页逻辑

### `calculateGridSize` 新规则

| 分镜数量 | 宫格规格 | 格数 |
|---------|---------|------|
| 1-4 | 2×2 | 4 |
| 5-9 | 3×3 | 9 |
| 10-16 | 4×4 | 16 |
| 17-25 | 5×5 | 25 |

### 分页策略

- 总分镜 ≤25：单页，规格自匹配
- 总分镜 >25：第一页 5×5（25格），剩余递归匹配
- 例：30 个分镜 → 第一页 5×5(25个) + 第二页 3×3(9格，用5个填黑)

### 分辨率

- 生成分辨率：**3840×2160**（所有规格统一 4K）
  > 注：2160 不是 64 的整数倍，Seedream API 可能需要 64 对齐。实现时需检查
  > `SeedreamImageService.getSizeString()` 是否会对 2160 做调整。
  > 备选方案：如被拒绝则使用 **3840×2176**（34×64，仍在 maxPixels 范围内）。
- 分隔线：统一 **8px** 黑线
- 切分后每格有效像素（公式：`(imgDim - (gridDim-1) * 8) / gridDim`，向下取整）：

| 规格 | 每格像素 | 扣除分隔线后 |
|------|---------|------------|
| 2×2 | 1920×1080 | 1916×1076 |
| 3×3 | 1280×720 | 1274×714 |
| 4×4 | 960×540 | 954×534 |
| 5×5 | 768×432 | 761×425 |

## 2. Prompt 结构增强

`PanelPromptBuilder.buildGridPrompt` 重新设计为 4 层约束 + 大宫格专属约束。

### 第 1 层：全局风格锁

```
整张图必须严格保持统一的【{视觉风格}】风格，色调、光影、线条粗细、
色彩饱和度在所有格子中必须完全一致，禁止任何格子偏离此风格。
```

放在 prompt 最前面，最高优先级。

### 第 2 层：叙事上下文（新增）

```
本页讲述的是：{整页剧情摘要，1-2句话}
故事发生在：{统一场景描述}
```

从 shots 列表中自动提取，让 AI 先理解整体故事再画细节。

### 第 3 层：角色锚定（增强现有）

```
角色统一规范（适用于本页所有格子）：
- {角色名}：{外貌描述}，{服装描述}，{显著特征}
- ...
严格遵循以上角色设定，所有格子中同一角色必须完全一致。
```

### 第 4 层：逐格指令（增强现有）

```
严格输出一张 {cols}×{rows} 宫格图，用{cols-1}条黑色竖线(8px)和
{rows-1}条黑色横线(8px)均匀分隔。
第1行第1列: {shot描述} [与前一场景连续/新场景开场]
第1行第2列: {shot描述} [镜头{拉近/平移/切换}]
...
```

空格填："纯黑色填充"。

**新增过渡标签**：根据相邻 shot 的场景/镜头关系自动生成：
- `[与前一场景连续]` — 同一场景的连续动作
- `[镜头拉近]` / `[镜头拉远]` — 景别变化
- `[场景切换至：{新场景}]` — 明确的转场

### 第 5 层：大宫格约束（5×5 时启用）

```
警告：本图包含25个格子，必须严格遵守以下规则：
- 每个格子的内容必须严格限制在其边界内，禁止内容溢出到相邻格子
- 相邻格子之间的分隔线必须清晰可见
- 最后5列格子容易被忽略，请确保第4列和第5列都有内容
```

### 视觉风格前缀

保留现有 "8K超高清分辨率" 前缀不变。分辨率通过 `ImageGenerationService` 的参数（3840×2160）传递给图片模型，不在 prompt 中重复指定。

## 3. 图片切分策略

### 新切分规则

```
cellWidth  = (imageWidth  - (cols - 1) * SEPARATOR) / cols
cellHeight = (imageHeight - (rows - 1) * SEPARATOR) / rows

每格起始坐标：
  x = col * (cellWidth + SEPARATOR)
  y = row * (cellHeight + SEPARATOR)
```

- 分隔线常量：`GRID_SEPARATOR_PIXELS = 8`
- 切分后每格独立保存，不继承 EXIF
- 文件名格式：`grid_{cols}x{rows}_cell_{index}.png`

### 容错处理

- **切分边界保护**：最后一行/列取整时向内收缩，避免越界（差异 ≤1px）
- **宫格验证**：切分后校验每格尺寸与预期差异 ≤2px，偏差过大记录告警日志但不中断

### Fusion 图调整

- 分辨率保持 **1920×1080**（给视频生成用，不需要 4K）
- 主区域布局动态适配实际 shot 数量（不再强制 3×3）：
  - `fCols` 从硬编码 3 改为根据 shot 数量计算（≤4 用 2，≤9 用 3，≤16 用 4，≤25 用 5）
  - `fRows = ceil(shots.size() / fCols)`
- 底部角色参考栏保持 180px 不变

## 4. 代码变更范围

### GridImageService.java

| 项目 | 现在 | 改后 |
|------|------|------|
| `GRID_COLS/ROWS` | 硬编码 3,3 | 删除 |
| `calculateGridSize()` | 2×2, 3×3 | 扩展至 4×4, 5×5 |
| 生成分辨率 | 1920×1080 | 3840×2160 |
| `GRID_SEPARATOR_PIXELS` | 4 | 8 |
| `SHOTS_PER_PAGE` | 9 | 删除，动态计算 |

### PanelPromptBuilder.java

- `buildGridPrompt` 按 4 层结构重写
- 新增 `buildNarrativeContext(shots)` — 提取整页剧情摘要
- 新增 `buildTransitionTag(prevShot, currentShot)` — 生成过渡标签
- 新增 `buildLargeGridWarning()` — 5×5 专属约束
- 默认重载从 `3,3` 改为根据 shots 数量自动计算
- prompt 中的分隔线描述从 "约 4px 宽" 改为 "约 8px 宽"
- `buildSceneStylePrefix()` 保持 "8K超高清分辨率" 不变，分辨率通过参数传给图片模型

### GridEpisodeCard.tsx

- `getGridSize()` 扩展支持 4×4、5×5，逻辑与后端 `calculateGridSize()` 完全一致：
  ```typescript
  function getGridSize(shotCount: number): { cols: number; rows: number } {
    if (shotCount <= 4) return { cols: 2, rows: 2 };
    if (shotCount <= 9) return { cols: 3, rows: 3 };
    if (shotCount <= 16) return { cols: 4, rows: 4 };
    return { cols: 5, rows: 5 };
  }
  ```
- `buildAdaptivePages()` 适配新分页规则（≤25 单页，>25 多页）
- 未手动编辑时自动生成新格式 prompt

### ComicCommentaryPanelPromptBuilder.java

- 同步适配新 prompt 结构，逻辑独立
- prompt 中的分隔线描述从 "约 4px 宽" 改为 "约 8px 宽"
- 同步添加叙事上下文层和过渡标签

### GridImageService.java — `createFusionImage`

- `fCols` 从硬编码 3 改为根据 shot 数量动态计算（与 `calculateGridSize` 逻辑一致）

### 不变的部分

- 无新增 API 接口
- 无数据库 schema 变更
- `GridConfig` 结构不变（`gridCols/gridRows` 自然支持 4 和 5）
- `ImageGenerationService` 调用方式不变

## 5. 风险与缓解

### 5×5 大宫格 fallback

如果 5×5 宫格生成质量不达标（AI 模型无法可靠地画 25 个独立分镜），fallback 策略：
- 自动拆分为 3×3 + 3×3 + 3×3 = 27 格（两页或三页），而不是一页 5×5
- 此 fallback 可在实现后根据实际效果决定是否启用

### Prompt token 预算

5×5 宫格的 prompt 较长（25 个 shot 描述 + 4 层约束），实现时需：
- 估算总 prompt 长度，确认在 Seedream 模型的 token 限制内
- 如超限，对每个 shot 描述做精简（保留关键动作和场景，去掉冗余修饰）
- 叙事上下文限制在 2 句话以内

### 图片服务分辨率兼容

分辨率 3840×2160 通过参数传给图片生成服务，需要确认各服务的兼容性：

- **Seedream**：`getSizeString()` 会检查像素范围（3,686,400 ~ 10,404,496），3840×2160=8,294,400 在范围内。
  但 2160 不是 64 的倍数，API 可能拒绝或静默调整。如出现问题，备选 **3840×2176**（34×64）。
- **Nanobanana**：通过 `computeAspectRatio()` 计算 16:9 宽高比传入，分辨率由其 API 内部决定。
  需确认其 API 是否支持 4K 级别输出。
- 其他潜在图片服务：统一通过 `width/height` 参数传入，各服务自行处理分辨率适配。
