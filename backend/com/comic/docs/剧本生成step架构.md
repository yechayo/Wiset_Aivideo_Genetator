剧本大纲和剧本分集模块复刻指南
一、整体架构流程

┌─────────────────────────────────────────────────────────────────────┐
│                           用户输入层                                  │
│  核心创意 + 配置参数（类型、背景、风格、集数、时长）                    │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      剧本大纲节点 (SCRIPT_PLANNER)                     │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Prompt 构建：                                                 │ │
│  │  - 用户核心创意                                                 │ │
│  │  - 配置参数（主题/类型/背景/集数/时长/风格）                       │ │
│  │  - 动态计算（章节数/角色数/物品数）                               │ │
│  │  - 可选：剧目精炼参考信息                                        │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                    │                                 │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  AI Prompt 指令：SCRIPT_PLANNER_INSTRUCTION                    │ │
│  │  - 角色分级（核心/重要/其他）                                     │ │
│  │  - 物品分级（核心/辅助/世界）                                     │ │
│  │  - 章节结构（每章2-5集，100-150字）                              │ │
│  │  - 节奏规律（3-5集小高潮，10-15集大转折）                          │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                    │                                 │
│                                    ▼                                 │
│  输出：Markdown 格式的完整大纲                                       │
│  - 剧名、一句话梗概、类型、主题、背景                                  │
│  - 主要人物小传（分级描述）                                          │
│  - 关键物品设定（分级描述）                                          │
│  - 剧集结构规划（N章，每章概要）                                      │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      剧本分集节点 (SCRIPT_EPISODE)                     │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  前置条件：                                                     │ │
│  │  - 必须连接上游 SCRIPT_PLANNER 节点                              │ │
│  │  - 从大纲解析章节列表                                            │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                    │                                 │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  用户选择：                                                     │ │
│  │  - 选择章节（从大纲解析）                                        │ │
│  │  - 拆分集数（1-10集）                                           │ │
│  │  - 修改建议（可选）                                              │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                    │                                 │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Prompt 构建：                                                 │ │
│  │  - 剧本大纲全文                                                 │ │
│  │  - 目标章节                                                     │ │
│  │  - 全局角色设定（从大纲提取）                                    │ │
│  │  - 全局物品设定（从大纲提取）                                    │ │
│  │  - 前序剧集摘要（保持连贯性）                                     │ │
│  │  - 拆分集数、时长、风格                                          │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                    │                                 │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  AI Prompt 指令：SCRIPT_EPISODE_INSTRUCTION                    │ │
│  │  - 连贯性要求（角色/物品/剧情/场景）                              │ │
│  │  - 内容长度要求（每分钟200-250字）                               │ │
│  │  - 输出 JSON 格式                                               │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                    │                                 │
│                                    ▼                                 │
│  输出：JSON 数组                                                   │
│  [{ title, content, characters, keyItems, continuityNote }, ...]    │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        自动创建子节点                                  │
│  为每集自动创建 PROMPT_INPUT 节点，传递内容用于后续分镜/视频生成        │
└─────────────────────────────────────────────────────────────────────┘
二、Prompt 指令模板
1. 剧本大纲 Prompt (SCRIPT_PLANNER_INSTRUCTION)

