package com.comic.service.pipeline;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.service.production.EpisodeProductionService;
import com.comic.service.script.ScriptService;
import com.comic.service.story.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline orchestration service.
 * Drives project status transitions and triggers the next stage when needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final EpisodeProductionRepository episodeProductionRepository;
    private final CharacterRepository characterRepository;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;
    private final EpisodeProductionService episodeProductionService;

    @Lazy
    @Autowired
    private StoryboardService storyboardService;

    @Transactional
    public String createProject(String userId, String storyPrompt, String genre,
                                String targetAudience, Integer totalEpisodes,
                                Integer episodeDuration, String visualStyle) {
        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setUserId(userId);
        project.setStoryPrompt(storyPrompt);
        project.setGenre(genre);
        project.setTargetAudience(targetAudience);
        project.setTotalEpisodes(totalEpisodes);
        project.setEpisodeDuration(episodeDuration);
        project.setVisualStyle(visualStyle);
        project.setStatus(ProjectStatus.DRAFT.getCode());

        projectRepository.insert(project);

        log.info("Project created: projectId={}, userId={}", project.getProjectId(), userId);
        return project.getProjectId();
    }

    @Transactional
    public void advancePipeline(String projectId, String event) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }

        String currentStatus = project.getStatus();
        String nextStatus = calculateNextStatus(currentStatus, event);
        if (nextStatus == null) {
            throw new BusinessException("Cannot transition from status " + currentStatus + " via event " + event);
        }

        project.setStatus(nextStatus);
        projectRepository.updateById(project);

        log.info("Pipeline advanced: projectId={}, {} -> {}", projectId, currentStatus, nextStatus);
        triggerNextStageAsync(projectId, nextStatus);
    }

    private void triggerNextStageAsync(String projectId, String status) {
        try {
            triggerNextStage(projectId, status);
        } catch (Exception e) {
            log.error(
                    "Failed to trigger next stage after status update: projectId={}, status={}, error={}",
                    projectId,
                    status,
                    e.getMessage(),
                    e
            );
        }
    }

    public Project getProjectStatus(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("Project not found");
        }
        return project;
    }

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

                // Auto-recover stale generating episodes so the user can retry
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

    /** Detect episodes stuck in GENERATING for too long (e.g. server restarted). */
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

    public List<ProjectListItemResponse> getProjectsByUserId(String userId) {
        List<Project> projects = projectRepository.findAllByUserId(userId);
        List<ProjectListItemResponse> result = new ArrayList<>();
        for (Project project : projects) {
            result.add(toListItemDTO(project));
        }
        return result;
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

    private String calculateNextStatus(String currentStatus, String event) {
        switch (event) {
            case "start_script_generation":
                return ProjectStatus.OUTLINE_GENERATING.getCode();
            case "script_generated":
                return ProjectStatus.SCRIPT_REVIEW.getCode();
            case "script_confirmed":
                return ProjectStatus.SCRIPT_CONFIRMED.getCode();
            case "script_revision_requested":
                return ProjectStatus.OUTLINE_REVIEW.getCode();
            case "start_character_extraction":
                return ProjectStatus.CHARACTER_EXTRACTING.getCode();
            case "characters_extracted":
                return ProjectStatus.CHARACTER_REVIEW.getCode();
            case "characters_confirmed":
                return ProjectStatus.CHARACTER_CONFIRMED.getCode();
            case "start_image_generation":
                return ProjectStatus.IMAGE_GENERATING.getCode();
            case "images_generated":
                return ProjectStatus.IMAGE_REVIEW.getCode();
            case "image_confirmed":
                return ProjectStatus.ASSET_LOCKED.getCode();
            case "start_storyboard":
                return ProjectStatus.STORYBOARD_GENERATING.getCode();
            case "storyboard_generated":
                return ProjectStatus.STORYBOARD_REVIEW.getCode();
            case "storyboard_revision":
                return ProjectStatus.STORYBOARD_GENERATING.getCode();
            case "storyboard_retry":
                return ProjectStatus.STORYBOARD_GENERATING.getCode();
            case "start_production":
                return ProjectStatus.PRODUCING.getCode();
            case "production_completed":
                return ProjectStatus.COMPLETED.getCode();
            default:
                return null;
        }
    }

    private void triggerNextStage(String projectId, String status) {
        ProjectStatus projectStatus = ProjectStatus.fromCode(status);
        switch (projectStatus) {
            case OUTLINE_GENERATING:
                scriptService.generateScriptOutline(projectId);
                break;

            case CHARACTER_EXTRACTING:
                characterExtractService.extractCharacters(projectId);
                break;

            case IMAGE_GENERATING:
                generateAllCharacterImagesAsync(projectId);
                break;

            case STORYBOARD_GENERATING:
                CompletableFuture.runAsync(() -> {
                    try {
                        storyboardService.startStoryboardGeneration(projectId);
                    } catch (Exception e) {
                        log.error("Storyboard generation failed: projectId={}, error={}", projectId, e.getMessage(), e);
                    }
                });
                break;

            case PRODUCING:
                CompletableFuture.runAsync(() -> {
                    try {
                        episodeProductionService.startProductionForProject(projectId);
                    } catch (Exception e) {
                        log.error("Production failed: projectId={}, error={}", projectId, e.getMessage(), e);
                    }
                });
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
                        characterImageGenerationService.generateAll(character.getCharId());
                        successCount++;
                    } catch (Exception e) {
                        log.warn(
                                "Character image generation failed and will continue: charId={}, error={}",
                                character.getCharId(),
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
                    project.setStatus(ProjectStatus.IMAGE_REVIEW.getCode());
                    projectRepository.updateById(project);
                    log.info("Project moved to IMAGE_REVIEW: projectId={}", projectId);
                }
            } catch (Exception e) {
                log.error("Character image batch generation failed: projectId={}", projectId, e);
                Project project = projectRepository.findByProjectId(projectId);
                if (project != null && ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
                    project.setStatus(ProjectStatus.IMAGE_GENERATING_FAILED.getCode());
                    projectRepository.updateById(project);
                    log.info("Project moved to IMAGE_GENERATING_FAILED: projectId={}", projectId);
                }
            }
        });
    }

    private String generateProjectId() {
        return "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
