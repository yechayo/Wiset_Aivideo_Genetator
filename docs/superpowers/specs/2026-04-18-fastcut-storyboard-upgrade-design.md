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

**不改的文件：** PanelPromptBuilder.java, StoryboardAgentServiceTest.java（现有测试不受影响）

## 设计

### Part 1：骨架时长节奏变化

根据每个 shot 所属的 `NarrativePhase.name` 选择不同时长分配策略：

| 叙事阶段 | 时长模式 | 策略 |
|---------|---------|------|
| 开场钩子 | 2s 为主 | 80% 用 2s，20% 用 1s |
| 铺垫发展 | 2-3s 混合 | 交替 2s 和 3s |
| 冲突升级 | 1-2s 快切 | 60% 用 2s，40% 用 1s |
| 高潮爆发 | 1-2s + 偶尔 3-4s 升格 | 每第 4-5 个镜头插一个 3s 升格特写，其余 1-2s |
| 收束悬念 | 3-4s 长镜头 | 以 3s 为主，最后一个镜头可用 4s |

**实现方式：** 新增 `allocateDuration(int shotIndex, int shotCount, String phaseName)` 方法。骨架生成循环中，原来用 `avgDuration` 统一分配，改为调用此方法按 phase 分配。

**兜底：** phaseName 匹配不到任何已知阶段时，退回原来的均匀分配（`beat.duration / shotCount`）。

**时长总和校验：** 最后一个镜头的 duration 自动调整为剩余时长（`beat.duration - 已分配总和`），clamp 到 1-4s 范围。

### Part 2：精修 System Prompt 防翻车指南

在 `buildRefineSystemPrompt()` 的"对话约束"之后、"爽剧/解说模式"之前，插入通用的防翻车指南块：

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
2. 3-4s 镜头允许：缓慢推镜头、微平移
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

**另外改动：** prompt 末尾从 "保持 duration 不变" 改为 "duration 可在骨架基础上 ±1s 微调（范围 1-4s）"。

## 不做的事

- 不改 NarrativePhase 数据类
- 不改 Phase 1 分析 prompt
- 不改 PanelPromptBuilder（视频提示词组装）
- 不改 `buildRefineUserPrompt()`（精修 user prompt 结构不变）
- 爽剧/解说模式各自的特殊约束（hookPoint、narration）保持不变

## 验证

1. 运行 `StoryboardAgentServiceTest` — 现有 9 个单元测试不受影响
2. 运行 `StoryboardV2RealTest`（180s 爽剧模式）— 检查：
   - 镜头时长是否有变化（不再全是 3s）
   - sceneDescription 是否遵循单一变化原则
   - shotSize 是否偏向特写
   - transitionHint 是否写了"硬切"
   - cameraMovement 是否简化
