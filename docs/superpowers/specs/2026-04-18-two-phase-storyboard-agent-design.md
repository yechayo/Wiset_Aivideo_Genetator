# 两阶段分镜 Agent 设计

## 问题

当前 StoryboardAgentService 的批量生产架构有三个核心缺陷：

1. **剧情连贯性差**：executor 每次调用是无状态单次请求，只看最近 3 个 shot 的摘要，跨批次叙事断裂
2. **角色/场景一致性缺失**：LLM 不知道每个场景谁在场、角色当前状态是什么，经常混淆角色、凭空出现/消失角色
3. **上下文浪费**：把 LLM 当"任务分配器"用（reasoner 决定下一批生成什么），结构性工作本该代码做

## 设计目标

- **Agent 做初稿，Chat 做精修**：代码处理结构拆分，LLM 只做创意工作
- **角色一致性**：每个叙事段落明确标注谁在场、什么状态
- **场景一致性**：显式追踪场景变化，角色位置不能凭空跳跃
- **剧情可读性**：生成的分镜脚本，人能读出完整剧情
- **三种模式统一**：爽剧/普通/解说走同一套流程

## 架构

### 改动前

```
剧本 → [循环] Reasoner决定批次 → Executor批量生成 → 汇总
         ↑ 无状态，只看摘要        ↑ 只看最近3个shot
```

### 改动后

```
剧本 → Phase1: LLM结构分析（1次调用）→ 带角色状态的叙事节点列表
     → Phase2: 代码拆shot骨架（无LLM）→ 全部shot骨架（含角色/场景信息）
     → Phase3: 全局导演规划（1次调用，复用现有NarrativePlan）
     → Phase4: 接续式分段精修（3-5次调用）→ 带完整字段的shot数组
```

## Phase 1：LLM 结构分析

**职责**：把剧本拆成带角色状态的叙事节点（beat），不是 shot，是更大的叙事段落。

**输入**：剧本内容 + 目标时长 + 角色列表

**输出**（1 次 LLM 调用，reasoner 模型）：

```json
{
  "storyArc": "林晓星发现秘密 → 被追杀 → 反杀 → 真相揭露",
  "beats": [
    {
      "id": 1,
      "beat": "林晓星进入废弃工厂",
      "duration": 12,
      "mood": "紧张好奇",
      "characters": [
        { "name": "林晓星", "state": "警惕探索", "position": "工厂大厅" }
      ]
    },
    {
      "id": 2,
      "beat": "发现地下实验室，遇到陈墨",
      "duration": 10,
      "mood": "震惊",
      "characters": [
        { "name": "林晓星", "state": "震惊发现", "position": "地下实验室" },
        { "name": "陈墨", "state": "神秘出现", "position": "地下实验室" }
      ]
    }
  ],
  "transitions": [
    { "from": 1, "to": 2, "bridge": "推开铁门看到微光" },
    { "from": 2, "to": 3, "bridge": "远处传来脚步声" }
  ]
}
```

**关键约束**：
- 每个 beat 必须包含 characters 数组，标注谁在场、什么状态、在哪个位置
- 相邻 beat 间必须有 transition，描述场景如何过渡
- 所有 beat 的 duration 之和 = 目标时长 ± 10%
- beat 数量通常 10-20 个

**兜底**：LLM 解析失败时，代码按字符数均匀拆分剧本，角色列表从入参继承，无 transition。

**storyArc 传递**：Phase 1 的 `storyArc` 直接传递给 Phase 3 的 `buildNarrativePlan()`，Phase 3 不再让 LLM 重新概括弧线，而是复用 Phase 1 的结果。如果 Phase 1 失败走兜底，Phase 3 用空 storyArc，LLM 自行概括。

**transitions 约束**：transitions 数组长度 = beats.length - 1。如果 LLM 输出缺失某个 transition，代码自动填充默认值 "场景自然过渡"。

## Phase 2：代码拆 shot 骨架

**职责**：纯代码，把 beats 拆成 shot 骨架。不调 LLM。

**逻辑**：

```
对每个 beat:
  shotCount = max(1, round(beat.duration / 3.5))  // 平均 3.5s/shot
  每个 shot 骨架 = {
    shotNumber: 全局递增,
    duration: 按时长均分（clamp 1-4秒）,
    beatId: 所属 beat,
    sceneHint: beat.description,
    characters: 从 beat.characters 继承,
    narrativePhase: 根据累计时长匹配 NarrativePhase
  }
```

**骨架字段**（精修前）：

