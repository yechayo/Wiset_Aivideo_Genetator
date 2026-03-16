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

    // ================= 两级剧本生成新增方法 =================

    /**
     * 动态计算剧本参数
     * 单集模式（totalEpisodes == 1）：直接生成单集剧本，不需要章节规划
     */
    public ScriptParams calculateScriptParameters(int totalEpisodes) {
        // 单集模式：直接生成单集剧本
        if (totalEpisodes == 1) {
            int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
            int maxCharacters = (int) Math.round(minCharacters * 1.3);
            int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
            int maxItems = (int) Math.round(minItems * 1.25);
            return new ScriptParams(0, 0, minCharacters, maxCharacters, minItems, maxItems, true);
        }

        // 多集模式：动态计算每章集数
        int episodesPerChapter;
        if (totalEpisodes <= 3) {
            episodesPerChapter = 1;
        } else if (totalEpisodes <= 10) {
            episodesPerChapter = (totalEpisodes <= 6) ? 2 : 3;
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

    /**
     * 构建剧本大纲生成的系统提示词（SCRIPT_PLANNER）
     * 单集模式：直接生成单集剧本大纲
     * 多集模式：生成章节结构大纲
     */
    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience) {
        ScriptParams params = calculateScriptParameters(totalEpisodes);

        // 单集模式：直接生成单集剧本
        if (params.isSingleEpisode) {
            return buildSingleEpisodePrompt(genre, params);
        }

        // 多集模式：生成章节结构大纲
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专精于短剧和微电影的专业编剧。\n");
        sb.append("你的任务是根据用户的核心创意和约束条件，创建一个引人入胜的**中文剧本大纲**。\n\n");

        sb.append("**核心原则：剧本大纲只在章节层面规划，不细化到每集**\n\n");

        sb.append("## 剧集规模要求（必须严格遵守）\n\n");
        sb.append("**总集数限制**：本剧总共 **").append(totalEpisodes).append(" 集**，不能多也不能少！\n");
        sb.append("**章节规划**：需要规划 **").append(params.chapterCount).append(" 个章节**。\n\n");

        // 计算每章集数分配
        sb.append("**每章集数分配**：\n");
        int episodesPerChapter = totalEpisodes / params.chapterCount;
        int remainder = totalEpisodes % params.chapterCount;

        for (int i = 1; i <= params.chapterCount; i++) {
            int episodesInThisChapter = episodesPerChapter + (i <= remainder ? 1 : 0);
            sb.append("- 第").append(i).append("章：包含 ").append(episodesInThisChapter).append(" 集");
            if (i == 1) {
                sb.append("（第1-").append(episodesInThisChapter).append("集）");
            } else if (i == params.chapterCount) {
                int startEp = (i - 1) * episodesPerChapter + Math.min(i - 1, remainder) + 1;
                sb.append("（第").append(startEp).append("-").append(totalEpisodes).append("集）");
            } else {
                int startEp = (i - 1) * episodesPerChapter + Math.min(i - 1, remainder) + 1;
                int endEp = startEp + episodesInThisChapter - 1;
                sb.append("（第").append(startEp).append("-").append(endEp).append("集）");
            }
            sb.append("\n");
        }

        sb.append("\n**重要提醒**：所有章节的集数加起来必须等于 ").append(totalEpisodes).append(" 集，绝对不能超出！\n\n");

        sb.append("### 角色数量要求：").append(params.minCharacters).append("-").append(params.maxCharacters).append(" 个角色\n\n");
        sb.append("**角色分级与描述重点：**\n\n");
        sb.append("**A. 核心角色（3-5人）- 需要详细小传**\n");
        sb.append("- **主角团队**（2-3人）：故事的绝对核心\n");
        sb.append("- **核心反派**（1-2人）：与主角对抗的主要力量\n");
        sb.append("描述要求（每个角色80-120字）\n\n");

        sb.append("**B. 重要配角（8-12人）- 简单描述**\n");
        sb.append("- 导师/盟友/中立角色等\n");
        sb.append("描述要求（每个角色20-40字）\n\n");

        sb.append("**C. 其他角色（剩余数量）- 一笔带过**\n");
        sb.append("- 群演、背景角色、一次性角色等\n");
        sb.append("描述要求（每个角色5-10字）\n\n");

        sb.append("### 物品数量要求：").append(params.minItems).append("-").append(params.maxItems).append(" 个关键物品\n\n");
        sb.append("**物品分级与描述：**\n\n");
        sb.append("**A. 核心物品（3-5个）- 推动主线**\n");
        sb.append("描述要求（每个物品30-50字）\n\n");
        sb.append("**B. 辅助物品（5-8个）- 特定章节使用**\n");
        sb.append("描述要求（每个物品15-25字）\n\n");
        sb.append("**C. 世界物品（剩余数量）- 丰富设定**\n");
        sb.append("描述要求（每个物品10-15字）\n\n");

        sb.append("## 章节结构与节奏要求\n\n");
        sb.append("### 核心原则：每个章节描述包含的集数的整体故事\n\n");
        sb.append("### 节奏规律（必须严格遵循）\n\n");
        sb.append("1. **小高潮**：每3-5集设置一次小高潮\n");
        sb.append("2. **大转折**：每10-15集设置一次大转折\n\n");

        sb.append("## 输出格式要求 (必须严格遵守 Markdown 格式)\n\n");
        sb.append("# 剧名 (Title)\n");
        sb.append("**一句话梗概**: [一句话总结故事核心]\n");
        sb.append("**类型**: [").append(genre).append("] | **主题**: [主题] | **背景**: [故事背景] | **视觉风格**: [Visual Style]\n\n");
        sb.append("----\n\n");
        sb.append("## 主要人物小传\n\n");
        sb.append("### 核心角色（详细小传，80-120字/人）\n");
        sb.append("* **[姓名]**: [角色定位] - [年龄] [外貌特征]。性格：[性格特点]。背景：[重要经历]。\n\n");
        sb.append("### 重要配角（简单描述，20-40字/人）\n");
        sb.append("* **[姓名]**: [角色定位和作用，简短描述]\n\n");
        sb.append("### 其他角色（一笔带过，5-10字/人）\n");
        sb.append("* **[姓名]**: [身份或作用]\n\n");
        sb.append("----\n\n");
        sb.append("## 关键物品设定\n\n");
        sb.append("### 核心物品（30-50字/个）\n");
        sb.append("* **[物品名称]**: [物品描述、功能、象征意义]\n\n");
        sb.append("### 辅助物品（15-25字/个）\n");
        sb.append("* **[物品名称]**: [物品描述和出现时机]\n\n");
        sb.append("### 世界物品（10-15字/个）\n");
        sb.append("* **[物品名称]**: [简要描述]\n\n");
        sb.append("----\n\n");
        sb.append("## 剧集结构规划（共 ").append(totalEpisodes).append(" 集，").append(params.chapterCount).append(" 章）\n\n");
        sb.append("### 章节格式标准（每章100-150字）\n\n");
        sb.append("#### 第X章：章节名称（第A-B集）\n\n");
        sb.append("**涉及角色**：[本章主要角色，3-5人]\n\n");
        sb.append("**关键物品**：[本章重要物品，2-3个]\n\n");
        sb.append("**章节剧情**（100-150字）：\n");
        sb.append("[这几集的整体故事描述，包含起承转合]\n\n");
        sb.append("- [第A集]：[发生了什么]\n");
        sb.append("- [第A+1集]：[情节推进]\n");
        sb.append("- [第B集]：[本章高潮/转折]\n\n");
        sb.append("**关键节点**：[标注：小高潮 或 大转折]\n");

        return sb.toString();
    }

    /**
     * 构建剧本大纲生成的用户提示词
     */
    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                                int totalEpisodes, int episodeDuration, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("核心创意: ").append(storyPrompt).append("\n");
        sb.append("类型: ").append(genre != null ? genre : "N/A").append("\n");
        sb.append("背景: ").append(setting != null ? setting : "N/A").append("\n");
        sb.append("预估集数: ").append(totalEpisodes).append("\n");
        sb.append("单集时长: ").append(episodeDuration).append(" 分钟\n");
        sb.append("视觉风格: ").append(visualStyle != null ? visualStyle : "N/A").append("\n");
        return sb.toString();
    }

    /**
     * 构建分集生成的系统提示词（SCRIPT_EPISODE）
     */
    public String buildScriptEpisodeSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的短剧分集编剧，擅长创作连贯、一致的系列剧集。\n");
        sb.append("你的任务是根据提供的【剧本大纲】和【指定章节】，将该章节拆分为 N 个具体的剧集脚本。\n\n");

        sb.append("**连贯性和一致性要求**\n\n");
        sb.append("1. **角色一致性**: 严格遵循全局角色设定中的角色外貌、性格、说话方式\n");
        sb.append("2. **物品命名一致性**: 严格使用全局物品设定中的标准名称\n");
        sb.append("3. **剧情连贯性**: 参考前序剧集摘要，确保时间线、事件顺序合理衔接\n");
        sb.append("4. **场景连贯性**: 场景描述应该符合既定的视觉风格\n\n");

        sb.append("**输出要求：**\n");
        sb.append("请直接输出一个 **JSON 数组**，格式如下：\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"title\": \"第X集：[分集标题]\",\n");
        sb.append("    \"content\": \"[详细剧本内容，包含场景描写、动作指令和对白]\",\n");
        sb.append("    \"characters\": \"[本集涉及的角色列表]\",\n");
        sb.append("    \"keyItems\": \"[本集出现的关键物品列表]\",\n");
        sb.append("    \"visualStyleNote\": \"[针对本集的视觉风格备注]\",\n");
        sb.append("    \"continuityNote\": \"[本集的连贯性说明]\"\n");
        sb.append("  }\n");
        sb.append("]\n\n");

        sb.append("**内容要求：**\n");
        sb.append("1. **全中文写作**\n");
        sb.append("2. **剧本内容长度要求**：每分钟时长需要 200-250字 的详细剧本内容\n");
        sb.append("3. **内容结构要求**：\n");
        sb.append("   - 场景描述：详细的环境描写、光影氛围、空间布局\n");
        sb.append("   - 肢体动作：角色的身体姿势、动作细节、位置移动\n");
        sb.append("   - 表情细节：眼神、微表情、情绪变化\n");
        sb.append("   - 精彩对白：符合角色性格的对话\n");
        sb.append("   - 情感描写：内心活动、情感转变\n");

        return sb.toString();
    }

    /**
     * 构建分集生成的用户提示词
     */
    public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                                String globalItems, String previousSummary, int splitCount,
                                                int duration, String modificationSuggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("剧本大纲全文：\n");
        sb.append(outline).append("\n\n");

        sb.append("目标章节：").append(chapter).append("\n");
        sb.append("拆分集数：").append(splitCount).append("\n");
        sb.append("单集时长参考：").append(duration).append(" 分钟\n");

        if (modificationSuggestion != null && !modificationSuggestion.isEmpty()) {
            sb.append("\n修改建议：").append(modificationSuggestion).append("\n");
        }

        sb.append("\n=== 全局角色设定 ===\n");
        sb.append(globalCharacters).append("\n\n");

        sb.append("=== 全局物品设定 ===\n");
        sb.append(globalItems).append("\n\n");

        sb.append("=== 前序剧集摘要（用于保持连贯性）===\n");
        sb.append(previousSummary).append("\n");

        return sb.toString();
    }

    /**
     * 剧本参数内部类
     */
    public static class ScriptParams {
        public final int chapterCount;
        public final int episodesPerChapter;
        public final int minCharacters;
        public final int maxCharacters;
        public final int minItems;
        public final int maxItems;
        public final boolean isSingleEpisode; // 是否为单集模式

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

        // 兼容旧代码的构造函数
        public ScriptParams(int chapterCount, int episodesPerChapter, int minCharacters,
                           int maxCharacters, int minItems, int maxItems) {
            this(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems, false);
        }
    }

    // ================= 单集/多集模式私有方法 =================

    /**
     * 构建单集模式的提示词
     */
    private String buildSingleEpisodePrompt(String genre, ScriptParams params) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专精于短剧和微电影的专业编剧。\n");
        sb.append("你的任务是根据用户的核心创意和约束条件，创作一部**单集完整剧本**。\n\n");

        sb.append("**核心原则：这是一部单集作品，请直接创作完整的单集剧本，无需章节规划**\n\n");

        sb.append("## 角色数量要求：").append(params.minCharacters).append("-").append(params.maxCharacters).append(" 个角色\n\n");
        sb.append("**角色分级与描述重点：**\n\n");
        sb.append("**A. 核心角色（2-4人）- 需要详细小传**\n");
        sb.append("- **主角团队**（1-2人）：故事的绝对核心\n");
        sb.append("- **核心反派/对手**（0-1人）：与主角对抗的力量\n");
        sb.append("描述要求（每个角色60-100字）\n\n");

        sb.append("**B. 重要配角（2-6人）- 简单描述**\n");
        sb.append("- 导师/盟友/中立角色等\n");
        sb.append("描述要求（每个角色15-30字）\n\n");

        sb.append("**C. 其他角色（剩余数量）- 一笔带过**\n");
        sb.append("- 群演、背景角色等\n");
        sb.append("描述要求（每个角色5-10字）\n\n");

        sb.append("### 物品数量要求：").append(params.minItems).append("-").append(params.maxItems).append(" 个关键物品\n\n");
        sb.append("**物品分级与描述：**\n\n");
        sb.append("**A. 核心物品（2-4个）- 推动剧情**\n");
        sb.append("描述要求（每个物品20-40字）\n\n");

        sb.append("**B. 辅助物品（3-6个）- 场景使用**\n");
        sb.append("描述要求（每个物品10-20字）\n\n");

        sb.append("**C. 世界物品（剩余数量）- 丰富设定**\n");
        sb.append("描述要求（每个物品8-15字）\n\n");

        sb.append("## 单集剧本结构要求\n\n");
        sb.append("### 核心原则：创作一个完整的单集故事，包含起承转合\n\n");
        sb.append("### 节奏要求（必须严格遵循）\n\n");
        sb.append("1. **开场**（15%）：快速建立场景、角色和冲突\n");
        sb.append("2. **发展**（50%）：冲突升级，角色面临挑战\n");
        sb.append("3. **高潮**（25%）：冲突达到顶点，做出关键选择\n");
        sb.append("4. **结尾**（10%）：解决冲突，给出结局或悬念\n\n");

        sb.append("## 输出格式要求 (必须严格遵守 Markdown 格式)\n\n");
        sb.append("# 剧名 (Title)\n");
        sb.append("**一句话梗概**: [一句话总结故事核心]\n");
        sb.append("**类型**: [").append(genre).append("] | **主题**: [主题] | **背景**: [故事背景] | **视觉风格**: [Visual Style]\n\n");
        sb.append("----\n\n");
        sb.append("## 主要人物小传\n\n");
        sb.append("### 核心角色（详细小传，60-100字/人）\n");
        sb.append("* **[姓名]**: [角色定位] - [年龄] [外貌特征]。性格：[性格特点]。背景：[重要经历]。\n\n");
        sb.append("### 重要配角（简单描述，15-30字/人）\n");
        sb.append("* **[姓名]**: [角色定位和作用，简短描述]\n\n");
        sb.append("### 其他角色（一笔带过，5-10字/人）\n");
        sb.append("* **[姓名]**: [身份或作用]\n\n");
        sb.append("----\n\n");
        sb.append("## 关键物品设定\n\n");
        sb.append("### 核心物品（20-40字/个）\n");
        sb.append("* **[物品名称]**: [物品描述、功能、象征意义]\n\n");
        sb.append("### 辅助物品（10-20字/个）\n");
        sb.append("* **[物品名称]**: [物品描述和出现时机]\n\n");
        sb.append("### 世界物品（8-15字/个）\n");
        sb.append("* **[物品名称]**: [简要描述]\n\n");
        sb.append("----\n\n");
        sb.append("## 单集剧本大纲\n\n");
        sb.append("**故事时长**：单集完整故事\n\n");
        sb.append("**核心冲突**：[本集的核心矛盾是什么]\n\n");
        sb.append("**故事走向**（150-200字）：\n");
        sb.append("[完整描述这个单集故事，包含起承转合]\n\n");
        sb.append("**关键场景**：\n");
        sb.append("- [开场场景]：[发生了什么，建立了什么]\n");
        sb.append("- [转折场景]：[什么事件改变了局势]\n");
        sb.append("- [高潮场景]：[最终的对决或选择]\n");
        sb.append("- [结局场景]：[如何结束，留下什么]\n\n");
        sb.append("**情感基调**：[整体的情感氛围]\n");
        sb.append("**主题表达**：[这个单集想要表达什么]\n");

        return sb.toString();
    }
}