const SCRIPT_PLANNER_INSTRUCTION = `
你是一位专精于短剧和微电影的专业编剧。
你的任务是根据用户的核心创意和约束条件，创建一个引人入胜的**中文剧本大纲**。

**核心原则：剧本大纲只在章节层面规划，不细化到每集**

## 📊 剧集规模要求

本剧为 **{TotalEpisodes} 集**，需要规划 **{ChapterCount} 个章节**，
每个章节包含 **{EpisodesPerChapter} 集**。

### 角色数量要求：{MinCharacters}-{MaxCharacters} 个角色

**角色分级与描述重点：**

**A. 核心角色（3-5人）- 需要详细小传**
- **主角团队**（2-3人）：故事的绝对核心
- **核心反派**（1-2人）：与主角对抗的主要力量
描述要求（每个角色80-120字）

**B. 重要配角（8-12人）- 简单描述**
- 导师/盟友/中立角色等
描述要求（每个角色20-40字）

**C. 其他角色（剩余数量）- 一笔带过**
- 群演、背景角色、一次性角色等
描述要求（每个角色5-10字）

### 物品数量要求：{MinItems}-{MaxItems} 个关键物品

**物品分级与描述：**

**A. 核心物品（3-5个）- 推动主线**
描述要求（每个物品30-50字）

**B. 辅助物品（5-8个）- 特定章节使用**
描述要求（每个物品15-25字）

**C. 世界物品（剩余数量）- 丰富设定**
描述要求（每个物品10-15字）

## 🎯 章节结构与节奏要求

### 核心原则：每章包含2-5集，描述这几集的整体故事

### 节奏规律（必须严格遵循）

1. **小高潮**：每3-5集设置一次小高潮
2. **大转折**：每10-15集设置一次大转折

## 📝 输出格式要求 (必须严格遵守 Markdown 格式)

# 剧名 (Title)
**一句话梗概**: [一句话总结故事核心]
**类型**: [类型] | **主题**: [主题] | **背景**: [故事背景] | **视觉风格**: [Visual Style]

----

## 主要人物小传

### 核心角色（详细小传，80-120字/人）
* **[姓名]**: [角色定位] - [年龄] [外貌特征]。性格：[性格特点]。背景：[重要经历]。

### 重要配角（简单描述，20-40字/人）
* **[姓名]**: [角色定位和作用，简短描述]

### 其他角色（一笔带过，5-10字/人）
* **[姓名]**: [身份或作用]

----

## 关键物品设定

### 核心物品（30-50字/个）
* **[物品名称]**: [物品描述、功能、象征意义]

### 辅助物品（15-25字/个）
* **[物品名称]**: [物品描述和出现时机]

### 世界物品（10-15字/个）
* **[物品名称]**: [简要描述]

----

## 剧集结构规划（共 {TotalEpisodes} 集，{ChapterCount} 章）

### 章节格式标准（每章100-150字）

#### 第X章：章节名称（第A-B集）

**涉及角色**：[本章主要角色，3-5人]

**关键物品**：[本章重要物品，2-3个]

**章节剧情**（100-150字）：
[这几集的整体故事描述，包含起承转合]

- [第A集]：[发生了什么]
- [第A+1集]：[情节推进]
- [第B集]：[本章高潮/转折]

**关键节点**：[标注：小高潮 或 大转折]
`;
2. 剧本分集 Prompt (SCRIPT_EPISODE_INSTRUCTION)

const SCRIPT_EPISODE_INSTRUCTION = `
你是一位专业的短剧分集编剧，擅长创作连贯、一致的系列剧集。
你的任务是根据提供的【剧本大纲】和【指定章节】，将该章节拆分为 N 个具体的剧集脚本。

**输入上下文：**
1. 剧本大纲 - 包含所有章节的概览
2. 目标章节 - 当前要拆分的章节
3. 前序剧集摘要 - 之前已生成的剧集摘要，用于保持连贯性
4. 全局角色设定 - 剧本大纲中定义的所有角色信息
5. 全局物品设定 - 剧本大纲中定义的所有关键物品信息
6. 拆分集数: [N]
7. 单集时长参考
8. 视觉风格: [STYLE]
9. 修改建议 - 用户针对之前生成版本的修改意见

**连贯性和一致性要求**

1. **角色一致性**: 严格遵循全局角色设定中的角色外貌、性格、说话方式
2. **物品命名一致性**: 严格使用全局物品设定中的标准名称
3. **剧情连贯性**: 参考前序剧集摘要，确保时间线、事件顺序合理衔接
4. **场景连贯性**: 场景描述应该符合既定的视觉风格

**输出要求：**
请直接输出一个 **JSON 数组**，格式如下：
[
  {
    "title": "第X集：[分集标题]",
    "content": "[详细剧本内容，包含场景描写、动作指令和对白]",
    "characters": "[本集涉及的角色列表]",
    "keyItems": "[本集出现的关键物品列表]",
    "visualStyleNote": "[针对本集的视觉风格备注]",
    "continuityNote": "[本集的连贯性说明]"
  }
]

**内容要求：**
1. **全中文写作**
2. **剧本内容长度要求**：每分钟时长需要 200-250字 的详细剧本内容
3. **内容结构要求**：
   - 场景描述：详细的环境描写、光影氛围、空间布局
   - 肢体动作：角色的身体姿势、动作细节、位置移动
   - 表情细节：眼神、微表情、情绪变化
   - 精彩对白：符合角色性格的对话
   - 情感描写：内心活动、情感转变
   `;
   三、动态参数计算逻辑

