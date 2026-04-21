# 分镜标签模板改造设计

## 概述

将分镜 `sceneDescription` 从自由文本改为结构化标签模板格式，使每个镜头的描述拆解为原子化的动作、环境、状态、表情等标签行。LLM 在 Phase 4 精修阶段直接输出标签模板文本，图片/视频提示词通过解析器按需提取标签。

## 1. Shot 输出字段

### 1.1 保留字段

| 字段 | 说明 | 来源 |
|------|------|------|
| `shotNumber` | 镜头编号 | 骨架 |
| `duration` | 时长 1-5s | 骨架 |
| `scene` | 场景名 | 模型输出，用于视频分组 |
| `characters` | 角色列表（含参考图等结构化数据） | 骨架带入 |
| `shotSize` | 景别 | 模型输出 |
| `cameraAngle` | 镜头角度 | 模型输出 |
| `cameraMovement` | 运镜 | 模型输出 |
| `sceneDescription` | **核心** — 标签模板文本 | 模型输出 |
| `dialogue` | 台词 | 模型输出，供 TTS 用 |
| `speaker` | 说话人 | 模型输出，供 TTS 用 |
| `audioEffects` | 音效 | 模型输出，传给视频模型 |
| `visualEffects` | 视觉效果 | 模型输出，传给图片/视频模型 |
| `transitionHint` | 镜头衔接 | 模型输出 |
| `hookPoint` | 爽点标注 | 模型输出，爽剧功能字段 |
| `narration` | 旁白 | 模型输出，解说功能字段 |

### 1.2 删除字段

| 字段 | 原因 |
|------|------|
| `visualDescription` | 与 sceneDescription 重复，标签模板已覆盖 |

### 1.3 字段职责划分

- **描述层**（sceneDescription 标签模板）：给图片/视频模型的视觉指令
- **参数层**（dialogue, speaker, audioEffects, hookPoint, narration 等）：给 TTS、分组、质量检查等系统逻辑用

## 2. 标签模板格式

### 2.1 sceneDescription 内容（LLM 生成）

LLM 直接输出以下标签行，不含（场景）和（出场）：

```
（走位）纪兰嫣｜位置锁=玉石台阶边｜姿态锁=坐着｜朝向锁=侧对广场中央｜道具锁=无
（走位）谢长音｜位置锁=广场中央席位｜姿态锁=端坐｜朝向锁=面向玉石台阶边｜道具锁=茶盏
（动作）谢长音端起茶盏
（动作）谢长音将茶盏凑近唇边
（环境）温热茶雾袅袅升起
（动作）谢长音隔着茶雾瞥向那抹红影
（状态）纪兰嫣坐在台阶上、姿势随意
（环境）金色阳光透过稀薄云层
（状态）红裙镀上一层金边、肩侧落着暖光
（动作）纪兰嫣歪了歪头
（表情）纪兰嫣轻轻蹙眉
（表情）纪兰嫣双眼放空
（动作）谢长音垂下眼帘
（表情）谢长音神色微微黯淡
（动作）谢长音抿下一口灵茶
内声：「天品水灵根，甚是少见。」
（音效）内声贴耳【AUX】
内声：「加之如此容颜，定会被各峰觊觎。」
（音效）心声微沉【AUX】
内声：「也不知玉露峰，能否留得住此人。」
（表情）谢长音眸色微黯
```

### 2.2 拼装层生成（视频提示词时组装）

```
（场景）{本组 shot 的 scene 字段去重拼接}
（出场）{本组 characters 提取角色名}
{shot1 的 sceneDescription}
{shot2 的 sceneDescription}
...
```

### 2.3 格式规则

| 规则 | 说明 |
|------|------|
| 每行一个标签 | 标签名用中文全角括号 |
| 同类标签可多次出现 | 每个（动作）是一个原子动作，不合并 |
| 简单句原则 | 每个标签内容只用一个简单句，不写复合句 |
| 角色名前缀 | （动作）/（状态）/（表情）内容以角色名开头 |
| 走位格式 | `（走位）{角色}｜位置锁={x}｜姿态锁={x}｜朝向锁={x}｜道具锁={x}` |
| 内声格式 | `内声：「{角色名}：{台词}」` |
| 旁白格式（解说模式） | `内声：（旁白）「{旁白内容}」` |
| 音效格式 | `（音效）{音效描述}【AUX】` |
| 内声+音效交错 | 内声后面可跟（音效）标注语气/效果 |
| 无内容标注"无" | 该类别无内容时填"无" |
| 标签顺序 | 走位 → [动作/环境/状态/表情自由排列] → [内声+音效交错] |

