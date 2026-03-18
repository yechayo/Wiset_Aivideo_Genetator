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
        try {
            JsonNode rootNode = objectMapper.readTree(storyboardJson);
            JsonNode panelsNode = rootNode.get("panels");

            if (panelsNode == null || !panelsNode.isArray()) {
                throw new IllegalArgumentException("分镜数据格式错误，缺少panels数组");
            }

            List<PanelInfo> panels = parsePanels(panelsNode);
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

        for (PanelInfo panel : panels) {
            // 判断是否需要创建新组
            if (currentGroup == null || currentDuration + panel.getDuration() > maxDurationPerGroup) {
                // 保存上一个组
                if (currentGroup != null) {
                    taskGroups.add(currentGroup);
                }

                // 创建新组
                currentGroup = new VideoTaskGroupModel();
                currentGroup.setPanelIndexes(new ArrayList<>());
                currentGroup.setPrompts(new ArrayList<>());
                currentGroup.setTotalDuration(0);
                currentDuration = 0;
            }

            // 添加到当前组
            currentGroup.getPanelIndexes().add(panel.getIndex());
            currentDuration += panel.getDuration();
            currentGroup.setTotalDuration((int) Math.ceil(currentDuration));

            // 构建提示词
            VideoPromptModel prompt = buildPromptForPanel(panel, visualStyle);
            currentGroup.getPrompts().add(prompt);
        }

        // 添加最后一个组
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
            // Sora格式：中文直接拼接
            promptText = buildSoraPrompt(panel, visualStyle);
        } else {
            // 其他平台：英文格式，需调用LLM翻译
            promptText = buildEnglishPrompt(panel, visualStyle);
        }

        prompt.setPromptText(promptText);
        return prompt;
    }

    /**
     * 构建Sora格式的提示词（中文）
     */
    private String buildSoraPrompt(PanelInfo panel, String visualStyle) {
        StringBuilder sb = new StringBuilder();

        sb.append("第").append(panel.getIndex() + 1).append("镜 / ");
        sb.append((int) Math.ceil(panel.getDuration())).append("秒 / ");
        sb.append("场景：");

        // 景别
        sb.append(panel.getShotSize()).append("，");

        // 角度
        sb.append(panel.getCameraAngle()).append("，");

        // 运镜
        sb.append(panel.getCameraMovement()).append("，");

        // 画面描述
        sb.append(panel.getDescription());

        // 对白
        if (panel.getDialogue() != null && !panel.getDialogue().isEmpty()) {
            sb.append("，对白：").append(panel.getDialogue());
        }

        // 特效
        if (panel.getEffects() != null && !panel.getEffects().isEmpty()) {
            sb.append("，特效：").append(panel.getEffects());
        }

        sb.append("。");

        // 风格前缀
        if (visualStyle != null && !visualStyle.isEmpty()) {
            return visualStyle + "风格。" + sb.toString();
        }

        return sb.toString();
    }

    /**
     * 构建英文格式的提示词
     * 调用LLM进行翻译和优化
     */
    private String buildEnglishPrompt(PanelInfo panel, String visualStyle) {
        String systemPrompt = "你是一个专业的视频提示词工程师。请将中文分镜描述翻译成英文视频生成提示词。";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("请将以下分镜描述翻译成英文视频提示词：\n\n");
        userPrompt.append("风格：").append(visualStyle != null ? visualStyle : "3D animation").append("\n");
        userPrompt.append("景别：").append(panel.getShotSize()).append("\n");
        userPrompt.append("角度：").append(panel.getCameraAngle()).append("\n");
        userPrompt.append("运镜：").append(panel.getCameraMovement()).append("\n");
        userPrompt.append("场景：").append(panel.getScene()).append("\n");
        userPrompt.append("画面描述：").append(panel.getDescription()).append("\n");

        if (panel.getDialogue() != null && !panel.getDialogue().isEmpty()) {
            userPrompt.append("对白：").append(panel.getDialogue()).append("\n");
        }

        if (panel.getEffects() != null && !panel.getEffects().isEmpty()) {
            userPrompt.append("特效：").append(panel.getEffects()).append("\n");
        }

        userPrompt.append("\n请输出简洁的英文提示词，专注于画面描述。");

        try {
            return textGenerationService.generate(systemPrompt, userPrompt.toString());
        } catch (Exception e) {
            log.warn("LLM翻译失败，使用基础翻译: {}", e.getMessage());
            // 降级：使用简单的关键词翻译
            return buildFallbackEnglishPrompt(panel, visualStyle);
        }
    }

    /**
     * 降级英文提示词（简单翻译）
     */
    private String buildFallbackEnglishPrompt(PanelInfo panel, String visualStyle) {
        return String.format("%s style. %s shot, %s angle, %s camera movement. %s",
                visualStyle != null ? visualStyle : "3D animation",
                translateShotSize(panel.getShotSize()),
                translateAngle(panel.getCameraAngle()),
                translateMovement(panel.getCameraMovement()),
                panel.getDescription());
    }

    private String translateShotSize(String shotSize) {
        Map<String, String> map = new HashMap<>();
        map.put("特写", "close-up");
        map.put("近景", "close shot");
        map.put("中景", "medium shot");
        map.put("全景", "wide shot");
        map.put("远景", "long shot");
        return map.getOrDefault(shotSize, "medium shot");
    }

    private String translateAngle(String angle) {
        Map<String, String> map = new HashMap<>();
        map.put("平视", "eye-level");
        map.put("仰视", "low angle");
        map.put("俯视", "high angle");
        map.put("鸟瞰", "bird's eye view");
        return map.getOrDefault(angle, "eye-level");
    }

    private String translateMovement(String movement) {
        Map<String, String> map = new HashMap<>();
        map.put("固定", "fixed");
        map.put("推进", "push in");
        map.put("拉远", "pull back");
        map.put("左移", "pan left");
        map.put("右移", "pan right");
        map.put("跟踪", "tracking");
        return map.getOrDefault(movement, "fixed");
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
    }
}
