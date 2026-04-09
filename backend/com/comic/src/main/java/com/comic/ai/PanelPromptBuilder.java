package com.comic.ai;

import com.comic.util.NumberFormatter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 分镜 prompt 构建器
 * 新流程：九宫格生成 + 多镜头视频生成
 */
@Component
public class PanelPromptBuilder {

    private static final Set<String> FLASHBACK_KEYWORDS = new HashSet<>(Arrays.asList(
        "回忆", "闪回", "往事", "过去", "memory", "flashback", "past"
    ));

    private static final String[] FLASHBACK_FLAG_KEYS = {
        "isFlashback", "flashback", "is_flashback", "isMemory", "is_memory", "memory"
    };

    private static final String[] FLASHBACK_TEXT_KEYS = {
        "timeType", "time_type", "timelineTag", "timeline_tag",
        "sceneTime", "scene_time", "scene", "visualDescription",
        "visual_description", "transitionHint", "transition_hint",
        "dialogue", "narration"
    };

    // ================= 生产阶段：九宫格 / 视频 =================

    /**
     * 场景风格前缀（纯风格，不含具体场景内容）
     */
    public String buildSceneStylePrefix(CharacterPromptManager.VisualStyle style) {
        switch (style) {
            case REAL:
                return "写实风格，电影级摄影质感，8K超高清分辨率，专业摄影级别，" +
                       "自然光效，体积光，柔和阴影，景深效果，色彩真实。" +
                       "人物默认为东亚面孔特征，除非角色设定中明确描述了其他种族外貌。";
            case D_3D:
                return "3D渲染风格，Octane渲染，光线追踪，全局光照，8K超高清分辨率，" +
                       "影棚灯光，HDRI环境光，环境光遮蔽，PBR材质质感。" +
                       "人物默认为东亚面孔特征，除非角色设定中明确描述了其他种族外貌。";
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
     * 场景风格前缀（String 重载）
     */
    public String buildSceneStylePrefix(String visualStyle) {
        try {
            return buildSceneStylePrefix(CharacterPromptManager.VisualStyle.fromCode(visualStyle));
        } catch (Exception e) {
            return buildSceneStylePrefix(CharacterPromptManager.VisualStyle.ANIME);
        }
    }

    /**
     * 构建九宫格图片生成提示词（含角色描述锚定）
     * @param visualStyle 风格
     * @param shots 分镜列表
     * @param charRefs 角色参考（含物种、外貌等描述），可为 null
     */
    public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                                   List<?> charRefs) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildSceneStylePrefix(visualStyle));
        sb.append("专业动画关键帧级别，电影级画面构图，精致光影与色彩。\n\n");

        // ===== 布局要求 =====
        sb.append("【布局要求 - 必须严格遵守】\n");
        sb.append("输出一张严格 3×3 九宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。\n");
        sb.append("图片必须被 2 条黑色竖线（约 4px 宽）和 2 条黑色横线（约 4px 宽）均匀分割为 3 行 3 列，共 9 个等大的格子。\n");
        sb.append("每个格子是一个完全独立的分镜画面，场景、人物、时间可以不同。\n");
        sb.append("绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。\n");
        sb.append("图片中不包含任何文字、数字、标号或水印。\n\n");
        sb.append("【时间态标识】若分镜属于回忆/闪回（字段标记或描述语义显示为回忆），该格必须使用柔和虚化边框/暗角区分时间线；非回忆格禁止使用该效果。\n\n");

