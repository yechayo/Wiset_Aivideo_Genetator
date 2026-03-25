package com.comic.service.production;

import com.comic.dto.model.SceneGroupModel;
import com.comic.dto.model.VideoPromptModel;
import com.comic.dto.model.VideoTaskGroupModel;
import com.comic.ai.text.TextGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;

import java.util.*;

/**
 * 视频提示词构建服务
 * 为分镜构建视频生成提示词，支持不同视频提供商
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPromptBuilderService {

    private final TextGenerationService textGenerationService;
    private final ObjectMapper objectMapper;
    private final CharacterRepository characterRepository;

    @Value("${comic.video.provider:seedance}")
    private String videoProvider;

    @Value("${comic.video.max-duration-per-group:10}")
    private int maxDurationPerGroup;

    /**
     * 为分镜列表构建视频提示词
     *
     * @param storyboardJson 分镜JSON
     * @param sceneGroups 场景分组
     * @param visualStyle 视觉风格
     * @return 视频任务组列表
     */
    public List<VideoTaskGroupModel> buildPromptsForPanels(String storyboardJson, List<SceneGroupModel> sceneGroups, String visualStyle) {
        return buildPromptsForPanels(storyboardJson, sceneGroups, visualStyle, null);
    }

    /**
     * 为分镜列表构建视频提示词（带projectId用于角色一致性）
     */
    public List<VideoTaskGroupModel> buildPromptsForPanels(String storyboardJson, List<SceneGroupModel> sceneGroups, String visualStyle, String projectId) {
        try {
            JsonNode rootNode = objectMapper.readTree(storyboardJson);
            JsonNode panelsNode = rootNode.get("panels");

            if (panelsNode == null || !panelsNode.isArray()) {
                throw new IllegalArgumentException("分镜数据格式错误，缺少panels数组");
            }

            List<PanelInfo> panels = parsePanels(panelsNode);
            // 注入projectId到每个panel用于角色一致性查询
            if (projectId != null) {
                panels.forEach(p -> p.setProjectId(projectId));
            }
            return groupPanelsAndBuildPrompts(panels, sceneGroups, visualStyle);

        } catch (Exception e) {
            log.error("构建视频提示词失败", e);
            throw new RuntimeException("构建视频提示词失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析分镜信息
     */
    private List<PanelInfo> parsePanels(JsonNode panelsNode) {
        List<PanelInfo> panels = new ArrayList<>();
        int index = 0;

        for (JsonNode panelNode : panelsNode) {
            PanelInfo panel = new PanelInfo();
            panel.setIndex(index++);

            // 提取场景
            JsonNode sceneNode = panelNode.get("scene");
            panel.setScene(sceneNode != null ? sceneNode.asText() : "");

            // 提取景别
            JsonNode shotSizeNode = panelNode.get("shot_size");
            panel.setShotSize(shotSizeNode != null ? shotSizeNode.asText() : "中景");

            // 提取角度
            JsonNode angleNode = panelNode.get("camera_angle");
            panel.setCameraAngle(angleNode != null ? angleNode.asText() : "平视");

            // 提取运镜
            JsonNode movementNode = panelNode.get("camera_movement");
            panel.setCameraMovement(movementNode != null ? movementNode.asText() : "固定");

            // 提取画面描述
            JsonNode descNode = panelNode.get("description");
            panel.setDescription(descNode != null ? descNode.asText() : "");

            // 提取对白
            JsonNode dialogueNode = panelNode.get("dialogue");
            panel.setDialogue(dialogueNode != null ? dialogueNode.asText() : "");

            // 提取特效
            JsonNode effectsNode = panelNode.get("effects");
            panel.setEffects(effectsNode != null ? effectsNode.asText() : "");

            // 默认时长2.5秒
            panel.setDuration(2.5f);

            panels.add(panel);
        }

        return panels;
    }

    /**
     * 分组分镜并构建提示词
     */
    private List<VideoTaskGroupModel> groupPanelsAndBuildPrompts(List<PanelInfo> panels, List<SceneGroupModel> sceneGroups, String visualStyle) {
        List<VideoTaskGroupModel> taskGroups = new ArrayList<>();
        VideoTaskGroupModel currentGroup = null;
        float currentDuration = 0;

        // 构建分镜→场景组的映射
        Map<Integer, SceneGroupModel> panelToGroup = new HashMap<>();
        for (SceneGroupModel group : sceneGroups) {
            if (group.getStartPanelIndex() != null && group.getEndPanelIndex() != null) {
                for (int i = group.getStartPanelIndex(); i <= group.getEndPanelIndex(); i++) {
                    panelToGroup.put(i, group);
                }
            }
        }

        for (PanelInfo panel : panels) {
            // 判断是否需要创建新组
            if (currentGroup == null || currentDuration + panel.getDuration() > maxDurationPerGroup) {
                if (currentGroup != null) {
                    taskGroups.add(currentGroup);
                }
                currentGroup = new VideoTaskGroupModel();
                currentGroup.setPanelIndexes(new ArrayList<>());
                currentGroup.setPrompts(new ArrayList<>());
                currentGroup.setTotalDuration(0);
                currentDuration = 0;
            }

            currentGroup.getPanelIndexes().add(panel.getIndex());
            currentDuration += panel.getDuration();
            currentGroup.setTotalDuration((int) Math.ceil(currentDuration));

            // 注入场景组信息到panel
            SceneGroupModel group = panelToGroup.get(panel.getIndex());
            if (group != null) {
                panel.setSceneGroupId(group.getSceneId());
                panel.setCharacters(group.getCharacters());
            }

            VideoPromptModel prompt = buildPromptForPanel(panel, visualStyle);
            currentGroup.getPrompts().add(prompt);
        }

        if (currentGroup != null) {
            taskGroups.add(currentGroup);
        }

        log.info("分镜分组完成: {}个分镜分成{}个任务组", panels.size(), taskGroups.size());
        return taskGroups;
    }

    /**
     * 为单个分镜构建提示词
     */
    private VideoPromptModel buildPromptForPanel(PanelInfo panel, String visualStyle) {
        VideoPromptModel prompt = new VideoPromptModel();
        prompt.setPanelIndex(panel.getIndex());
        prompt.setDuration((int) Math.ceil(panel.getDuration()));
        prompt.setAspectRatio("16:9");

        String promptText;
        if ("sora".equalsIgnoreCase(videoProvider)) {
            promptText = buildSoraPrompt(panel, visualStyle);
        } else {
            promptText = buildEnglishPrompt(panel, visualStyle, panel.getSceneGroupId());
        }

        prompt.setPromptText(promptText);
        return prompt;
    }

    /**
     * 构建视觉风格前缀
     */
    public String buildStylePrefix(String visualStyle) {
        if (visualStyle == null || visualStyle.isEmpty()) {
            return "";
        }
        String style = visualStyle.toLowerCase().trim();
        if (style.contains("anime") || style.contains("动漫") || style.contains("日系")) {
            return "Anime style, Japanese animation, cel-shaded, vibrant colors, clean linework, "
                    + "dramatic lighting with soft ambient occlusion, cinematic composition, "
                    + "high detail background art, consistent character proportions, "
                    + "professional animation quality, 4K resolution";
        } else if (style.contains("manga") || style.contains("漫画")) {
            return "Manga style, black and white with screen tones, bold linework, "
                    + "expressive character designs, dynamic panel composition, "
                    + "high contrast shading, Japanese comic art aesthetic";
        } else if (style.contains("real") || style.contains("写实") || style.contains("真人")) {
            return "Photorealistic style, cinematic live-action quality, natural lighting, "
                    + "lifelike textures and skin tones, shallow depth of field, "
                    + "film grain, anamorphic lens characteristics, 4K ultra HD";
        } else if (style.contains("3d") || style.contains("三维")) {
            return "High quality 3D animation style, Pixar-quality rendering, "
                    + "subsurface scattering on skin, global illumination, "
                    + "physically based rendering, volumetric lighting, "
                    + "smooth anti-aliased edges, cinematic depth of field, 4K resolution";
        } else if (style.contains("watercolor") || style.contains("水彩")) {
            return "Watercolor painting style, soft blending of colors, visible brushstrokes, "
                    + "light paper texture, delicate color washes, artistic interpretation, "
                    + "flowing organic edges, gentle luminosity, fine art aesthetic";
        } else if (style.contains("oil") || style.contains("油画")) {
            return "Oil painting style, rich impasto texture, visible brushwork, "
                    + "warm color palette, classical composition, chiaroscuro lighting, "
                    + "canvas texture, museum quality fine art, dramatic contrast";
        } else if (style.contains("pixel") || style.contains("像素") || style.contains("retro")) {
            return "Pixel art style, 16-bit retro game aesthetic, limited color palette, "
                    + "crisp pixel edges, nostalgic gaming atmosphere, "
                    + "detailed sprite work, vibrant saturated colors";
        } else {
            return visualStyle + " style, high quality, cinematic composition, professional production value, 4K resolution";
        }
    }

    /**
     * 构建角色一致性指令
     */
    private String buildCharacterConsistencyInstruction(List<String> characterNames, String projectId) {
        if (characterNames == null || characterNames.isEmpty() || projectId == null || projectId.isEmpty()) {
            return "";
        }
        List<Character> characters = characterRepository.findByProjectId(projectId);
        if (characters.isEmpty()) {
            return "";
        }
        Map<String, Character> charMap = new HashMap<>();
        for (Character c : characters) {
            charMap.put(getCharacterInfoStr(c, "name"), c);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nCRITICAL CHARACTER CONSISTENCY REQUIREMENTS:\n");
        sb.append("- All characters must EXACTLY match the provided reference image in appearance\n");
        sb.append("- Zero tolerance for deviation in: facial features, hair color/style, body proportions, clothing\n");
        for (String name : characterNames) {
            Character c = charMap.get(name);
            if (c != null && getCharacterInfoStr(c, "appearance") != null && !getCharacterInfoStr(c, "appearance").isEmpty()) {
                sb.append("- Character ").append(name).append(": ").append(getCharacterInfoStr(c, "appearance")).append("\n");
            }
        }
        sb.append("- Maintain absolute visual continuity across all shots\n");
        return sb.toString();
    }

    /**
     * 构建Sora格式的提示词（中文）
     */
    private String buildSoraPrompt(PanelInfo panel, String visualStyle) {
        StringBuilder sb = new StringBuilder();

        // 风格前缀
        String stylePrefix = buildStylePrefix(visualStyle);
        if (!stylePrefix.isEmpty()) {
            sb.append(stylePrefix).append(". ");
        }

        sb.append("第").append(panel.getIndex() + 1).append("镜 / ");
        sb.append((int) Math.ceil(panel.getDuration())).append("秒 / ");
        sb.append("场景：");

        sb.append(translateShotSizeChinese(panel.getShotSize())).append("，");
        sb.append(panel.getCameraAngle()).append("，");
        sb.append(translateMovementChinese(panel.getCameraMovement())).append("，");
        sb.append(panel.getDescription());

        if (panel.getDialogue() != null && !panel.getDialogue().isEmpty()) {
            sb.append("，对白：").append(panel.getDialogue());
        }
        if (panel.getEffects() != null && !panel.getEffects().isEmpty()) {
            sb.append("，特效：").append(panel.getEffects());
        }
        sb.append("。");

        return sb.toString();
    }

    /**
     * 构建英文格式的提示词（调用LLM翻译）
     */
    private String buildEnglishPrompt(PanelInfo panel, String visualStyle, String sceneGroupId) {
        String systemPrompt = "You are a professional video prompt engineer. "
                + "Translate the following Chinese storyboard description into an English video generation prompt. "
                + "Output ONLY the English prompt text, no explanations.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Style prefix (prepend exactly as-is):\n");
        userPrompt.append(buildStylePrefix(visualStyle)).append("\n\n");
        userPrompt.append("Shot size: ").append(translateShotSize(panel.getShotSize())).append("\n");
        userPrompt.append("Camera angle: ").append(translateAngle(panel.getCameraAngle())).append("\n");
        userPrompt.append("Camera movement: ").append(translateMovement(panel.getCameraMovement())).append("\n");
        userPrompt.append("Scene: ").append(panel.getScene()).append("\n");
        userPrompt.append("Description: ").append(panel.getDescription()).append("\n");

        if (panel.getDialogue() != null && !panel.getDialogue().isEmpty()) {
            userPrompt.append("Dialogue: ").append(panel.getDialogue()).append("\n");
        }
        if (panel.getEffects() != null && !panel.getEffects().isEmpty()) {
            userPrompt.append("Effects: ").append(panel.getEffects()).append("\n");
        }

        // 角色一致性指令
        String charInstruction = buildCharacterConsistencyInstruction(panel.getCharacters(), panel.getProjectId());
        if (!charInstruction.isEmpty()) {
            userPrompt.append(charInstruction);
        }

        userPrompt.append("\n\nOutput a concise, cinematic English prompt focused on visual description. "
                + "Start with the style prefix, then shot/camera details, then scene content.");

        try {
            return textGenerationService.generate(systemPrompt, userPrompt.toString());
        } catch (Exception e) {
            log.warn("LLM翻译失败，使用基础翻译: {}", e.getMessage());
            return buildFallbackEnglishPrompt(panel, visualStyle);
        }
    }

    /**
     * 降级英文提示词（简单翻译）
     */
    private String buildFallbackEnglishPrompt(PanelInfo panel, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        String stylePrefix = buildStylePrefix(visualStyle);
        if (!stylePrefix.isEmpty()) {
            sb.append(stylePrefix).append(". ");
        }
        sb.append(translateShotSize(panel.getShotSize())).append(", ");
        sb.append(translateAngle(panel.getCameraAngle())).append(", ");
        sb.append(translateMovement(panel.getCameraMovement())).append(". ");
        sb.append(panel.getDescription());

        // 角色一致性
        String charInstruction = buildCharacterConsistencyInstruction(panel.getCharacters(), panel.getProjectId());
        if (!charInstruction.isEmpty()) {
            sb.append(charInstruction);
        }

        return sb.toString();
    }

    // ========== 扩充的景别映射（15+） ==========

    private String translateShotSize(String shotSize) {
        Map<String, String> map = new HashMap<>();
        map.put("大远景", "extreme long shot, vast panoramic view, figures appear tiny");
        map.put("远景", "long shot, full environment visible");
        map.put("全景", "wide shot, full body + environment");
        map.put("中景", "medium shot, waist-up framing");
        map.put("近景", "medium close-up, chest-up");
        map.put("特写", "close-up, face fills frame");
        map.put("大特写", "extreme close-up, detail shot");
        map.put("过肩镜头", "over-the-shoulder shot");
        map.put("主观视角", "POV shot, first-person perspective");
        map.put("半身景", "half-body shot, waist-up");
        map.put("全身景", "full-body shot");
        map.put("七分身", "medium shot, knee-up framing");
        map.put("远景群像", "long shot group portrait, multiple figures in landscape");
        map.put("仰拍", "low angle shot, looking up at subject");
        map.put("俯拍", "high angle shot, looking down at subject");
        map.put("平视", "eye-level shot");
        return map.getOrDefault(shotSize, "medium shot");
    }

    private String translateShotSizeChinese(String shotSize) {
        // Sora模式下使用中文原值（已包含在输入中），这里做标准化
        return shotSize != null ? shotSize : "中景";
    }

    // ========== 扩充的角度映射 ==========

    private String translateAngle(String angle) {
        Map<String, String> map = new HashMap<>();
        map.put("平视", "eye-level");
        map.put("仰视", "low angle");
        map.put("俯视", "high angle");
        map.put("鸟瞰", "bird's eye view");
        map.put("倾斜", "dutch angle, tilted frame");
        map.put("正面", "frontal view");
        map.put("侧面", "side view, profile shot");
        map.put("背面", "rear view, from behind");
        return map.getOrDefault(angle, "eye-level");
    }

    // ========== 扩充的运镜映射（12+） ==========

    private String translateMovement(String movement) {
        Map<String, String> map = new HashMap<>();
        map.put("固定", "static shot, locked camera");
        map.put("推进", "slow push-in, dolly forward");
        map.put("拉远", "pull back, dolly out");
        map.put("左移", "pan left, lateral tracking");
        map.put("右移", "pan right, lateral tracking");
        map.put("跟踪", "tracking shot, follow subject");
        map.put("环绕", "orbit shot, 360-degree arc around subject");
        map.put("升降", "crane shot, vertical movement");
        map.put("摇摄", "tilt shot, vertical camera rotation");
        map.put("手持", "handheld camera, slight natural shake");
        map.put("变焦", "zoom in/out, focal length change");
        map.put("甩镜", "whip pan, fast camera swing");
        map.put("缓慢推近", "slow creep forward, subtle dolly in");
        map.put("快速拉远", "rapid zoom out, dramatic pull back");
        return map.getOrDefault(movement, "static shot, locked camera");
    }

    private String translateMovementChinese(String movement) {
        return movement != null ? movement : "固定";
    }

    // ========== 新增的光影映射 ==========

    private String translateLighting(String lighting) {
        if (lighting == null || lighting.isEmpty()) {
            return "";
        }
        Map<String, String> map = new HashMap<>();
        map.put("逆光", "backlighting, silhouette rim light");
        map.put("侧光", "side lighting, dramatic shadows");
        map.put("柔光", "soft diffused lighting, gentle shadows");
        map.put("硬光", "harsh direct lighting, sharp shadows");
        map.put("顶光", "overhead lighting, dramatic downward shadows");
        map.put("自然光", "natural ambient lighting");
        map.put("暖光", "warm golden light, sunset tones");
        map.put("冷光", "cool blue-white light, moonlight tones");
        map.put("霓虹", "neon lighting, vibrant colored glow");
        map.put("烛光", "candlelight, warm flickering illumination");
        return map.getOrDefault(lighting, "");
    }

    private String getCharacterInfoStr(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    /**
     * 分镜信息内部类
     */
    private static class PanelInfo {
        private Integer index;
        private String scene;
        private String shotSize;
        private String cameraAngle;
        private String cameraMovement;
        private String description;
        private String dialogue;
        private String effects;
        private float duration;
        private String projectId;
        private String sceneGroupId;
        private List<String> characters;

        public Integer getIndex() { return index; }
        public void setIndex(Integer index) { this.index = index; }

        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }

        public String getShotSize() { return shotSize; }
        public void setShotSize(String shotSize) { this.shotSize = shotSize; }

        public String getCameraAngle() { return cameraAngle; }
        public void setCameraAngle(String cameraAngle) { this.cameraAngle = cameraAngle; }

        public String getCameraMovement() { return cameraMovement; }
        public void setCameraMovement(String cameraMovement) { this.cameraMovement = cameraMovement; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getDialogue() { return dialogue; }
        public void setDialogue(String dialogue) { this.dialogue = dialogue; }

        public String getEffects() { return effects; }
        public void setEffects(String effects) { this.effects = effects; }

        public float getDuration() { return duration; }
        public void setDuration(float duration) { this.duration = duration; }

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }

        public String getSceneGroupId() { return sceneGroupId; }
        public void setSceneGroupId(String sceneGroupId) { this.sceneGroupId = sceneGroupId; }

        public List<String> getCharacters() { return characters; }
        public void setCharacters(List<String> characters) { this.characters = characters; }
    }
}
