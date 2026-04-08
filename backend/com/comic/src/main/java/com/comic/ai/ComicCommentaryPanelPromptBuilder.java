package com.comic.ai;

import com.comic.util.NumberFormatter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 漫剧解说模式下的分镜九宫格与多镜头视频 prompt，与 {@link PanelPromptBuilder} 实时动画链路分离。
 * 画面结构约束（3×3、16:9、分隔线、角色锚定字段）与主链路保持一致，便于沿用切割与融合流程。
 */
@Component
public class ComicCommentaryPanelPromptBuilder {

    private final PanelPromptBuilder panelPromptBuilder;

    public ComicCommentaryPanelPromptBuilder(PanelPromptBuilder panelPromptBuilder) {
        this.panelPromptBuilder = panelPromptBuilder;
    }

    public String buildGridPrompt(String visualStyle, List<Map<String, Object>> shots, List<?> charRefs) {
        StringBuilder sb = new StringBuilder();
        sb.append(panelPromptBuilder.buildSceneStylePrefix(visualStyle));
        sb.append("漫剧解说风格关键帧：每格为独立「漫画分镜式」画面，适合旁白解说与字幕叠加，构图清晰、主体突出。\n\n");

        sb.append("【布局要求 - 必须严格遵守】\n");
        sb.append("输出一张严格 3×3 九宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。\n");
        sb.append("图片必须被 2 条黑色竖线（约 4px 宽）和 2 条黑色横线（约 4px 宽）均匀分割为 3 行 3 列，共 9 个等大的格子。\n");
        sb.append("每个格子是一个完全独立的画面，可表现不同时间或场景；整体像动态漫/条漫分格，便于后期加解说与花字。\n");
        sb.append("绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。\n");
        sb.append("图片中不包含任何文字、数字、标号或水印（解说与字幕由后期添加）。\n\n");

        if (charRefs != null && !charRefs.isEmpty()) {
            sb.append("【角色设定 - 必须严格遵守】\n");
            sb.append("只允许绘制以下角色，绝对不要出现列表之外的角色、路人或背景人物。\n");
            sb.append("每个角色在不同格子中必须保持外貌、体型比例、服装、发型完全一致。\n\n");

            for (Object refObj : charRefs) {
                String name = null;
                String species = null;
                String appearance = null;
                String role = null;
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

        sb.append("【景别约束】解说模式以中景、近景、特写为主；远景/大远景仅用于开场或转场，总数不超过 2 格。\n");
        sb.append("【字幕安全区】构图需留出上方约 1/4 区域，避免关键内容被花字遮挡。\n\n");

        sb.append("【分镜内容 - 从左到右、从上到下填入九宫格；每格信息密度适中，利于口播节奏】\n");
        sb.append("每格需交代清楚场景氛围与角色状态，情绪对比可略夸张以增强解说张力。\n\n");
        for (int i = 0; i < shots.size(); i++) {
            Map<String, Object> shot = shots.get(i);
            int row = i / 3 + 1;
            int col = i % 3 + 1;
            sb.append("第").append(row).append("行第").append(col).append("列: ");
            sb.append(shot.getOrDefault("visualDescription", ""));
            String shotSize = (String) shot.getOrDefault("shotSize", "");
            if (shotSize != null && !shotSize.isEmpty()) {
                sb.append("，").append(shotSize);
            }
            String cameraAngle = (String) shot.getOrDefault("cameraAngle", "");
            if (cameraAngle != null && !cameraAngle.isEmpty()) {
                sb.append("，").append(cameraAngle);
            }
            String cameraMovement = (String) shot.getOrDefault("cameraMovement", "");
            if (cameraMovement != null && !cameraMovement.isEmpty()) {
                sb.append("，").append(cameraMovement);
            }
            String scene = (String) shot.getOrDefault("scene", "");
            if (scene != null && !scene.isEmpty()) {
                sb.append("，场景: ").append(scene);
            }
            sb.append("\n");
        }

        int emptySlots = 9 - shots.size();
        if (emptySlots > 0) {
            sb.append("剩余 ").append(emptySlots).append(" 个格子留空（纯黑色填充，不绘制任何内容）。\n\n");
        }

        sb.append("负面提示词：文字、水印、标签、签名、人体结构错误、肢体融合、多余手指、多余肢体、");
        sb.append("面部变形、眼睛异常、模糊、低质量、色块 artefact、粗糙线条、草稿感、");
        sb.append("字幕、旁白文字、屏幕上的任何文字。\n");
        sb.append("禁止快速运动、剧烈动作、突然变向。");
        return sb.toString();
    }

    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo) {
        return buildMultiShotPrompt(visualStyle, panelInfo, null);
    }

    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo,
                                       List<Map<String, String>> characterInfos) {
        return buildMultiShotPrompt(visualStyle, panelInfo, characterInfos, null);
    }

