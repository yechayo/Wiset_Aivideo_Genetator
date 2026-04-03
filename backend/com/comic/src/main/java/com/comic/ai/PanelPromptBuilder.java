package com.comic.ai;

import com.comic.util.NumberFormatter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 分镜 prompt 构建器
 * 新流程：九宫格生成 + 多镜头视频生成
 */
@Component
public class PanelPromptBuilder {

    // ================= 生产阶段：九宫格 / 视频 =================

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
     * 构建九宫格图片生成提示词
     */
    public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots,
                                   List<String> charReferences) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildSceneStylePrefix(visualStyle));
        sb.append("\n\n生成一张 3x3 分镜九宫格图片。图片比例 16:9，黑色细边框分隔。保持角色外观一致性，图片中不包含任何文字。\n\n");

        if (charReferences != null && !charReferences.isEmpty()) {
            sb.append("角色参考：").append(String.join("、", charReferences)).append("\n\n");
        }

        for (int i = 0; i < shots.size(); i++) {
            Map<String, Object> shot = shots.get(i);
            sb.append("面板 ").append(i + 1).append(": ");
            sb.append("16:9 - ").append(shot.getOrDefault("visualDescription", ""));
            sb.append(" ").append(shot.getOrDefault("shotSize", ""));
            sb.append(" ").append(shot.getOrDefault("cameraAngle", ""));
            sb.append(" camera: ").append(shot.getOrDefault("cameraMovement", ""));
            sb.append(" environment: ").append(shot.getOrDefault("scene", ""));
            sb.append("\n");
        }

        int emptySlots = 9 - shots.size();
        if (emptySlots > 0) {
            sb.append("剩余 ").append(emptySlots).append(" 个面板: (empty panel - storyboard end)");
        }
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

        if (shots != null) {
            for (int i = 0; i < shots.size(); i++) {
                Map<String, Object> shot = shots.get(i);
                sb.append("【镜头").append(i + 1).append("】\n");
                sb.append("duration: ").append(shot.get("duration")).append("s\n");
                sb.append("Scene: ").append(shot.getOrDefault("shotSize", ""))
                  .append("，").append(shot.getOrDefault("cameraAngle", ""))
                  .append("，").append(shot.getOrDefault("cameraMovement", ""))
                  .append("，").append(shot.getOrDefault("visualDescription", "")).append("\n");

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
        return sb.toString();
    }
}
