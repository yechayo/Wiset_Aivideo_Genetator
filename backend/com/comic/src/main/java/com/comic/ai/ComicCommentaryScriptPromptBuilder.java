package com.comic.ai;

import org.springframework.stereotype.Component;

/**
 * 漫剧解说模式专用剧本 prompt，与 {@link ScriptPromptBuilder} 的实时动画链路分离。
 */
@Component
public class ComicCommentaryScriptPromptBuilder {

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience,
                                                 int chapterCount, int episodesPerChapter) {
        ScriptPromptBuilder.ScriptParams params = calculateScriptParameters(totalEpisodes);
        if (params.isSingleEpisode) {
            return buildSingleEpisodeOutlineSystemPrompt(genre, params);
        }

        return "你是一名擅长「漫剧解说」短视频的编剧与策划。\n"
                + "作品形式为：画面以漫画/动态漫分镜为主，配合解说旁白与字幕节奏，强调钩子、信息密度与情绪起伏。\n"
                + "【集数硬约束（最高优先级，必须严格遵守）】\n"
                + "用户设定的总集数为 " + totalEpisodes + " 集：JSON 中 episodes 数组必须恰好包含 " + totalEpisodes + " 个对象，不得多也不得少。\n"
                + "episodes 中每个对象的 ep 字段必须从 1 到 " + totalEpisodes + " 连续递增、不重复、不跳号；禁止把多集解说合并成一条，也禁止把一集拆成多条。\n"
                + "若总集数与章节划分冲突，以总集数为准调整每章承担的集数说明，但 episodes 条数始终等于 " + totalEpisodes + "。\n\n"
                + "请根据用户提供的信息生成结构化的剧本大纲。\n"
                + "题材类型：" + genre + "\n"
                + "目标受众：" + targetAudience + "\n"
                + "总集数：" + totalEpisodes + " 集\n"
                + "章节数：" + chapterCount + "（每个章节包含 " + episodesPerChapter + " 集）\n\n"
                + "输出格式：仅返回 JSON，不要 markdown 代码块标记。\n"
                + "JSON 结构：\n"
                + "{\n"
                + "  \"outline\": \"Markdown 格式的完整大纲文本\",\n"
                + "  \"characters\": [{\"name\":\"...\",\"role\":\"主角/反派/配角\",\"personality\":\"...\",\"appearance\":\"...\",\"background\":\"...\"}],\n"
                + "  \"items\": [{\"name\":\"...\",\"description\":\"...\"}],\n"
                + "  \"episodes\": [{\"ep\":1,\"title\":\"...\",\"synopsis\":\"...\",\"characters\":[\"角色名\"],\"keyItems\":[\"物品名\"]}]\n"
                + "}\n\n"
                + "要求：\n"
                + "1. outline 需体现解说视角：每章注明本集「开场钩子」「核心冲突」「结尾悬念」，并标注建议旁白语气（冷静/激昂/幽默等）\n"
                + "2. outline 仍须包含世界观、角色小传、关键物品设定、章节剧情线\n"
                + "3. outline 中的章节剧情线必须使用「### 第X章」格式，每章描述该章包含 " + episodesPerChapter + " 集的剧情走向\n"
                + "4. 章节标题中必须包含对应集数范围，格式如「### 第一章: 标题（第1-2集）」\n"
                + "5. episodes 数组长度必须恰好等于总集数 " + totalEpisodes + "（与上方硬约束一致）\n"
                + "6. 每集 synopsis 100-200 字，并隐含本集解说重点与情绪节奏\n"
                + "7. characters 和 items 尽可能详细，便于后续分镜与口播对齐";
    }

    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                               int totalEpisodes, int episodeDuration, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("核心创意：").append(storyPrompt).append("\n");
        sb.append("题材类型：").append(genre != null ? genre : "未指定").append("\n");
        sb.append("背景设定：").append(setting != null ? setting : "未指定").append("\n");
        sb.append("【总集数（不可更改）】").append(totalEpisodes).append(" 集：episodes 必须恰好 ").append(totalEpisodes)
                .append(" 条，ep 从 1 连续到 ").append(totalEpisodes).append("。\n");
        sb.append("每集时长：").append(episodeDuration).append(" 秒（解说口播与画面节奏需适配该时长）\n");
        sb.append("视觉风格：").append(visualStyle != null ? visualStyle : "未指定");
        return sb.toString();
    }

    public String buildScriptEpisodeSystemPrompt() {
        return "你是一名「漫剧解说」分集编剧。\n"
                + "在保持剧情连贯的前提下，将指定章节拆分为可拍摄的剧集脚本（按「集」输出）：每集需适合旁白解说，突出信息点与情绪转折。\n"
                + "【集数硬约束】用户消息中的「拆分集数」即本章节必须生成的集数：你输出的 JSON 数组长度必须恰好等于该数字，"
                + "一集对应数组中的一个对象；禁止合并多集、禁止少生成、禁止多生成。\n"
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
        sb.append("时长参考：").append(duration).append(" 秒（解说密度与留白需与此匹配）\n\n");
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

    private String buildSingleEpisodeOutlineSystemPrompt(String genre, ScriptPromptBuilder.ScriptParams params) {
        return "创建完整的单集「漫剧解说」剧本大纲，使用 markdown 格式。\n"
                + "【集数】本项目固定为 1 集：不得扩展为多集大纲或多条分集。\n"
                + "题材类型：" + genre + "\n"
                + "角色数量：" + params.minCharacters + "-" + params.maxCharacters + "\n"
                + "关键物品：" + params.minItems + "-" + params.maxItems + "\n"
                + "需包含：开场钩子、发展、高潮、结局，并标注解说语气与节奏建议。";
    }
}