```java
public static class ShotSkeleton {
    public int shotNumber;
    public int duration;           // 1-4秒
    public int beatId;             // 所属 beat
    public String sceneHint;       // beat 描述
    public List<CharacterInScene> characters;  // 从 beat 继承
    public String narrativePhase;  // 所属叙事阶段
}

public static class CharacterInScene {
    public String name;
    public String state;    // 角色当前状态
    public String position; // 角色在场景中的位置
}
```

**特殊处理**：
- Phase 1 失败时，直接按目标时长 ÷ 3.5s 生成均匀骨架
- 每个 shot 自动标记所属 NarrativePhase（根据累计时长匹配 phase 的 allocatedSeconds）
- 跨 NarrativePhase 边界的 shot 归属到占比更大的阶段（累计时长在哪个阶段范围内就归哪个）
- beat.duration 过短（< 5s）时仍保证至少 1 个 shot，精修时 LLM 负责展开细节

## Phase 3：全局导演规划

**复用现有 NarrativePlan，不改动。**

1 次 reasoner 调用，输出 5 阶段规划：开场钩子 → 铺垫 → 冲突升级 → 高潮 → 收束。每阶段指定对话密度、情绪走向、节奏指导。失败时 fallback 到硬编码分配。

## Phase 4：接续式分段精修

**职责**：在骨架上精修，填充 dialogue、sceneDescription、composition 等完整字段。

**分段规则**：每段 12-18 个 shot 骨架。

**重试策略**：每段精修最多重试 2 次（复用现有 `MAX_EXECUTOR_RETRIES`）。重试仍失败时，将骨架转换为最小完整 shot（缺失字段填默认值：dialogue=""、speaker="无"、sceneDescription=sceneHint、shotSize="MEDIUM"、cameraAngle="eye_level"），确保下游不因缺字段报错。

**narrationPerspective**：解说模式下，`narrationPerspective` 参数传入精修 prompt，与现有逻辑一致（third_person/first_person）。

**每段输入**：

```
全局故事弧线: storyArc（来自 Phase 1）
叙事规划: 当前阶段的密度/情绪/节奏（来自 Phase 3）
前段接续: 前 3 个已精修 shot 的完整 JSON（不是摘要）
当前段骨架: 12-18 个 shot 的骨架 JSON（含角色状态）
transitions: 当前段涉及的 beat 间过渡
角色列表 + 风格
```

**精修 prompt 核心约束**：

```
【角色一致性 - 硬性约束】
1. 每个 shot 的 characters 必须使用骨架中的角色名，禁止换名或用泛称
2. 只有骨架中列出的角色才能出现，不能凭空增减角色
3. 角色状态必须连贯：上 shot 奔跑 → 本 shot 不能突然坐着喝茶
4. 角色位置变化必须有过渡镜头
5. 禁止使用"主角""反派""路人"等泛称

【场景一致性】
1. 相邻 shot 的场景不能凭空跳转，必须通过 transition 过渡
2. sceneDescription 必须与当前 beat 的 mood 和 position 匹配
3. 角色动作必须符合当前场景（在地下实验室不会"仰望星空"）

【对话约束】（复用现有）
- 1s≤5字, 2s≤8字, 3s≤15字, 4s≤20字
- 不是每个 shot 都需要 dialogue
- 连续 3 个 shot 不能都有 dialogue
```

**三种模式差异**（通过 prompt 区分）：

| 模式 | 额外约束 |
|------|----------|
| 爽剧 | hookPoint 字段必填，标注爽点类型（从骨架所属 beat 的爽点描述推导）；节奏极快 |
| 普通 | 标准 prompt |
| 解说 | 每个shot必须有 narration；narration 和 dialogue 互斥；narration 优先级更高 |

**前段接续保证连贯**：

```java
// 精修循环
List<Map<String, Object>> refinedShots = new ArrayList<>();
List<Map<String, Object>> lastRefinedForContext = new ArrayList<>();

for (每段 skeletonSegment) {
    String prompt = buildRefineUserPrompt(
        storyStructure, narrativePlan, lastRefinedForContext, skeletonSegment,
        characters, visualStyle, comicMode, scriptStyle
    );

    List<Map<String, Object>> segmentResult = refineSegment(prompt, comicMode, scriptStyle);
    refinedShots.addAll(segmentResult);

    // 保存最后 3 个精修 shot 用于下一段接续
    lastRefinedForContext = segmentResult.subList(
        Math.max(0, segmentResult.size() - 3), segmentResult.size()
    );
}
```

## 调用次数对比