// 从 generateScriptPlanner 函数中的计算逻辑
function calculateScriptParameters(episodes: number) {
    const episodesPerChapter = 4; // 每章包含4集
    const chapterCount = Math.ceil(episodes / episodesPerChapter);
    
    const minCharacters = Math.round(10 + (episodes * 0.15));  // 100集 = 25人
    const maxCharacters = Math.round(minCharacters * 1.3);
    
    const minItems = Math.round(8 + (episodes * 0.1));  // 100集 = 18个
    const maxItems = Math.round(minItems * 1.25);
    
    return { chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems };
}

// Prompt 占位符替换
function buildPrompt(systemInstruction, config, episodes) {
    const params = calculateScriptParameters(config.episodes || 10);
    
    return systemInstruction
        .replace(/{TotalEpisodes}/g, String(config.episodes || 10))
        .replace(/{ChapterCount}/g, String(params.chapterCount))
        .replace(/{EpisodesPerChapter}/g, String(params.episodesPerChapter))
        .replace(/{MinCharacters}/g, String(params.minCharacters))
        .replace(/{MaxCharacters}/g, String(params.maxCharacters))
        .replace(/{MinItems}/g, String(params.minItems))
        .replace(/{MaxItems}/g, String(params.maxItems))
        .replace(/{Duration}/g, String(config.duration || 1));
}
四、章节解析逻辑

/**
 * 从剧本大纲中提取章节列表
 * 正则匹配 "#### 第X章：..." 格式
 */
 function extractChaptersFromOutline(outline: string): string[] {
    const chapterRegex = /####\s+第([一二三四五六七八九十百千万0-9]+)章[：:]\s*([^\n]+)/g;
    const chapters: string[] = [];
    let match;
    
    while ((match = chapterRegex.exec(outline)) !== null) {
        chapters.push(match[0]); // 完整的章节标题
    }
    
    return chapters;
 }

