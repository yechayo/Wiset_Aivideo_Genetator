package com.comic.ai;

import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt builder.
 */
@Component
public class PromptBuilder {

    public String buildStoryboardSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates) {
        StringBuilder sb = new StringBuilder();
        String genre = world != null && world.getGenre() != null ? world.getGenre() : "unknown";
        String worldRules = world != null && world.getRulesText() != null ? world.getRulesText() : "";

        sb.append("你是一名专业的").append(genre).append("漫画分镜师。\n\n");
        sb.append("## 世界观设定\n").append(worldRules).append("\n\n");
        sb.append("## 角色状态\n");
        if (charStates != null) {
            for (CharacterStateModel state : charStates) {
                if (state != null) {
                    sb.append(state.toPromptText()).append("\n");
                }
            }
        }

        sb.append("\n## 输出格式要求（必须严格遵守）\n");
        sb.append("仅返回 JSON，不要 markdown，不要解释。\n");
        sb.append("顶层必填字段：episode(数字)、title(字符串)、panels(数组)。\n");
        sb.append("每个 panel 必须包含：panel_id、shot_type、camera_angle、composition、");
        sb.append("background(scene_desc,time_of_day,atmosphere)、characters[]、dialogue[]、sfx[]、pacing、image_prompt_hint。\n");
        sb.append("每个角色对象必须包含：char_id、position、pose、expression、costume_state。\n");
        sb.append("当 panel 中没有可见角色时，使用 characters=[]。\n");
        sb.append("每条对话对象必须包含：speaker、text、bubble_type。\n");
        sb.append("允许的枚举值：shot_type={WIDE_SHOT,MID_SHOT,CLOSE_UP,OVER_SHOULDER}、");
        sb.append("camera_angle={eye_level,low_angle,high_angle,bird_eye}、pacing={slow,normal,fast}、");
        sb.append("bubble_type={speech,thought,narration_box}、costume_state={normal,battle_worn}。\n");
        return sb.toString();
    }

    public String buildEpisodeUserPrompt(int episodeNum, String outlineNode, String recentMemory) {
        StringBuilder sb = new StringBuilder();
        sb.append("为第 ").append(episodeNum).append(" 集生成分镜。\n\n");
        sb.append("## 剧集大纲\n").append(outlineNode != null ? outlineNode : "").append("\n\n");
        sb.append("## 前情提要\n").append(recentMemory != null ? recentMemory : "").append("\n\n");
        sb.append("要求：\n");
        sb.append("- 保持叙事连贯性\n");
        sb.append("- 至少有一个高张力的画面\n");
        sb.append("- 严格遵循系统输出格式要求\n");
        sb.append("- 仅输出 JSON\n");
        return sb.toString();
    }

    public String buildStoryboardRevisionUserPrompt(int episodeNum, String outlineNode, String recentMemory,
                                                    String currentStoryboardJson, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("修改第 ").append(episodeNum).append(" 集的分镜。\n\n");
        sb.append("## 剧集大纲\n").append(outlineNode != null ? outlineNode : "").append("\n\n");
        sb.append("## 前情提要\n").append(recentMemory != null ? recentMemory : "").append("\n\n");
        sb.append("## 当前分镜\n").append(currentStoryboardJson != null ? currentStoryboardJson : "{}").append("\n\n");
        sb.append("## 修改意见\n").append(feedback != null ? feedback : "").append("\n\n");
        sb.append("要求：\n");
        sb.append("- 根据反馈意见进行增量修改\n");
        sb.append("- 保持未修改部分的连贯性\n");
        sb.append("- 严格遵循系统输出格式要求\n");
        sb.append("- 仅输出完整的修改后 JSON\n");
        return sb.toString();
    }

    public String buildOutlineSystemPrompt(String genre, String targetAudience) {
        return "你是一名经验丰富的漫画编剧，题材类型：" + genre
                + "，目标受众：" + targetAudience
                + "。输出必须为有效的 JSON 格式。";
    }

    public String buildOutlineUserPrompt(String seriesName, String basicSetting, int episodesPerSeason) {
        return "为系列作品 '" + seriesName + "' 创建一季共 " + episodesPerSeason + " 集的大纲。\n"
                + "基础设定：\n" + basicSetting + "\n"
                + "输出 JSON 格式：{\"season\":1,\"main_conflict\":\"...\",\"episodes\":[{\"ep\":1,\"title\":\"...\",\"plot_node\":\"...\",\"key_event\":\"...\"}]}";
    }

    public String buildScriptSystemPrompt(String genre, String worldRules, String targetAudience) {
        return "你是一名专业的漫画剧本编剧。题材类型：" + genre
                + "，目标受众：" + targetAudience
                + "。\n世界观设定：\n" + worldRules
                + "\n输出必须为有效的 JSON 格式。";
    }

    public String buildScriptUserPrompt(String storyPrompt, int totalEpisodes, int episodeDuration) {
        return "根据以下故事提示，生成 " + totalEpisodes + " 集内容。\n"
                + "故事提示：\n" + storyPrompt + "\n"
                + "每集时长：" + episodeDuration + " 秒。\n"
                + "仅输出 JSON。";
    }

    public String buildCharacterExtractSystemPrompt() {
        return "从剧本文本中提取主要角色。仅输出 JSON 数组："
                + "[{\"name\":\"...\",\"role\":\"...\",\"personality\":\"...\",\"appearance\":\"...\",\"background\":\"...\"}]";
    }

    public String buildCharacterExtractUserPrompt(String scriptContent) {
        return "从以下剧本中提取主要角色：\n\n" + scriptContent + "\n\n仅输出 JSON 数组。";
    }

    public String addStricterConstraints(String systemPrompt, int attempt) {
        if (attempt == 2) {
            return systemPrompt
                    + "\n\n重要重试规则（第 2 次尝试）：\n"
                    + "1. 仅返回 JSON。\n"
                    + "2. 包含所有必需的顶层和 panel 字段。\n"
                    + "3. 每个角色对象必须包含 char_id、position、pose、expression、costume_state。\n"
                    + "4. 如果 panel 没有可见角色，将 characters 设为空数组。\n"
                    + "5. 每条对话对象必须包含 speaker、text、bubble_type。\n"
                    + "6. 保持枚举值在允许的集合内。";
        } else if (attempt >= 3) {
            return systemPrompt
                    + "\n\n最后警告（第 3 次尝试）：\n"
                    + "输出必须是以 '{' 开头、'}' 结尾的有效 JSON 对象。\n"
                    + "不允许有任何额外文本。\n"
                    + "不要省略角色或对话的必需嵌套字段。";
        }
        return systemPrompt;
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

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience) {
        ScriptParams params = calculateScriptParameters(totalEpisodes);
        if (params.isSingleEpisode) {
            return buildSingleEpisodePrompt(genre, params);
        }

        return "创建章节级剧本大纲，使用中文 markdown 格式。\n"
                + "总集数：" + totalEpisodes + "\n"
                + "章节数：" + params.chapterCount + "\n"
                + "每章参考集数：" + params.episodesPerChapter + "\n"
                + "目标受众：" + targetAudience + "\n"
                + "包含角色、关键物品和章节剧情线。";
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

    public static String[] getExpressionTypes() {
        return new String[]{"happy", "sad", "angry", "surprised", "fear", "disgust", "contempt", "shy", "calm"};
    }

    public static String[] getViewTypes() {
        return new String[]{"front", "side", "back"};
    }
}