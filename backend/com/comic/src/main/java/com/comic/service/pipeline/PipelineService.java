package com.comic.service.pipeline;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.common.EpisodeInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.request.ProjectCreateRequest;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectProductionSummaryResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.service.script.ScriptService;
import com.comic.service.panel.PanelGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline orchestration service.
 * Drives project status transitions and triggers the next stage when needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService implements StageCompletionCallback {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final CharacterRepository characterRepository;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;
    private final ProjectStatusBroadcaster broadcaster;

    @Lazy
    @Autowired
    private PanelGenerationService panelGenerationService;

    @Lazy
    @Autowired
    private com.comic.service.production.PanelProductionService panelProductionService;

    /** 自引用，用于异步线程中调用 advancePipeline（绕过 Spring 代理） */
    @Lazy
    @Autowired
    private PipelineService pipelineServiceSelf;

    // ==================== Map 辅助方法 ====================

    private String getProjectInfoStr(Project project, String key) {
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Integer getProjectInfoInt(Project project, String key) {
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? ((Number) v).intValue() : null;
    }

    private String getEpisodeInfoStr(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Integer getEpisodeInfoInt(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? ((Number) v).intValue() : null;
    }

    private String getCharacterInfoStr(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    // ==================== CRUD ====================

    @Transactional
    public String createProject(String userId, String storyPrompt, String genre,
                                String targetAudience, Integer totalEpisodes,
                                Integer episodeDuration, String visualStyle) {
        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setUserId(userId);
        project.setDeleted(false);
        project.setStatus(ProjectStatus.DRAFT.getCode());

        Map<String, Object> info = new HashMap<>();
        info.put(ProjectInfoKeys.STORY_PROMPT, storyPrompt);
        info.put(ProjectInfoKeys.GENRE, genre);
        info.put(ProjectInfoKeys.TARGET_AUDIENCE, targetAudience);
        info.put(ProjectInfoKeys.TOTAL_EPISODES, totalEpisodes);
        info.put(ProjectInfoKeys.EPISODE_DURATION, episodeDuration);
        info.put(ProjectInfoKeys.VISUAL_STYLE, visualStyle);
        project.setProjectInfo(info);

        projectRepository.insert(project);

        log.info("Project created: projectId={}, userId={}", project.getProjectId(), userId);
        return project.getProjectId();
    }

    @Transactional
    public void updateProject(String projectId, ProjectCreateRequest request) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        Map<String, Object> info = project.getProjectInfo();
        if (info == null) {
            info = new HashMap<>();
        }
        if (request.getStoryPrompt() != null) info.put(ProjectInfoKeys.STORY_PROMPT, request.getStoryPrompt());
        if (request.getGenre() != null) info.put(ProjectInfoKeys.GENRE, request.getGenre());
        if (request.getTargetAudience() != null) info.put(ProjectInfoKeys.TARGET_AUDIENCE, request.getTargetAudience());
        if (request.getTotalEpisodes() != null) info.put(ProjectInfoKeys.TOTAL_EPISODES, request.getTotalEpisodes());
        if (request.getEpisodeDuration() != null) info.put(ProjectInfoKeys.EPISODE_DURATION, request.getEpisodeDuration());
        if (request.getVisualStyle() != null) info.put(ProjectInfoKeys.VISUAL_STYLE, request.getVisualStyle());
        project.setProjectInfo(info);
        projectRepository.updateById(project);
    }

    @Transactional
    public void logicalDeleteProject(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        project.setDeleted(true);
        projectRepository.updateById(project);
    }

    // ==================== Pipeline 状态转换（唯一入口）====================

    @Override
    @Transactional
    public void onStageComplete(String projectId, String event) {
        advancePipeline(projectId, event);
    }

    @Override
    @Transactional
    public void onStageFailed(String projectId, String event) {
        advancePipeline(projectId, event);
    }

    @Transactional
    public void advancePipeline(String projectId, String event) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        ProjectStatus current = ProjectStatus.fromCode(project.getStatus());
        ProjectStatus next = ProjectStatus.resolveTransition(current, event);

        if (next == null) {
            log.warn("Illegal transition rejected: projectId={}, current={}, event={}", projectId, current, event);
            throw new BusinessException("非法状态转换: " + current.getCode() + " + " + event);
        }

        // Gate: verify project has producible panels before entering PRODUCING
        if ("all_panels_confirmed".equals(event)) {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            int totalPanels = 0;
            for (Episode ep : episodes) {
                totalPanels += panelRepository.findByEpisodeId(ep.getId()).size();
            }
            if (totalPanels == 0) {
                throw new BusinessException("没有可生产的分镜，请先完成分镜生成");
            }
        }

        String oldStatus = project.getStatus();
        project.setStatus(next.getCode());
        projectRepository.updateById(project);
        log.info("Pipeline advanced: projectId={}, {} -> {} (event={})", projectId, oldStatus, next.getCode(), event);

        broadcaster.broadcast(projectId, oldStatus, next.getCode());
        triggerNextStage(projectId, next);
    }

    @Transactional
    public void advancePipeline(String projectId, String direction, String event) {
        if ("backward".equals(direction)) {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null) throw new BusinessException("项目不存在");
            rollbackPipeline(project);
        } else {
            advancePipeline(projectId, event);
        }
    }

    private void rollbackPipeline(Project project) {
        ProjectStatus current = ProjectStatus.fromCode(project.getStatus());
        ProjectStatus previous = getRollbackTarget(current);
        if (previous == null) {
            throw new BusinessException("Cannot go back from status " + current.getCode());
        }

        String projectId = project.getProjectId();
        cleanupAfterRollback(projectId, current);

        project.setStatus(previous.getCode());
        projectRepository.updateById(project);
        log.info("Pipeline rolled back: projectId={}, {} -> {}", projectId, current.getCode(), previous.getCode());
        broadcaster.broadcast(projectId, current.getCode(), previous.getCode());
    }

    private ProjectStatus getRollbackTarget(ProjectStatus from) {
        switch (from) {
            case OUTLINE_REVIEW:
            case EPISODE_GENERATING:
            case SCRIPT_REVIEW:
                return ProjectStatus.DRAFT;
            case SCRIPT_CONFIRMED:
                return ProjectStatus.SCRIPT_REVIEW;
            case CHARACTER_EXTRACTING:
                return ProjectStatus.SCRIPT_CONFIRMED;
            case CHARACTER_REVIEW:
                return ProjectStatus.CHARACTER_EXTRACTING;
            case CHARACTER_CONFIRMED:
                return ProjectStatus.CHARACTER_REVIEW;
            case IMAGE_GENERATING:
                return ProjectStatus.CHARACTER_CONFIRMED;
            case IMAGE_REVIEW:
                return ProjectStatus.IMAGE_GENERATING;
            case ASSET_LOCKED:
                return ProjectStatus.IMAGE_REVIEW;
            case PANEL_GENERATING:
                return ProjectStatus.ASSET_LOCKED;
            case PANEL_REVIEW:
                return ProjectStatus.PANEL_GENERATING;
            case PRODUCING:
                return ProjectStatus.PANEL_REVIEW;
            case COMPLETED:
                return ProjectStatus.PRODUCING;
            default:
                return null;
        }
    }

    private void cleanupAfterRollback(String projectId, ProjectStatus from) {
        switch (from) {
            case OUTLINE_REVIEW:
            case EPISODE_GENERATING:
            case SCRIPT_REVIEW:
                episodeRepository.deleteByProjectId(projectId);
                break;
            case SCRIPT_CONFIRMED:
                characterRepository.deleteByProjectId(projectId);
                episodeRepository.deleteByProjectId(projectId);
                break;
            case CHARACTER_EXTRACTING:
            case CHARACTER_REVIEW:
            case CHARACTER_CONFIRMED:
                characterRepository.deleteByProjectId(projectId);
                break;
            case IMAGE_GENERATING:
            case IMAGE_REVIEW:
                // 清除角色图片信息
                List<Character> characters = characterRepository.findByProjectId(projectId);
                for (Character c : characters) {
                    Map<String, Object> info = c.getCharacterInfo();
                    if (info != null) {
                        info.remove(CharacterInfoKeys.THREE_VIEWS_URL);
                        info.remove(CharacterInfoKeys.EXPRESSION_IMAGE_URL);
                        info.remove(CharacterInfoKeys.EXPRESSION_STATUS);
                        info.remove(CharacterInfoKeys.THREE_VIEW_STATUS);
                        info.remove(CharacterInfoKeys.EXPRESSION_ERROR);
                        info.remove(CharacterInfoKeys.THREE_VIEW_ERROR);
                        info.remove(CharacterInfoKeys.IS_GENERATING_EXPRESSION);
                        info.remove(CharacterInfoKeys.IS_GENERATING_THREE_VIEW);
                        info.remove(CharacterInfoKeys.EXPRESSION_GRID_URL);
                        info.remove(CharacterInfoKeys.THREE_VIEW_GRID_URL);
                        info.remove(CharacterInfoKeys.EXPRESSION_GRID_PROMPT);
                        info.remove(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT);
                        c.setCharacterInfo(info);
                        characterRepository.updateById(c);
                    }
                }
                break;
            case ASSET_LOCKED:
            case PANEL_GENERATING:
            case PANEL_REVIEW:
            case PRODUCING:
                // 清除分镜和生产数据
                List<Episode> episodes = episodeRepository.findByProjectId(projectId);
                for (Episode ep : episodes) {
                    Map<String, Object> info = ep.getEpisodeInfo();
                    if (info != null) {
                        info.remove(EpisodeInfoKeys.PRODUCTION_STATUS);
                        ep.setEpisodeInfo(info);
                    }
                    ep.setStatus("DRAFT");
                    episodeRepository.updateById(ep);

                    // 清除该 Episode 下所有 Panel 的生产状态
                    List<Panel> panels = panelRepository.findByEpisodeId(ep.getId());
                    for (Panel panel : panels) {
                        Map<String, Object> panelInfo = panel.getPanelInfo();
                        if (panelInfo != null) {
                            panelInfo.remove("backgroundUrl");
                            panelInfo.remove("backgroundStatus");
                            panelInfo.remove("comicUrl");
                            panelInfo.remove("comicStatus");
                            panelInfo.remove("videoUrl");
                            panelInfo.remove("videoStatus");
                            panelInfo.remove("videoTaskId");
                            panelInfo.remove("errorMessage");
                            panel.setPanelInfo(panelInfo);
                            panelRepository.updateById(panel);
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    // ==================== 状态查询 ====================

    public Project getProjectStatus(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    public ProjectStatusResponse getProjectStatusDetail(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectStatusResponse dto = new ProjectStatusResponse();
        dto.setProjectId(project.getProjectId());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setAvailableActions(status.getAvailableActions());

        if (status == ProjectStatus.PRODUCING) {
            enrichProducingStatus(dto, projectId);
        } else if (status == ProjectStatus.PANEL_GENERATING
                || status == ProjectStatus.PANEL_REVIEW
                || status == ProjectStatus.PANEL_GENERATING_FAILED) {
            enrichPanelStatus(dto, projectId);
        } else {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            dto.setGenerating(status.isGenerating());
        }

        return dto;
    }

    /**
     * 获取项目级生产摘要（PRODUCING 阶段）
     */
    public ProjectProductionSummaryResponse getProductionSummary(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        ProjectProductionSummaryResponse summary = new ProjectProductionSummaryResponse();

        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        int totalPanels = 0;
        int completedPanels = 0;
        int currentIndex = 0;

        for (Episode episode : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(episode.getId());
            for (Panel panel : panels) {
                totalPanels++;
                currentIndex++;
                Map<String, Object> info = panel.getPanelInfo();
                String videoStatus = info != null ? strVal(info, "videoStatus") : null;

                if ("completed".equals(videoStatus)) {
                    completedPanels++;
                    continue;
                }

                // This is the first non-completed panel -> it's the current panel
                if (summary.getCurrentPanelId() == null) {
                    summary.setCurrentEpisodeId(episode.getId());
                    summary.setCurrentPanelId(panel.getId());
                    summary.setCurrentPanelIndex(currentIndex);

                    // Determine sub-stage and blocked reason
                    if (info == null) {
                        summary.setProductionSubStage("background");
                    } else {
                        String bgStatus = strVal(info, "backgroundStatus");
                        String comicStatus = strVal(info, "comicStatus");
                        String vStatus = strVal(info, "videoStatus");

                        if ("failed".equals(bgStatus)) {
                            summary.setProductionSubStage("background");
                            summary.setBlockedReason("panel_failed");
                        } else if ("generating".equals(bgStatus)) {
                            summary.setProductionSubStage("background");
                        } else if ("failed".equals(comicStatus)) {
                            summary.setProductionSubStage("comic");
                            summary.setBlockedReason("panel_failed");
                        } else if ("generating".equals(comicStatus)) {
                            summary.setProductionSubStage("comic");
                        } else if ("pending_review".equals(comicStatus)) {
                            summary.setProductionSubStage("pending_review");
                            summary.setBlockedReason("awaiting_comic_approval");
                        } else if ("approved".equals(comicStatus)) {
                            if ("failed".equals(vStatus)) {
                                summary.setProductionSubStage("video");
                                summary.setBlockedReason("panel_failed");
                            } else if ("generating".equals(vStatus)) {
                                summary.setProductionSubStage("video");
                            } else {
                                summary.setProductionSubStage("video");
                            }
                        } else {
                            summary.setProductionSubStage("background");
                        }
                    }
                }
            }
        }

        summary.setTotalPanelCount(totalPanels);
        summary.setCompletedPanelCount(completedPanels);
        return summary;
    }

    public IPage<Project> getProjectPage(String userId, String status, String sortBy, String sortOrder, int page, int size) {
        return projectRepository.findPage(userId, status, sortBy, sortOrder, page, size);
    }

    public List<ProjectListItemResponse> getProjectsByUserId(String userId) {
        List<Project> projects = projectRepository.findAllByUserId(userId);
        List<ProjectListItemResponse> result = new ArrayList<>();
        for (Project project : projects) {
            result.add(toListItemDTO(project));
        }
        return result;
    }

    public ProjectListItemResponse toListItemDTO(Project project) {
        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());
        Map<String, Object> info = project.getProjectInfo();

        ProjectListItemResponse dto = new ProjectListItemResponse();
        dto.setProjectId(project.getProjectId());
        dto.setStoryPrompt(getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT));
        dto.setGenre(getProjectInfoStr(project, ProjectInfoKeys.GENRE));
        dto.setTargetAudience(getProjectInfoStr(project, ProjectInfoKeys.TARGET_AUDIENCE));
        dto.setTotalEpisodes(getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES));
        dto.setEpisodeDuration(getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION));
        dto.setVisualStyle(getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE));
        dto.setStatusCode(status.getCode());
        dto.setStatusDescription(status.getDescription());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setGenerating(status.isGenerating());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());

        return dto;
    }

    // ==================== 状态增强（Producing / Panel）====================
    private void enrichProducingStatus(ProjectStatusResponse dto, String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);

            // 聚合所有 Episode 下 Panel 的生产状态
            int totalPanels = 0;
            int completedPanels = 0;
            int failedPanels = 0;
            int generatingPanels = 0;
            boolean hasPending = false;

            for (Episode ep : episodes) {
                List<Panel> panels = panelRepository.findByEpisodeId(ep.getId());
                totalPanels += panels.size();
                for (Panel panel : panels) {
                    String overallStatus = getPanelOverallStatus(panel);
                    if ("completed".equals(overallStatus)) {
                        completedPanels++;
                    } else if ("failed".equals(overallStatus)) {
                        failedPanels++;
                    } else if ("in_progress".equals(overallStatus)) {
                        generatingPanels++;
                    } else {
                        hasPending = true;
                    }
                }
            }

            if (totalPanels == 0) {
                // 没有 Panel，等待用户操作
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription("Ready to start production");
                dto.setGenerating(false);
                return;
            }

            // 所有 Panel 完成 → 等待编排器触发 production_completed 持久化
            if (completedPanels == totalPanels) {
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription("All panels completed, finalizing...");
                dto.setGenerating(false);
                dto.setProductionProgress(100);
                return;
            }

            // 计算进度百分比
            int progress = (int) ((completedPanels * 100.0) / totalPanels);
            dto.setProductionProgress(progress);

            if (generatingPanels > 0) {
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription("Producing (" + completedPanels + "/" + totalPanels + " panels)");
                dto.setGenerating(true);
            } else if (failedPanels > 0) {
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription("Production failed on some panels (" + failedPanels + " failed)");
                dto.setGenerating(false);
            } else {
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription(hasPending ? "Ready to start production" : "Producing");
                dto.setGenerating(false);
            }
        } catch (Exception e) {
            log.warn("Failed to enrich producing status: projectId={}, error={}", projectId, e.getMessage());
            dto.setStatusCode("PRODUCING");
            dto.setStatusDescription("Producing");
            dto.setGenerating(true);
        }
    }

    /**
     * 根据 Panel.panelInfo 推导整体生产状态
     */
    private String getPanelOverallStatus(Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) return "pending";

        String videoStatus = strVal(info, "videoStatus");
        String comicStatus = strVal(info, "comicStatus");
        String bgStatus = strVal(info, "backgroundStatus");
        String bgUrl = strVal(info, "backgroundUrl");

        // 视频完成 → 整体完成
        if ("completed".equals(videoStatus)) return "completed";
        // 任一阶段失败 → 整体失败
        if ("failed".equals(videoStatus) || "failed".equals(comicStatus) || "failed".equals(bgStatus)) return "failed";
        // 任一阶段正在生成
        if ("generating".equals(videoStatus) || "generating".equals(comicStatus) || "generating".equals(bgStatus)) return "in_progress";
        // 有产出但未完成
        if (bgUrl != null || strVal(info, "comicUrl") != null) return "in_progress";
        return "pending";
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private void enrichPanelStatus(ProjectStatusResponse dto, String projectId) {
        try {
            Project project = projectRepository.findByProjectId(projectId);
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            int totalEpisodes = episodes.size();

            Episode failedEpisode = null;
            Episode generatingEpisode = null;
            Episode reviewEpisode = null;
            Episode draftEpisode = null;

            for (Episode ep : episodes) {
                if (failedEpisode == null
                        && ("PANEL_FAILED".equals(ep.getStatus())
                            || isPanelGeneratingWithError(ep)
                            || isStaleGenerating(ep))) {
                    failedEpisode = ep;
                }
                if (generatingEpisode == null
                        && "PANEL_GENERATING".equals(ep.getStatus())
                        && !isPanelGeneratingWithError(ep)
                        && !isStaleGenerating(ep)) {
                    generatingEpisode = ep;
                }
                if (reviewEpisode == null && "PANEL_DONE".equals(ep.getStatus())) {
                    reviewEpisode = ep;
                }
                if (draftEpisode == null && (ep.getStatus() == null || "DRAFT".equals(ep.getStatus()))) {
                    draftEpisode = ep;
                }
            }

            Episode currentEpisode = failedEpisode != null ? failedEpisode
                    : generatingEpisode != null ? generatingEpisode
                    : reviewEpisode != null ? reviewEpisode
                    : draftEpisode;

            int completedCount = 0;
            for (Episode ep : episodes) {
                if ("PANEL_CONFIRMED".equals(ep.getStatus())) {
                    completedCount++;
                }
            }

            dto.setPanelTotalEpisodes(totalEpisodes);
            if (currentEpisode != null) {
                Integer epNum = getEpisodeInfoInt(currentEpisode, EpisodeInfoKeys.EPISODE_NUM);
                dto.setPanelCurrentEpisode(epNum);
                dto.setPanelReviewEpisodeId(String.valueOf(currentEpisode.getId()));
            }

            ProjectStatus projectStatus = ProjectStatus.fromCode(project.getStatus());
            if (failedEpisode != null && projectStatus == ProjectStatus.PANEL_GENERATING) {
                projectStatus = ProjectStatus.PANEL_GENERATING_FAILED;
            }

            dto.setStatusCode(projectStatus.getCode());
            dto.setStatusDescription(projectStatus.getDescription());
            dto.setGenerating(projectStatus.isGenerating());
            dto.setFailed(projectStatus.isFailed());
            dto.setReview(projectStatus.isReview());

            boolean allConfirmed = completedCount == totalEpisodes && projectStatus == ProjectStatus.PANEL_REVIEW;
            dto.setPanelAllConfirmed(allConfirmed);
            if (allConfirmed) {
                dto.setPanelReviewEpisodeId(null);
                dto.setStatusDescription("All " + totalEpisodes + " panel episodes are confirmed");
                return;
            }

            if (currentEpisode != null) {
                Integer epNum = getEpisodeInfoInt(currentEpisode, EpisodeInfoKeys.EPISODE_NUM);
                switch (projectStatus) {
                    case PANEL_GENERATING:
                        dto.setStatusDescription("Generating panels for episode " + epNum + "...");
                        break;
                    case PANEL_REVIEW:
                        dto.setStatusDescription(
                                "Review episode " + epNum
                                        + " panels (" + completedCount + "/" + totalEpisodes + ")"
                        );
                        break;
                    case PANEL_GENERATING_FAILED:
                        dto.setStatusDescription(
                                "Episode " + epNum + " panel generation failed"
                        );
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich panel status: projectId={}, error={}", projectId, e.getMessage());
        }
    }

    private boolean isPanelGeneratingWithError(Episode episode) {
        if (episode == null) {
            return false;
        }
        if (!"PANEL_GENERATING".equals(episode.getStatus())) {
            return false;
        }
        String errorMsg = getEpisodeInfoStr(episode, EpisodeInfoKeys.ERROR_MSG);
        boolean hasError = errorMsg != null && !errorMsg.trim().isEmpty();
        // panelJson 已移除（分镜数据存 Panel 表），只看 errorMsg 判断
        return hasError;
    }

    /** Detect episodes stuck in GENERATING for too long (e.g. server restarted). */
    private boolean isStaleGenerating(Episode episode) {
        if (episode == null || !"PANEL_GENERATING".equals(episode.getStatus())) {
            return false;
        }
        if (isPanelGeneratingWithError(episode)) {
            return false;
        }
        LocalDateTime updatedAt = episode.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        return Duration.between(updatedAt, LocalDateTime.now()).toMinutes() >= 10;
    }

    // ==================== 阶段触发 ====================

    private void triggerNextStage(String projectId, ProjectStatus status) {
        switch (status) {
            case OUTLINE_GENERATING:
                scriptService.generateScriptOutline(projectId);
                break;

            case CHARACTER_EXTRACTING:
                characterExtractService.extractCharacters(projectId);
                break;

            case IMAGE_GENERATING:
                generateAllCharacterImagesAsync(projectId);
                break;

            case PANEL_GENERATING:
                CompletableFuture.runAsync(() -> {
                    try {
                        panelGenerationService.startPanelGeneration(projectId);
                    } catch (Exception e) {
                        log.error("Panel generation failed: projectId={}, error={}", projectId, e.getMessage(), e);
                    }
                });
                break;

            case SCRIPT_CONFIRMED:
                // Auto-advance: confirm -> immediately start character extraction
                pipelineServiceSelf.advancePipeline(projectId, "start_character_extraction");
                break;

            case CHARACTER_CONFIRMED:
                // Auto-advance: confirm -> immediately start image generation
                pipelineServiceSelf.advancePipeline(projectId, "start_image_generation");
                break;

            case ASSET_LOCKED:
                // Auto-advance: images confirmed -> immediately start panel generation
                pipelineServiceSelf.advancePipeline(projectId, "start_panels");
                break;

            case PRODUCING:
                // Auto-start strict-serial production orchestrator
                try {
                    panelProductionService.startOrResume(projectId);
                } catch (Exception e) {
                    log.error("Failed to start production orchestrator: projectId={}", projectId, e);
                }
                break;

            default:
                break;
        }
    }

    private void generateAllCharacterImagesAsync(String projectId) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Character> characters = characterRepository.findByProjectId(projectId);
                log.info("Generating character images: projectId={}, characterCount={}", projectId, characters.size());

                int successCount = 0;
                int failCount = 0;
                for (Character character : characters) {
                    try {
                        String charId = getCharacterInfoStr(character, CharacterInfoKeys.CHAR_ID);
                        characterImageGenerationService.generateAll(charId);
                        successCount++;
                    } catch (Exception e) {
                        log.warn(
                                "Character image generation failed and will continue: charId={}, error={}",
                                getCharacterInfoStr(character, CharacterInfoKeys.CHAR_ID),
                                e.getMessage()
                        );
                        failCount++;
                    }
                }

                log.info(
                        "Character image generation finished: projectId={}, success={}, fail={}",
                        projectId,
                        successCount,
                        failCount
                );

                Project project = projectRepository.findByProjectId(projectId);
                if (project != null && ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
                    try {
                        pipelineServiceSelf.advancePipeline(projectId, "images_generated");
                    } catch (Exception e2) {
                        log.warn("Failed to advance status after image generation: projectId={}, error={}", projectId, e2.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Character image batch generation failed: projectId={}", projectId, e);
                Project project = projectRepository.findByProjectId(projectId);
                if (project != null && ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
                    try {
                        pipelineServiceSelf.advancePipeline(projectId, "images_failed");
                    } catch (Exception e2) {
                        log.warn("Failed to set failed status: projectId={}, error={}", projectId, e2.getMessage());
                    }
                }
            }
        });
    }

    private String generateProjectId() {
        return "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
    }
}