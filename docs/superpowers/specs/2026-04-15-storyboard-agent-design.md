# Storyboard Agent 分镜生成

## 背景

当前分镜生成存在时长不达标问题。用户设置每集 3 分钟（180 秒），但实际生成的分镜总时长往往只有 60-80 秒。

**根因**：
1. `DeepSeekTextService.generateStoryboard()` 一次性生成全部分镜，AI 无法可靠地生成足够数量的分镜
2. Prompt 中的时长约束是软约束（"尽量接近"），AI 可忽略
3. 后处理只截断超出的分镜，从不补充不足的分镜
4. 剧本生成的内容密度不足以支撑 3 分钟的分镜

**不能用简单修复方案**：
- 按比例拉长 shot duration → 剧情拖沓，叙事节奏被破坏
- 强化 prompt 硬约束 → 一次性生成太多分镜，AI 质量下降

## 核心方案

使用 **DeepSeek Reasoner 驱动的 ReAct 循环**，将分镜生成从"一次性全部生成"改为"按剧情节点分批生成"。

## 架构

```
StoryboardAgentService（新增）
  ├─ generate()              ← 主入口
  ├─ Reasoner（DeepSeek Reasoner，单上下文）
  │   └─ 每轮：分析进度 → 决策下一批覆盖什么 → 输出 action JSON
  └─ Executor（DeepSeek-chat，每轮独立）
      └─ 按 Reasoner 指令生成本批分镜
```

### 循环流程

每轮循环：
1. **Reasoner 思考**：输入当前状态（已有时长、已覆盖剧情节点、剩余内容），输出决策 JSON
2. **DeepSeek-chat 执行**：按 Reasoner 指定的剧情段落生成本批分镜（只看到当前段落 + 上一批末尾 3 个 shot）
3. **代码累加**：更新总时长、coveredBeats、lastShots
4. **判断继续/结束**：Reasoner 输出 `done` 或达到 maxRounds（15）

### Reasoner 决策类型

| action | 含义 | 触发条件 |
|--------|------|----------|
| `generate` | 继续覆盖下一个剧情节点 | 还有剧情未覆盖 |
| `expand` | 回头扩展已有节点（补充细节） | 剧情覆盖完但时长不足 |
| `pad` | 生成过渡/氛围镜头 | expand 后仍不足 |
| `done` | 结束 | 剧情已完整覆盖 |

### Reasoner 上下文 vs Executor 上下文

- **Reasoner**：单 agent 单上下文，保留完整推理链。每轮追加状态摘要 + 上轮决策
- **Executor**：每轮独立调用，只传入当前剧情段落 + 上一批末尾 3 个 shot + 角色信息

### 时长范围

- 目标时长：`episodeDuration`（如 180 秒）
- Prompt 告知 Reasoner 合理范围：目标的 2/3 ~ 4/3（如 120~240 秒）
- **不做任何后处理修正**（不截断、不拉长），时长完全靠 Reasoner 在规划阶段控制

### 覆盖范围

- 普通模式和漫剧解说模式都走 agent 循环
- 精修模式（lockedShots）仍走原有 `generateStoryboard()` 路径，不经过 agent

## 数据类

### ReasonerDecision

```java
public static class ReasonerDecision {
    String action;           // generate | expand | pad | done
    String nextBeatDescription;
    int estimatedSeconds;
    String reasoning;
    int targetBeatIndex;     // expand 时指定扩展哪个节点（-1 表示不指定）
}
```

### AgentState

```java
public static class AgentState {
    int targetDuration;
    int accumulatedDuration;
    List<String> coveredBeats;
    List<Map<String, Object>> allShots;
    List<Map<String, Object>> lastShots;  // 末尾 3 个，用于衔接
}
```

## 新增文件

| 文件 | 说明 |
|------|------|
| `service/production/StoryboardAgentService.java` | Agent 循环主体 |
| `test/.../StoryboardAgentServiceTest.java` | 22 个单元测试 |

## 改动文件

| 文件 | 改动 |
|------|------|
| `config/AiServiceConfiguration.java` | 新增 Reasoner Bean |
| `service/production/PanelProductionService.java` | `generateStoryboardForEpisode()` 中接入 agent |
| `application.yml` | 新增 `comic.deepseek-reasoner` 配置段 |

## 配置

```yaml
comic:
  deepseek-reasoner:
    api-key: ${DEEPSEEK_API_KEY:}      # 复用同一 key
    base-url: https://api.deepseek.com
    model: deepseek-reasoner
    max-tokens: 4096
```

## 异常处理

| 情况 | 处理 |
|------|------|
| Reasoner 调用失败 | break 循环，返回已有分镜 |
| Executor 调用失败 | 重试当前轮，最多 2 次 |
| 轮次用完（≥15） | 强制 break，返回已有分镜 |
| Executor 返回空/解析失败 | 跳过本批，继续下一轮 |

## 测试覆盖

22 个单元测试（已全部通过）：
- Reasoner 决策解析：generate/expand/pad/done/markdown/非法JSON/缺字段
- Prompt 构建：目标时长/进度追踪/衔接shot/漫剧模式
- 执行 Prompt：当前段落/衔接/不泄露全剧本
- 状态管理：时长累加/lastShots/globalShotNumber
- Agent 循环：正常完成/maxRounds/重试/漫剧模式/跨轮衔接
