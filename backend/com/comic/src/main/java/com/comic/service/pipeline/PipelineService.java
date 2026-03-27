package com.comic.service.pipeline;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Project;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Project Status Query Service
 * 状态查询服务（状态转换已迁移到状态机）
 *
 * @deprecated 状态转换功能已迁移到 ProjectStateMachineService，此类仅保留状态查询功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Deprecated
public class PipelineService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final EpisodeProductionRepository episodeProductionRepository;

    /**
     * 获取项目状态
     */
    public Project getProjectStatus(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }
        return project;
    }

    /**
     * 获取项目状态详情
     */
    public ProjectStatusResponse getProjectStatusDetail(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
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
        } else if (status == ProjectStatus.STORYBOARD_GENERATING
                || status == ProjectStatus.STORYBOARD_REVIEW
                || status == ProjectStatus.STORYBOARD_GENERATING_FAILED) {
            enrichStoryboardStatus(dto, projectId);
        } else {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            dto.setGenerating(status.isGenerating());
        }

        return dto;
    }

    /**
     * 获取用户项目列表
     */
    public List<ProjectListItemResponse> getProjectsByUserId(String userId) {
        List<Project> projects = projectRepository.findAllByUserId(userId);
        List<ProjectListItemResponse> result = new ArrayList<>();
        for (Project project : projects) {
            result.add(toListItemDTO(project));
        }
        return result;
    }

    // ===== 私有方法 =====

    private void enrichProducingStatus(ProjectStatusResponse dto, String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            Episode producingEpisode = null;
            for (Episode ep : episodes) {
                if ("IN_PROGRESS".equals(ep.getProductionStatus())) {
                    producingEpisode = ep;
                    break;
                }
            }

            if (producingEpisode == null) {
                boolean hasProducible = false;
                for (Episode ep : episodes) {
                    if ("DONE".equals(ep.getStatus())
                            && (ep.getProductionStatus() == null
                            || "NOT_STARTED".equals(ep.getProductionStatus())
                            || "FAILED".equals(ep.getProductionStatus()))) {
                        hasProducible = true;
                        break;
                    }
                    if ("DRAFT".equals(ep.getStatus()) || "FAILED".equals(ep.getStatus())) {
                        hasProducible = true;
                        break;
                    }
                }

                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription(hasProducible ? "Ready to start production" : "Producing");
                dto.setGenerating(false);
                return;
            }

            EpisodeProduction production = episodeProductionRepository.findByEpisodeId(producingEpisode.getId());
            if (production == null) {
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription("Producing");
                dto.setGenerating(true);
                return;
            }

            String episodeStatus = production.getStatus();
            String progressMsg = production.getProgressMessage();
            int progress = production.getProgressPercent() != null ? production.getProgressPercent() : 0;

            dto.setStatusCode(mapEpisodeToProjectStatus(episodeStatus));
            dto.setStatusDescription(progressMsg != null ? progressMsg : "Producing");
            dto.setGenerating(isEpisodeGenerating(episodeStatus));
            dto.setProductionProgress(progress);
            dto.setProductionSubStage(episodeStatus);

            if ("COMPLETED".equals(episodeStatus)) {
                Project project = projectRepository.findByProjectId(projectId);
                project.setStatus(ProjectStatus.COMPLETED.getCode());
                projectRepository.updateById(project);
                dto.setStatusCode(ProjectStatus.COMPLETED.getCode());
                dto.setStatusDescription("Completed");
                dto.setGenerating(false);
            }
        } catch (Exception e) {
            log.warn("Failed to enrich producing status: projectId={}, error={}", projectId, e.getMessage());
            dto.setStatusCode("PRODUCING");
            dto.setStatusDescription("Producing");
            dto.setGenerating(true);
        }
    }

    private String mapEpisodeToProjectStatus(String episodeStatus) {
        if (episodeStatus == null) {
            return "PRODUCING";
        }
        switch (episodeStatus) {
            case "ANALYZING":
            case "GRID_GENERATING":
            case "GRID_FUSION_PENDING":
            case "BUILDING_PROMPTS":
            case "GENERATING":
            case "GENERATING_SUBS":
            case "COMPOSING":
                return "PRODUCING";
            case "COMPLETED":
                return "COMPLETED";
            case "FAILED":
            default:
                return "PRODUCING";
        }
    }

    private void enrichStoryboardStatus(ProjectStatusResponse dto, String projectId) {
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
                            || isStoryboardGeneratingWithError(ep)
                            || isStaleGenerating(ep))) {
                    failedEpisode = ep;
                }
                if (generatingEpisode == null
                        && "STORYBOARD_GENERATING".equals(ep.getStatus())
                        && !isStoryboardGeneratingWithError(ep)
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

            dto.setStoryboardTotalEpisodes(totalEpisodes);
            if (currentEpisode != null) {
                dto.setStoryboardCurrentEpisode(currentEpisode.getEpisodeNum());
                dto.setStoryboardReviewEpisodeId(String.valueOf(currentEpisode.getId()));
            }

            ProjectStatus projectStatus = ProjectStatus.fromCode(project.getStatus());
            if (failedEpisode != null && projectStatus == ProjectStatus.STORYBOARD_GENERATING) {
                projectStatus = ProjectStatus.STORYBOARD_GENERATING_FAILED;

                if (isStaleGenerating(failedEpisode)) {
                    failedEpisode.setStatus("STORYBOARD_FAILED");
                    failedEpisode.setErrorMsg("Generation timed out (server may have restarted)");
                    episodeRepository.updateById(failedEpisode);
                    log.warn("Recovered stale generating episode: episodeId={}, episodeNum={}",
                            failedEpisode.getId(), failedEpisode.getEpisodeNum());
                }
            }

            dto.setStatusCode(projectStatus.getCode());
            dto.setStatusDescription(projectStatus.getDescription());
            dto.setGenerating(projectStatus.isGenerating());
            dto.setFailed(projectStatus.isFailed());
            dto.setReview(projectStatus.isReview());

            boolean allConfirmed = completedCount == totalEpisodes && projectStatus == ProjectStatus.STORYBOARD_REVIEW;
            dto.setStoryboardAllConfirmed(allConfirmed);
            if (allConfirmed) {
                dto.setStoryboardReviewEpisodeId(null);
                dto.setStatusDescription("All " + totalEpisodes + " storyboard episodes are confirmed");
                return;
            }

            if (currentEpisode != null) {
                switch (projectStatus) {
                    case STORYBOARD_GENERATING:
                        dto.setStatusDescription("Generating storyboard for episode " + currentEpisode.getEpisodeNum() + "...");
                        break;
                    case STORYBOARD_REVIEW:
                        dto.setStatusDescription(
                                "Review episode " + currentEpisode.getEpisodeNum()
                                        + " storyboard (" + completedCount + "/" + totalEpisodes + ")"
                        );
                        break;
                    case STORYBOARD_GENERATING_FAILED:
                        dto.setStatusDescription(
                                "Episode " + currentEpisode.getEpisodeNum() + " storyboard generation failed"
                        );
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich storyboard status: projectId={}, error={}", projectId, e.getMessage());
        }
    }

    private boolean isStoryboardGeneratingWithError(Episode episode) {
        if (episode == null) {
            return false;
        }
        if (!"STORYBOARD_GENERATING".equals(episode.getStatus())) {
            return false;
        }
        boolean hasError = episode.getErrorMsg() != null && !episode.getErrorMsg().trim().isEmpty();
        boolean hasStoryboard = episode.getStoryboardJson() != null && !episode.getStoryboardJson().trim().isEmpty();
        return hasError && !hasStoryboard;
    }

    private boolean isStaleGenerating(Episode episode) {
        if (episode == null || !"STORYBOARD_GENERATING".equals(episode.getStatus())) {
            return false;
        }
        if (isStoryboardGeneratingWithError(episode)) {
            return false;
        }
        LocalDateTime updatedAt = episode.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        return Duration.between(updatedAt, LocalDateTime.now()).toMinutes() >= 10;
    }

    private boolean isEpisodeGenerating(String episodeStatus) {
        if (episodeStatus == null) {
            return false;
        }
        switch (episodeStatus) {
            case "ANALYZING":
            case "GRID_GENERATING":
            case "BUILDING_PROMPTS":
            case "GENERATING":
            case "GENERATING_SUBS":
            case "COMPOSING":
                return true;
            default:
                return false;
        }
    }

    private ProjectListItemResponse toListItemDTO(Project project) {
        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectListItemResponse dto = new ProjectListItemResponse();
        dto.setProjectId(project.getProjectId());
        dto.setStoryPrompt(project.getStoryPrompt());
        dto.setGenre(project.getGenre());
        dto.setTargetAudience(project.getTargetAudience());
        dto.setTotalEpisodes(project.getTotalEpisodes());
        dto.setEpisodeDuration(project.getEpisodeDuration());
        dto.setVisualStyle(project.getVisualStyle());
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
}
