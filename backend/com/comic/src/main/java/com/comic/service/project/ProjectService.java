package com.comic.service.project;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.common.EpisodeInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.statemachine.enums.ProjectState;
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

import com.comic.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Project management service.
 * Handles project CRUD, status queries, and delegates state transitions to the state machine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final CharacterRepository characterRepository;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;

    @Lazy
    @Autowired
    private com.comic.service.production.PanelProductionService panelProductionService;

    @Lazy
    @Autowired
    private StoryboardService storyboardService;


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
        project.setStatus(ProjectState.DRAFT.getCode());

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
        projectRepository.deleteById(project.getId());
    }

    // ==================== 状态查询 ====================

    public Project getProjectStatus(String projectId) {
        return getProjectState(projectId);
    }

    public Project getProjectState(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    public ProjectStatusResponse getProjectStatusDetail(String projectId) {
        return getProjectStateDetail(projectId);
    }

    public ProjectStatusResponse getProjectStateDetail(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        ProjectState status = ProjectState.fromCode(project.getStatus());

        ProjectStatusResponse dto = new ProjectStatusResponse();
        dto.setProjectId(project.getProjectId());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setAvailableActions(status.getAvailableActions());

        if (status == ProjectState.PRODUCING) {
            enrichProducingStatus(dto, projectId);
        } else if (status == ProjectState.MERGING) {
            enrichMergingStatus(dto, project);
        } else if (status == ProjectState.EPISODE_SCRIPT_GENERATING
                || status == ProjectState.EPISODE_SCRIPT_GENERATING_FAILED
                || status == ProjectState.STORYBOARD_GENERATING
                || status == ProjectState.STORYBOARD_GENERATING_FAILED
                || status == ProjectState.STORYBOARD_REVIEW) {
            enrichPanelStatus(dto, projectId);
        } else {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            dto.setGenerating(status.isGenerating());
            // COMPLETED 时附带合并结果
            if (status == ProjectState.COMPLETED) {
                Map<String, Object> info = project.getProjectInfo();
                if (info != null) {
                    dto.setFinalVideoUrl(strVal(info, "finalVideoUrl"));
                    dto.setMergeStatus("completed");
                }
            }
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
                        summary.setProductionSubStage("grid");
                    } else {
                        String gridStatus = strVal(info, "gridStatus");
                        String vStatus = strVal(info, "videoStatus");

                        if ("failed".equals(gridStatus)) {
                            summary.setProductionSubStage("grid");
                            summary.setBlockedReason("panel_failed");
                        } else if ("generating".equals(gridStatus)) {
                            summary.setProductionSubStage("grid");
                        } else if ("pending".equals(gridStatus) || "generated".equals(gridStatus)) {
                            summary.setProductionSubStage("pending_review");
                            summary.setBlockedReason("awaiting_grid_approval");
                        } else if ("approved".equals(gridStatus)) {
                            if ("failed".equals(vStatus)) {
                                summary.setProductionSubStage("video");
                                summary.setBlockedReason("panel_failed");
                            } else if ("generating".equals(vStatus)) {
                                summary.setProductionSubStage("video");
                            } else {
                                summary.setProductionSubStage("video");
                            }
                        } else {
                            summary.setProductionSubStage("grid");
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
        ProjectState status = ProjectState.fromCode(project.getStatus());
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
        String gridStatus = strVal(info, "gridStatus");

        if ("completed".equals(videoStatus)) return "completed";
        if ("failed".equals(videoStatus) || "failed".equals(gridStatus)) return "failed";
        if ("generating".equals(videoStatus) || "generating".equals(gridStatus)) return "in_progress";
        if (strVal(info, "fusionImageUrl") != null) return "in_progress";
        return "pending";
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private void enrichMergingStatus(ProjectStatusResponse dto, Project project) {
        Map<String, Object> info = project.getProjectInfo();
        String finalVideoUrl = info != null ? strVal(info, "finalVideoUrl") : null;
        String mergeStatus = info != null ? strVal(info, "mergeStatus") : null;
        dto.setStatusCode("MERGING");
        dto.setFinalVideoUrl(finalVideoUrl);
        dto.setMergeStatus(mergeStatus != null ? mergeStatus : "idle");
        if (finalVideoUrl != null) {
            dto.setStatusDescription("视频合并已完成");
            dto.setGenerating(false);
        } else {
            dto.setStatusDescription("视频合并");
            dto.setGenerating(false);
        }
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
                        && ("STORYBOARD_FAILED".equals(ep.getStatus())
                            || isPanelGeneratingWithError(ep)
                            || isStaleGenerating(ep))) {
                    failedEpisode = ep;
                }
                if (generatingEpisode == null
                        && "STORYBOARD_GENERATING".equals(ep.getStatus())
                        && !isPanelGeneratingWithError(ep)
                        && !isStaleGenerating(ep)) {
                    generatingEpisode = ep;
                }
                if (reviewEpisode == null && "STORYBOARD_DONE".equals(ep.getStatus())) {
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
                if ("STORYBOARD_CONFIRMED".equals(ep.getStatus())) {
                    completedCount++;
                }
            }

            dto.setPanelTotalEpisodes(totalEpisodes);
            if (currentEpisode != null) {
                Integer epNum = getEpisodeInfoInt(currentEpisode, EpisodeInfoKeys.EPISODE_NUM);
                dto.setPanelCurrentEpisode(epNum);
                dto.setPanelReviewEpisodeId(String.valueOf(currentEpisode.getId()));
            }

            ProjectState projectStatus = ProjectState.fromCode(project.getStatus());
            if (failedEpisode != null && projectStatus == ProjectState.STORYBOARD_GENERATING) {
                projectStatus = ProjectState.STORYBOARD_GENERATING_FAILED;
            } else if (failedEpisode == null && projectStatus == ProjectState.STORYBOARD_GENERATING_FAILED) {
                // 失败已恢复：有完成/审核中的 episode 则恢复到 STORYBOARD_REVIEW，否则回到 STORYBOARD_GENERATING
                projectStatus = (completedCount > 0 || reviewEpisode != null)
                        ? ProjectState.STORYBOARD_REVIEW : ProjectState.STORYBOARD_GENERATING;
                project.setStatus(projectStatus.getCode());
                projectRepository.updateById(project);
                log.info("Panel status recovered: projectId={}, STORYBOARD_GENERATING_FAILED -> {}", projectId, projectStatus.getCode());
            }

            dto.setStatusCode(projectStatus.getCode());
            dto.setStatusDescription(projectStatus.getDescription());
            dto.setGenerating(projectStatus.isGenerating());
            dto.setFailed(projectStatus.isFailed());
            dto.setReview(projectStatus.isReview());

            boolean allConfirmed = completedCount == totalEpisodes && projectStatus == ProjectState.STORYBOARD_REVIEW;
            dto.setPanelAllConfirmed(allConfirmed);
            if (allConfirmed) {
                dto.setPanelReviewEpisodeId(null);
                dto.setStatusDescription("All " + totalEpisodes + " panel episodes are confirmed");
                return;
            }

            // 根据 projectStatus 设置状态描述
            Integer epNum = currentEpisode != null
                    ? getEpisodeInfoInt(currentEpisode, EpisodeInfoKeys.EPISODE_NUM)
                    : null;

            switch (projectStatus) {
                case EPISODE_SCRIPT_GENERATING:
                    dto.setStatusDescription("Generating episode script...");
                    break;
                case EPISODE_SCRIPT_GENERATING_FAILED:
                    dto.setStatusDescription("Episode script generation failed");
                    break;
                case STORYBOARD_GENERATING:
                    dto.setStatusDescription("Generating storyboard for episode " + epNum + "...");
                    break;
                case STORYBOARD_REVIEW:
                    dto.setStatusDescription(
                            "Review episode " + epNum
                                    + " storyboard (" + completedCount + "/" + totalEpisodes + ")"
                    );
                    break;
                case STORYBOARD_GENERATING_FAILED:
                    dto.setStatusDescription(
                            "Episode " + epNum + " storyboard generation failed"
                    );
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to enrich panel status: projectId={}, error={}", projectId, e.getMessage());
        }
    }

    private boolean isPanelGeneratingWithError(Episode episode) {
        if (episode == null) {
            return false;
        }
        if (!"STORYBOARD_GENERATING".equals(episode.getStatus())) {
            return false;
        }
        String errorMsg = getEpisodeInfoStr(episode, EpisodeInfoKeys.ERROR_MSG);
        boolean hasError = errorMsg != null && !errorMsg.trim().isEmpty();
        // panelJson 已移除（分镜数据存 Panel 表），只看 errorMsg 判断
        return hasError;
    }

    /** Detect episodes stuck in GENERATING for too long (e.g. server restarted). */
    private boolean isStaleGenerating(Episode episode) {
        if (episode == null || !"STORYBOARD_GENERATING".equals(episode.getStatus())) {
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

    private String generateProjectId() {
        return "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
    }
}