package com.comic.service.pipeline;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.common.EpisodeInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.request.ProjectCreateRequest;
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

    // ==================== Pipeline 状态转换 ====================

    @Transactional
    public void advancePipeline(String projectId, String direction, String event) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if ("backward".equals(direction)) {
            rollbackPipeline(project);
        } else {
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
    }

    /** 保留旧签名的兼容方法 */
    @Transactional
    public void advancePipeline(String projectId, String event) {
        advancePipeline(projectId, "forward", event);
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
            case STORYBOARD_GENERATING:
                return ProjectStatus.ASSET_LOCKED;
            case STORYBOARD_REVIEW:
                return ProjectStatus.STORYBOARD_GENERATING;
            case PRODUCING:
                return ProjectStatus.STORYBOARD_REVIEW;
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
            case STORYBOARD_GENERATING:
            case STORYBOARD_REVIEW:
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
                }
                break;
            default:
                break;
        }
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

    // ==================== 状态增强（Producing / Storyboard）====================

    private void enrichProducingStatus(ProjectStatusResponse dto, String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            Episode producingEpisode = null;
            for (Episode ep : episodes) {
                String productionStatus = getEpisodeInfoStr(ep, EpisodeInfoKeys.PRODUCTION_STATUS);
                if ("IN_PROGRESS".equals(productionStatus)) {
                    producingEpisode = ep;
                    break;
                }
            }

            if (producingEpisode == null) {
                boolean hasProducible = false;
                for (Episode ep : episodes) {
                    String productionStatus = getEpisodeInfoStr(ep, EpisodeInfoKeys.PRODUCTION_STATUS);
                    if ("DONE".equals(ep.getStatus())
                            && (productionStatus == null
                            || "NOT_STARTED".equals(productionStatus)
                            || "FAILED".equals(productionStatus))) {
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
                Integer epNum = getEpisodeInfoInt(currentEpisode, EpisodeInfoKeys.EPISODE_NUM);
                dto.setStoryboardCurrentEpisode(epNum);
                dto.setStoryboardReviewEpisodeId(String.valueOf(currentEpisode.getId()));
            }

            ProjectStatus projectStatus = ProjectStatus.fromCode(project.getStatus());
            if (failedEpisode != null && projectStatus == ProjectStatus.STORYBOARD_GENERATING) {
                projectStatus = ProjectStatus.STORYBOARD_GENERATING_FAILED;

                // Auto-recover stale generating episodes so the user can retry
                if (isStaleGenerating(failedEpisode)) {
                    failedEpisode.setStatus("STORYBOARD_FAILED");
                    Map<String, Object> info = failedEpisode.getEpisodeInfo();
                    if (info == null) {
                        info = new HashMap<>();
                        failedEpisode.setEpisodeInfo(info);
                    }
                    info.put(EpisodeInfoKeys.ERROR_MSG, "Generation timed out (server may have restarted)");
                    episodeRepository.updateById(failedEpisode);
                    log.warn("Recovered stale generating episode: episodeId={}, episodeNum={}",
                            failedEpisode.getId(), getEpisodeInfoInt(failedEpisode, EpisodeInfoKeys.EPISODE_NUM));
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
                Integer epNum = getEpisodeInfoInt(currentEpisode, EpisodeInfoKeys.EPISODE_NUM);
                switch (projectStatus) {
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
        String errorMsg = getEpisodeInfoStr(episode, EpisodeInfoKeys.ERROR_MSG);
        boolean hasError = errorMsg != null && !errorMsg.trim().isEmpty();
        // storyboardJson 已移除（分镜数据存 Panel 表），只看 errorMsg 判断
        return hasError;
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

    // ==================== 阶段触发 ====================

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