    @SuppressWarnings("unchecked")
    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo,
                                       List<Map<String, String>> characterInfos,
                                       Map<String, Object> previousPanelLastShot) {
        StringBuilder sb = new StringBuilder();
        sb.append(panelPromptBuilder.buildSceneStylePrefix(visualStyle));
        sb.append(" 漫剧风格连续视频：画面节奏服务于叙事与情绪递进。\n\n");

        if (previousPanelLastShot != null && !previousPanelLastShot.isEmpty()) {
            sb.append("## 承接上一段画面\n");
            sb.append("本段需与上一段结尾自然衔接。\n");
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
            sb.append("\n");
        }

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
        sb.append("以下 ").append(n).append(" 个镜头在同一视频中连续呈现，节奏平缓，信息点清晰、留白合理：\n\n");

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
                String speaker = (String) shot.get("speaker");
                if (dialogue != null && !"无".equals(dialogue) && !dialogue.isEmpty()
                        && (speaker == null || !speaker.contains("旁白"))) {
                    String tone = (String) shot.get("dialogueTone");
                    sb.append("角色对白");
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
                String transition = (String) shot.get("transitionHint");
                if (transition != null && !"无".equals(transition) && !transition.isEmpty()
                        && !transition.contains("最后一个镜头") && i < shots.size() - 1) {
                    sb.append("衔接: ").append(transition).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("## 运镜约束\n");
        sb.append("运镜以缓慢推拉和微平移为主，禁止快速摇移或大幅度环绕。\n");
        sb.append("每个镜头需有短暂静止留白。\n\n");

        sb.append("## 画面衔接\n");
        if (previousPanelLastShot != null && !previousPanelLastShot.isEmpty()) {
            sb.append("开头与上一段结尾连贯；镜头运动平缓。\n");
        }
        sb.append("镜头间过渡清晰，遵循衔接提示；保持角色与情绪连贯。\n");
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

        sb.append("\n\n## 负面提示词（严格遵守，违反任何一条即为失败）\n");
        sb.append("文字、水印、签名、logo、字幕、旁白文字、屏幕上出现的任何文字、人体结构错误、肢体融合、多余手指、多余肢体、");
        sb.append("面部变形、眼睛异常、模糊、闪烁、低质量、色块 artefact。\n");
        sb.append("禁止快速奔跑、剧烈运动、突然变向——运镜以缓慢推拉和微平移为主，用剪辑快切体现节奏。");
        return sb.toString();
    }

    /**
     * 带 Panel 间 narration 上下文的多镜头视频 prompt（解说模式专用）
     */
    public String buildMultiShotPrompt(String visualStyle, Map<String, Object> panelInfo,
                                       List<Map<String, String>> characterInfos,
                                       Map<String, Object> previousPanelLastShot,
                                       String previousPanelLastNarration,
                                       String nextPanelFirstNarration) {
        String base = buildMultiShotPrompt(visualStyle, panelInfo, characterInfos, previousPanelLastShot);

        if ((previousPanelLastNarration == null || previousPanelLastNarration.isEmpty())
                && (nextPanelFirstNarration == null || nextPanelFirstNarration.isEmpty())) {
            return base;
        }

        StringBuilder ctx = new StringBuilder("\n\n## 叙事上下文（Panel 间衔接）\n");
        if (previousPanelLastNarration != null && !previousPanelLastNarration.isEmpty()) {
            ctx.append("【上一段结尾】").append(previousPanelLastNarration).append("\n");
            ctx.append("本段画面应自然承接上一段的叙事节奏。\n");
        }
        if (nextPanelFirstNarration != null && !nextPanelFirstNarration.isEmpty()) {
            ctx.append("【下一段开头】").append(nextPanelFirstNarration).append("\n");
            ctx.append("本段结尾应为下一段的叙事做铺垫。\n");
        }

        int negIdx = base.lastIndexOf("\n\n## 负面提示词");
        if (negIdx > 0) {
            return base.substring(0, negIdx) + ctx.toString() + base.substring(negIdx);
        }
        return base + ctx.toString();
    }

    /** narration 优先；否则兼容旧数据 speaker=旁白 + dialogue */
    private static String effectiveNarrationText(Map<String, Object> shot) {
        if (shot == null) {
            return null;
        }
        Object n = shot.get("narration");
        if (n != null) {
            String s = n.toString().trim();
            if (!s.isEmpty() && !"无".equals(s)) {
                return s;
            }
        }
        String sp = shot.get("speaker") != null ? shot.get("speaker").toString() : "";
        String dlg = shot.get("dialogue") != null ? shot.get("dialogue").toString() : "";
        if (sp.contains("旁白") && dlg != null && !dlg.isEmpty() && !"无".equals(dlg.trim())) {
            return dlg.trim();
        }
        return null;
    }
}