| 场景 | 现有方案 | 新方案 |
|------|----------|--------|
| 120s 爽剧 | ~11 次（1 plan + 5 reasoner + 5 executor） | ~7 次（1 分析 + 1 plan + 5 精修） |
| 60s 普通 | ~4 次（1 plan + 2 reasoner + 1 executor） | ~4 次（1 分析 + 1 plan + 2 精修） |
| 60s 解说 | ~4 次 | ~4 次 |

## 文件改动

**只改一个文件**：`StoryboardAgentService.java`

### 新增方法

| 方法 | 职责 |
|------|------|
| `analyzeStoryStructure()` | Phase 1：调 reasoner 分析剧本结构 |
| `buildAnalysisSystemPrompt()` | Phase 1 system prompt |
| `buildAnalysisUserPrompt()` | Phase 1 user prompt |
| `parseStoryStructure()` | 解析 Phase 1 JSON |
| `buildShotSkeletons()` | Phase 2：纯代码拆骨架 |
| `buildRefineSystemPrompt()` | Phase 4 精修 system prompt |
| `buildRefineUserPrompt()` | Phase 4 精修 user prompt |
| `refineSegment()` | Phase 4：单段精修调用 |
| `generateV2()` | 新统一流程：Phase 1→2→3→4 |

### 删除方法

| 方法 | 原因 |
|------|------|
| `generateReAct()` | 替换为 `generateV2()` |
| `generateIterative()` | 替换为 `generateV2()` |
| `buildReactSystemPrompt()` | 精修 prompt 替代 |
| `buildReactUserPrompt()` | 精修 prompt 替代 |
| `buildReasonerSystemPrompt()` | 分析 prompt 替代 |
| `buildReasonerPrompt()` | 精修 prompt 替代 |
| `buildExecutorSystemPrompt()` | `buildRefineSystemPrompt()` 替代 |
| `buildExecutorPrompt()` | `buildRefineUserPrompt()` 替代 |
| `callExecutor()` | `refineSegment()` 替代 |

### 保留方法

| 方法 | 原因 |
|------|------|
| `buildNarrativePlan()` | Phase 3 直接复用 |
| `buildFallbackNarrativePlan()` | 兜底逻辑不变 |
| `parseNarrativePlan()` | 不变 |
| `NarrativePhase` / `NarrativePlan` | 数据类不变 |
| `parseShotArray()` / `cleanJson()` 等 JSON 工具 | 不变 |
| `checkBatchQuality()` | 改名 `checkSegmentQuality()` |
| `generate()` 主入口 | 签名不变，路由到 `generateV2()` |

### 新增数据类

```java
public static class StoryBeat {
    public int id;
    public String beat;
    public int duration;
    public String mood;
    public List<CharacterInScene> characters;
}

public static class StoryStructure {
    public String storyArc;
    public List<StoryBeat> beats;
    public List<Map<String, String>> transitions;  // {from, to, bridge}
}

public static class ShotSkeleton {
    public int shotNumber;
    public int duration;
    public int beatId;
    public String sceneHint;
    public List<CharacterInScene> characters;
    public String narrativePhase;
}

public static class CharacterInScene {
    public String name;
    public String state;
    public String position;
}
```

### 公共 API（不变）

```java
public List<Map<String, Object>> generate(String episodeContent, String characters,
                                           int targetDuration, String visualStyle,
                                           boolean comicMode, String narrationPerspective,
                                           String scriptStyle)
```

## 验收标准

1. **人可读测试**：生成的分镜脚本人能读出完整剧情，有开头有结尾有起伏
2. **角色一致性**：角色不会凭空出现/消失，名字不混淆，状态连贯
3. **场景一致性**：场景过渡有逻辑，角色位置不会跳跃
4. **对话密度**：≤60% 的 shot 有 dialogue
5. **对话长度**：3s 镜头 ≤15 字，4s 镜头 ≤20 字
6. **三种模式**：爽剧/普通/解说都能正常工作
7. **测试更新**：删除旧方法对应的测试用例，为新方法补充测试（`analyzeStoryStructure`、`buildShotSkeletons`、`refineSegment`、`generateV2`）

## 向后兼容

- 公共 API `generate()` 签名不变
- Phase 1 失败有 fallback（均匀拆分），不会阻塞
- Phase 3 已有 fallback（硬编码 5 阶段）
- Phase 4 精修失败会将骨架转换为最小完整 shot（缺失字段填默认值），下游不会因缺字段报错
- 质量检查是报告性质，不影响生成
