package com.comic.ai;

import com.comic.dto.CharacterStateDTO;
import com.comic.dto.WorldConfigDTO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 构建器（Java 8 版）
 * 将业务数据组装成 Claude 能理解的提示词
 */
@Component
public class PromptBuilder {

    /**
     * 构建分镜生成的系统提示词
     */
    public String buildStoryboardSystemPrompt(WorldConfigDTO world, List<CharacterStateDTO> charStates) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 你的角色\n");
        sb.append("你是一个专业的漫画分镜脚本编剧，擅长").append(world.getGenre()).append("风格漫画。\n\n");

        sb.append("## 世界观设定\n");
        sb.append(world.getRulesText()).append("\n\n");

        sb.append("## 当前角色状态\n");
        for (CharacterStateDTO state : charStates) {
            sb.append(state.toPromptText()).append("\n");
        }

        sb.append("\n## 输出格式（严格遵守）\n");
        sb.append("只输出合法JSON，不含任何markdown标记或解释文字。格式：\n");
        sb.append("{\n");
        sb.append("  \"episode\": 集数,\n");
        sb.append("  \"title\": \"集标题\",\n");
        sb.append("  \"panels\": [\n");
        sb.append("    {\n");
        sb.append("      \"panel_id\": \"ep{集数}_p{序号}\",\n");
        sb.append("      \"shot_type\": \"WIDE_SHOT或MID_SHOT或CLOSE_UP或OVER_SHOULDER\",\n");
        sb.append("      \"camera_angle\": \"eye_level或low_angle或high_angle或bird_eye\",\n");
        sb.append("      \"composition\": \"构图描述\",\n");
        sb.append("      \"background\": {\"scene_desc\": \"场景\", \"time_of_day\": \"时段\", \"atmosphere\": \"氛围\"},\n");
        sb.append("      \"characters\": [{\"char_id\": \"ID\", \"position\": \"位置\", \"pose\": \"姿势\", \"expression\": \"表情\", \"costume_state\": \"normal或battle_worn\"}],\n");
        sb.append("      \"dialogue\": [{\"speaker\": \"char_id或narrator\", \"text\": \"内容\", \"bubble_type\": \"speech或thought或narration_box\"}],\n");
        sb.append("      \"sfx\": [\"音效\"],\n");
        sb.append("      \"pacing\": \"slow或normal或fast\",\n");
        sb.append("      \"image_prompt_hint\": \"给图像AI的视觉提示\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 构建单集剧情生成提示词
     */
    public String buildEpisodeUserPrompt(int episodeNum, String outlineNode, String recentMemory) {
        StringBuilder sb = new StringBuilder();
        sb.append("请生成第 ").append(episodeNum).append(" 集的完整分镜脚本。\n\n");
        sb.append("## 本集大纲节点\n").append(outlineNode).append("\n\n");
        sb.append("## 近期剧情记忆\n").append(recentMemory).append("\n\n");
        sb.append("## 要求\n");
        sb.append("- 包含 6-8 格分镜\n");
        sb.append("- 结尾有悬念钩子\n");
        sb.append("- 对白符合角色风格\n");
        sb.append("- 至少一个高张力全景\n\n");
        sb.append("输出JSON：");
        return sb.toString();
    }

    /**
     * 构建大纲生成系统提示词
     */
    public String buildOutlineSystemPrompt(String genre, String targetAudience) {
        return "你是资深漫画编剧，专注" + genre + "风格，目标读者" + targetAudience +
               "。\n输出必须为合法JSON，不含任何额外文字。";
    }

    public String buildOutlineUserPrompt(String seriesName, String basicSetting, int episodesPerSeason) {
        return "请为漫画《" + seriesName + "》生成第一季大纲（共" + episodesPerSeason + "集）。\n" +
               "基本设定：" + basicSetting + "\n" +
               "JSON格式：{\"season\":1,\"main_conflict\":\"...\",\"episodes\":[{\"ep\":1,\"title\":\"...\",\"plot_node\":\"...\",\"key_event\":\"...\"}]}";
    }

    /**
     * 构建剧本生成的系统提示词
     */
    public String buildScriptSystemPrompt(String genre, String worldRules, String targetAudience) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 你的角色\n");
        sb.append("你是一个专业的漫画编剧，擅长").append(genre).append("风格漫画。\n\n");

        sb.append("## 目标读者\n");
        sb.append(targetAudience).append("\n\n");

        sb.append("## 世界观规则\n");
        sb.append(worldRules).append("\n\n");

        sb.append("## 输出格式（严格遵守）\n");
        sb.append("只输出合法JSON，不含任何markdown标记或解释文字。格式：\n");
        sb.append("{\n");
        sb.append("  \"series_name\": \"漫画名称\",\n");
        sb.append("  \"episodes\": [\n");
        sb.append("    {\n");
        sb.append("      \"episode_num\": 1,\n");
        sb.append("      \"title\": \"第1集标题\",\n");
        sb.append("      \"outline\": \"本集大纲\",\n");
        sb.append("      \"key_events\": [\"关键事件1\", \"关键事件2\"],\n");
        sb.append("      \"characters\": [\"涉及的角色ID\"],\n");
        sb.append("      \"cliffhanger\": \"结尾悬念\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 构建剧本生成的用户提示词
     */
    public String buildScriptUserPrompt(String storyPrompt, int totalEpisodes, int episodeDuration) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下故事提示词生成完整的").append(totalEpisodes).append("集剧本。\n\n");

        sb.append("## 故事提示词\n");
        sb.append(storyPrompt).append("\n\n");

        sb.append("## 要求\n");
        sb.append("- 总共").append(totalEpisodes).append("集\n");
        sb.append("- 每集约").append(episodeDuration).append("秒（大约").append(episodeDuration / 10).append("个分镜格子）\n");
        sb.append("- 每集要有明确的故事推进\n");
        sb.append("- 角色发展要连贯\n");
        sb.append("- 适当留悬念\n\n");

        sb.append("输出JSON：");
        return sb.toString();
    }

    /**
     * 构建角色提取的系统提示词
     */
    public String buildCharacterExtractSystemPrompt() {
        return "你是专业的角色设计师，擅长从剧本中提取角色特征。\n\n" +
               "## 输出格式（严格遵守）\n" +
               "只输出合法JSON数组，不含任何markdown标记或解释文字。格式：\n" +
               "[\n" +
               "  {\n" +
               "    \"name\": \"角色名\",\n" +
               "    \"role\": \"主角/反派/配角\",\n" +
               "    \"personality\": \"性格描述\",\n" +
               "    \"appearance\": \"外貌描述\",\n" +
               "    \"background\": \"背景故事\"\n" +
               "  }\n" +
               "]";
    }

    /**
     * 构建角色提取的用户提示词
     */
    public String buildCharacterExtractUserPrompt(String scriptContent) {
        return "请从以下剧本中提取所有主要角色（至少出现2次或有重要戏份的角色）：\n\n" +
               "## 剧本内容\n" +
               scriptContent + "\n\n" +
               "输出角色JSON数组：";
    }

    /**
     * 重试时加强格式约束
     */
    public String addStricterConstraints(String systemPrompt, int attempt) {
        if (attempt == 2) {
            return systemPrompt + "\n\n重要：上次输出格式不合法，请严格只输出JSON。";
        } else if (attempt >= 3) {
            return systemPrompt + "\n\n最终警告：必须从 { 开始到 } 结束，绝对不能有其他字符。";
        }
        return systemPrompt;
    }
}
