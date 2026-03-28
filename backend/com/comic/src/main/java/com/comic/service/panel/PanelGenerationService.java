package com.comic.service.panel;

import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.common.AiCallException;
import com.comic.common.BusinessException;
import com.comic.common.EpisodeInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
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
import java.util.stream.Collectors;

/**
 * 分镜生成服务（两步生成：先规划再细化）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PanelGenerationService {

    private final TextGenerationService textGenerationService;
    private final CharacterService characterService;
    private final WorldRuleService worldRuleService;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final PanelPromptBuilder panelPromptBuilder;

    private static final Set<String> ALLOWED_SHOT_TYPES = new HashSet<>(Arrays.asList(
            "WIDE_SHOT", "MID_SHOT", "CLOSE_UP", "OVER_SHOULDER",
            "EXTREME_CLOSE_UP", "FULL_SHOT", "LONG_SHOT", "TWO_SHOT",
            "POV", "ESTABLISHING_SHOT"
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
    private static final int DEFAULT_EPISODE_DURATION = 60;

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

    private int getProjectInfoIntOrDefault(Project project, String key) {
        if (project == null) {
            return DEFAULT_EPISODE_DURATION;
        }
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? ((Number) v).intValue() : DEFAULT_EPISODE_DURATION;
    }

    // ==================== 公开方法 ====================

    /**
     * 两步生成分镜：Step1 规划 panel 列表 → Step2 逐个细化 panel 详情
     */
    public String generatePanels(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }

        episode.setStatus("PANEL_GENERATING");
        epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);
        epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
        episodeRepository.updateById(episode);

        try {
            WorldConfigModel world = worldRuleService.getWorldConfig(episode.getProjectId());
            List<CharacterStateModel> charStates = characterService.getCurrentStates(episode.getProjectId());
            Integer epNum = getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM);
            int safeEpNum = epNum != null ? epNum : 0;
            String content = preferredEpisodeText(episode);
            String recentMemory = getRecentMemory(episode.getProjectId(), safeEpNum);

            Project project = projectRepository.findByProjectId(episode.getProjectId());
            int episodeDuration = getProjectInfoIntOrDefault(project, ProjectInfoKeys.EPISODE_DURATION);

            // ===== Step 1: 分镜规划 =====
            log.info("Panel generation Step1 (plan): episodeId={}, epNum={}", episodeId, safeEpNum);
            String planSystemPrompt = panelPromptBuilder.buildPlanSystemPrompt(world, charStates, episodeDuration);
            String planUserPrompt = panelPromptBuilder.buildPlanUserPrompt(safeEpNum, content, recentMemory, episodeDuration);

            String planRawResult = callLlmWithRetry(planSystemPrompt, planUserPrompt, 3, "plan", episode);
            JsonNode planJson = objectMapper.readTree(cleanJsonOutput(planRawResult));

            // 保存 plan 到 episode info
            epInfo(episode).put("panelPlan", objectMapper.writeValueAsString(planJson));
            episodeRepository.updateById(episode);

            // ===== Step 2: 逐 Panel 细化 =====
            JsonNode panelsPlan = planJson.get("panels");
            if (panelsPlan == null || !panelsPlan.isArray() || panelsPlan.size() == 0) {
                throw new IllegalStateException("Panel plan has no panels");
            }

            // Validate and fill missing duration fields from Step1
            for (int i = 0; i < panelsPlan.size(); i++) {
                JsonNode panelPlan = panelsPlan.get(i);
                if (!panelPlan.has("duration") || !panelPlan.get("duration").isInt()) {
                    int defaultDuration = Math.min(16, Math.max(10,
                            (int) Math.round((double) episodeDuration / panelsPlan.size())));
                    ((ObjectNode) panelPlan).put("duration", defaultDuration);
                    log.warn("Panel {} missing duration, assigned default: {}", i, defaultDuration);
                }
            }

            String title = planJson.has("title") ? planJson.get("title").asText() : "";
            ArrayNode detailedPanels = objectMapper.createArrayNode();
            String previousPanelSummary = null;

            String detailSystemPrompt = panelPromptBuilder.buildPanelDetailSystemPrompt(world, charStates);

            // 先删除该 episode 下旧的 Panel（逻辑删除），再逐个生成
            List<Panel> oldPanels = panelRepository.findByEpisodeId(episodeId);
            for (Panel old : oldPanels) {
                panelRepository.deleteById(old.getId());
            }

            // ===== 预先创建骨架 Panel 记录，让前端能知道总共有多少个 panels =====
            List<Panel> skeletonPanels = new java.util.ArrayList<>();
            for (int i = 0; i < panelsPlan.size(); i++) {
                JsonNode panelPlan = panelsPlan.get(i);
                String panelId = panelPlan.has("panel_id") ? panelPlan.get("panel_id").asText() : "p" + (i + 1);

                // 创建骨架 Panel，只包含基本信息
                Panel skeletonPanel = new Panel();
                skeletonPanel.setEpisodeId(episodeId);
                skeletonPanel.setStatus("GENERATING"); // 标记为生成中

                // 构建 skeleton panelInfo（只包含 Step1 的基本信息）
                Map<String, Object> skeletonInfo = new java.util.LinkedHashMap<>();
                skeletonInfo.put("panel_id", panelId);
                skeletonInfo.put("planPanelId", panelId); // 用于匹配 panelPlan
                if (panelPlan.has("scene_summary")) {
                    skeletonInfo.put("scene_summary", panelPlan.get("scene_summary").asText());
                }
                if (panelPlan.has("characters")) {
                    skeletonInfo.put("characters", objectMapper.convertValue(panelPlan.get("characters"), List.class));
                }
                if (panelPlan.has("mood")) {
                    skeletonInfo.put("mood", panelPlan.get("mood").asText());
                }
                if (panelPlan.has("time_of_day")) {
                    skeletonInfo.put("time_of_day", panelPlan.get("time_of_day").asText());
                }
                if (panelPlan.has("duration")) {
                    skeletonInfo.put("duration", panelPlan.get("duration").asInt());
                }
                skeletonPanel.setPanelInfo(skeletonInfo);

                panelRepository.insert(skeletonPanel);
                skeletonPanels.add(skeletonPanel);
            }
            log.info("Created {} skeleton panels: episodeId={}", skeletonPanels.size(), episodeId);

            int confirmedDurationTotal = 0;
            for (int i = 0; i < panelsPlan.size(); i++) {
                JsonNode panelPlan = panelsPlan.get(i);
                String panelPlanStr = objectMapper.writeValueAsString(panelPlan);

                log.info("Panel generation Step2 (detail): episodeId={}, panel {}/{}", episodeId, i + 1, panelsPlan.size());

                int plannedDuration = panelPlan.has("duration") ? panelPlan.get("duration").asInt() : 10;
                int remainingPanels = panelsPlan.size() - i;
                int remainingBudget = episodeDuration - confirmedDurationTotal;

                String detailUserPrompt = panelPromptBuilder.buildPanelDetailUserPrompt(
                        safeEpNum, i + 1, panelPlanStr, content, previousPanelSummary,
                        plannedDuration, remainingBudget, remainingPanels);

                String detailRawResult = callLlmWithRetry(detailSystemPrompt, detailUserPrompt, 3,
                        "detail[" + (i + 1) + "]", episode);
                JsonNode detailJson = objectMapper.readTree(cleanJsonOutput(detailRawResult));

                // 标准化 + 校验
                normalizePanelDetail(detailJson, episode, charStates, i);
                validatePanelDetail(detailJson);

                // Track actual duration from Step2 output
                int actualDuration = detailJson.has("duration") && detailJson.get("duration").isInt()
                        ? detailJson.get("duration").asInt() : plannedDuration;
                confirmedDurationTotal += actualDuration;

                detailedPanels.add(detailJson);

                // ===== 增量保存：更新骨架 Panel 为完整详情 =====
                String targetPanelId = detailJson.has("panel_id") ? detailJson.get("panel_id").asText() : "p" + (i + 1);
                Panel skeletonPanel = skeletonPanels.stream()
                        .filter(p -> {
                            Map<String, Object> info = p.getPanelInfo();
                            String panelId = info != null ? (String) info.get("panel_id") : null;
                            return targetPanelId.equals(panelId);
                        })
                        .findFirst()
                        .orElse(null);

                if (skeletonPanel != null) {
                    // 保留骨架Panel中的scene_summary（Step1的剧情摘要）
                    Map<String, Object> skeletonInfo = skeletonPanel.getPanelInfo();
                    String sceneSummary = (String) skeletonInfo.get("scene_summary");

                    // 更新骨架Panel为完整详情
                    skeletonPanel.setStatus("CREATED");
                    Map<String, Object> detailInfo = objectMapper.convertValue(detailJson, Map.class);

                    // 如果Step2的detail没有scene_summary，但骨架Panel有，则保留
                    if (sceneSummary != null && !detailInfo.containsKey("scene_summary")) {
                        detailInfo.put("scene_summary", sceneSummary);
                    }

                    skeletonPanel.setPanelInfo(detailInfo);
                    panelRepository.updateById(skeletonPanel);
                    log.info("Panel updated progressively: episodeId={}, panel {}/{}, panelId={}",
                            episodeId, i + 1, panelsPlan.size(), targetPanelId);
                } else {
                    // 兜底：如果找不到骨架Panel，创建新的（理论上不应该发生）
                    // 从panelPlan中获取scene_summary
                    JsonNode currentPanelPlan = panelsPlan.get(i);
                    String sceneSummaryFromPlan = currentPanelPlan.has("scene_summary")
                            ? currentPanelPlan.get("scene_summary").asText() : null;

                    Panel panelEntity = new Panel();
                    panelEntity.setEpisodeId(episodeId);
                    panelEntity.setStatus("CREATED");
                    Map<String, Object> detailInfo = objectMapper.convertValue(detailJson, Map.class);

                    // 保留scene_summary
                    if (sceneSummaryFromPlan != null && !detailInfo.containsKey("scene_summary")) {
                        detailInfo.put("scene_summary", sceneSummaryFromPlan);
                    }

                    panelEntity.setPanelInfo(detailInfo);
                    panelRepository.insert(panelEntity);
                    log.warn("Panel created (fallback): episodeId={}, panel {}/{}, panelId={}",
                            episodeId, i + 1, panelsPlan.size(), targetPanelId);
                }

                // 用当前 panel 的概要作为下一个 panel 的上下文
                previousPanelSummary = buildPanelSummary(detailJson);
            }

            // Calibrate durations to ensure total is within ±5% of target
            calibrateDurations(episodeDuration, detailedPanels);

            // 构建最终结果
            ObjectNode result = objectMapper.createObjectNode();
            result.put("episode", safeEpNum);
            result.put("title", title);
            result.set("panels", detailedPanels);
            String resultJson = objectMapper.writeValueAsString(result);

            // 更新角色状态
            characterService.updateStatesFromPanelJson(episode.getProjectId(), result);

            epInfo(episode).put("panelJson", resultJson);
            episode.setStatus("PANEL_DONE");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
            episodeRepository.updateById(episode);

            log.info("Panels generated: episodeId={}, epNum={}, panelCount={}", episodeId, safeEpNum, detailedPanels.size());
            return resultJson;
        } catch (Exception e) {
            episode.setStatus("PANEL_FAILED");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, e.getMessage());
            episodeRepository.updateById(episode);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * 带反馈修改分镜（全量重新生成，传入旧结果 + 反馈）
     */
    public String revisePanels(Long episodeId, String feedback) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }

        episode.setStatus("PANEL_GENERATING");
        epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);
        epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
        episodeRepository.updateById(episode);

        try {
            String result = generateWithRetryAndFeedback(episode, feedback);

            epInfo(episode).put("panelJson", result);
            episode.setStatus("PANEL_DONE");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
            episodeRepository.updateById(episode);

            log.info("Panels revised: episodeId={}, epNum={}", episodeId, getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM));
            return result;
        } catch (Exception e) {
            episode.setStatus("PANEL_FAILED");
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, e.getMessage());
            episodeRepository.updateById(episode);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void confirmPanels(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("Episode not found");
        }

        episode.setStatus("PANEL_CONFIRMED");
        episodeRepository.updateById(episode);

        String projectId = episode.getProjectId();
        Episode nextEpisode = findNextPendingEpisode(projectId);
        if (nextEpisode != null) {
            Project project = projectRepository.findByProjectId(projectId);
            pipelineService.advancePipeline(projectId, "start_panels");
            generateSingleEpisodeAsync(projectId, nextEpisode);
        } else {
            log.info("All episodes confirmed: projectId={}", projectId);
        }
    }

    @Transactional
    public void retryFailedPanels(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("Episode not found");
        }
        if (!canRetryEpisode(episode)) {
            throw new BusinessException("Panel generation is still in progress for this episode");
        }

        String projectId = episode.getProjectId();
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null) {
            pipelineService.advancePipeline(projectId, "start_panels");
        }
        markEpisodeGenerating(episode);

        final Long epId = episodeId;
        new Thread(() -> {
            try {
                generatePanels(epId);
                pipelineService.advancePipeline(projectId, "panel_generated");
            } catch (Exception e) {
                log.error("Panel retry failed: episodeId={}", epId, e);
                updateEpisodeToFailedIfNeeded(epId, e.getMessage());
                updateProjectToFailed(projectId);
            }
        }).start();
    }

    /**
     * 修改单个分镜（AI 重新生成，保留已有的生产状态字段）
     */
    public void reviseSinglePanel(Long panelId, String feedback) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");

        Episode episode = episodeRepository.selectById(panel.getEpisodeId());
        if (episode == null) throw new BusinessException("剧集不存在");

        Integer epNum = getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM);

        WorldConfigModel world = worldRuleService.getWorldConfig(episode.getProjectId());
        List<CharacterStateModel> charStates = characterService.getCurrentStates(episode.getProjectId());

        String systemPrompt = panelPromptBuilder.buildPanelDetailSystemPrompt(world, charStates);

        // 将单个 panel 包装成 episode 格式（复用已有的 LLM 输出格式）
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("episode", epNum != null ? epNum : 0);
        wrapped.put("title", "");
        wrapped.put("panels", Arrays.asList(panel.getPanelInfo()));
        String currentPanelJson;
        try {
            currentPanelJson = objectMapper.writeValueAsString(wrapped);
        } catch (Exception e) {
            throw new BusinessException("序列化分镜数据失败");
        }

        String userPrompt = panelPromptBuilder.buildRevisionUserPrompt(
                epNum != null ? epNum : 0,
                preferredEpisodeText(episode),
                getRecentMemory(episode.getProjectId(), epNum != null ? epNum : 0),
                currentPanelJson,
                feedback
        );

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String rawResult = textGenerationService.generate(systemPrompt, userPrompt);
                String cleanResult = cleanJsonOutput(rawResult);
                JsonNode jsonNode = objectMapper.readTree(cleanResult);

                // LLM 可能返回单个 panel 对象或 episode 包裹格式，统一处理
                JsonNode normalizedNode;
                if (jsonNode.has("panels")) {
                    normalizedNode = normalizePanelJson(jsonNode, episode, charStates);
                    validatePanelJson(normalizedNode);
                } else {
                    // 单个 panel 格式，包装成 episode 格式
                    ObjectNode wrapper = objectMapper.createObjectNode();
                    wrapper.put("episode", epNum != null ? epNum : 0);
                    wrapper.put("title", "");
                    wrapper.set("panels", objectMapper.createArrayNode().add(jsonNode));
                    normalizedNode = normalizePanelJson(wrapper, episode, charStates);
                }

                // 提取第一个 panel
                JsonNode panels = normalizedNode.get("panels");
                if (panels == null || !panels.isArray() || panels.isEmpty()) {
                    throw new IllegalStateException("LLM 返回数据中缺少 panels 数组");
                }
                Map<String, Object> newPanelInfo = objectMapper.convertValue(panels.get(0), Map.class);

                // 保留生产状态字段
                Map<String, Object> existingInfo = panel.getPanelInfo() != null
                        ? panel.getPanelInfo() : new HashMap<>();
                Set<String> productionFields = new HashSet<>(Arrays.asList(
                        "backgroundUrl", "backgroundStatus", "comicUrl", "comicStatus",
                        "videoUrl", "videoStatus", "videoTaskId", "videoDuration", "errorMessage"
                ));
                Map<String, Object> preserved = new HashMap<>();
                for (String field : productionFields) {
                    Object value = existingInfo.get(field);
                    if (value != null) preserved.put(field, value);
                }

                existingInfo.putAll(newPanelInfo);
                existingInfo.putAll(preserved);
                panel.setPanelInfo(existingInfo);
                panelRepository.updateById(panel);

                // 更新角色状态
                characterService.updateStatesFromPanelJson(episode.getProjectId(), normalizedNode);

                log.info("单分镜修改完成: panelId={}, attempt={}", panelId, attempt);
                return;
            } catch (AiCallException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("单分镜修改失败: panelId={}, attempt={}", panelId, attempt, e);
                if (attempt < 3) {
                    try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new RuntimeException("单分镜修改失败: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    @Transactional
    public void startPanelGeneration(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }

        Episode nextEpisode = findNextPendingEpisode(projectId);
        if (nextEpisode == null) {
            log.warn("No pending episodes for panel generation: projectId={}", projectId);
            if (ProjectStatus.PANEL_GENERATING.getCode().equals(project.getStatus())) {
                pipelineService.advancePipeline(projectId, "panels_generated");
            }
            return;
        }

        // 状态已由 advancePipeline("start_panels") 设置
        markEpisodeGenerating(nextEpisode);

        generateSingleEpisodeAsync(projectId, nextEpisode);
    }

    @Transactional
    public void startProductionFromPanels(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }

        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            if (!"PANEL_CONFIRMED".equals(ep.getStatus())) {
                throw new BusinessException("All episodes must be panel-confirmed before production");
            }
        }

        pipelineService.advancePipeline(projectId, "start_production");
    }

    // ==================== LLM 调用 + 重试 ====================

    private String callLlmWithRetry(String systemPrompt, String userPrompt, int maxAttempts,
                                     String stepLabel, Episode episode) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String currentSystemPrompt = buildRetrySystemPrompt(systemPrompt, attempt, lastError);
                return textGenerationService.generate(currentSystemPrompt, userPrompt);
            } catch (AiCallException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("Panel generation {} attempt failed: episodeId={}, attempt={}", stepLabel, episode.getId(), attempt, e);
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new RuntimeException("Panel generation " + stepLabel + " failed after retries: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /**
     * 带反馈的修改（全量重新生成，保留旧的单步生成方式）
     */
    private String generateWithRetryAndFeedback(Episode episode, String feedback) {
        WorldConfigModel world = worldRuleService.getWorldConfig(episode.getProjectId());
        List<CharacterStateModel> charStates = characterService.getCurrentStates(episode.getProjectId());

        String systemPrompt = panelPromptBuilder.buildPanelDetailSystemPrompt(world, charStates);
        String userPrompt = buildRevisionUserPrompt(episode, feedback);

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String currentSystemPrompt = buildRetrySystemPrompt(systemPrompt, attempt, lastError);

                String rawResult = textGenerationService.generate(currentSystemPrompt, userPrompt);
                String cleanResult = cleanJsonOutput(rawResult);

                JsonNode jsonNode = objectMapper.readTree(cleanResult);
                JsonNode normalizedNode = normalizePanelJson(jsonNode, episode, charStates);
                String normalizedJson = objectMapper.writeValueAsString(normalizedNode);

                validatePanelJson(normalizedNode);
                characterService.updateStatesFromPanelJson(episode.getProjectId(), normalizedNode);

                epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, attempt - 1);
                return normalizedJson;
            } catch (AiCallException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("Panel revision attempt failed: episodeId={}, attempt={}", episode.getId(), attempt, e);
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("Panel revision failed after retries: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    // ==================== 私有方法 ====================

    private void generateSingleEpisodeAsync(String projectId, Episode episode) {
        final Long epId = episode.getId();
        new Thread(() -> {
            try {
                generatePanels(epId);
                pipelineService.advancePipeline(projectId, "panel_generated");
            } catch (Exception e) {
                Integer epNum = null;
                Episode ep = episodeRepository.selectById(epId);
                if (ep != null) epNum = getEpInfoInt(ep, EpisodeInfoKeys.EPISODE_NUM);
                log.error("Panel generation failed: episodeId={}, episodeNum={}", epId, epNum != null ? epNum : "unknown", e);
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
            if ("PANEL_FAILED".equals(ep.getStatus())) {
                return ep;
            }
        }
        for (Episode ep : episodes) {
            if ("PANEL_GENERATING".equals(ep.getStatus())) {
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
        if (project != null && ProjectStatus.PANEL_GENERATING.getCode().equals(project.getStatus())) {
            pipelineService.advancePipeline(projectId, "panels_failed");
        }
    }

    private boolean canRetryEpisode(Episode episode) {
        if (episode == null) {
            return false;
        }
        if ("PANEL_FAILED".equals(episode.getStatus())) {
            return true;
        }
        if ("PANEL_GENERATING".equals(episode.getStatus())) {
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
        episode.setStatus("PANEL_GENERATING");
        epInfo(episode).put(EpisodeInfoKeys.RETRY_COUNT, 0);
        epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, null);
        episodeRepository.updateById(episode);
    }

    private void updateEpisodeToFailedIfNeeded(Long episodeId, String errorMsg) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            return;
        }

        boolean alreadyFinalized = "PANEL_DONE".equals(episode.getStatus())
                || "PANEL_CONFIRMED".equals(episode.getStatus());
        if (alreadyFinalized) {
            return;
        }

        episode.setStatus("PANEL_FAILED");
        if (errorMsg != null && !errorMsg.trim().isEmpty()) {
            epInfo(episode).put(EpisodeInfoKeys.ERROR_MSG, errorMsg);
        }
        episodeRepository.updateById(episode);
    }

    // ==================== 标准化 ====================

    /**
     * 标准化单个 panel 详情（Step2 产出）
     */
    private void normalizePanelDetail(JsonNode jsonNode, Episode episode,
                                       List<CharacterStateModel> charStates, int panelIndex) {
        if (jsonNode == null || !jsonNode.isObject()) {
            return;
        }
        ObjectNode panel = (ObjectNode) jsonNode;

        normalizePanelId(panel, episode, panelIndex);
        normalizeCharacterIds(panel, buildCharacterLookup(charStates));
        normalizeShotType(panel);
        normalizeDialogue(panel);
        normalizeSfx(panel);
    }

    /**
     * 标准化完整 panel JSON（修改/重试时的全量输出）
     */
    private JsonNode normalizePanelJson(JsonNode jsonNode, Episode episode, List<CharacterStateModel> charStates) {
        if (jsonNode == null || !jsonNode.isObject()) {
            return jsonNode;
        }
        JsonNode panelsNode = jsonNode.get("panels");
        if (panelsNode == null || !panelsNode.isArray()) {
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
            normalizeShotType(panel);
            normalizeDialogue(panel);
            normalizeSfx(panel);
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

    private static final Map<String, String> SHOT_TYPE_ALIAS_MAP = new HashMap<>();
    static {
        // 常见 AI 生成的 shot_type 别名映射到标准值
        SHOT_TYPE_ALIAS_MAP.put("EXTREME_CLOSE_UP", "CLOSE_UP");
        SHOT_TYPE_ALIAS_MAP.put("ECU", "CLOSE_UP");
        SHOT_TYPE_ALIAS_MAP.put("FULL_SHOT", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("LONG_SHOT", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("WIDE", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("LONG", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("FULL", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("TWO_SHOT", "MID_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("OTS", "OVER_SHOULDER");
        SHOT_TYPE_ALIAS_MAP.put("OVER_THE_SHOULDER", "OVER_SHOULDER");
        SHOT_TYPE_ALIAS_MAP.put("POV", "CLOSE_UP");
        SHOT_TYPE_ALIAS_MAP.put("ESTABLISHING_SHOT", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("INSERT", "CLOSE_UP");
        SHOT_TYPE_ALIAS_MAP.put("CUTAWAY", "WIDE_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("MEDIUM_SHOT", "MID_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("MEDIUM", "MID_SHOT");
        SHOT_TYPE_ALIAS_MAP.put("CLOSE", "CLOSE_UP");
        SHOT_TYPE_ALIAS_MAP.put("MACRO", "CLOSE_UP");
    }

    private void normalizeShotType(ObjectNode panel) {
        JsonNode shotTypeNode = panel.get("shot_type");
        if (shotTypeNode == null || !shotTypeNode.isTextual()) return;
        String shotType = shotTypeNode.asText().trim().toUpperCase();
        if (ALLOWED_SHOT_TYPES.contains(shotType)) return;
        String mapped = SHOT_TYPE_ALIAS_MAP.get(shotType);
        if (mapped != null) {
            panel.put("shot_type", mapped);
        } else {
            panel.put("shot_type", "MID_SHOT");
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
            } else {
                // char_id 已有值，但可能 AI 把名字填进了 char_id（如 "T-1"），需验证并修正
                String currentCharId = character.get("char_id").asText("").trim();
                if (!currentCharId.startsWith("CHAR-") && !characterLookup.containsValue(currentCharId)) {
                    // 当前值不是合法 ID，尝试按名字查找
                    String mappedCharId = characterLookup.get(currentCharId);
                    if (mappedCharId != null) {
                        character.put("char_id", mappedCharId);
                        // 同时补上 name 字段（如果缺失）
                        if (!hasNonBlankText(character, "name")) {
                            character.put("name", currentCharId);
                        }
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

    private void normalizeDialogue(ObjectNode panel) {
        JsonNode dialogueNode = panel.get("dialogue");
        if (dialogueNode == null || !dialogueNode.isArray()) {
            return;
        }
        ArrayNode dialogue = (ArrayNode) dialogueNode;
        com.fasterxml.jackson.databind.node.ArrayNode normalizedDialogue =
                panel.objectNode().arrayNode();

        for (int i = 0; i < dialogue.size(); i++) {
            JsonNode item = dialogue.get(i);
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                String text = item.asText().trim();
                if (text.isEmpty()) continue;
                ObjectNode obj = normalizedDialogue.objectNode();
                obj.put("text", text);
                obj.put("bubble_type", "speech");
                normalizedDialogue.add(obj);
            } else if (item.isObject()) {
                ObjectNode obj = (ObjectNode) item;
                String text = firstNonBlankValue(obj, "text", "content", "line", "dialog", "dialogText");
                if (text == null || text.trim().isEmpty()) continue;
                if (!hasNonBlankText(obj, "text")) {
                    obj.put("text", text);
                }
                if (!hasNonBlankText(obj, "bubble_type")) {
                    normalizeEnumField(obj, "bubble_type", "speech", ALLOWED_BUBBLE_TYPES,
                            "type", "bubbleType");
                }
                normalizedDialogue.add(obj);
            }
        }
        panel.set("dialogue", normalizedDialogue);
    }

    private void normalizeSfx(ObjectNode panel) {
        JsonNode sfxNode = panel.get("sfx");
        if (sfxNode == null || !sfxNode.isArray()) {
            return;
        }
        ArrayNode sfx = (ArrayNode) sfxNode;
        com.fasterxml.jackson.databind.node.ArrayNode normalizedSfx =
                panel.objectNode().arrayNode();

        for (int i = 0; i < sfx.size(); i++) {
            JsonNode item = sfx.get(i);
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                String text = item.asText().trim();
                if (text.isEmpty()) continue;
                normalizedSfx.add(text);
            } else if (item.isObject()) {
                String text = firstNonBlankValue((ObjectNode) item,
                        "description", "name", "type", "desc", "text", "sound");
                if (text != null && !text.trim().isEmpty()) {
                    normalizedSfx.add(text.trim());
                }
            }
        }
        panel.set("sfx", normalizedSfx);
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

    // ==================== 校验 ====================

    /**
     * 校验单个 panel 详情（Step2 产出）
     */
    private void validatePanelDetail(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("Panel detail must be a JSON object");
        }

        String panelPath = "panel";
        requireTextField(json, "panel_id", panelPath);
        requireEnumField(json, "shot_type", panelPath, ALLOWED_SHOT_TYPES);
        requireEnumField(json, "camera_angle", panelPath, ALLOWED_CAMERA_ANGLES);
        requireTextField(json, "composition", panelPath);
        requireEnumField(json, "pacing", panelPath, ALLOWED_PACING);
        // 验证 duration 字段：必须是整数，范围 10-16
        JsonNode durationNode = json.get("duration");
        if (durationNode == null || !durationNode.isInt()) {
            throw new IllegalStateException(panelPath + ".duration must be an integer between 10 and 16");
        }
        int duration = durationNode.asInt();
        if (duration < 10 || duration > 16) {
            throw new IllegalStateException(panelPath + ".duration must be between 10 and 16, got: " + duration);
        }
        requireTextField(json, "image_prompt_hint", panelPath);

        JsonNode background = requireObjectField(json, "background", panelPath);
        requireTextField(background, "scene_desc", panelPath + ".background");
        requireTextField(background, "time_of_day", panelPath + ".background");
        requireTextField(background, "atmosphere", panelPath + ".background");

        JsonNode characters = requireArrayField(json, "characters", panelPath, true);
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

        validateDialogueAndSfx(json, panelPath);
    }

    /**
     * 校验完整 panel JSON（修改/重试时的全量输出）
     */
    private void validatePanelJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("Panel JSON must be a JSON object");
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

            validateDialogueAndSfx(panel, panelPath);
        }
    }

    private void validateDialogueAndSfx(JsonNode panel, String panelPath) {
        JsonNode dialogue = requireArrayField(panel, "dialogue", panelPath, true);
        for (int j = 0; j < dialogue.size(); j++) {
            JsonNode dialogueNode = dialogue.get(j);
            if (dialogueNode == null) {
                throw new IllegalStateException(panelPath + ".dialogue[" + j + "] must not be null");
            }
            if (dialogueNode.isTextual()) {
                if (dialogueNode.asText().trim().isEmpty()) {
                    throw new IllegalStateException(panelPath + ".dialogue[" + j + "] must be a non-empty string");
                }
            } else if (dialogueNode.isObject()) {
                String text = firstNonBlankValue((ObjectNode) dialogueNode, "text", "content", "line");
                if (text == null || text.trim().isEmpty()) {
                    throw new IllegalStateException(panelPath + ".dialogue[" + j + "].text must be a non-empty string");
                }
            } else {
                throw new IllegalStateException(panelPath + ".dialogue[" + j + "] must be a string or object");
            }
        }

        JsonNode sfx = requireArrayField(panel, "sfx", panelPath, true);
        for (int j = 0; j < sfx.size(); j++) {
            JsonNode sfxNode = sfx.get(j);
            if (sfxNode == null) {
                throw new IllegalStateException(panelPath + ".sfx[" + j + "] must not be null");
            }
            if (sfxNode.isTextual()) {
                if (sfxNode.asText().trim().isEmpty()) {
                    throw new IllegalStateException(panelPath + ".sfx[" + j + "] must be a non-empty string");
                }
            } else if (sfxNode.isObject()) {
                String text = firstNonBlankValue((ObjectNode) sfxNode, "description", "name", "text", "sound");
                if (text == null || text.trim().isEmpty()) {
                    throw new IllegalStateException(panelPath + ".sfx[" + j + "] object must contain a non-empty text field");
                }
            } else {
                throw new IllegalStateException(panelPath + ".sfx[" + j + "] must be a string or object");
            }
        }
    }

    // ==================== 通用工具方法 ====================

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
        if (allowedValues.contains(value)) {
            return value;
        }
        // Case-insensitive fallback
        for (String allowed : allowedValues) {
            if (allowed.equalsIgnoreCase(value)) {
                if (node.isObject()) {
                    ((ObjectNode) node).put(field, allowed);
                }
                return allowed;
            }
        }
        throw new IllegalStateException("Field " + path + "." + field + " has invalid value: " + value);
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
                ? panelPromptBuilder.addRetryConstraints(systemPrompt, attempt)
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

    /**
     * 构建 panel 摘要，用于传递给下一个 panel 作为上下文
     */
    private String buildPanelSummary(JsonNode detailJson) {
        StringBuilder sb = new StringBuilder();
        if (detailJson.has("panel_id")) {
            sb.append("[").append(detailJson.get("panel_id").asText()).append("] ");
        }
        if (detailJson.has("scene_summary")) {
            sb.append(detailJson.get("scene_summary").asText());
        } else if (detailJson.has("composition")) {
            sb.append(detailJson.get("composition").asText());
        }
        if (detailJson.has("characters") && detailJson.get("characters").isArray()) {
            List<String> charNames = new java.util.ArrayList<>();
            for (JsonNode c : detailJson.get("characters")) {
                if (c.has("name")) charNames.add(c.get("name").asText());
            }
            if (!charNames.isEmpty()) {
                sb.append(" (角色: ").append(String.join(", ", charNames)).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * 按比例校准所有 panel 时长，确保总时长在目标 ±5% 范围内
     */
    private void calibrateDurations(int targetDuration, ArrayNode detailedPanels) {
        if (detailedPanels == null || detailedPanels.size() == 0) {
            return;
        }

        int minTarget = (int) Math.round(targetDuration * 0.95);
        int maxTarget = (int) Math.round(targetDuration * 1.05);

        // Calculate total
        int totalActual = 0;
        for (int i = 0; i < detailedPanels.size(); i++) {
            JsonNode panel = detailedPanels.get(i);
            if (panel.has("duration") && panel.get("duration").isInt()) {
                totalActual += panel.get("duration").asInt();
            }
        }

        // Already within range
        if (totalActual >= minTarget && totalActual <= maxTarget) {
            log.info("Duration calibration not needed: total={} target={}[{},{}]", totalActual, targetDuration, minTarget, maxTarget);
            return;
        }

        log.info("Calibrating durations: total={} target={}[{},{}]", totalActual, targetDuration, minTarget, maxTarget);
        double ratio = (double) targetDuration / totalActual;

        // Proportional scaling + clamp to [10, 16]
        for (int i = 0; i < detailedPanels.size(); i++) {
            JsonNode panel = detailedPanels.get(i);
            if (panel.isObject() && panel.has("duration")) {
                int original = panel.get("duration").asInt();
                int calibrated = (int) Math.round(original * ratio);
                calibrated = Math.max(10, Math.min(16, calibrated));
                ((ObjectNode) panel).put("duration", calibrated);
            }
        }

        // Recalculate and fine-tune if still out of range
        int newTotal = 0;
        int[] durations = new int[detailedPanels.size()];
        for (int i = 0; i < detailedPanels.size(); i++) {
            durations[i] = detailedPanels.get(i).get("duration").asInt();
            newTotal += durations[i];
        }

        int maxIterations = detailedPanels.size() * 2;
        int iteration = 0;
        while ((newTotal < minTarget || newTotal > maxTarget) && iteration < maxIterations) {
            int maxDeviation = -1;
            int maxDeviationIndex = 0;
            double idealPerPanel = (double) targetDuration / detailedPanels.size();

            for (int i = 0; i < durations.length; i++) {
                int deviation = Math.abs(durations[i] - (int) Math.round(idealPerPanel));
                if (deviation > maxDeviation) {
                    if (newTotal < minTarget && durations[i] < 16) {
                        maxDeviation = deviation;
                        maxDeviationIndex = i;
                    } else if (newTotal > maxTarget && durations[i] > 10) {
                        maxDeviation = deviation;
                        maxDeviationIndex = i;
                    }
                }
            }

            if (newTotal < minTarget && durations[maxDeviationIndex] < 16) {
                durations[maxDeviationIndex]++;
                newTotal++;
            } else if (newTotal > maxTarget && durations[maxDeviationIndex] > 10) {
                durations[maxDeviationIndex]--;
                newTotal--;
            } else {
                break;
            }
            iteration++;
        }

        // Write back calibrated durations
        for (int i = 0; i < detailedPanels.size(); i++) {
            ((ObjectNode) detailedPanels.get(i)).put("duration", durations[i]);
        }

        if (newTotal < minTarget || newTotal > maxTarget) {
            log.warn("Duration calibration did not fully converge: total={} target=[{},{}]", newTotal, minTarget, maxTarget);
        } else {
            log.info("Duration calibration complete: total={} target={}[{},{}]", newTotal, targetDuration, minTarget, maxTarget);
        }
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
                .collect(Collectors.toList());
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

    private String buildRevisionUserPrompt(Episode episode, String feedback) {
        Integer epNum = getEpInfoInt(episode, EpisodeInfoKeys.EPISODE_NUM);
        String panelJson = getEpInfoStr(episode, "panelJson");
        if (panelJson == null) {
            // 兼容旧数据中 storyboardJson 字段名
            panelJson = getEpInfoStr(episode, "storyboardJson");
        }
        return panelPromptBuilder.buildRevisionUserPrompt(
                epNum != null ? epNum : 0,
                preferredEpisodeText(episode),
                getRecentMemory(episode.getProjectId(), epNum != null ? epNum : 0),
                panelJson,
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
