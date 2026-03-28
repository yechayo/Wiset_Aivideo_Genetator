package com.comic.ai;

import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜 prompt 构建器
 * 两步生成：Step1 规划 panel 列表 → Step2 逐个细化 panel 详情
 * 生产阶段：背景图 / 四宫格漫画 / 视频的 prompt 构建
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
        sb.append("      \"duration\": 10\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        int minDuration = (int) Math.round(episodeDuration * 0.95);
        int maxDuration = (int) Math.round(episodeDuration * 1.05);

        sb.append("## 时长规划\n");
        sb.append("目标总时长：").append(episodeDuration).append(" 秒（可接受范围：").append(minDuration).append("~").append(maxDuration).append(" 秒）\n");
        sb.append("- 每个 panel 必须包含 duration 字段（整数秒，范围 10~16）\n");
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
        sb.append("- 单 panel 时长范围：10~16 秒\n");
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

        sb.append("## 面板衔接规则\n");
        sb.append("- 你必须确保每个面板的画面从上一个面板的结尾状态自然延续\n");
        sb.append("- 角色位置、动作、表情必须与上一面板的结尾状态保持一致\n");
        sb.append("- 场景转换必须有合理的过渡（如角色移动到新位置需要中间过程）\n\n");

        sb.append("## 输出格式（仅返回单个 panel 的 JSON 对象）\n");
        sb.append("{\n");
        sb.append("  \"panel_id\": \"p1\",\n");
        sb.append("  \"shot_type\": \"WIDE_SHOT|MID_SHOT|CLOSE_UP|OVER_SHOULDER\",\n");
        sb.append("  \"camera_angle\": \"eye_level|low_angle|high_angle|bird_eye\",\n");
        sb.append("  \"composition\": \"构图描述（详细，50字以上）\",\n");
        sb.append("  \"pacing\": \"slow|normal|fast\",\n");
        sb.append("  \"duration\": 10,\n");
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
            sb.append("## 前一个 Panel 摘要\n").append(previousPanelSummary).append("\n");
            sb.append("请基于上一面板的结尾状态设计本面板的起始画面，确保画面连贯\n\n");
        }

        int minAdjusted = Math.max(10, plannedDuration - 2);
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

    // ================= 生产阶段：背景图 / 四宫格 / 视频 =================

    /**
     * 场景风格前缀（纯风格，不含具体场景内容）
     */
    public String buildSceneStylePrefix(CharacterPromptManager.VisualStyle style) {
        switch (style) {
            case REAL:
                return "写实风格，电影级摄影质感，8K超高清分辨率，专业摄影级别，" +
                       "自然光效，体积光，柔和阴影，景深效果，色彩真实。";
            case D_3D:
                return "3D渲染风格，Octane渲染，光线追踪，全局光照，8K超高清分辨率，" +
                       "影棚灯光，HDRI环境光，环境光遮蔽，PBR材质质感。";
            case ANIME:
            case MANGA:
                return "日系动漫风格，动漫背景艺术，高质量，杰作级别，精细插画，" +
                       "柔光效果，轮廓光，色彩鲜艳丰富，干净线条，清晰轮廓。";
            case INK:
                return "中国水墨画风格，水墨写意，高质量，杰作级别，精细插画，" +
                       "柔光效果，意境深远，墨色浓淡有致。";
            case CYBERPUNK:
                return "赛博朋克动漫风格，霓虹灯光，未来感，高质量，杰作级别，精细插画，" +
                       "柔光效果，轮廓光，色彩鲜艳丰富，暗色调对比。";
            default:
                return "高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。";
        }
    }

    /**
     * 构建背景图提示词（纯背景，无角色，中文）
     * 结构：风格前缀 + 场景描述 + 背景专用指令
     */
    public String buildBackgroundPrompt(CharacterPromptManager.VisualStyle style, Map<String, Object> panelInfo) {
        if (panelInfo == null) return "";

        String stylePrefix = buildSceneStylePrefix(style);

        // 场景内容
        String sceneDesc = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> bg = (Map<String, Object>) panelInfo.get("background");
        if (bg != null) {
            sceneDesc = getStr(bg, "scene_desc");
        }
        String timeOfDay = bg != null ? getStr(bg, "time_of_day") : null;
        String atmosphere = bg != null ? getStr(bg, "atmosphere") : null;

        if (sceneDesc == null || sceneDesc.isEmpty()) {
            sceneDesc = getStr(panelInfo, "sceneDescription");
        }
        if (sceneDesc == null || sceneDesc.isEmpty()) return "";

        StringBuilder prompt = new StringBuilder();
        prompt.append(stylePrefix);
        prompt.append(sceneDesc);

        if (atmosphere != null && !atmosphere.isEmpty()) {
            prompt.append("，").append(atmosphere).append("的氛围");
        }
        if (timeOfDay != null && !timeOfDay.isEmpty()) {
            prompt.append("，").append(timeOfDay).append("时分的自然光效");
        }

        prompt.append("。纯场景背景，不包含任何人物角色。广角横屏构图，电影级景深，画面层次分明。");

        return prompt.toString();
    }

    /**
     * 构建四宫格漫画提示词（向后兼容，无角色信息）
     */
    public String buildComicPrompt(Map<String, Object> panelInfo) {
        return buildComicPrompt(panelInfo, new HashMap<>(), null);
    }

    /**
     * 构建四宫格漫画提示词（无剧情简介）
     */
    public String buildComicPrompt(Map<String, Object> panelInfo, Map<String, Map<String, String>> charDescriptions) {
        return buildComicPrompt(panelInfo, charDescriptions, null);
    }

    /**
     * 构建四宫格漫画提示词（增强版：剧情上下文 + 角色参考图映射）
     *
     * @param panelInfo        分镜信息
     * @param charDescriptions 角色描述 Map<charId, Map<"name"|"appearance"|"imageIndex"|"role", ...>>
     * @param episodeSynopsis  剧情简介（可为 null）
     */
    public String buildComicPrompt(Map<String, Object> panelInfo, Map<String, Map<String, String>> charDescriptions, String episodeSynopsis) {
        if (panelInfo == null) return "";
        if (charDescriptions == null) charDescriptions = new HashMap<>();

        StringBuilder prompt = new StringBuilder();

        // 专业角色前缀
        prompt.append("你是一个专业的漫画家，请根据以下分镜描述生成高质量的四宫格漫画。");

        // 0. 剧情背景（可选）
        if (episodeSynopsis != null && !episodeSynopsis.isEmpty()) {
            prompt.append("【剧情背景】").append(episodeSynopsis);
        }

        // 0.5 参考图说明（仅在有角色参考图时添加）
        boolean hasCharImages = charDescriptions.values().stream()
                .anyMatch(d -> d.get("imageIndex") != null);
        if (hasCharImages) {
            prompt.append("【参考图说明】");
            prompt.append("图1：场景背景参考图。");
            for (Map.Entry<String, Map<String, String>> entry : charDescriptions.entrySet()) {
                Map<String, String> desc = entry.getValue();
                String imageIndex = desc.get("imageIndex");
                String name = desc.get("name");
                String role = desc.get("role");
                if (imageIndex != null && name != null) {
                    prompt.append("图").append(imageIndex).append("：角色\"").append(name).append("\"的参考图");
                    if (role != null && !role.isEmpty()) {
                        prompt.append("（").append(role).append("）");
                    }
                    prompt.append("，请严格按照该参考图中的角色外貌来绘制该角色。");
                }
            }
        }

        // 1. 构图描述
        String composition = getStr(panelInfo, "composition");
        if (composition != null && !composition.isEmpty()) {
            prompt.append("【构图描述】").append(composition).append("。");
        }

        // 2. 场景描述 + 氛围 + 时间
        @SuppressWarnings("unchecked")
        Map<String, Object> bg = (Map<String, Object>) panelInfo.get("background");
        if (bg != null) {
            String sceneDesc = getStr(bg, "scene_desc");
            if (sceneDesc == null || sceneDesc.isEmpty()) {
                sceneDesc = getStr(panelInfo, "sceneDescription");
            }
            if (sceneDesc != null && !sceneDesc.isEmpty()) {
                prompt.append("【场景描述】").append(sceneDesc).append("。");
            }
            String atmosphere = getStr(bg, "atmosphere");
            if (atmosphere != null && !atmosphere.isEmpty()) {
                prompt.append("【氛围】").append(atmosphere).append("。");
            }
            String timeOfDay = getStr(bg, "time_of_day");
            if (timeOfDay != null && !timeOfDay.isEmpty()) {
                prompt.append("【时间】").append(timeOfDay).append("。");
            }
        }

        // 3. 镜头语言
        String shotType = getStr(panelInfo, "shot_type");
        String cameraAngle = getStr(panelInfo, "camera_angle");

        if (shotType != null) {
            switch (shotType) {
                case "WIDE_SHOT": prompt.append("【镜头类型】远景，展示完整场景。"); break;
                case "MID_SHOT": prompt.append("【镜头类型】中景，聚焦角色半身。"); break;
                case "CLOSE_UP": prompt.append("【镜头类型】特写，聚焦面部细节。"); break;
                case "OVER_SHOULDER": prompt.append("【镜头类型】过肩镜头。"); break;
                default: break;
            }
        }

        if (cameraAngle != null) {
            switch (cameraAngle) {
                case "eye_level": prompt.append("【镜头角度】平视角度。"); break;
                case "low_angle": prompt.append("【镜头角度】低角度仰拍。"); break;
                case "high_angle": prompt.append("【镜头角度】高角度俯拍。"); break;
                case "bird_eye": prompt.append("【镜头角度】鸟瞰俯视视角。"); break;
                default: break;
            }
        }

        // 4. 角色表演（名称 + 外貌 + 姿态 + 位置 + 表情）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) panelInfo.get("characters");
        if (characters != null && !characters.isEmpty()) {
            prompt.append("【角色表演】");
            for (Map<String, Object> ch : characters) {
                String charId = ch.get("char_id") != null ? ch.get("char_id").toString() : null;

                // 从 charDescriptions 获取角色名称和外貌
                String name = null;
                String appearance = null;
                if (charId != null && charDescriptions != null) {
                    Map<String, String> desc = charDescriptions.get(charId);
                    if (desc != null) {
                        name = desc.get("name");
                        appearance = desc.get("appearance");
                    }
                }

                String pose = ch.get("pose") != null ? ch.get("pose").toString() : null;
                String position = ch.get("position") != null ? ch.get("position").toString() : null;
                String expression = ch.get("expression") != null ? ch.get("expression").toString() : null;
                String costumeState = ch.get("costume_state") != null ? ch.get("costume_state").toString() : null;

                if (name != null) prompt.append(name);
                if (appearance != null) prompt.append("（").append(appearance).append("）");
                if (pose != null) prompt.append("，").append(pose);
                if (position != null) prompt.append("，位置：").append(position);
                if (expression != null) prompt.append("，").append(expression).append("表情");
                if (costumeState != null && !"normal".equals(costumeState)) {
                    prompt.append("，服装状态：").append(costumeState);
                }
                prompt.append("；");
            }
        }

        // 5. 对话台词
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dialogue = (List<Map<String, Object>>) panelInfo.get("dialogue");
        if (dialogue != null && !dialogue.isEmpty()) {
            prompt.append("【对话台词】");
            for (Map<String, Object> d : dialogue) {
                String speaker = d.get("speaker") != null ? d.get("speaker").toString() : null;
                String text = d.get("text") != null ? d.get("text").toString() : null;
                if (speaker != null && text != null) {
                    prompt.append(speaker).append("：\"").append(text).append("\"；");
                } else if (text != null) {
                    prompt.append("\"").append(text).append("\"；");
                }
            }
        }

        // 6. 画面细节参考（LLM 生成的详细英文提示词）
        String imagePromptHint = getStr(panelInfo, "image_prompt_hint");
        if (imagePromptHint != null && !imagePromptHint.isEmpty()) {
            prompt.append("【画面细节参考】").append(imagePromptHint);
        }

        // 7. 四宫格布局指令 + 风格指令 + 无文字说明
        prompt.append("保持参考图的背景风格和场景布局不变。");
        prompt.append("生成2行2列四宫格漫画，共4个连续分镜画面，每个格子标注序号(1,2,3,4)。");
        prompt.append("高质量动漫风格，画面精细。");
        prompt.append("图片中不要有任何文字或字幕。");

        return prompt.toString();
    }

    /**
     * 构建视频生成提示词（中文）
     * 结构：风格前缀 + 画面内容 + 镜头语言 + 角色动作
     */
    public String buildVideoPrompt(CharacterPromptManager.VisualStyle style, Map<String, Object> panelInfo) {
        if (panelInfo == null) return "";

        String stylePrefix = buildSceneStylePrefix(style);
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个专业的视频导演，请根据以下分镜描述生成高质量的视频。");

        // 构图描述
        String composition = getStr(panelInfo, "composition");
        if (composition != null && !composition.isEmpty()) {
            prompt.append("【构图描述】").append(composition).append("。");
        }

        // 场景描述 + 氛围 + 时间
        @SuppressWarnings("unchecked")
        Map<String, Object> bgVideo = (Map<String, Object>) panelInfo.get("background");
        if (bgVideo != null) {
            String sceneDesc = getStr(bgVideo, "scene_desc");
            if (sceneDesc == null || sceneDesc.isEmpty()) {
                sceneDesc = getStr(panelInfo, "sceneDescription");
            }
            if (sceneDesc != null && !sceneDesc.isEmpty()) {
                prompt.append("【场景描述】").append(sceneDesc).append("。");
            }
            String atmosphere = getStr(bgVideo, "atmosphere");
            if (atmosphere != null && !atmosphere.isEmpty()) {
                prompt.append("【氛围】").append(atmosphere).append("。");
            }
            String timeOfDay = getStr(bgVideo, "time_of_day");
            if (timeOfDay != null && !timeOfDay.isEmpty()) {
                prompt.append("【时间】").append(timeOfDay).append("。");
            }
        }

        // 镜头语言
        String shotType = getStr(panelInfo, "shot_type");
        String cameraAngle = getStr(panelInfo, "camera_angle");
        String pacing = getStr(panelInfo, "pacing");

        if (shotType != null) {
            switch (shotType) {
                case "WIDE_SHOT": prompt.append("【镜头类型】远景，展示完整场景。"); break;
                case "MID_SHOT": prompt.append("【镜头类型】中景，聚焦角色半身。"); break;
                case "CLOSE_UP": prompt.append("【镜头类型】特写，聚焦面部细节。"); break;
                case "OVER_SHOULDER": prompt.append("【镜头类型】过肩镜头。"); break;
                default: break;
            }
        }

        if (cameraAngle != null) {
            switch (cameraAngle) {
                case "eye_level": prompt.append("【镜头角度】平视角度。"); break;
                case "low_angle": prompt.append("【镜头角度】低角度仰拍。"); break;
                case "high_angle": prompt.append("【镜头角度】高角度俯拍。"); break;
                case "bird_eye": prompt.append("【镜头角度】鸟瞰俯视视角。"); break;
                default: break;
            }
        }

        if (pacing != null) {
            switch (pacing) {
                case "slow": prompt.append("【运动节奏】缓慢、从容的运动节奏。"); break;
                case "fast": prompt.append("【运动节奏】快速、充满动感的运动节奏。"); break;
                default: prompt.append("【运动节奏】自然平稳的运动节奏。"); break;
            }
        }

        // 角色动作
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) panelInfo.get("characters");
        if (characters != null && !characters.isEmpty()) {
            prompt.append("【角色表演】");
            for (Map<String, Object> ch : characters) {
                String pose = ch.get("pose") != null ? ch.get("pose").toString() : null;
                String expression = ch.get("expression") != null ? ch.get("expression").toString() : null;
                String position = ch.get("position") != null ? ch.get("position").toString() : null;
                if (pose != null) prompt.append(pose);
                if (expression != null) prompt.append("，").append(expression).append("表情");
                if (position != null) prompt.append("，位置：").append(position);
                prompt.append("；");
            }
        }

        // 对话
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dialogue = (List<Map<String, Object>>) panelInfo.get("dialogue");
        if (dialogue != null && !dialogue.isEmpty()) {
            prompt.append("【对话台词】");
            for (Map<String, Object> d : dialogue) {
                String speaker = getStr(d, "speaker");
                String text = getStr(d, "text");
                if (speaker != null && !speaker.isEmpty()) prompt.append(speaker).append("：");
                if (text != null) prompt.append(text);
                prompt.append("；");
            }
        }

        // 音效
        @SuppressWarnings("unchecked")
        List<String> sfx = (List<String>) panelInfo.get("sfx");
        if (sfx != null && !sfx.isEmpty()) {
            prompt.append("【音效设计】");
            for (String s : sfx) {
                if (s != null && !s.isEmpty()) prompt.append(s).append("、");
            }
            // 去掉末尾的顿号
            if (prompt.charAt(prompt.length() - 1) == '、') {
                prompt.setLength(prompt.length() - 1);
            }
            prompt.append("。");
        }

        // 画面提示词
        String imagePromptHint = getStr(panelInfo, "image_prompt_hint");
        if (imagePromptHint != null && !imagePromptHint.isEmpty()) {
            prompt.append("【画面细节参考】").append(imagePromptHint);
        }

        prompt.append(stylePrefix);
        prompt.append("流畅的动画效果，自然的镜头运动。");

        return prompt.toString();
    }

    // ================= 辅助方法 =================

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }
}
