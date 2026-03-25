package com.comic.service.production;

import com.comic.ai.text.TextGenerationService;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Storyboard enhancement service.
 * Adds professional camera parameters to storyboard panels by either:
 * 1) rule-based recommendation (no LLM cost), or
 * 2) LLM selection from predefined terminology.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardEnhancementService {

    private static final Set<String> SHOT_TYPES = new LinkedHashSet<>(Arrays.asList(
            "EXTREME_LONG_SHOT",
            "LONG_SHOT",
            "FULL_SHOT",
            "MEDIUM_LONG_SHOT",
            "MEDIUM_SHOT",
            "MEDIUM_CLOSE_UP",
            "CLOSE_UP",
            "EXTREME_CLOSE_UP"
    ));

    private static final Set<String> CAMERA_ANGLES = new LinkedHashSet<>(Arrays.asList(
            "eye_level",
            "low_angle",
            "high_angle",
            "bird_eye",
            "dutch_angle",
            "shoulder_level"
    ));

    private static final Set<String> CAMERA_MOVEMENTS = new LinkedHashSet<>(Arrays.asList(
            "static",
            "push_in",
            "pull_out",
            "pan_left",
            "pan_right",
            "tilt_up",
            "tilt_down",
            "dolly_in",
            "dolly_out",
            "tracking_follow",
            "slider_lateral"
    ));

    private static final String DEFAULT_SHOT = "MEDIUM_SHOT";
    private static final String DEFAULT_ANGLE = "eye_level";
    private static final String DEFAULT_MOVEMENT = "static";

    private final TextGenerationService textGenerationService;
    private final EpisodeRepository episodeRepository;
    private final ObjectMapper objectMapper;

    @Value("${comic.storyboard.enhancement.enabled:true}")
    private boolean enhancementEnabled = true;

    @Value("${comic.storyboard.enhancement.mode:RULE}")
    private String enhancementMode = "RULE";

    @Value("${comic.storyboard.enhancement.delay-ms:500}")
    private long enhancementDelayMs = 500L;

    /**
     * Enhance one episode storyboard and persist the result.
     */
    public void enhanceEpisodeStoryboard(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }
        if (getEpisodeInfoStr(episode, "storyboardJson") == null || getEpisodeInfoStr(episode, "storyboardJson").trim().isEmpty()) {
            throw new IllegalArgumentException("Storyboard JSON is empty for episode: " + episodeId);
        }

        String enhancedJson = enhanceStoryboardJson(getEpisodeInfoStr(episode, "storyboardJson"));
        episode.getEpisodeInfo().put("storyboardJson", enhancedJson);
        episodeRepository.updateById(episode);
    }

    /**
     * Enhance storyboard JSON in memory.
     */
    public String enhanceStoryboardJson(String storyboardJson) {
        if (!enhancementEnabled) {
            return storyboardJson;
        }
        if (storyboardJson == null || storyboardJson.trim().isEmpty()) {
            throw new IllegalArgumentException("storyboardJson must not be empty");
        }

        try {
            JsonNode rootNode = objectMapper.readTree(storyboardJson);
            JsonNode panelsNode = rootNode.get("panels");
            if (panelsNode == null || !panelsNode.isArray()) {
                throw new IllegalArgumentException("Storyboard JSON must contain panels array");
            }

            EnhancementMode mode = resolveMode(enhancementMode);
            ArrayNode panels = (ArrayNode) panelsNode;
            for (int i = 0; i < panels.size(); i++) {
                JsonNode panelNode = panels.get(i);
                if (!(panelNode instanceof ObjectNode)) {
                    continue;
                }
                ObjectNode panelObject = (ObjectNode) panelNode;
                if (mode == EnhancementMode.RULE) {
                    applyRuleRecommendation(panelObject);
                } else {
                    applyLlmRecommendation(panelObject);
                    if (i < panels.size() - 1) {
                        sleepBetweenPanels(enhancementDelayMs);
                    }
                }
            }

            return objectMapper.writeValueAsString(rootNode);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to enhance storyboard JSON", e);
        }
    }

    void sleepBetweenPanels(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during enhancement throttling", e);
        }
    }

    void setEnhancementModeForTest(String mode) {
        this.enhancementMode = mode;
    }

    void setEnhancementDelayMsForTest(long delayMs) {
        this.enhancementDelayMs = delayMs;
    }

    private EnhancementMode resolveMode(String modeText) {
        if (modeText == null) {
            return EnhancementMode.RULE;
        }
        String normalized = modeText.trim().toUpperCase(Locale.ROOT);
        if ("LLM".equals(normalized)) {
            return EnhancementMode.LLM;
        }
        return EnhancementMode.RULE;
    }

    private void applyRuleRecommendation(ObjectNode panel) {
        String text = buildPanelContextText(panel).toLowerCase(Locale.ROOT);
        String shotType = DEFAULT_SHOT;
        String cameraAngle = DEFAULT_ANGLE;
        String cameraMovement = DEFAULT_MOVEMENT;
        List<String> reasonParts = new ArrayList<>();

        if (containsAny(text, "脸", "表情", "face", "expression")) {
            shotType = "CLOSE_UP";
            reasonParts.add("face/expression -> CLOSE_UP");
        }
        if (containsAny(text, "跟随", "行走", "walk", "follow")) {
            cameraMovement = "tracking_follow";
            reasonParts.add("follow/walk -> tracking_follow");
        }
        if (containsAny(text, "俯拍", "高位", "top-down", "high angle")) {
            cameraAngle = "high_angle";
            reasonParts.add("high/top-down -> high_angle");
        }
        if (reasonParts.isEmpty()) {
            reasonParts.add("default cinematic recommendation");
        }

        panel.put("shot_type", shotType);
        panel.put("camera_angle", cameraAngle);
        panel.put("camera_movement", cameraMovement);
        panel.put("enhancement_reason", "rule: " + String.join("; ", reasonParts));
    }

    private void applyLlmRecommendation(ObjectNode panel) {
        String systemPrompt = buildLlmSystemPrompt();
        String userPrompt = buildLlmUserPrompt(panel);
        String raw = textGenerationService.generate(systemPrompt, userPrompt);
        String clean = cleanJson(raw);

        JsonNode recommendation;
        try {
            recommendation = objectMapper.readTree(clean);
        } catch (Exception e) {
            throw new IllegalStateException("LLM enhancement output is not valid JSON", e);
        }

        String shotType = requiredText(recommendation, "shot_type");
        String cameraAngle = requiredText(recommendation, "camera_angle");
        String cameraMovement = requiredText(recommendation, "camera_movement");
        String reason = requiredText(recommendation, "reason");

        if (!SHOT_TYPES.contains(shotType)) {
            throw new IllegalStateException("LLM returned unsupported shot_type: " + shotType);
        }
        if (!CAMERA_ANGLES.contains(cameraAngle)) {
            throw new IllegalStateException("LLM returned unsupported camera_angle: " + cameraAngle);
        }
        if (!CAMERA_MOVEMENTS.contains(cameraMovement)) {
            throw new IllegalStateException("LLM returned unsupported camera_movement: " + cameraMovement);
        }

        panel.put("shot_type", shotType);
        panel.put("camera_angle", cameraAngle);
        panel.put("camera_movement", cameraMovement);
        panel.put("enhancement_reason", "llm: " + reason);
    }

    private String buildLlmSystemPrompt() {
        return "You are a storyboard cinematography assistant.\n"
                + "Select one option for each key strictly from the allowed lists.\n"
                + "Allowed shot_type: " + String.join(",", SHOT_TYPES) + "\n"
                + "Allowed camera_angle: " + String.join(",", CAMERA_ANGLES) + "\n"
                + "Allowed camera_movement: " + String.join(",", CAMERA_MOVEMENTS) + "\n"
                + "Return JSON only: {\"shot_type\":\"...\",\"camera_angle\":\"...\",\"camera_movement\":\"...\",\"reason\":\"...\"}";
    }

    private String buildLlmUserPrompt(ObjectNode panel) {
        String composition = textOrEmpty(panel, "composition");
        String sceneDesc = "";
        JsonNode background = panel.get("background");
        if (background != null && background.isObject()) {
            sceneDesc = textOrEmpty((ObjectNode) background, "scene_desc");
        }
        String dialogueText = "";
        JsonNode dialogue = panel.get("dialogue");
        if (dialogue != null && dialogue.isArray()) {
            List<String> fragments = new ArrayList<>();
            for (JsonNode item : dialogue) {
                if (item != null && item.isObject()) {
                    JsonNode text = item.get("text");
                    if (text != null && text.isTextual()) {
                        fragments.add(text.asText());
                    }
                }
            }
            dialogueText = String.join(" | ", fragments);
        }

        return "Panel composition: " + composition + "\n"
                + "Scene description: " + sceneDesc + "\n"
                + "Dialogue: " + dialogueText + "\n"
                + "Pick the best shot_type/camera_angle/camera_movement and explain briefly.";
    }

    private String buildPanelContextText(ObjectNode panel) {
        StringBuilder sb = new StringBuilder();
        sb.append(textOrEmpty(panel, "composition")).append(' ');

        JsonNode background = panel.get("background");
        if (background != null && background.isObject()) {
            JsonNode sceneDesc = background.get("scene_desc");
            if (sceneDesc != null && sceneDesc.isTextual()) {
                sb.append(sceneDesc.asText()).append(' ');
            }
        }

        JsonNode dialogue = panel.get("dialogue");
        if (dialogue != null && dialogue.isArray()) {
            for (JsonNode d : dialogue) {
                if (d != null && d.isObject()) {
                    JsonNode textNode = d.get("text");
                    if (textNode != null && textNode.isTextual()) {
                        sb.append(textNode.asText()).append(' ');
                    }
                }
            }
        }
        return sb.toString();
    }

    private boolean containsAny(String source, String... tokens) {
        if (source == null || source.isEmpty() || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isEmpty() && source.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String textOrEmpty(ObjectNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode fieldNode = node.get(field);
        if (fieldNode == null || !fieldNode.isTextual()) {
            return "";
        }
        return fieldNode.asText();
    }

    private String cleanJson(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private String requiredText(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("LLM enhancement JSON must be an object");
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalStateException("LLM enhancement field is missing: " + field);
        }
        return value.asText().trim();
    }

    private String getEpisodeInfoStr(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private enum EnhancementMode {
        RULE,
        LLM
    }
}
