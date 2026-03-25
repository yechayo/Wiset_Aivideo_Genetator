package com.comic.service.story;

import com.comic.ai.PromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.common.AiCallException;
import com.comic.common.BusinessException;
import com.comic.common.EpisodeInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.world.CharacterService;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardService {

    private final TextGenerationService textGenerationService;
    private final CharacterService characterService;
    private final WorldRuleService worldRuleService;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;

    private static final Set<String> ALLOWED_SHOT_TYPES = new HashSet<>(Arrays.asList(
            "WIDE_SHOT", "MID_SHOT", "CLOSE_UP", "OVER_SHOULDER"
    ));
    private static final Set<String> ALLOWED_CAMERA_ANGLES = new HashSet<>(Arrays.asList(
            "eye_level", "low_angle", "high_angle", "bird_eye"
    ));
    private static final Set<String> ALLOWED_PACING = new HashSet<>(Arrays.asList(
            "slow", "normal", "fast"
    ));
    private static final Set<String> ALLOWED_BUBBLE_TYPES = new HashSet<>(Arrays.asList(
            "speech", "thought", "narration_box"
    ));
    private static final Set<String> ALLOWED_COSTUME_STATE = new HashSet<>(Arrays.asList(
            "normal", "battle_worn"
    ));
    private static final String DEFAULT_CHARACTER_POSITION = "center";
    private static final String DEFAULT_CHARACTER_POSE = "standing";
    private static final String DEFAULT_CHARACTER_EXPRESSION = "neutral";
    private static final String DEFAULT_COSTUME_STATE = "normal";

    @Lazy
    @Autowired
    private PipelineService pipelineService;

    // ==================== Map 辅助方法 ====================

    private Map<String, Object> epInfo(Episode episode) {
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info == null) {
            info = new HashMap<>();
            episode.setEpisodeInfo(info);
        }
        return info;
    }

    private String getEpInfoStr(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Integer getEpInfoInt(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? ((Number) v).intValue() : null;
    }

    // ==================== 公开方法 ====================

    public String generateStoryboard(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }

        episode.setStatus("STORYBOARD_GENERATING");
        epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);
        epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
        episodeRepository.updateById(episode);

        try {
            String result = generateWithRetry(episode);

            epInfo(episode).put("storyboardJson", result);
            episode.setStatus("STORYBOARD_DONE");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
            episodeRepository.updateById(episode);

            log.info("Storyboard generated: episodeId={}, episodeNum={}", episodeId, getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM));
            return result;
        } catch (Exception e) {
            episode.setStatus("STORYBOARD_FAILED");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, e.getMessage());
            episodeRepository.updateById(episode);
            throw e;
        }
    }

    public String generateStoryboardWithFeedback(Long episodeId, String feedback) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }

        episode.setStatus("STORYBOARD_GENERATING");
        epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);
        epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
        episodeRepository.updateById(episode);

        try {
            String result = generateWithRetryAndFeedback(episode, feedback);

            epInfo(episode).put("storyboardJson", result);
            episode.setStatus("STORYBOARD_DONE");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
            episodeRepository.updateById(episode);

            log.info("Storyboard revised: episodeId={}, episodeNum={}", episodeId, getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM));
            return result;
        } catch (Exception e) {
            episode.setStatus("STORYBOARD_FAILED");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, e.getMessage());
            episodeRepository.updateById(episode);
            throw e;
        }
    }

    @Transactional
    public void startStoryboardGeneration(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }

        Episode nextEpisode = findNextPendingEpisode(projectId);
        if (nextEpisode == null) {
            log.warn("No pending episodes for storyboard generation: projectId={}", projectId);
            if (ProjectStatus.STORYBOARD_GENERATING.getCode().equals(project.getStatus())) {
                project.setStatus(ProjectStatus.STORYBOARD_REVIEW.getCode());
                projectRepository.updateById(project);
            }
            return;
        }

        project.setStatus(ProjectStatus.STORYBOARD_GENERATING.getCode());
        projectRepository.updateById(project);
        markEpisodeGenerating(nextEpisode);

        generateSingleEpisodeAsync(projectId, nextEpisode);
    }

    @Transactional
    public void confirmEpisodeStoryboard(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("Episode not found");
        }

        episode.setStatus("STORYBOARD_CONFIRMED");
        episodeRepository.updateById(episode);

        String projectId = episode.getProjectId();
        Episode nextEpisode = findNextPendingEpisode(projectId);
        if (nextEpisode != null) {
            Project project = projectRepository.findByProjectId(projectId);
            project.setStatus(ProjectStatus.STORYBOARD_GENERATING.getCode());
            projectRepository.updateById(project);
            generateSingleEpisodeAsync(projectId, nextEpisode);
        } else {
            log.info("All episodes confirmed: projectId={}", projectId);
        }
    }

    @Transactional
    public void reviseEpisodeStoryboard(Long episodeId, String feedback) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("Episode not found");
        }

        String projectId = episode.getProjectId();
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null) {
            project.setStatus(ProjectStatus.STORYBOARD_GENERATING.getCode());
            projectRepository.updateById(project);
        }
        markEpisodeGenerating(episode);

        final Long epId = episodeId;
        final String fb = feedback;
        new Thread(() -> {
            try {
                generateStoryboardWithFeedback(epId, fb);
                pipelineService.advancePipeline(projectId, "storyboard_generated");
            } catch (Exception e) {
                log.error("Storyboard revision failed: episodeId={}", epId, e);
                updateEpisodeToFailedIfNeeded(epId, e.getMessage());
                updateProjectToFailed(projectId);
            }
        }).start();
    }

    @Transactional
    public void retryFailedStoryboard(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("Episode not found");
        }
        if (!canRetryEpisode(episode)) {
            throw new BusinessException("Storyboard generation is still in progress for this episode");
        }

        String projectId = episode.getProjectId();
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null) {
            project.setStatus(ProjectStatus.STORYBOARD_GENERATING.getCode());
            projectRepository.updateById(project);
        }
        markEpisodeGenerating(episode);

        final Long epId = episodeId;
        new Thread(() -> {
            try {
                generateStoryboard(epId);
                pipelineService.advancePipeline(projectId, "storyboard_generated");
            } catch (Exception e) {
                log.error("Storyboard retry failed: episodeId={}", epId, e);
                updateEpisodeToFailedIfNeeded(epId, e.getMessage());
                updateProjectToFailed(projectId);
            }
        }).start();
    }

    @Transactional
    public void startProductionFromStoryboard(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }

        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            if (!"STORYBOARD_CONFIRMED".equals(ep.getStatus())) {
                throw new BusinessException("All episodes must be storyboard-confirmed before production");
            }
        }

        pipelineService.advancePipeline(projectId, "start_production");
    }

    // ==================== 私有方法 ====================

    private void generateSingleEpisodeAsync(String projectId, Episode episode) {
        final Long epId = episode.getId();
        new Thread(() -> {
            try {
                generateStoryboard(epId);
                pipelineService.advancePipeline(projectId, "storyboard_generated");
            } catch (Exception e) {
                Integer epNum = null;
                Episode ep = episodeRepository.selectById(epId);
                if (ep != null) epNum = getEpInfoInt(ep, EpisodeInfoKeys.EPISODE_NUM);
                log.error("Storyboard generation failed: episodeId={}, episodeNum={}", epId, epNum != null ? epNum : "unknown", e);
                updateEpisodeToFailedIfNeeded(epId, e.getMessage());
                updateProjectToFailed(projectId);
            }
        }).start();
    }

    private Episode findNextPendingEpisode(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            if ("DRAFT".equals(ep.getStatus()) || ep.getStatus() == null) {
                return ep;
            }
        }
        for (Episode ep : episodes) {
            if ("STORYBOARD_FAILED".equals(ep.getStatus())) {
                return ep;
            }
        }
        for (Episode ep : episodes) {
            if ("STORYBOARD_GENERATING".equals(ep.getStatus())) {
                String errorMsg = getEpInfoStr(ep, EpisodeInfoKeys.ERROR_MSG);
                if (errorMsg != null && !errorMsg.trim().isEmpty()) {
                    return ep;
                }
            }
        }
        return null;
    }

    private void updateProjectToFailed(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null && ProjectStatus.STORYBOARD_GENERATING.getCode().equals(project.getStatus())) {
            project.setStatus(ProjectStatus.STORYBOARD_GENERATING_FAILED.getCode());
            projectRepository.updateById(project);
        }
    }

    private boolean canRetryEpisode(Episode episode) {
        if (episode == null) {
            return false;
        }
        if ("STORYBOARD_FAILED".equals(episode.getStatus())) {
            return true;
        }
        if ("STORYBOARD_GENERATING".equals(episode.getStatus())) {
            Map<String, Object> info = episode.getEpisodeInfo();
            if (info != null) {
                Object errorMsg = info.get(EpisodeInfoKeys.ERROR_MSG);
                return errorMsg != null && !errorMsg.toString().trim().isEmpty();
            }
        }
        return false;
    }

    private void markEpisodeGenerating(Episode episode) {
        if (episode == null) {
            return;
        }
        episode.setStatus("STORYBOARD_GENERATING");
        epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);
        epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
        episodeRepository.updateById(episode);
    }

    private void updateEpisodeToFailedIfNeeded(Long episodeId, String errorMsg) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            return;
        }

        boolean alreadyFinalized = "STORYBOARD_DONE".equals(episode.getStatus())
                || "STORYBOARD_CONFIRMED".equals(episode.getStatus());
        if (alreadyFinalized) {
            return;
        }

        episode.setStatus("STORYBOARD_FAILED");
        if (errorMsg != null && !errorMsg.trim().isEmpty()) {
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, errorMsg);
        }
        episodeRepository.updateById(episode);
    }

    private String generateWithRetry(Episode episode) {
        WorldConfigModel world = worldRuleService.getWorldConfig(episode.getProjectId());
        List<CharacterStateModel> charStates = characterService.getCurrentStates(episode.getProjectId());

        String systemPrompt = buildSystemPrompt(world, charStates);
        String userPrompt = buildUserPrompt(episode);

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String currentSystemPrompt = buildRetrySystemPrompt(systemPrompt, attempt, lastError);

                String rawResult = textGenerationService.generate(currentSystemPrompt, userPrompt);
                String cleanResult = cleanJsonOutput(rawResult);

                JsonNode jsonNode = objectMapper.readTree(cleanResult);
                JsonNode normalizedNode = normalizeStoryboardJson(jsonNode, episode, charStates);
                String normalizedJson = objectMapper.writeValueAsString(normalizedNode);

                validateStoryboardJson(normalizedNode);
                characterService.updateStatesFromStoryboard(episode.getProjectId(), normalizedNode);

                epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, attempt - 1);
                return normalizedJson;
            } catch (AiCallException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("Storyboard generation attempt failed: episodeId={}, attempt={}", episode.getId(), attempt, e);
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("Storyboard generation failed after retries: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    private String generateWithRetryAndFeedback(Episode episode, String feedback) {
        WorldConfigModel world = worldRuleService.getWorldConfig(episode.getProjectId());
        List<CharacterStateModel> charStates = characterService.getCurrentStates(episode.getProjectId());

        String systemPrompt = buildSystemPrompt(world, charStates);
        String userPrompt = buildRevisionUserPrompt(episode, feedback);

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String currentSystemPrompt = buildRetrySystemPrompt(systemPrompt, attempt, lastError);

                String rawResult = textGenerationService.generate(currentSystemPrompt, userPrompt);
                String cleanResult = cleanJsonOutput(rawResult);

                JsonNode jsonNode = objectMapper.readTree(cleanResult);
                JsonNode normalizedNode = normalizeStoryboardJson(jsonNode, episode, charStates);
                String normalizedJson = objectMapper.writeValueAsString(normalizedNode);

                validateStoryboardJson(normalizedNode);
                characterService.updateStatesFromStoryboard(episode.getProjectId(), normalizedNode);

                epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, attempt - 1);
                return normalizedJson;
            } catch (AiCallException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("Storyboard revision attempt failed: episodeId={}, attempt={}", episode.getId(), attempt, e);
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("Storyboard revision failed after retries: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    private JsonNode normalizeStoryboardJson(JsonNode jsonNode, Episode episode, List<CharacterStateModel> charStates) {
        if (jsonNode == null || !jsonNode.isObject()) {
            return jsonNode;
        }
        JsonNode panelsNode = jsonNode.get("panels");
        if (!panelsNode.isArray()) {
            return jsonNode;
        }

        Map<String, String> characterLookup = buildCharacterLookup(charStates);
        ArrayNode panels = (ArrayNode) panelsNode;
        for (int i = 0; i < panels.size(); i++) {
            JsonNode panelNode = panels.get(i);
            if (panelNode == null || !panelNode.isObject()) {
                continue;
            }
            ObjectNode panel = (ObjectNode) panelNode;
            normalizePanelId(panel, episode, i);
            normalizeCharacterIds(panel, characterLookup);
        }
        return jsonNode;
    }

    private void normalizePanelId(ObjectNode panel, Episode episode, int panelIndex) {
        if (!hasNonBlankText(panel, "panel_id")) {
            panel.put("panel_id", buildPanelId(getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM), panelIndex));
            return;
        }

        JsonNode panelId = panel.get("panel_id");
        if (panelId != null && !panelId.isTextual()) {
            panel.put("panel_id", buildPanelId(getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM), panelIndex));
        }
    }

    private Map<String, String> buildCharacterLookup(List<CharacterStateModel> charStates) {
        Map<String, String> lookup = new HashMap<>();
        if (charStates == null) {
            return lookup;
        }

        for (CharacterStateModel charState : charStates) {
            if (charState == null) {
                continue;
            }
            String charId = trimToNull(charState.getCharId());
            if (charId == null) {
                continue;
            }

            String name = trimToNull(charState.getName());
            if (name != null) {
                lookup.put(name, charId);
            }
        }
        return lookup;
    }

    private void normalizeCharacterIds(ObjectNode panel, Map<String, String> characterLookup) {
        JsonNode charactersNode = panel.get("characters");
        if (charactersNode == null || !charactersNode.isArray()) {
            return;
        }

        ArrayNode characters = (ArrayNode) charactersNode;
        for (int i = 0; i < characters.size(); i++) {
            JsonNode characterNode = characters.get(i);
            if (characterNode == null || !characterNode.isObject()) {
                continue;
            }

            ObjectNode character = (ObjectNode) characterNode;
            if (!hasNonBlankText(character, "char_id")) {
                String alias = firstNonBlankValue(character, "id", "character_id", "characterId", "charId");
                if (alias != null) {
                    character.put("char_id", alias);
                } else {
                    String characterName = firstNonBlankValue(character, "name", "character_name", "characterName");
                    String mappedCharId = characterName != null ? characterLookup.get(characterName) : null;
                    if (mappedCharId != null) {
                        character.put("char_id", mappedCharId);
                    }
                }
            }

            normalizeCharacterField(character, "position", DEFAULT_CHARACTER_POSITION,
                    "placement", "location", "screen_position");
            normalizeCharacterField(character, "pose", DEFAULT_CHARACTER_POSE,
                    "action", "gesture", "body_pose");
            normalizeCharacterField(character, "expression", DEFAULT_CHARACTER_EXPRESSION,
                    "emotion", "facial_expression", "mood");
            normalizeEnumField(character, "costume_state", DEFAULT_COSTUME_STATE, ALLOWED_COSTUME_STATE,
                    "costume", "outfit_state", "outfit");
        }
    }

    private void normalizeCharacterField(ObjectNode node, String targetField, String defaultValue, String... aliasFields) {
        if (hasNonBlankText(node, targetField)) {
            return;
        }

        String alias = firstNonBlankValue(node, aliasFields);
        node.put(targetField, alias != null ? alias : defaultValue);
    }

    private void normalizeEnumField(ObjectNode node, String targetField, String defaultValue,
                                    Set<String> allowedValues, String... aliasFields) {
        String value = firstNonBlankValue(node, targetField);
        if (value == null) {
            value = firstNonBlankValue(node, aliasFields);
        }

        if (value == null || !allowedValues.contains(value)) {
            node.put(targetField, defaultValue);
            return;
        }

        node.put(targetField, value);
    }

    private String buildPanelId(Integer episodeNum, int panelIndex) {
        int safeEpisodeNum = episodeNum != null ? episodeNum : 0;
        return "ep" + safeEpisodeNum + "_p" + (panelIndex + 1);
    }

    private boolean hasNonBlankText(ObjectNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty();
    }

    private String firstNonBlankValue(ObjectNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }

            String text = value.asText();
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildRetrySystemPrompt(String systemPrompt, int attempt, Exception lastError) {
        String currentSystemPrompt = attempt > 1
                ? promptBuilder.addStricterConstraints(systemPrompt, attempt)
                : systemPrompt;

        if (attempt > 1 && isLikelyTruncatedJson(lastError)) {
            currentSystemPrompt += "\n\nTRUNCATION RECOVERY:\n"
                    + "Previous attempt output was truncated.\n"
                    + "Keep every non-dialogue string concise.\n"
                    + "Keep title, composition, background fields, and image_prompt_hint short.\n"
                    + "Avoid long quoted UI text unless it is essential to the scene.\n";
        }
        return currentSystemPrompt;
    }

    private boolean isLikelyTruncatedJson(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                if (lowerMessage.contains("unexpected end-of-input")
                        || lowerMessage.contains("unexpected end of input")
                        || lowerMessage.contains("was expecting closing quote")
                        || lowerMessage.contains("finish_reason=length")
                        || lowerMessage.contains("output truncated")
                        || lowerMessage.contains("truncated")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String cleanJsonOutput(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private void validateStoryboardJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("Storyboard must be a JSON object");
        }

        JsonNode episodeNode = requireField(json, "episode", "root");
        if (!episodeNode.isIntegralNumber()) {
            throw new IllegalStateException("Field root.episode must be an integer");
        }

        requireTextField(json, "title", "root");
        JsonNode panels = requireArrayField(json, "panels", "root", false);

        for (int i = 0; i < panels.size(); i++) {
            JsonNode panel = panels.get(i);
            if (panel == null || !panel.isObject()) {
                throw new IllegalStateException("Field root.panels[" + i + "] must be an object");
            }

            String panelPath = "root.panels[" + i + "]";
            requireTextField(panel, "panel_id", panelPath);
            requireEnumField(panel, "shot_type", panelPath, ALLOWED_SHOT_TYPES);
            requireEnumField(panel, "camera_angle", panelPath, ALLOWED_CAMERA_ANGLES);
            requireTextField(panel, "composition", panelPath);
            requireEnumField(panel, "pacing", panelPath, ALLOWED_PACING);
            requireTextField(panel, "image_prompt_hint", panelPath);

            JsonNode background = requireObjectField(panel, "background", panelPath);
            requireTextField(background, "scene_desc", panelPath + ".background");
            requireTextField(background, "time_of_day", panelPath + ".background");
            requireTextField(background, "atmosphere", panelPath + ".background");

            JsonNode characters = requireArrayField(panel, "characters", panelPath, true);
            for (int j = 0; j < characters.size(); j++) {
                JsonNode character = characters.get(j);
                if (character == null || !character.isObject()) {
                    throw new IllegalStateException(panelPath + ".characters[" + j + "] must be an object");
                }
                String charPath = panelPath + ".characters[" + j + "]";
                requireTextField(character, "char_id", charPath);
                requireTextField(character, "position", charPath);
                requireTextField(character, "pose", charPath);
                requireTextField(character, "expression", charPath);
                requireEnumField(character, "costume_state", charPath, ALLOWED_COSTUME_STATE);
            }

            JsonNode dialogue = requireArrayField(panel, "dialogue", panelPath, true);
            for (int j = 0; j < dialogue.size(); j++) {
                JsonNode dialogueNode = dialogue.get(j);
                if (dialogueNode == null || !dialogueNode.isTextual() || dialogueNode.asText().trim().isEmpty()) {
                    throw new IllegalStateException(panelPath + ".dialogue[" + j + "] must be a non-empty string");
                }
            }

            JsonNode sfx = requireArrayField(panel, "sfx", panelPath, true);
            for (int j = 0; j < sfx.size(); j++) {
                JsonNode sfxNode = sfx.get(j);
                if (sfxNode == null || !sfxNode.isTextual() || sfxNode.asText().trim().isEmpty()) {
                    throw new IllegalStateException(panelPath + ".sfx[" + j + "] must be a non-empty string");
                }
            }
        }
    }

    private JsonNode requireField(JsonNode node, String field, String path) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            throw new IllegalStateException("Missing required field: " + path + "." + field);
        }
        return node.get(field);
    }

    private JsonNode requireObjectField(JsonNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isObject()) {
            throw new IllegalStateException("Field " + path + "." + field + " must be an object");
        }
        return value;
    }

    private JsonNode requireArrayField(JsonNode node, String field, String path, boolean allowEmpty) {
        JsonNode value = requireField(node, field, path);
        if (!value.isArray()) {
            throw new IllegalStateException("Field " + path + "." + field + " must be an array");
        }
        if (!allowEmpty && value.size() == 0) {
            throw new IllegalStateException("Field " + path + "." + field + " must not be empty");
        }
        return value;
    }

    private String requireTextField(JsonNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalStateException("Field " + path + "." + field + " must be a non-empty string");
        }
        return value.asText();
    }

    private String requireEnumField(JsonNode node, String field, String path, Set<String> allowedValues) {
        String value = requireTextField(node, field, path);
        if (!allowedValues.contains(value)) {
            throw new IllegalStateException("Field " + path + "." + field + " has invalid value: " + value);
        }
        return value;
    }

    private String getRecentMemory(String projectId, int currentEp) {
        int startEp = Math.max(1, currentEp - 5);
        List<Episode> recentEps = episodeRepository.findByProjectId(projectId).stream()
                .filter(ep -> {
                    Integer epNum = getEpInfoInt(ep, EpisodeInfoKeys.EPISODE_NUM);
                    return epNum != null && epNum >= startEp && epNum < currentEp;
                })
                .sorted((a, b) -> {
                    Integer aNum = getEpInfoInt(a, EpisodeInfoKeys.EPISODE_NUM);
                    Integer bNum = getEpInfoInt(b, EpisodeInfoKeys.EPISODE_NUM);
                    return Integer.compare(aNum != null ? aNum : 0, bNum != null ? bNum : 0);
                })
                .collect(java.util.stream.Collectors.toList());
        if (recentEps.isEmpty()) {
            return "This is the first episode.";
        }

        StringBuilder sb = new StringBuilder();
        for (Episode ep : recentEps) {
            Integer epNum = getEpInfoInt(ep, EpisodeInfoKeys.EPISODE_NUM);
            String title = getEpInfoStr(ep, EpisodeInfoKeys.TITLE);
            sb.append("[EP").append(epNum != null ? epNum : "?").append("] ")
                    .append(title != null ? title : "")
                    .append(": ")
                    .append(preferredEpisodeText(ep))
                    .append("\n");
        }
        return sb.toString();
    }

    private String buildSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates) {
        return promptBuilder.buildStoryboardSystemPrompt(world, charStates);
    }

    private String buildUserPrompt(Episode episode) {
        Integer epNum = getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM);
        return promptBuilder.buildEpisodeUserPrompt(
                epNum != null ? epNum : 0,
                preferredEpisodeText(episode),
                getRecentMemory(episode.getProjectId(), epNum != null ? epNum : 0)
        );
    }

    private String buildRevisionUserPrompt(Episode episode, String feedback) {
        Integer epNum = getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM);
        String storyboardJson = getEpInfoStr(episode, "storyboardJson");
        return promptBuilder.buildStoryboardRevisionUserPrompt(
                epNum != null ? epNum : 0,
                preferredEpisodeText(episode),
                getRecentMemory(episode.getProjectId(), epNum != null ? epNum : 0),
                storyboardJson,
                feedback
        );
    }

    private String preferredEpisodeText(Episode episode) {
        String content = getEpInfoStr(episode, EpisodeInfoKeys.CONTENT);
        if (content != null && !content.trim().isEmpty()) {
            return content;
        }
        String outlineNode = getEpInfoStr(episode, EpisodeInfoKeys.OUTLINE_NODE);
        return outlineNode != null ? outlineNode : "";
    }
}