        // ===== 角色锚定 =====
        if (charRefs != null && !charRefs.isEmpty()) {
            sb.append("【角色设定 - 必须严格遵守】\n");
            sb.append("只允许绘制以下角色，绝对不要出现列表之外的角色、路人或背景人物。\n");
            sb.append("每个角色在不同格子中必须保持外貌、体型比例、服装、发型完全一致。\n\n");

            for (Object refObj : charRefs) {
                String name = null;
                String species = null;
                String appearance = null;
                String role = null;
                // 兼容 CharRef 对象和纯 String
                if (refObj instanceof com.comic.service.panel.GridImageService.CharRef) {
                    com.comic.service.panel.GridImageService.CharRef cr =
                            (com.comic.service.panel.GridImageService.CharRef) refObj;
                    name = cr.name;
                    species = cr.species;
                    appearance = cr.appearance;
                    role = cr.role;
                }
                if (name == null || name.isEmpty()) continue;

                sb.append("- ").append(name);
                if (role != null && !role.isEmpty()) {
                    sb.append("（").append(role).append("）");
                }
                if (species != null && !species.isEmpty()) {
                    sb.append("：物种=").append(species);
                    // 针对拟人化物种，强调保持拟人形态
                    if (species.contains("拟人") || species.contains("ANTHRO")) {
                        sb.append("，始终为拟人化形态（直立行走、人形身体比例、兽耳兽尾等特征，非四足野兽形态）");
                    }
                }
                if (appearance != null && !appearance.isEmpty()) {
                    sb.append("，外貌特征: ").append(appearance);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ===== 分镜内容 =====
        sb.append("【分镜内容 - 按从左到右、从上到下填入九宫格，每个格子必须是精致的关键帧画面】\n");
        sb.append("每个分镜必须包含：完整的场景环境细节（光影、色调、空间纵深）、角色的精确外貌与服装、");
        sb.append("细腻的面部表情和肢体语言、精心设计的构图与景深关系。画面要有电影级质感。\n\n");
        for (int i = 0; i < shots.size(); i++) {
            Map<String, Object> shot = shots.get(i);
            int row = i / 3 + 1;
            int col = i % 3 + 1;
            sb.append("第").append(row).append("行第").append(col).append("列: ");
            String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
            sb.append(visualDescription != null ? visualDescription : "");
            String shotSize = getShotValue(shot, "shotSize", "shot_size");
            if (shotSize != null && !shotSize.isEmpty()) {
                sb.append("，").append(shotSize);
            }
            String cameraAngle = getShotValue(shot, "cameraAngle", "camera_angle");
            if (cameraAngle != null && !cameraAngle.isEmpty()) {
                sb.append("，").append(cameraAngle);
            }
            String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
            if (cameraMovement != null && !cameraMovement.isEmpty()) {
                sb.append("，").append(cameraMovement);
            }
            String scene = getShotValue(shot, "scene");
            if (scene != null && !scene.isEmpty()) {
                sb.append("，场景: ").append(scene);
            }
            if (isFlashbackShot(shot)) {
                sb.append("，回忆镜头（需添加柔和虚化边框作为时间标识）");
            }
            String imageHint = getShotValue(shot, "image_prompt_hint");
            if (imageHint != null && !imageHint.isEmpty()) {
                sb.append("，画面补充提示: ").append(imageHint);
            }
            sb.append("\n");
        }

        int emptySlots = 9 - shots.size();
        if (emptySlots > 0) {
            sb.append("剩余 ").append(emptySlots).append(" 个格子留空（纯黑色填充，不绘制任何内容）。\n\n");
        }

        // ===== 负面提示词 =====
        sb.append("负面提示词：文字、水印、标签、签名、人体结构错误、肢体融合、多余手指、多余肢体、");
        sb.append("面部变形、眼睛异常、模糊、低质量、色块 artefact、粗糙线条、草稿感。");
        return sb.toString();
    }

    /**
     * 构建多镜头视频生成提示词
     */
    @SuppressWarnings("unchecked")
    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo) {
        return buildMultiShotPrompt(visualStyle, panelInfo, null);
    }

    /**
     * 构建多镜头视频生成提示词（含角色设定）
     */
    @SuppressWarnings("unchecked")
    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo,
                                        List<Map<String, String>> characterInfos) {
        return buildMultiShotPrompt(visualStyle, panelInfo, characterInfos, null);
    }

