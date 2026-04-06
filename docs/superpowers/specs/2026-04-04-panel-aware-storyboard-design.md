# Panel-Aware 分镜生成重构

## 背景

当前分镜生成流程：
1. `generateStoryboard` 生成平铺 shots 列表
2. 九宫格生成 + 切割 → `splitShots`
3. `greedyGroup(splitShots, 10)` — 按 **10 秒贪心分组** → 每组创建一个 Panel
4. 每个 Panel 独立生成视频

Panel 边界由后端贪心算法决定，AI 完全不感知。导致三个连贯性问题：
1. **narration 文本不连贯**：解说词跳跃、重复、缺乏逻辑递进
2. **画面与解说脱节**：narration 和 visualDescription 匹配度不够
3. **Panel 间跳变**：跨 Panel 的画面/解说缺乏过渡

## 核心改动

一次 AI 调用生成整集分镜，AI **感知 Panel 边界**，按 Panel 分组输出。

### 约束规则

- 每个 Panel **总时长不超过 10 秒**（与当前贪心算法的 maxDuration 一致）
- 每个 Panel 包含 3-5 个 shots（每个 shot 2-4 秒）
- 整集 shots 总时长 = episodeDuration（如 60 秒）
- AI 明确知道 Panel 边界，在边界处设计过渡
- 仅适用于**漫剧解说模式**（comic_commentary），实时动画模式保持现有逻辑不变

### 与现有贪心分组的关系

- 新方案中 AI 自行分组，替代后端 `greedyGroup` 的职责
- 保留 `greedyGroup` 作为 fallback（兼容旧数据）
- 新方案中 AI 的分组结果会被存储，`EpisodeController` 中创建 Panel 时直接使用 AI 分组结果，不再调用 `greedyGroup`

## 输出 JSON 结构

### 当前结构

```json
[
  { "shotNumber": 1, "narration": "...", "visualDescription": "...", ... },
  { "shotNumber": 2, "narration": "...", "visualDescription": "...", ... }
]
```

### 新结构

```json
{
  "panels": [
    {
      "panelIndex": 1,
      "shots": [
        { "shotNumber": 1, "duration": 2, "narration": "...", "visualDescription": "...", ... },
        { "shotNumber": 2, "duration": 2, "narration": "...", "visualDescription": "...", ... }
      ]
    },
    {
      "panelIndex": 2,
      "shots": [...]
    }
  ]
}
```

## 修改文件清单

### 1. `DeepSeekTextService.java`

#### `buildStoryboardSystemPrompt` 改动

- 新增参数：`int targetPanelCount`（根据 episodeDuration / 10 向上取整）
- 告知 AI Panel 边界规则：每个 Panel 3-5 shots，总时长不超过 10 秒
- 输出格式改为嵌套 JSON（`{ "panels": [ { "panelIndex": 1, "shots": [...] }, ... ] }`）
- 连贯性规则（最高优先级）：
  - 整集的 narration 连起来是一篇完整旁白稿
  - Panel 边界处 narration 自然过渡（不要硬切）
  - 最后一个 Panel 的最后 shot 要有收束感
  - 第一个 Panel 的第一个 shot 要有开场引入感
- 仅在 `comicCommentary=true` 时启用 Panel-aware 生成，实时动画模式保持平铺 shots

#### `generateStoryboard` 改动

- 返回值改为 `List<List<Map<String, Object>>>`（按 Panel 分组的嵌套列表）
- 内部解析新的嵌套 JSON 格式
- 保留 `normalizeComicNarrationFields`（仅解说模式）
- 钳制时长逻辑保持不变（2-4 秒 per shot，总量不超过 episodeDuration）
- 新增重载方法：`generateSinglePanel(episodeContent, characters, visualStyle, comicCommentary, panelIndex, prevPanelLastNarration, nextPanelFirstNarration)` 用于退回单 Panel 重生成

### 2. `PanelProductionService.java`

#### `generateEpisodeScripts` 改动

- 接收 `List<List<Map<String, Object>>>` 分组结果
- 每个 Panel 的 shots 作为一组存入数据
- `episodeInfo.shots` 保持平铺（向后兼容），每个 shot 新增 `panelIndex` 字段
- 创建 Episode 时，shots 带有 `panelIndex`

#### `doGenerateVideoByPanelId` 改动

- 构建视频 prompt 时，提取前后 Panel 的 narration 上下文
- 传入 `buildMultiShotPrompt` 的新参数

### 3. `ComicCommentaryPanelPromptBuilder.java` / `PanelPromptBuilder.java`

#### `buildMultiShotPrompt` 改动（仅 ComicCommentaryPanelPromptBuilder）

- 新增参数：`String previousPanelLastNarration`（上一个 Panel 最后一个 shot 的 narration）
- 新增参数：`String nextPanelFirstNarration`（下一个 Panel 第一个 shot 的 narration）
- 在 prompt 中增加「叙事上下文」段落，让视频模型知道前后 Panel 的解说走向
- `PanelPromptBuilder`（实时动画）不变

### 4. `EpisodeController.java`

#### 创建 Panel 逻辑改动（仅解说模式）

- 当前：`PanelService.greedyGroup(splitShots, 10)` 分组
- 改为：如果项目是解说模式且 `splitShots` 中 shot 带有 `panelIndex`，直接按 `panelIndex` 分组
- 如果没有 `panelIndex` 或非解说模式，fallback 到 `greedyGroup`

## 不改的文件

- `GridImageService.java` — 九宫格生成逻辑不变
- `PanelService.java` — `greedyGroup` 保留作为 fallback，Panel CRUD 不变
- `PanelPromptBuilder.java` — 实时动画模式的 prompt 不变
- 前端 `Step4Production.tsx` — 数据展示逻辑不变

## 兼容性

- 旧数据的 shots 没有 `panelIndex` 字段，`EpisodeController` 中 fallback 到 `greedyGroup`
- 退回重生成时，如果旧数据没有 `panelIndex`，则按顺序推断

## 验证

1. 编译通过：`mvn compile`
2. 运行现有测试：`mvn test`
3. 手动验证：
   - 创建解说模式项目，生成到 4a 阶段，检查 shots 是否按 Panel 分组
   - 创建实时动画项目，确认行为与之前一致（平铺 shots，greedyGroup 分组）
   - 每个 Panel 总时长不超过 10 秒
   - 检查 narration 连贯性：整集读起来是否流畅
   - 退回某个 Panel 后重新生成，检查衔接是否自然
   - 4c 视频生成 prompt 中是否包含前后 Panel 的 narration 上下文