### 2.4 不由 LLM 生成的标签

| 标签 | 来源 | 原因 |
|------|------|------|
| （场景） | `scene` 字段去重拼接 | 字段已有，避免冗余 |
| （出场） | `characters` 的 name 拼接 | 字段已有，避免冗余 |

## 3. 图片提示词提取

从 sceneDescription 中提取可视标签，跳过非视觉标签：

| 标签 | 图片提示词 |
|------|-----------|
| （动作） | 保留 |
| （环境） | 保留 |
| （状态） | 保留 |
| （表情） | 保留 |
| （走位） | 跳过 |
| 内声 | 跳过 |
| （音效） | 跳过 |

（场景）从 shot 的 `scene` 字段取，简化为单句描述。

## 4. 模式标签策略

### 4.1 爽剧模式

> 标签策略：以（动作）和（表情）为主，每个镜头 1-3 个原子动作。内声密集（70%+ 镜头有台词）。环境描写尽量精简。1-2s 镜头只写 1 个（动作）+ 1 个（表情）。走位可简略。

### 4.2 解说模式

> 标签策略：以（环境）和（状态）为主，营造画面氛围。内声使用旁白格式 `内声：（旁白）「…」`，不用角色对话。走位可简略。每个镜头 2-3 个环境/状态标签。

### 4.3 标准模式

> 标签策略：动作、环境、状态、表情平衡使用，内声适度。

## 5. 模板解析器

将 `StoryboardLabelTemplateFormatter` 重写为解析/拼装工具类：

### 5.1 解析方法

```java
public final class StoryboardTemplateParser {

    // 提取可视标签文本（动作+环境+状态+表情）— 供图片提示词用
    public static String extractVisualLabels(String template);

    // 提取内声行列表 — 供 TTS / 质量检查用
    public static List<DialogueLine> extractDialogue(String template);

    // 提取音效列表
    public static List<String> extractAudioEffects(String template);

    // 提取走位信息
    public static List<BlockingInfo> extractBlocking(String template);

    // 判断是否为标签模板文本
    public static boolean isTemplateText(String text);
}
```

### 5.2 拼装方法

```java
public final class StoryboardTemplateAssembler {

    // 拼装完整视频提示词模板（场景+出场+各shot标签）
    public static String assembleVideoTemplate(
        List<String> scenes,
        List<String> characterNames,
        List<String> shotDescriptions
    );

    // 拼装图片提示词模板（简化场景+可视标签）
    public static String assembleImageTemplate(
        String scene,
        String visualLabels
    );
}
```

## 6. Phase 4 精修 Prompt 改造

### 6.1 System Prompt 改动

1. **输出字段更新**：列出新的字段列表，sceneDescription 格式要求用标签模板
2. **格式示例**：给出一完整 shot 输出示例（含 sceneDescription 标签模板）
3. **标签规则**：简单句原则、原子动作拆分、角色名前缀
4. **模式标签策略**：根据 scriptStyle/comicMode 插入对应的标签使用策略
5. **保留原有约束**：角色一致性、对话密度、快切防翻车（适配到新格式）

### 6.2 关键约束适配

- 内声字数仍受 duration 约束：1s≤5字, 2s≤8字, 3s≤15字, 4s≤20字
- 1-2s 镜头：只有 1-2 个（动作）标签 + 最多 1 个（表情）
- 3-5s 镜头：可适当增加标签数量
- 出场角色必须与骨架角色一致
- （场景）字段切换时必须有 transitionHint 过渡

## 7. 改动文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `StoryboardAgentService.java` | 修改 | Phase 4 system/user prompt 适配新模板格式 |
| `StoryboardLabelTemplateFormatter.java` | 重写 → `StoryboardTemplateParser.java` | 从模板生成器改为解析器 |
| 新增 `StoryboardTemplateAssembler.java` | 新增 | 视频/图片提示词模板拼装 |
| `PanelPromptBuilder.java` | 修改 | 图片提示词从解析器提取可视标签；视频提示词从拼装器获取完整模板 |
| `PanelProductionService.java` | 修改 | 相关字段读取适配 |
| 前端 `ShotDetail.tsx` | 修改 | 展示 sceneDescription 标签文本 |

## 8. 向后兼容

- 骨架阶段（Phase 1-3）不受影响，内部中间格式不变
- 已有的 storyStructure / narrativePlan / shotSkeletons 数据结构不变
- `isTemplateText()` 方法需同时识别旧格式和新格式，确保兼容已有数据
- `dialogue` / `speaker` 字段保留，TTS 等下游无需改动
