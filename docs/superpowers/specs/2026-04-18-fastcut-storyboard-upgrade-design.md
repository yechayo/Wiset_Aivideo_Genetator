# 快切防翻车分镜升级设计

## Context

当前 StoryboardAgentService 的 V2 4-Phase 流程生成的分镜存在两个问题：

1. **时长单一**：骨架生成用 `beat.duration / shotCount` 取平均，导致所有镜头都是 3s，没有节奏变化
2. **运镜过复杂**：精修 prompt 没有限制运镜复杂度，LLM 会在 1-3s 镜头里写"推+环绕+拉远"等复合运镜，AI 视频生成必崩

用户提供了完整的"快切防翻车指南"，需要将这些规则编码到 agent 的骨架生成（代码层）和精修 prompt（LLM 层）中。

## 改动范围

**唯一修改文件：** `StoryboardAgentService.java`
- `buildShotSkeletons()` — 引入节奏变化的时长分配
- `buildFallbackSkeletons()` — 同上
- `buildRefineSystemPrompt()` — 加入防翻车指南 prompt
- `buildRefineUserPrompt()` — 末尾从 "保持 duration 不变" 改为允许 ±1s 微调

**需要更新预期值的测试：** `StoryboardAgentServiceTest.java`（`buildShotSkeletons` 相关测试的 duration 预期值会变）

## 设计

### Part 1：骨架时长节奏变化

根据每个 shot 所属的 `NarrativePhase.name` 选择不同时长分配策略：

| 叙事阶段 | 时长模式 | 策略 |
|---------|---------|------|
| 开场钩子 | 2s 为主 | 80% 用 2s，20% 用 1s |
| 铺垫发展 | 2-3s 混合 | 交替 2s 和 3s |
| 冲突升级 | 1-2s 快切 | 60% 用 2s，40% 用 1s |
| 高潮爆发 | 1-2s + 偶尔 3-5s 升格 | 每第 4-5 个镜头插一个 4s 升格特写，其余 1-2s |
| 收束悬念 | 3-5s 长镜头 | 以 3s 为主，最后一个镜头可用 4-5s |

**实现方式：** 新增 `allocateDuration(int phaseLocalIndex, int phaseLocalCount, String phaseName, int allocatedSeconds)` 方法。

- `phaseLocalIndex`：当前 shot 在**同一叙事阶段内**的索引（非全局索引）
- `phaseLocalCount`：当前叙事阶段内的总 shot 数
- `phaseName`：叙事阶段名称
- `allocatedSeconds`：已分配的总秒数（用于兜底）

骨架生成循环中，原来用 `avgDuration` 统一分配，改为调用此方法按 phase 分配。调用方需跟踪"当前 phase 内已分配的镜头数"。

**phase 名称匹配规则（contains 子串匹配）：**
```
开场钩子 → contains("开场") || contains("钩子")
铺垫发展 → contains("铺垫") || contains("发展")
冲突升级 → contains("冲突") || contains("升级")
高潮爆发 → contains("高潮") || contains("爆发")
收束悬念 → contains("收束") || contains("悬念")
```
兜底：phaseName 匹配不到任何关键词时，退回原来的均匀分配（`beat.duration / shotCount`）。

**Fallback 路径（`buildFallbackSkeletons`）：** 同样使用 `allocateDuration`，`phaseLocalIndex` 通过累加同一 `matchNarrativePhase` 返回值范围内的 shot 数计算。因为 fallback 没有 beat 边界（只有一个平铺的 shot 数组），所以用全局 shot 索引，但在 phase 切换时重置 `phaseLocalIndex`。

**确定性分配（不使用随机数）：** 基于 `phaseLocalIndex` 的取模分配：
- 开场钩子：`phaseLocalIndex % 5 == 0` 给 1s，其余 2s
- 铺垫发展：`phaseLocalIndex % 2 == 0` 给 2s，奇数给 3s
- 冲突升级：`phaseLocalIndex % 5 < 2` 给 1s，其余 2s
- 高潮爆发：`phaseLocalIndex % 5 == 4` 给 4s（升格），其余交替 1s/2s
- 收束悬念：全部 3s，最后一个镜头给 5s

**时长总和校验：** 每个 beat 的最后一个 shot，duration 自动调整为 `beat.duration - 已分配总和`，clamp 到 1-5s 范围。如果调整后仍与 `beat.duration` 有差距，则按比例缩放该 beat 内所有 shot 的 duration。

### Part 2：精修 System Prompt 防翻车指南

在 `buildRefineSystemPrompt()` 的"对话约束"块（第 397 行 `sb.append("\n")`）之后、爽剧/解说模式判断（第 399 行）之前，插入**所有模式通用**的防翻车指南块：

```
【快切防翻车指南 - 硬性约束】

一、动作设计：做减法
1. 一个镜头只做一个单一变化（位置、姿势、表情，三选一）
2. 禁止复合动作（如"拔刀+冲刺+劈砍"），必须拆成多个镜头
3. 切"结果"不切"过程"：写"男主背对镜头，刀已入鞘"而非"男主转身收刀"

二、镜头语言：贴脸
1. 快切镜头（1-2s）必须用特写或极特写，禁止全景
2. 用"局部的动"代替"全局的动"：脚踩碎地砖、刀刃划过鼻尖、手腕翻转
3. 动作结果用静止镜头表现："两人背对背站立，地面裂痕"

三、运镜约束
1. 1-2s 镜头只允许：固定镜头、极特写、主观视角(POV)
2. 3-5s 镜头允许：缓慢推镜头、微平移
3. 禁止复杂运镜（推+环绕+拉远）

四、提示词写法
1. 锁死起始状态："原本侧面朝左，猛然转头看向右"
2. 定格保底："挥拳瞬间画面定格，只有背景雨滴飞溅"
3. 分离前景背景："背景完全静止，只有前景角色在动"
4. 用"升格拍摄（高帧率）"代替"慢动作"

五、转场
1. transitionHint 必须写"硬切"
2. 禁止渐变、模糊、溶解过渡
```

**另外改动：**
1. `buildRefineSystemPrompt()` 第 421 行：`"保持骨架的 shotNumber 和 duration 不变"` → `"保持骨架的 shotNumber 不变，duration 可在骨架基础上 ±1s 微调（范围 1-5s）"`
2. `buildRefineUserPrompt()` 第 501 行：`"保持 shotNumber 和 duration 不变"` → `"保持 shotNumber 不变，duration 可在骨架基础上 ±1s 微调（范围 1-5s）"`

## 不做的事

- 不改 NarrativePhase 数据类
- 不改 Phase 1 分析 prompt
- 不改 PanelPromptBuilder（视频提示词组装）
- 不改 `buildRefineUserPrompt()` 的结构（仅改末尾一句话）
- 爽剧/解说模式各自的特殊约束（hookPoint、narration）保持不变

## 验证

1. 运行 `StoryboardAgentServiceTest` — 更新 `buildShotSkeletons` 相关测试的 duration 预期值
2. 运行 `StoryboardV2RealTest`（180s 爽剧模式）— 检查：
   - 镜头时长是否有变化（不再全是 3s）
   - sceneDescription 是否遵循单一变化原则
   - shotSize 是否偏向特写
   - transitionHint 是否写了"硬切"
   - cameraMovement 是否简化
