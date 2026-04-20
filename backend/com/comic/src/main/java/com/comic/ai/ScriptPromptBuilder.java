package com.comic.ai;

import com.comic.constant.ProjectInfoKeys;
import org.springframework.stereotype.Component;

/**
 * 剧本 prompt 构建器
 * 负责大纲生成和剧集拆分的提示词
 */
@Component
public class ScriptPromptBuilder {

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience,
                                                  int chapterCount, int episodesPerChapter, int episodeDuration,
                                                  String scriptStyle) {
        ScriptParams params = calculateScriptParameters(totalEpisodes);
        if (params.isSingleEpisode) {
            return buildSingleEpisodePrompt(genre, params, episodeDuration, scriptStyle);
        }

        String base = "你是一名专业的漫画剧本编剧。\n"
                + "【集数硬约束（最高优先级，必须严格遵守）】\n"
                + "用户设定的总集数为 " + totalEpisodes + " 集。\n\n"
                + "请根据用户提供的信息生成结构化的剧本大纲。\n"
                + "题材类型：" + genre + "\n"
                + "目标受众：" + targetAudience + "\n"
                + "总集数：" + totalEpisodes + " 集\n"
                + "章节数：" + chapterCount + "（每个章节包含 " + episodesPerChapter + " 集）\n"
                + "每集目标时长：" + episodeDuration + " 秒\n\n"
                + "输出格式：仅返回 JSON，不要 markdown 代码块标记。\n"
                + "JSON 结构：\n"
                + "{\n"
                + "  \"outline\": \"Markdown 格式的完整大纲文本\"\n"
                + "}\n\n"
                + "要求：\n"
                + "1. outline 包含完整的世界观、角色小传、关键物品设定、章节剧情线\n"
                + "2. outline 中的章节剧情线必须使用「### 第X章」格式（如 ### 第一章、### 第二章），每章描述该章包含 " + episodesPerChapter + " 集的剧情走向\n"
                + "3. 章节标题中必须包含对应集数范围，格式如「### 第一章: 标题（第1-2集）」\n"
                + "4. 每集需列出标题并用 300-500 字详细描述核心剧情，包括：具体场景、角色互动、对话方向、情节推进细节\n"
                + "5. 每集描述 3-6 个关键场景和转折点，明确角色在每个场景中的行为动机和冲突点\n"
                + "6. 章节剧情线要体现节奏变化：标注每集中哪些部分是高潮（需展开），哪些是过渡（需简洁）";

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base += "\n\n【爽剧剧情密度约束（爽剧模式生效）】\n"
                    + "- 核心原则：剧情节奏快 = 剧情量大、事件密集推进，而非画面节奏快\n"
                    + "- 每集概括字数增加到 400-600 字，必须包含密集的剧情事件和角色互动\n"
                    + "- 每集必须规划 4-6 个关键剧情转折（冲突爆发、真相揭露、身份反转、实力碾压、关系变化等）\n"
                    + "- 不要标注「爽点」「钩子」等元信息，直接写具体剧情内容\n"
                    + "- 节奏要求：\n"
                    + "  - 开篇直接进入冲突或事件，不铺垫背景\n"
                    + "  - 中间持续推进新事件和新冲突，不允许大段平铺叙述\n"
                    + "  - 每集结尾以悬念或关键转折收束，驱动观众看下一集\n"
                    + "- 剧情推进原则：事件密度优先，每个场景都要推进剧情或提供新的信息量\n"
                    + "- 禁止冗长铺垫、禁止重复已知信息、禁止空洞概括";
        }

        return base;
    }

    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                               int totalEpisodes, int episodeDuration, String visualStyle,
                                               String scriptStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("核心创意：").append(storyPrompt).append("\n");
        sb.append("题材类型：").append(genre != null ? genre : "未指定").append("\n");
        sb.append("背景设定：").append(setting != null ? setting : "未指定").append("\n");
        sb.append("总集数：").append(totalEpisodes).append(" 集\n");
        sb.append("每集时长：").append(episodeDuration).append(" 秒\n");
        sb.append("视觉风格：").append(visualStyle != null ? visualStyle : "未指定");

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            sb.append("\n剧本风格：爽剧（剧情密度高、事件密集推进、节奏极快）");
        }

        return sb.toString();
    }

    public String buildScriptEpisodeSystemPrompt(String scriptStyle) {
        String base = "你是一名专业的剧集编剧。\n"
                + "根据全局大纲和其中一个章节，将其拆分为具体的剧集（按「集」输出）。\n"
                + "【集数硬约束】用户消息中的「拆分集数」即本章节必须生成的集数：你输出的 JSON 数组长度必须恰好等于该数字，"
                + "一集对应数组中的一个对象；禁止合并多集、禁止少生成、禁止多生成。\n";

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base += "【内容量与时长匹配（爽剧模式 - 最高优先级）】\n"
                    + "- 每秒需要约 8-12 个字的剧情描述\n"
                    + "- 剧情事件数量是硬性要求，必须达标：\n"
                    + "  60秒 → content 约 480-720 字，必须包含至少 12 个 [爽点:XX] 标记\n"
                    + "  90秒 → content 约 720-1080 字，必须包含至少 18 个 [爽点:XX] 标记\n"
                    + "  120秒 → content 约 960-1440 字，必须包含至少 24 个 [爽点:XX] 标记\n"
                    + "  180秒 → content 约 1440-2160 字，必须包含至少 36 个 [爽点:XX] 标记\n"
                    + "  300秒 → content 约 2400-3600 字，必须包含至少 60 个 [爽点:XX] 标记\n"
                    + "- 爽点标记格式严格为 [爽点:描述]，不得省略方括号，不得用其他格式代替\n"
                    + "- 如果字数或爽点数量不足，你的输出将被退回重做\n"
                    + "【大纲与剧本的关系（关键）】\n"
                    + "- 大纲中每集有剧情概要，你必须在保持情节一致的前提下，展开为详细的场景级剧情描述\n"
                    + "- 剧情节奏快 = 剧情事件密集推进，不是画面切换快\n"
                    + "- 每个场景都要有明确的剧情目的：推进冲突、揭露信息、转变关系、展示实力\n"
                    + "- 禁止照搬大纲原文，必须补充场景细节和角色状态变化\n"
                    + "【爽剧剧情描述格式】\n"
                    + "- content 字段写剧情描述，以叙事体描述故事发展，不写具体台词和镜头语言\n"
                    + "- 描述内容：每个场景发生了什么、角色如何互动、情绪如何变化、情节如何推进\n"
                    + "- 不要写具体台词、不要写分镜头描述、不要写动作特写，这些由后续分镜阶段处理\n"
                    + "- 爽点用 [爽点:描述] 标记，标注在对应剧情事件之后，例如：\n"
                    + "  「林渊被赵虎击飞重伤，体内沉睡的智脑被迫激活接管身体。[爽点:身份反转]」\n"
                    + "- 平均每 5 秒必须出现一个明确的剧情爽点\n"
                    + "- 爽点应以剧情事件为主（冲突升级、真相揭露、实力碾压），而非纯视觉冲击\n"
                    + "- 禁止超过 3 句的平铺叙述，必须持续推进情节\n";
        } else {
            base += "【内容量与时长匹配（最高优先级）】\n"
                    + "用户会给出每集目标时长，你必须确保 content 字段的内容量足以支撑该时长：\n"
                    + "- 每秒需要约 4-6 个字的剧情描述\n"
                    + "- 60秒 → content 约 240-360 字，至少 5 个场景节点\n"
                    + "- 120秒 → content 约 480-720 字，至少 8 个场景节点\n"
                    + "- 180秒 → content 约 720-1080 字，至少 12 个场景节点\n"
                    + "- 300秒 → content 约 1200-1800 字，至少 20 个场景节点\n"
                    + "- 不要概括压缩剧情，要展开每个场景的剧情发展\n"
                    + "- content 字段写剧情描述：以叙事体描述每个场景发生了什么、角色如何互动、情绪如何变化、情节如何推进\n"
                    + "- 不要写具体台词、不要写分镜头描述、不要写动作特写，这些由后续分镜阶段处理\n";
        }

        base += "【叙事节奏原则】\n"
                + "- 每集内容要有叙事弧线：铺垫→冲突升级→高潮→收束，不能平铺直叙\n"
                + "- 高潮段落（情感爆发、关键转折）给足篇幅展开，过渡段落（信息交代、场景切换）要简洁明快\n"
                + "- 每集结尾设置钩子（悬念/反转/情绪留白），驱动观众看下一集\n"
                + "仅输出 JSON 数组，包含字段：title、content、characters、keyItems、visualStyleNote、continuityNote。";

        return base;
    }

    public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                               String globalItems, String previousSummary, int splitCount,
                                               int duration, String modificationSuggestion, String scriptStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("完整大纲：\n").append(outline).append("\n\n");
        sb.append("目标章节：").append(chapter).append("\n");
        sb.append("拆分集数（本章节须生成的集数）：").append(splitCount).append(" 集\n");
        sb.append("【硬性要求】JSON 数组必须恰好 ").append(splitCount).append(" 个元素，对应 ").append(splitCount)
                .append(" 集剧本；少一集或多一集均为错误。\n");
        int charsPerSec = ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? 8 : 4;
        int charsPerSecMax = ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? 12 : 6;
        int minWords = duration * charsPerSec;
        int maxWords = duration * charsPerSecMax;
        sb.append("【时长硬性要求】每集 ").append(duration).append(" 秒，content 字段必须 ").append(minWords).append("-").append(maxWords).append(" 字，")
                .append("包含充分的剧情描述。不得概括压缩。\n");
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            int minBeats = duration / 5;
            sb.append("【爽点数量硬性要求】content 中必须包含至少 ").append(minBeats).append(" 个 [爽点:XX] 标记，")
                    .append("平均每 5 秒一个，以剧情事件为主。不足此数量的输出将被退回。\n");
        }
        sb.append("\n");
        if (modificationSuggestion != null && !modificationSuggestion.isEmpty()) {
            sb.append("修改建议：").append(modificationSuggestion).append("\n\n");
        }
        sb.append("全局角色：\n").append(globalCharacters).append("\n\n");
        sb.append("全局物品：\n").append(globalItems).append("\n\n");
        sb.append("前一集剧情摘要：\n").append(previousSummary).append("\n");
        return sb.toString();
    }

    /**
     * 单集生成的 system prompt：只输出 1 集 JSON 对象（非数组）
     */
    public String buildSingleEpisodeSystemPrompt(String scriptStyle) {
        String base = "你是一名专业的剧集编剧。\n"
                + "根据全局大纲和章节信息，生成其中一集的剧情描述。\n"
                + "【硬约束】仅输出 1 集，输出一个 JSON 对象（不要数组），包含字段：title、content、characters、keyItems、visualStyleNote、continuityNote。\n";

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base += "【内容量与时长匹配（爽剧模式 - 最高优先级）】\n"
                    + "- 每秒需要约 8-12 个字的剧情描述\n"
                    + "- 剧情事件数量是硬性要求，必须达标：\n"
                    + "  60秒 → content 约 480-720 字，必须包含至少 12 个 [爽点:XX] 标记\n"
                    + "  90秒 → content 约 720-1080 字，必须包含至少 18 个 [爽点:XX] 标记\n"
                    + "  120秒 → content 约 960-1440 字，必须包含至少 24 个 [爽点:XX] 标记\n"
                    + "  180秒 → content 约 1440-2160 字，必须包含至少 36 个 [爽点:XX] 标记\n"
                    + "  300秒 → content 约 2400-3600 字，必须包含至少 60 个 [爽点:XX] 标记\n"
                    + "- 爽点标记格式严格为 [爽点:描述]，不得省略方括号，不得用其他格式代替\n"
                    + "- 如果字数或爽点数量不足，你的输出将被退回重做\n"
                    + "【大纲与剧本的关系（关键）】\n"
                    + "- 大纲中每集有剧情概要，你必须在保持情节一致的前提下，展开为详细的场景级剧情描述\n"
                    + "- 剧情节奏快 = 剧情事件密集推进，不是画面切换快\n"
                    + "【爽剧剧情描述格式】\n"
                    + "- content 字段写剧情描述，以叙事体描述故事发展\n"
                    + "- 描述内容：每个场景发生了什么、角色如何互动、情绪如何变化、情节如何推进\n"
                    + "- 不要写具体台词、不要写分镜头描述、不要写动作特写，这些由后续分镜阶段处理\n"
                    + "- 爽点应以剧情事件为主（冲突升级、真相揭露、实力碾压），而非纯视觉冲击\n";
        } else {
            base += "【内容量与时长匹配（最高优先级）】\n"
                    + "- 每秒需要约 4-6 个字的剧情描述\n"
                    + "- 不要概括压缩剧情，要展开每个场景的剧情发展\n"
                    + "- content 字段写剧情描述：以叙事体描述每个场景发生了什么、角色如何互动、情绪如何变化\n"
                    + "- 不要写具体台词、不要写分镜头描述、不要写动作特写，这些由后续分镜阶段处理\n";
        }

        base += "【叙事节奏原则】\n"
                + "- 有叙事弧线：铺垫→冲突升级→高潮→收束\n"
                + "- 结尾设置钩子（悬念/反转/情绪留白），驱动观众看下一集\n";

        return base;
    }

    /**
     * 单集生成的 user prompt
     */
    public String buildSingleEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                                String globalItems, String previousSummary,
                                                int currentEpInChapter, int totalEpsInChapter,
                                                int duration, String modificationSuggestion, String scriptStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("完整大纲：\n").append(outline).append("\n\n");
        sb.append("目标章节：").append(chapter).append("\n");
        sb.append("这是本章第 ").append(currentEpInChapter).append(" 集（共 ").append(totalEpsInChapter).append(" 集）。\n");
        sb.append("【硬性要求】只输出 1 集 JSON 对象，不要输出数组。\n");
        int charsPerSec = ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? 8 : 4;
        int charsPerSecMax = ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? 12 : 6;
        int minWords = duration * charsPerSec;
        int maxWords = duration * charsPerSecMax;
        sb.append("【时长硬性要求】").append(duration).append(" 秒，content 字段必须 ").append(minWords).append("-").append(maxWords).append(" 字。\n");
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            int minBeats = duration / 5;
            sb.append("【爽点数量硬性要求】content 中必须包含至少 ").append(minBeats).append(" 个 [爽点:XX] 标记，以剧情事件为主。\n");
        }
        sb.append("\n");
        if (modificationSuggestion != null && !modificationSuggestion.isEmpty()) {
            sb.append("修改建议：").append(modificationSuggestion).append("\n\n");
        }
        sb.append("全局角色：\n").append(globalCharacters).append("\n\n");
        sb.append("全局物品：\n").append(globalItems).append("\n\n");
        sb.append("前一集剧情摘要：\n").append(previousSummary).append("\n");
        return sb.toString();
    }

    public ScriptParams calculateScriptParameters(int totalEpisodes) {
        if (totalEpisodes == 1) {
            int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
            int maxCharacters = (int) Math.round(minCharacters * 1.3);
            int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
            int maxItems = (int) Math.round(minItems * 1.25);
            return new ScriptParams(0, 0, minCharacters, maxCharacters, minItems, maxItems, true);
        }

        int episodesPerChapter;
        if (totalEpisodes <= 6) {
            episodesPerChapter = 2;
        } else if (totalEpisodes <= 10) {
            episodesPerChapter = 3;
        } else {
            episodesPerChapter = 4;
        }

        int chapterCount = (int) Math.ceil((double) totalEpisodes / episodesPerChapter);
        int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
        int maxCharacters = (int) Math.round(minCharacters * 1.3);
        int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
        int maxItems = (int) Math.round(minItems * 1.25);

        return new ScriptParams(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems, false);
    }

    public static class ScriptParams {
        public final int chapterCount;
        public final int episodesPerChapter;
        public final int minCharacters;
        public final int maxCharacters;
        public final int minItems;
        public final int maxItems;
        public final boolean isSingleEpisode;

        public ScriptParams(int chapterCount, int episodesPerChapter, int minCharacters,
                            int maxCharacters, int minItems, int maxItems, boolean isSingleEpisode) {
            this.chapterCount = chapterCount;
            this.episodesPerChapter = episodesPerChapter;
            this.minCharacters = minCharacters;
            this.maxCharacters = maxCharacters;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.isSingleEpisode = isSingleEpisode;
        }

        public ScriptParams(int chapterCount, int episodesPerChapter, int minCharacters,
                            int maxCharacters, int minItems, int maxItems) {
            this(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems, false);
        }
    }

    private String buildSingleEpisodePrompt(String genre, ScriptParams params, int episodeDuration, String scriptStyle) {
        String base = "创建完整的单集剧本大纲，使用 markdown 格式。\n"
                + "【集数】本项目固定为 1 集：不得扩展为多集大纲或多条分集。\n"
                + "题材类型：" + genre + "\n"
                + "目标时长：" + episodeDuration + " 秒\n"
                + "角色数量：" + params.minCharacters + "-" + params.maxCharacters + "\n"
                + "关键物品：" + params.minItems + "-" + params.maxItems + "\n"
                + "输出格式：仅返回 JSON { \"outline\": \"Markdown 大纲\" }\n"
                + "大纲需包含开场、发展、高潮和结局。";

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base += "\n\n【爽剧剧情密度约束】\n"
                    + "- 核心原则：剧情节奏快 = 剧情量大、事件密集推进，而非画面节奏快\n"
                    + "- 规划 4-6 个关键剧情转折（冲突爆发、真相揭露、身份反转、实力碾压等）\n"
                    + "- 不要标注「爽点」「钩子」等元信息，直接写具体剧情内容\n"
                    + "- 开篇直接进入冲突，结尾以悬念或关键转折收束\n"
                    + "- 禁止冗长铺垫、禁止空洞概括，每个场景都要推进剧情";
        }

        return base;
    }
}