/**
 * 从大纲中提取角色信息
 */
 function extractCharactersFromOutline(outline: string): string {
    const characterSection = outline.match(/## 主要人物小传[^#]*/s);
    return characterSection ? characterSection[0].trim() : "未找到明确的角色定义";
 }

/**
 * 从大纲中提取物品信息
 */
 function extractItemsFromOutline(outline: string): string {
    const itemsSection = outline.match(/## 关键物品设定[^#]*/s);
    return itemsSection ? itemsSection[0].trim() : "未找到明确的物品定义";
 }
 五、数据结构定义

// 剧本大纲节点数据
interface ScriptPlannerData {
    scriptTheme?: string;           // 剧本主题
    scriptGenre?: string;           // 剧本类型
    scriptSetting?: string;         // 故事背景
    scriptVisualStyle?: 'REAL' | 'ANIME' | '3D';  // 视觉风格
    scriptEpisodes?: number;        // 总集数 (5-100)
    scriptDuration?: number;        // 单集时长 (1-5分钟)
    scriptOutline?: string;         // 生成的剧本大纲文本 (Markdown)
}

// 剧本分集节点数据
interface ScriptEpisodeData {
    selectedChapter?: string;       // 从大纲中选择要拆分的章节
    episodeSplitCount?: number;     // 拆分成几集 (1-10)
    episodeModificationSuggestion?: string;  // 用户修改建议
    generatedEpisodes?: Episode[];  // 生成的剧集列表
}

// 单集数据结构
interface Episode {
    title: string;                  // 如 "第1集：觉醒"
    content: string;                // 详细剧本内容
    characters: string;             // 涉及角色列表
    keyItems?: string;              // 关键物品列表
    visualStyleNote?: string;       // 视觉风格备注
    continuityNote?: string;        // 连贯性说明
}
六、核心调用流程

// 1. 生成剧本大纲
async function generateScriptPlanner(
    prompt: string,           // 用户核心创意
    config: {
        theme?: string,
        genre?: string,
        setting?: string,
        episodes?: number,
        duration?: number,
        visualStyle?: string
    },
    refinedInfo?: Record<string, string[]>,  // 可选：剧目精炼信息
    model?: string
): Promise<string> {
    // 构建 Prompt
    const fullPrompt = `
核心创意: ${prompt}
主题: ${config.theme || 'N/A'}
类型: ${config.genre || 'N/A'}
背景: ${config.setting || 'N/A'}
预估集数: ${config.episodes || 10}
单集时长: ${config.duration || 1} 分钟
视觉风格: ${config.visualStyle || 'N/A'}
`;

    // 替换占位符
    const systemInstruction = buildPrompt(SCRIPT_PLANNER_INSTRUCTION, config, config.episodes || 10);
    
    // 调用 AI
    const response = await llmProvider.generateContent(
        fullPrompt,
        model || defaultModel,
        { systemInstruction }
    );
    
    return response; // Markdown 格式的大纲
}

// 2. 生成剧本分集
async function generateScriptEpisodes(
    outline: string,              // 剧本大纲全文
    chapter: string,              // 目标章节
    splitCount: number,           // 拆分集数
    duration: number,             // 单集时长(分钟)
    style?: string,               // 视觉风格
    modificationSuggestion?: string,
    model?: string,
    previousEpisodes?: Episode[]  // 前序剧集（保持连贯性）
): Promise<Episode[]> {
    // 提取全局信息
    const globalCharacters = extractCharactersFromOutline(outline);
    const globalItems = extractItemsFromOutline(outline);
    
    // 构建前序摘要
    const previousSummary = previousEpisodes?.map((ep, idx) => `
第${idx + 1}集：${ep.title}
- 涉及角色：${ep.characters}
- 关键物品：${ep.keyItems || '无'}
- 剧情摘要：${ep.content.substring(0, 200)}...
    `).join('\n') || '无前序剧集';

    // 构建 Prompt
    const prompt = `
    剧本大纲全文：
    ${outline}

目标章节：${chapter}
拆分集数：${splitCount}
单集时长参考：${duration} 分钟
视觉风格：${style || 'N/A'}
${modificationSuggestion ? `\n修改建议：${modificationSuggestion}` : ''}

=== 全局角色设定 ===
${globalCharacters}

=== 全局物品设定 ===
${globalItems}

=== 前序剧集摘要（用于保持连贯性）===
${previousSummary}
`;

    // 调用 AI，返回 JSON
    const response = await llmProvider.generateContent(
        prompt,
        model || defaultModel,
        {
            systemInstruction: SCRIPT_EPISODE_INSTRUCTION,
            responseMimeType: 'application/json'
        }
    );
    
    return JSON.parse(response);
}
七、UI 配置选项

// 短剧类型选项
const SHORT_DRAMA_GENRES = [
    "都市爱情", "古代言情", "现代悬疑", "玄幻修真",
    "都市异能", "年代剧", "喜剧", "科幻",
    "战争", "武侠", "青春校园", "家庭伦理"
];

// 短剧背景选项
const SHORT_DRAMA_SETTINGS = [
    "现代都市", "古代宫廷", "仙侠世界", "未来科幻",
    "民国时期", "架空世界", "校园", "职场"
];

// 视觉风格选项
const VISUAL_STYLES = [
    { value: 'REAL', label: '真人' },
    { value: 'ANIME', label: '动漫' },
    { value: '3D', label: '3D' }
];
八、关键要点总结
两级架构：大纲负责宏观规划，分集负责细化执行
参数动态计算：根据集数自动计算章节数、角色数、物品数
连贯性保证：通过前序剧集摘要和全局设定确保一致性
输出格式区分：大纲用 Markdown，分集用 JSON
章节解析：用正则从大纲中提取章节列表供用户选择
可重入性：支持修改建议后重新生成