    /**
     * 构建多镜头视频生成提示词（含角色设定 + 前一个面板上下文）
     */
    @SuppressWarnings("unchecked")
    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo,
                                        List<Map<String, String>> characterInfos,
                                        Map<String, Object> previousPanelLastShot) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildSceneStylePrefix(visualStyle));
        sb.append(" 专业电影级画面。\n\n");

        // 前一个面板的承接上下文
        if (previousPanelLastShot != null && !previousPanelLastShot.isEmpty()) {
            sb.append("## 承接上一段画面\n");
            sb.append("此视频必须从上一段画面自然衔接开始。上一段画面结束时的状态：\n");
            String prevScene = (String) previousPanelLastShot.get("scene");
            if (prevScene != null && !prevScene.isEmpty()) {
                sb.append("- 结束场景：").append(prevScene).append("\n");
            }
            String prevDesc = (String) previousPanelLastShot.get("visualDescription");
            if (prevDesc != null && !prevDesc.isEmpty()) {
                sb.append("- 画面状态：").append(prevDesc).append("\n");
            }
            String prevCamera = (String) previousPanelLastShot.get("cameraMovement");
            if (prevCamera != null && !prevCamera.isEmpty()) {
                sb.append("- 结束运镜：").append(prevCamera).append("\n");
            }
            String prevTransition = (String) previousPanelLastShot.get("transitionHint");
            if (prevTransition != null && !"无".equals(prevTransition) && !prevTransition.contains("最后一个镜头")) {
                sb.append("- 衔接方式：").append(prevTransition).append("\n");
            }
            sb.append("请确保本段视频的开头在画面内容、角色位置、情绪氛围上与上述结束状态保持连贯。\n\n");
        }

        // 角色设定段
        if (characterInfos != null && !characterInfos.isEmpty()) {
            sb.append("## 角色设定\n");
            for (Map<String, String> ci : characterInfos) {
                sb.append("- 【").append(ci.getOrDefault("name", ""));
                String voice = ci.get("voice");
                if (voice != null && !voice.isEmpty()) {
                    sb.append("】声音：").append(voice);
                } else {
                    sb.append("】");
                }
                String appearance = ci.get("appearance");
                if (appearance != null && !appearance.isEmpty()) {
                    sb.append("，外貌：").append(appearance);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
        int n = shots != null ? shots.size() : 0;
        sb.append("多镜头连续拍摄指令，以下 ").append(n).append(" 个镜头必须在同一视频中连续呈现：\n\n");
                sb.append("若某个镜头属于回忆/闪回，必须仅在该镜头画面边缘加入柔和虚化边框/暗角作为时间态标识；现实时间镜头保持清晰边缘。\n\n");

        if (shots != null) {
            for (int i = 0; i < shots.size(); i++) {
                Map<String, Object> shot = shots.get(i);
                sb.append("【镜头").append(i + 1).append("】\n");
                sb.append("duration: ").append(shot.get("duration")).append("s\n");
                                String shotSize = getShotValue(shot, "shotSize", "shot_size");
                                String cameraAngle = getShotValue(shot, "cameraAngle", "camera_angle");
                                String cameraMovement = getShotValue(shot, "cameraMovement", "camera_movement");
                                String visualDescription = getShotValue(shot, "visualDescription", "visual_description");
                                sb.append("Scene: ").append(shotSize != null ? shotSize : "")
                                    .append("，").append(cameraAngle != null ? cameraAngle : "")
                                    .append("，").append(cameraMovement != null ? cameraMovement : "")
                                    .append("，").append(visualDescription != null ? visualDescription : "").append("\n");

                                if (isFlashbackShot(shot)) {
                                        sb.append("时间态: 回忆/闪回镜头，需添加柔和虚化边框（仅该镜头生效）\n");
                                }

                                String videoHint = getShotValue(shot, "video_prompt_hint");
                                if (videoHint != null && !videoHint.isEmpty()) {
                                        sb.append("补充画面要求: ").append(videoHint).append("\n");
                                }

                String dialogue = (String) shot.get("dialogue");
                if (dialogue != null && !"无".equals(dialogue) && !dialogue.isEmpty()) {
                    String speaker = (String) shot.get("speaker");
                    String tone = (String) shot.get("dialogueTone");
                    sb.append("对白");
                    if (speaker != null && !"无".equals(speaker) && !speaker.isEmpty()) {
                        sb.append("(").append(speaker);
                        if (tone != null && !"无".equals(tone) && !tone.isEmpty()) {
                            sb.append("，").append(tone);
                        }
                        sb.append(")");
                    } else if (tone != null && !"无".equals(tone) && !tone.isEmpty()) {
                        sb.append("(").append(tone).append(")");
                    }
                    sb.append(": ").append(dialogue).append("\n");
                }
                String audioEffects = (String) shot.get("audioEffects");
                if (audioEffects != null && !"无".equals(audioEffects)) {
                    sb.append("音效: [").append(audioEffects).append("]\n");
                }
                // 镜头衔接提示（非最后一个镜头时输出）
                String transition = (String) shot.get("transitionHint");
                if (transition != null && !"无".equals(transition) && !transition.isEmpty()
                        && !transition.contains("最后一个镜头") && i < shots.size() - 1) {
                    sb.append("衔接: ").append(transition).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("## 画面衔接\n");
        if (previousPanelLastShot != null && !previousPanelLastShot.isEmpty()) {
            sb.append("视频开头必须与上一段画面的结束状态自然衔接，保持角色位置、动作和情绪的连贯。\n");
        }
        sb.append("多镜头间必须平滑过渡，严格遵循每个镜头的衔接提示。\n");
        sb.append("保持角色位置、动作、表情和情绪的连贯性。\n");
        StringBuilder refBuilder = new StringBuilder("参考图中编号");
        for (int i = 0; i < n; i++) {
            refBuilder.append(NumberFormatter.toCircled(i + 1));
        }
        refBuilder.append("分别对应");
        for (int i = 0; i < n; i++) {
            if (i > 0) refBuilder.append("、");
            refBuilder.append("【镜头").append(i + 1).append("】");
        }
        refBuilder.append("的画面内容。");
        sb.append(refBuilder.toString());

        // ===== 负面提示词 =====
        sb.append("\n\n## 负面提示词（严格遵守，违反任何一条即为失败）\n");
        sb.append("文字、水印、签名、logo、人体结构错误、肢体融合、多余手指、多余肢体、");
        sb.append("面部变形、眼睛异常、模糊、闪烁、低质量、色块 artefact。\n");
        sb.append("禁止两人以上同框互动（拥抱、打斗、接触），多人互动必须拆分为单人反应镜头。\n");
        sb.append("禁止快速奔跑、剧烈运动、突然变向——镜头运动必须缓慢（缓慢推镜头、微平移、静止），用剪辑快切体现激烈而非画面快动。\n");
        return sb.toString();
    }

    private boolean isFlashbackShot(Map<String, Object> shot) {
        if (shot == null || shot.isEmpty()) {
            return false;
        }

        for (String key : FLASHBACK_FLAG_KEYS) {
            if (isTrueFlag(shot.get(key))) {
                return true;
            }
        }

        for (String key : FLASHBACK_TEXT_KEYS) {
            if (containsFlashbackKeyword(shot.get(key))) {
                return true;
            }
        }

        return false;
    }

    private boolean containsFlashbackKeyword(Object value) {
        if (value == null) {
            return false;
        }
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String keyword : FLASHBACK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrueFlag(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        String normalized = normalize(value);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized)
                || "是".equals(normalized);
    }

    private String getShotValue(Map<String, Object> shot, String... keys) {
        if (shot == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            Object value = shot.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return null;
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().toLowerCase(Locale.ROOT).trim();
    }
}
