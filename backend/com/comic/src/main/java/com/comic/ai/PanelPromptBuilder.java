package com.comic.ai;

import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分镜 prompt 构建器
 * 两步生成：Step1 规划 panel 列表 → Step2 逐个细化 panel 详情
 */
@Component
public class PanelPromptBuilder {

    // ================= Step1: 分镜规划 =================

    public String buildPlanSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates, int episodeDuration) {
        StringBuilder sb = new StringBuilder();
        String genre = world != null && world.getGenre() != null ? world.getGenre() : "unknown";
        String worldRules = world != null && world.getRulesText() != null ? world.getRulesText() : "";

        sb.append("你是一名专业的").append(genre).append("漫画分镜规划师。\n\n");
        sb.append("## 世界观设定\n").append(worldRules).append("\n\n");
        sb.append("## 角色列表\n");
        if (charStates != null) {
            for (CharacterStateModel state : charStates) {
                if (state != null) {
                    sb.append(state.toPromptText()).append("\n");
                }
            }
        }

        sb.append("\n## 任务\n");
        sb.append("根据剧集内容，规划该集的分镜列表。只需要列出每个 panel 的概要信息，不需要详细描述。\n\n");

        sb.append("## 输出格式（仅返回 JSON）\n");
        sb.append("{\n");
        sb.append("  \"episode\": 集数编号,\n");
        sb.append("  \"title\": \"剧集标题\",\n");
        sb.append("  \"panels\": [\n");
        sb.append("    {\n");
        sb.append("      \"panel_id\": \"p1\",\n");
        sb.append("      \"scene_summary\": \"该画面要展示的内容概要（20-40字）\",\n");
        sb.append("      \"characters\": [\"角色名1\", \"角色名2\"],\n");
        sb.append("      \"mood\": \"情绪基调（紧张/温馨/悲伤/激烈等）\",\n");
        sb.append("      \"time_of_day\": \"时间（day/night/dusk/dawn）\",\n");
        sb.append("      \"duration\": 5\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        int minDuration = (int) Math.round(episodeDuration * 0.95);
        int maxDuration = (int) Math.round(episodeDuration * 1.05);

        sb.append("## 时长规划\n");
        sb.append("目标总时长：").append(episodeDuration).append(" 秒（可接受范围：").append(minDuration).append("~").append(maxDuration).append(" 秒）\n");
        sb.append("- 每个 panel 必须包含 duration 字段（整数秒，范围 1~16）\n");
        sb.append("- 所有 panel 的 duration 之和必须在 ").append(minDuration).append("~").append(maxDuration).append(" 秒之间\n");
        sb.append("- 根据内容重要性合理分配时长，重要场景分配更多时间\n");
        sb.append("- 规划完成后，请自行验证总和是否在范围内\n");
        return sb.toString();
    }

    public String buildPlanUserPrompt(int episodeNum, String episodeContent, String recentMemory, int episodeDuration) {
        StringBuilder sb = new StringBuilder();
        sb.append("为第 ").append(episodeNum).append(" 集规划分镜。\n\n");

        int minDuration = (int) Math.round(episodeDuration * 0.95);
        int maxDuration = (int) Math.round(episodeDuration * 1.05);

        sb.append("## 时长约束\n");
        sb.append("- 目标总时长：").append(episodeDuration).append(" 秒\n");
        sb.append("- 单 panel 时长范围：1~16 秒\n");
        sb.append("- 所有 panel 时长之和必须在 ").append(minDuration).append("~").append(maxDuration).append(" 秒之间\n\n");
        sb.append("## 剧集内容\n").append(episodeContent != null ? episodeContent : "").append("\n\n");
        sb.append("## 前情提要\n").append(recentMemory != null ? recentMemory : "无").append("\n");
        return sb.toString();
    }

    // ================= Step2: 逐 Panel 细化 =================

    public String buildPanelDetailSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates) {
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

        sb.append("\n## 任务\n");
        sb.append("根据 panel 概要，生成该 panel 的完整分镜描述。\n\n");

        sb.append("## 输出格式（仅返回单个 panel 的 JSON 对象）\n");
        sb.append("{\n");
        sb.append("  \"panel_id\": \"p1\",\n");
        sb.append("  \"shot_type\": \"WIDE_SHOT|MID_SHOT|CLOSE_UP|OVER_SHOULDER\",\n");
        sb.append("  \"camera_angle\": \"eye_level|low_angle|high_angle|bird_eye\",\n");
        sb.append("  \"composition\": \"构图描述（详细，50字以上）\",\n");
        sb.append("  \"pacing\": \"slow|normal|fast\",\n");
        sb.append("  \"duration\": 5,\n");
        sb.append("  \"image_prompt_hint\": \"用于 AI 图片生成的详细英文提示词（包含画面构图、角色外观、动作、表情、光影、氛围等，100字以上）\",\n");
        sb.append("  \"background\": {\n");
        sb.append("    \"scene_desc\": \"场景描述\",\n");
        sb.append("    \"time_of_day\": \"时间\",\n");
        sb.append("    \"atmosphere\": \"氛围描述\"\n");
        sb.append("  },\n");
        sb.append("  \"characters\": [\n");
        sb.append("    {\n");
        sb.append("      \"char_id\": \"角色ID\",\n");
        sb.append("      \"position\": \"center|left|right|far_left|far_right\",\n");
        sb.append("      \"pose\": \"standing|sitting|running|fighting 等\",\n");
        sb.append("      \"expression\": \"neutral|happy|angry|sad 等\",\n");
        sb.append("      \"costume_state\": \"normal|battle_worn\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"dialogue\": [\n");
        sb.append("    {\"speaker\": \"角色名\", \"text\": \"台词内容\", \"bubble_type\": \"speech|thought|narration_box\"}\n");
        sb.append("  ],\n");
        sb.append("  \"sfx\": [\"音效描述\"]\n");
        sb.append("}\n\n");

        sb.append("关键要求：\n");
        sb.append("- image_prompt_hint 必须足够详细，是后续 AI 生成图片的核心输入\n");
        sb.append("- composition 要描述画面布局、角色站位关系\n");
        sb.append("- 仅返回单个 panel 的 JSON，不要数组\n");
        return sb.toString();
    }

    public String buildPanelDetailUserPrompt(int episodeNum, int panelIndex, String panelPlan,
                                              String episodeContent, String previousPanelSummary,
                                              int plannedDuration, int remainingBudget, int remainingPanels) {
        StringBuilder sb = new StringBuilder();
        sb.append("为第 ").append(episodeNum).append(" 集的第 ").append(panelIndex).append(" 个 panel 生成详细分镜。\n\n");
        sb.append("## 该 Panel 概要\n").append(panelPlan).append("\n\n");
        sb.append("## 剧集内容（参考上下文）\n").append(episodeContent != null ? episodeContent : "").append("\n\n");
        if (previousPanelSummary != null && !previousPanelSummary.isEmpty()) {
            sb.append("## 前一个 Panel 摘要\n").append(previousPanelSummary).append("\n\n");
        }

        int minAdjusted = Math.max(1, plannedDuration - 2);
        int maxAdjusted = Math.min(16, plannedDuration + 2);

        sb.append("## 时长信息\n");
        sb.append("- 规划阶段预定时长：").append(plannedDuration).append(" 秒\n");
        sb.append("- 允许调整范围：").append(minAdjusted).append("~").append(maxAdjusted).append(" 秒\n");
        sb.append("- 该集剩余预算：").append(remainingBudget).append(" 秒（还有 ").append(remainingPanels).append(" 个 panel 待细化）\n");
        sb.append("- 在输出中包含 duration 字段，填写调整后的实际时长\n\n");
        sb.append("要求：\n");
        sb.append("- 根据概要生成完整的 panel 描述\n");
        sb.append("- image_prompt_hint 要包含足够的视觉细节用于图片生成\n");
        sb.append("- 仅输出单个 panel 的 JSON 对象\n");
        return sb.toString();
    }

    // ================= 修改/重试 =================

    public String buildRevisionUserPrompt(int episodeNum, String outlineNode, String recentMemory,
                                           String currentPanelJson, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("修改第 ").append(episodeNum).append(" 集的分镜。\n\n");
        sb.append("## 剧集大纲\n").append(outlineNode != null ? outlineNode : "").append("\n\n");
        sb.append("## 前情提要\n").append(recentMemory != null ? recentMemory : "").append("\n\n");
        sb.append("## 当前分镜\n").append(currentPanelJson != null ? currentPanelJson : "{}").append("\n\n");
        sb.append("## 修改意见\n").append(feedback != null ? feedback : "").append("\n\n");
        sb.append("要求：\n");
        sb.append("- 根据反馈意见进行增量修改\n");
        sb.append("- 保持未修改部分的连贯性\n");
        sb.append("- 严格遵循系统输出格式要求\n");
        sb.append("- 仅输出完整的修改后 JSON\n");
        return sb.toString();
    }

    public String addRetryConstraints(String systemPrompt, int attempt) {
        if (attempt == 2) {
            return systemPrompt
                    + "\n\n重要重试规则（第 2 次尝试）：\n"
                    + "1. 仅返回 JSON。\n"
                    + "2. 包含所有必需字段。\n"
                    + "3. 每个角色对象必须包含 char_id、position、pose、expression、costume_state。\n"
                    + "4. 如果 panel 没有可见角色，将 characters 设为空数组。\n"
                    + "5. 每条对话对象必须包含 speaker、text、bubble_type。\n"
                    + "6. 保持枚举值在允许的集合内。";
        } else if (attempt >= 3) {
            return systemPrompt
                    + "\n\n最后警告（第 3 次尝试）：\n"
                    + "输出必须是以 '{' 开头、'}' 结尾的有效 JSON 对象。\n"
                    + "不允许有任何额外文本。\n"
                    + "不要省略必需嵌套字段。";
        }
        return systemPrompt;
    }
}
