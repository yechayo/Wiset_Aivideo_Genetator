package com.comic.ai;

import org.springframework.stereotype.Component;

/**
 * 漫剧解说模式专用剧本 prompt，与 {@link ScriptPromptBuilder} 的实时动画链路分离。
 */
@Component
public class ComicCommentaryScriptPromptBuilder {

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience,
                                                 int chapterCount, int episodesPerChapter, int episodeDuration) {
        ScriptPromptBuilder.ScriptParams params = calculateScriptParameters(totalEpisodes);
        if (params.isSingleEpisode) {
            return buildSingleEpisodeOutlineSystemPrompt(genre, params, episodeDuration);
        }

        return "你是一名擅长「漫剧解说」短视频的编剧与策划。\n"
                + "作品形式为：画面以漫画/动态漫分镜为主，配合解说旁白与字幕节奏，强调钩子、信息密度与情绪起伏。\n"
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
                + "1. outline 需体现解说视角：每章注明本集「开场钩子」「核心冲突」「结尾悬念」，并标注建议旁白语气（冷静/激昂/幽默等）\n"
                + "2. outline 仍须包含世界观、角色小传、关键物品设定、章节剧情线\n"
                + "3. outline 中的章节剧情线必须使用「### 第X章」格式，每章描述该章包含 " + episodesPerChapter + " 集的剧情走向\n"
                + "4. 章节标题中必须包含对应集数范围，格式如「### 第一章: 标题（第1-2集）」\n"
                + "5. 每集需列出标题并用 100-200 字概括核心冲突和结尾钩子\n"
                + "6. 每集描述 1-2 个关键场景转折点，确保后续编剧能据此展开完整口播稿\n"
                + "7. 章节剧情线要体现节奏变化：标注每集中哪些部分是高潮（需展开），哪些是过渡（需简洁）";
    }

    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                               int totalEpisodes, int episodeDuration, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("核心创意：").append(storyPrompt).append("\n");
        sb.append("题材类型：").append(genre != null ? genre : "未指定").append("\n");
        sb.append("背景设定：").append(setting != null ? setting : "未指定").append("\n");
        sb.append("总集数：").append(totalEpisodes).append(" 集\n");
        sb.append("每集时长：").append(episodeDuration).append(" 秒（解说口播与画面节奏需适配该时长）\n");
        sb.append("视觉风格：").append(visualStyle != null ? visualStyle : "未指定");
        return sb.toString();
    }

    public String buildScriptEpisodeSystemPrompt() {
        return "你是一名「漫剧解说」分集编剧。\n"
                + "在保持剧情连贯的前提下，将指定章节拆分为可拍摄的剧集脚本（按「集」输出）：每集需适合旁白解说，突出信息点与情绪转折。\n"
                + "【集数硬约束】用户消息中的「拆分集数」即本章节必须生成的集数：你输出的 JSON 数组长度必须恰好等于该数字，"
                + "一集对应数组中的一个对象；禁止合并多集、禁止少生成、禁止多生成。\n"
                + "【内容量与时长匹配（最高优先级）】\n"
                + "用户会给出每集目标时长，你必须确保 content 字段的内容量足以支撑该时长：\n"
                + "- 每秒需要约 5-7 个字的剧本内容（含旁白解说、对话、场景描述）\n"
                + "- 60秒 → content 约 300-420 字\n"
                + "- 120秒 → content 约 600-840 字\n"
                + "- 180秒 → content 约 900-1260 字\n"
                + "- 300秒 → content 约 1500-2100 字\n"
                + "- 不要概括压缩剧情，要展开每个场景的具体旁白解说、对话、动作描写\n"
                + "- 使用「（场景描述）」「角色（情绪）：台词」「旁白：解说词」格式，便于后续按镜头拆解\n"
                + "【叙事节奏原则】\n"
                + "- 每集内容要有叙事弧线：铺垫→冲突升级→高潮→收束，不能平铺直叙\n"
                + "- 高潮段落（情感爆发、关键转折）给足篇幅展开，过渡段落（信息交代、场景切换）要简洁明快\n"
                + "- 对话和动作交替出现，避免连续大段纯叙述或纯对话\n"
                + "- 每集结尾设置钩子（悬念/反转/情绪留白），驱动观众看下一集\n"
                + "仅输出 JSON 数组，包含字段：title、content、characters、keyItems、visualStyleNote、continuityNote。\n"
                + "其中 content 为正文，需自然分段，便于后续按镜头拆解；visualStyleNote 可提示画面氛围与转场。";
    }

    public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                               String globalItems, String previousSummary, int splitCount,
                                               int duration, String modificationSuggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("完整大纲：\n").append(outline).append("\n\n");
        sb.append("目标章节：").append(chapter).append("\n");
        sb.append("拆分集数（本章节须生成的集数）：").append(splitCount).append(" 集\n");
        sb.append("【硬性要求】JSON 数组必须恰好 ").append(splitCount).append(" 个元素，对应 ").append(splitCount)
                .append(" 集剧本；少一集或多一集均为错误。\n");
        int minWords = duration * 5;
        int maxWords = duration * 7;
        sb.append("【时长硬性要求】每集 ").append(duration).append(" 秒，content 字段必须 ").append(minWords).append("-").append(maxWords).append(" 字，")
                .append("包含充分的旁白解说、场景描写和对话细节。不得概括压缩。\n\n");
        if (modificationSuggestion != null && !modificationSuggestion.isEmpty()) {
            sb.append("修改建议：").append(modificationSuggestion).append("\n\n");
        }
        sb.append("全局角色：\n").append(globalCharacters).append("\n\n");
        sb.append("全局物品：\n").append(globalItems).append("\n\n");
        sb.append("前一集剧情摘要：\n").append(previousSummary).append("\n");
        return sb.toString();
    }

    /** 与 {@link ScriptPromptBuilder#calculateScriptParameters(int)} 逻辑一致，避免跨 builder 依赖。 */
    public ScriptPromptBuilder.ScriptParams calculateScriptParameters(int totalEpisodes) {
        if (totalEpisodes == 1) {
            int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
            int maxCharacters = (int) Math.round(minCharacters * 1.3);
            int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
            int maxItems = (int) Math.round(minItems * 1.25);
            return new ScriptPromptBuilder.ScriptParams(0, 0, minCharacters, maxCharacters, minItems, maxItems, true);
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

        return new ScriptPromptBuilder.ScriptParams(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems, false);
    }

    private String buildSingleEpisodeOutlineSystemPrompt(String genre, ScriptPromptBuilder.ScriptParams params,
                                                          int episodeDuration) {
        return "创建完整的单集「漫剧解说」剧本大纲，使用 markdown 格式。\n"
                + "【集数】本项目固定为 1 集：不得扩展为多集大纲或多条分集。\n"
                + "题材类型：" + genre + "\n"
                + "目标时长：" + episodeDuration + " 秒\n"
                + "角色数量：" + params.minCharacters + "-" + params.maxCharacters + "\n"
                + "关键物品：" + params.minItems + "-" + params.maxItems + "\n"
                + "输出格式：仅返回 JSON { \"outline\": \"Markdown 大纲\" }\n"
                + "需包含开场钩子、发展、高潮、结局，并标注解说语气与节奏建议。";
    }
}
