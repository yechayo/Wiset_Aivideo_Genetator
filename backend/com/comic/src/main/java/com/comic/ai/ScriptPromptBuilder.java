package com.comic.ai;

import org.springframework.stereotype.Component;

/**
 * 剧本 prompt 构建器
 * 负责大纲生成和剧集拆分的提示词
 */
@Component
public class ScriptPromptBuilder {

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre,
                                                  String targetAudience, String visualStyle) {
        ScriptParams params = calculateScriptParameters(totalEpisodes);

        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专精于短剧和微电影的专业编剧。\n");
        sb.append("根据用户提供的信息生成结构化的剧本大纲。\n\n");

        // ── 规模约束 ──
        sb.append("## 规模要求\n");
        sb.append("总集数：").append(totalEpisodes).append("\n");
        sb.append("章节数：").append(params.chapterCount).append("\n");
        sb.append("每章集数：").append(params.episodesPerChapter).append("\n\n");

        // ── 角色分级 ──
        sb.append("## 角色要求：").append(params.minCharacters)
                .append("-").append(params.maxCharacters).append(" 个角色\n");
        sb.append("按 role 字段区分主次：\n");
        sb.append("- 主角(2-3人)：详细描述，含外貌/性格/背景/动机/弱点，每人80-120字\n");
        sb.append("- 反派(1-2人)：详细描述，含动机/手段，每人60-100字\n");
        sb.append("- 配角(剩余)：简要描述，每人20-40字\n");
        sb.append("所有角色的 appearance 字段必须包含具体的身体特征、发型、着装描述。\n\n");

        // ── 物品分级 ──
        sb.append("## 物品要求：").append(params.minItems)
                .append("-").append(params.maxItems).append(" 个关键物品\n");
        sb.append("- 核心物品(2-3个)：推动主线剧情，详细描述功能和象征意义，30-50字\n");
        sb.append("- 辅助物品(剩余)：简略描述用途和出现时机，15-25字\n");
        sb.append("物品名称必须统一，不要使用同义词。\n\n");

        // ── 节奏规律（根据集数动态调整）──
        sb.append("## 节奏规律\n");
        if (totalEpisodes >= 10) {
            sb.append("- 每3-5集设置一次小高潮（冲突升级、秘密揭露、情感爆发）\n");
            sb.append("- 每10-15集设置一次大转折（阵营重组、身份揭露、命运逆转）\n");
        } else if (totalEpisodes >= 5) {
            sb.append("- 在中段（第2-3集左右）设置一次小高潮\n");
            sb.append("- 在结尾前设置一次转折或情感爆发\n");
        } else {
            // 2-4集：不给硬性节奏要求，只要求紧凑
            sb.append("- 因集数较少，要求剧情紧凑，每集都要有明确的推进\n");
        }
        sb.append("- 章节之间要有因果链条，避免情节断裂\n");
        sb.append("- 每集结尾要有悬念钩子\n");
        sb.append("- episodes 数组长度必须严格等于总集数 ").append(totalEpisodes)
                .append("，不得多生成也不得少生成\n\n");

        // ── 视觉风格 ──
        String style = visualStyle != null ? visualStyle : "REAL";
        sb.append("## 视觉风格：").append(style).append("\n");
        sb.append("在创作中始终贯彻该视觉风格的美学特征，场景描写和角色外观都要体现风格特点。\n\n");

        // ── 输出格式 ──
        sb.append("## 输出格式：仅返回 JSON，不要 markdown 代码块标记。\n");
        sb.append("JSON 结构：\n");
        sb.append("{\n");
        sb.append("  \"outline\": \"Markdown 格式的完整大纲文本（含世界观、章节剧情线、角色小传段落、物品设定段落）\",\n");
        sb.append("  \"characters\": [{\"name\":\"...\",\"role\":\"主角/反派/配角\","
                + "\"personality\":\"...\",\"appearance\":\"...\",\"background\":\"...\"}],\n");
        sb.append("  \"items\": [{\"name\":\"...\",\"description\":\"...\"}],\n");
        sb.append("  \"episodes\": [{\"ep\":1,\"title\":\"...\",\"synopsis\":\"...\","
                + "\"characters\":[\"角色名\"],\"keyItems\":[\"物品名\"]}]\n");
        sb.append("}\n\n");

        // ── 质量要求 ──
        sb.append("## 质量要求\n");
        sb.append("1. outline 包含完整的世界观、角色小传、关键物品设定、章节剧情线\n");
        sb.append("2. episodes 数组长度必须严格等于总集数 ").append(totalEpisodes)
                .append("，禁止多生成或少生成\n");
        sb.append("3. 每集 synopsis 100-200 字，包含明确的起承转合\n");
        sb.append("4. 角色的 personality 和 appearance 必须具体、有画面感\n");
        sb.append("5. 物品名称统一，禁止同义词混用\n");
        sb.append("6. 题材类型：").append(genre != null ? genre : "未指定").append("\n");
        sb.append("7. 目标受众：").append(targetAudience != null ? targetAudience : "未指定").append("\n");
        sb.append("8. 全中文输出\n");

        return sb.toString();
    }

    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                               int totalEpisodes, int episodeDuration, String visualStyle,
                                               String currentOutline) {
        StringBuilder sb = new StringBuilder();
        sb.append("核心创意：").append(storyPrompt).append("\n");
        sb.append("题材类型：").append(genre != null ? genre : "未指定").append("\n");
        sb.append("背景设定：").append(setting != null ? setting : "未指定").append("\n");
        sb.append("集数：").append(totalEpisodes).append("\n");
        sb.append("每集时长：").append(episodeDuration).append(" 分钟\n");
        sb.append("视觉风格：").append(visualStyle != null ? visualStyle : "未指定");

        // 如果有旧大纲，作为独立上下文呈现（用于 revision 场景）
        if (currentOutline != null && !currentOutline.isEmpty()) {
            sb.append("\n\n---\n\n");
            sb.append("## 当前大纲内容（请在此基础上进行修改）\n\n");
            sb.append(currentOutline);
        }

        return sb.toString();
    }

    public String buildScriptEpisodeSystemPrompt(int duration, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名专业的短剧分集编剧，擅长创作连贯、一致的系列剧集。\n");
        sb.append("根据提供的【剧本大纲】和【指定章节】，将该章节拆分为具体的剧集剧本。\n\n");

        // ── 连贯性约束 ──
        sb.append("## 连贯性和一致性要求（CRITICAL）\n\n");
        sb.append("1. 角色一致性：严格遵循【全局角色】中的外貌、性格、说话方式\n");
        sb.append("   不要改变角色的既定特征（如果某角色是冷静内敛的，不要突然变得热血冲动）\n");
        sb.append("2. 物品命名一致性：严格使用【全局物品】中的标准名称\n");
        sb.append("   禁止同一物品使用不同名称（例如不能一会儿叫\"脊骨\"一会儿叫\"灵骨\"）\n");
        sb.append("3. 剧情连贯性：承接【前序剧集摘要】的事件和角色状态\n");
        sb.append("   角色的知识、状态应该承接前文（如果前集主角受伤了，本集应体现这个状态）\n");
        sb.append("4. 场景连贯性：环境细节保持一致（同一个房间的布局、装饰等）\n\n");

        // ── 内容要求 ──
        sb.append("## 内容要求\n\n");
        sb.append("1. 全中文写作\n");
        sb.append("2. 剧本内容长度（CRITICAL）：每分钟时长需要 200-250 字的详细剧本内容\n");
        int targetMin = duration * 200;
        int targetMax = duration * 250;
        sb.append("   目标字数：").append(targetMin).append("-").append(targetMax).append(" 字\n");
        sb.append("   如果内容不足，增加场景描述、角色动作细节、对话、内心活动，而非凑字数\n\n");

        sb.append("3. 剧本内容 (content) 必须包含：\n");
        sb.append("   - 场景描写：环境、光影、氛围、空间布局\n");
        sb.append("   - 肢体动作：角色的身体姿势、动作细节、位置移动\n");
        sb.append("   - 表情细节：眼神、微表情、情绪变化\n");
        sb.append("   - 精彩对白：符合角色性格的对话，推动剧情发展\n");
        sb.append("   - 情感描写：内心活动、情感转变、动机暗示\n\n");

        sb.append("4. 每集结尾要有悬念（Cliffhanger）\n\n");

        if (visualStyle != null) {
            sb.append("5. 场景描述应体现 ").append(visualStyle).append(" 的视觉特点\n\n");
        }

        // ── 扩写技巧 ──
        sb.append("## 扩写技巧\n\n");
        sb.append("不要只写\"他走进房间\"，要写\"他推开沉重的红木门，"
                + "脚步沉重地踏入昏暗的书房，皮鞋在大理石地板上发出清脆的回响\"\n");
        sb.append("不要只写\"她哭了\"，要写\"她的眼泪如断了线的珍珠般滑落，"
                + "肩膀随着压抑的抽泣微微颤抖，双手紧紧攥着衣角，指节泛白\"\n");
        sb.append("不要只写\"房间很乱\"，要写\"书本散落一地，纸张如同秋风中的落叶般"
                + "铺满整个房间，书架歪斜，几本书籍摇摇欲坠地挂在边缘\"\n\n");

        // ── 输出格式 ──
        sb.append("## 输出格式\n\n");
        sb.append("仅输出 JSON 数组，不要 markdown 代码块标记。\n");
        sb.append("每个对象包含以下字段：\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"title\": \"第X集：[分集标题]\",\n");
        sb.append("    \"content\": \"[详细剧本内容，含场景描写/动作指令/对白]\",\n");
        sb.append("    \"characters\": [\"角色A\", \"角色B\"],  // 本集涉及的角色名字符串数组，必须与全局设定一致\n");
        sb.append("    \"keyItems\": [\"物品A\", \"物品B\"],  // 本集出现的关键物品字符串数组，必须使用标准名称\n");
        sb.append("    \"visualStyleNote\": \"针对本集的视觉风格备注\",\n");
        sb.append("    \"continuityNote\": \"本集的连贯性说明，如承接前文哪件事、角色状态变化等\"\n");
        sb.append("  }\n");
        sb.append("]\n\n");
        sb.append("每集的 continuityNote 必须明确说明与剧情主线的衔接关系。\n");

        return sb.toString();
    }

    public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                               String globalItems, String previousSummary, int splitCount,
                                               int duration, String modificationSuggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("完整大纲：\n").append(outline).append("\n\n");
        sb.append("目标章节：").append(chapter).append("\n");
        sb.append("拆分集数：").append(splitCount).append("\n");
        sb.append("时长参考：").append(duration).append(" 分钟\n\n");
        if (modificationSuggestion != null && !modificationSuggestion.isEmpty()) {
            sb.append("修改建议：").append(modificationSuggestion).append("\n\n");
        }
        sb.append("全局角色：\n").append(globalCharacters).append("\n\n");
        sb.append("全局物品：\n").append(globalItems).append("\n\n");
        sb.append("前一集剧情摘要：\n").append(previousSummary).append("\n");
        return sb.toString();
    }

    public ScriptParams calculateScriptParameters(int totalEpisodes) {
        int episodesPerChapter;
        if (totalEpisodes <= 6) {
            episodesPerChapter = 2;
        } else if (totalEpisodes <= 10) {
            episodesPerChapter = 3;
        } else {
            episodesPerChapter = 4;
        }

        // 确保 episodesPerChapter 不超过总集数
        episodesPerChapter = Math.min(totalEpisodes, episodesPerChapter);

        int chapterCount = (int) Math.ceil((double) totalEpisodes / episodesPerChapter);
        int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
        int maxCharacters = (int) Math.round(minCharacters * 1.3);
        int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
        int maxItems = (int) Math.round(minItems * 1.25);

        return new ScriptParams(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems);
    }

    public static class ScriptParams {
        public final int chapterCount;
        public final int episodesPerChapter;
        public final int minCharacters;
        public final int maxCharacters;
        public final int minItems;
        public final int maxItems;

        public ScriptParams(int chapterCount, int episodesPerChapter, int minCharacters,
                            int maxCharacters, int minItems, int maxItems) {
            this.chapterCount = chapterCount;
            this.episodesPerChapter = episodesPerChapter;
            this.minCharacters = minCharacters;
            this.maxCharacters = maxCharacters;
            this.minItems = minItems;
            this.maxItems = maxItems;
        }
    }
}
