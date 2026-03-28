package com.comic.ai;

import org.springframework.stereotype.Component;

/**
 * 剧本 prompt 构建器
 * 负责大纲生成和剧集拆分的提示词
 */
@Component
public class ScriptPromptBuilder {

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience) {
        ScriptParams params = calculateScriptParameters(totalEpisodes);
        if (params.isSingleEpisode) {
            return buildSingleEpisodePrompt(genre, params);
        }

        return "你是一名专业的漫画剧本编剧。\n"
                + "请根据用户提供的信息生成结构化的剧本大纲。\n"
                + "题材类型：" + genre + "\n"
                + "目标受众：" + targetAudience + "\n"
                + "总集数：" + totalEpisodes + "\n\n"
                + "输出格式：仅返回 JSON，不要 markdown 代码块标记。\n"
                + "JSON 结构：\n"
                + "{\n"
                + "  \"outline\": \"Markdown 格式的完整大纲文本\",\n"
                + "  \"characters\": [{\"name\":\"...\",\"role\":\"主角/反派/配角\",\"personality\":\"...\",\"appearance\":\"...\",\"background\":\"...\"}],\n"
                + "  \"items\": [{\"name\":\"...\",\"description\":\"...\"}],\n"
                + "  \"episodes\": [{\"ep\":1,\"title\":\"...\",\"synopsis\":\"...\",\"characters\":[\"角色名\"],\"keyItems\":[\"物品名\"]}]\n"
                + "}\n\n"
                + "要求：\n"
                + "1. outline 包含完整的世界观、角色小传、关键物品设定、章节剧情线\n"
                + "2. episodes 数组长度必须等于总集数 " + totalEpisodes + "\n"
                + "3. 每集 synopsis 100-200 字\n"
                + "4. characters 和 items 尽可能详细";
    }

    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                               int totalEpisodes, int episodeDuration, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("核心创意：").append(storyPrompt).append("\n");
        sb.append("题材类型：").append(genre != null ? genre : "未指定").append("\n");
        sb.append("背景设定：").append(setting != null ? setting : "未指定").append("\n");
        sb.append("集数：").append(totalEpisodes).append("\n");
        sb.append("每集时长：").append(episodeDuration).append(" 分钟\n");
        sb.append("视觉风格：").append(visualStyle != null ? visualStyle : "未指定");
        return sb.toString();
    }

    public String buildScriptEpisodeSystemPrompt() {
        return "你是一名专业的剧集剧本编剧。\n"
                + "根据全局大纲和其中一个章节，将其拆分为具体的剧集剧本。\n"
                + "仅输出 JSON 数组，包含字段：title、content、characters、keyItems、visualStyleNote、continuityNote。";
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

    private String buildSingleEpisodePrompt(String genre, ScriptParams params) {
        return "创建完整的单集剧本大纲，使用 markdown 格式。\n"
                + "题材类型：" + genre + "\n"
                + "角色数量：" + params.minCharacters + "-" + params.maxCharacters + "\n"
                + "关键物品：" + params.minItems + "-" + params.maxItems + "\n"
                + "包含开场、发展、高潮和结局。";
    }
}
