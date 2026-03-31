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

import com.comic.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private com.comic.service.production.PanelProductionService panelProductionService;

    @Lazy
    @Autowired
    private StoryboardService storyboardService;

    /** 自引用，用于异步线程中调用 advancePipeline（绕过 Spring 代理） */
    @Lazy
    @Autowired
    private PipelineService pipelineServiceSelf;

    /** Redis 操作，用于回滚时释放生产锁 */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
        if ("all_panels_confirmed".equals(event) || "all_grids_approved".equals(event)) {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);

            // 新流程：检查所有 Episode 的 gridStatus === "approved"
            boolean hasNewFlow = episodes.stream()
                .anyMatch(ep -> {
                    Map<String, Object> info = ep.getEpisodeInfo();
                    return info != null && info.containsKey("gridStatus");
                });

            if (hasNewFlow) {
                // 新流程验证：所有 Episode 九宫格必须审核通过
                for (Episode ep : episodes) {
                    Map<String, Object> info = ep.getEpisodeInfo();
                    if (info == null || !"approved".equals(info.get("gridStatus"))) {
                        throw new BusinessException("请先审核通过所有剧集的九宫格");
                    }
                }
            } else {
                // 旧流程验证：Panel 数量 > 0
                int totalPanels = 0;
                for (Episode ep : episodes) {
                    totalPanels += panelRepository.findByEpisodeId(ep.getId()).size();
                }
                if (totalPanels == 0) {
                    throw new BusinessException("没有可生产的分镜");
                }
            }
        }

        String oldStatus = project.getStatus();
        project.setStatus(next.getCode());
        projectRepository.updateById(project);
        log.info("Pipeline advanced: projectId={}, {} -> {} (event={})", projectId, oldStatus, next.getCode(), event);

        broadcaster.broadcast(projectId, oldStatus, next.getCode());

        // 将自动推进的中间状态合并到当前事务中，避免嵌套 afterCommit 导致回调丢失
        // 例如: confirm_script → SCRIPT_CONFIRMED → 自动推进 → CHARACTER_EXTRACTING
        // 这样整个链路只注册一次 afterCommit，确保 triggerNextStage 一定被触发
        next = collapseAutoAdvance(projectId, project, next);

        // 延迟到事务提交后再触发下一阶段，避免异步任务读到未提交的旧状态
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            ProjectStatus capturedNext = next;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    triggerNextStage(projectId, capturedNext);
                }
            });
        } else {
            triggerNextStage(projectId, next);
        }
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

    /**
     * 将自动推进的中间状态合并到当前事务中。
     * 例如 SCRIPT_CONFIRMED 会自动推进到 CHARACTER_EXTRACTING，
     * 不需要等到 afterCommit 再开一个新事务去推进，避免嵌套 afterCommit 回调丢失。
     *
     * @return 最终停留的状态（不再有自动推进的状态）
     */
    private ProjectStatus collapseAutoAdvance(String projectId, Project project, ProjectStatus current) {
        ProjectStatus next = current;
        while (true) {
            String autoEvent = getAutoAdvanceEvent(next);
            if (autoEvent == null) return next;

            ProjectStatus autoNext = ProjectStatus.resolveTransition(next, autoEvent);
            if (autoNext == null) return next;

            String oldCode = next.getCode();
            next = autoNext;
            project.setStatus(next.getCode());
            projectRepository.updateById(project);
            log.info("Pipeline auto-advanced: projectId={}, {} -> {} (event={})",
                    projectId, oldCode, next.getCode(), autoEvent);
            broadcaster.broadcast(projectId, oldCode, next.getCode());
        }
    }

    /**
     * 获取自动推进事件。返回 null 表示该状态没有自动推进。
     */
    private String getAutoAdvanceEvent(ProjectStatus status) {
        switch (status) {
            case SCRIPT_CONFIRMED: return "start_character_extraction";
            case CHARACTER_CONFIRMED: return "start_image_generation";
            case ASSET_LOCKED: return "start_episode_script";
            default: return null;
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
            case EPISODE_SCRIPT_GENERATING:
                return ProjectStatus.ASSET_LOCKED;
            case EPISODE_SCRIPT_GENERATING_FAILED:
                return ProjectStatus.EPISODE_SCRIPT_GENERATING;
            case STORYBOARD_GENERATING:
                return ProjectStatus.ASSET_LOCKED;
            case STORYBOARD_GENERATING_FAILED:
                return ProjectStatus.STORYBOARD_GENERATING;
            case STORYBOARD_REVIEW:
                return ProjectStatus.EPISODE_SCRIPT_GENERATING;
            case PRODUCING:
                return ProjectStatus.STORYBOARD_REVIEW;
            case MERGING:
                return ProjectStatus.PRODUCING;
            case COMPLETED:
                return ProjectStatus.MERGING;
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
                // No storyboard data exists at this stage, nothing to clean
                break;
            case EPISODE_SCRIPT_GENERATING:
            case EPISODE_SCRIPT_GENERATING_FAILED:
            case STORYBOARD_GENERATING:
            case STORYBOARD_GENERATING_FAILED:
            case STORYBOARD_REVIEW:
            case PRODUCING:
            case MERGING:
                // 回滚 PRODUCING 时释放生产锁
                if (from == ProjectStatus.PRODUCING && stringRedisTemplate != null) {
                    try {
                        stringRedisTemplate.delete("lock:production:" + projectId);
                    } catch (Exception e) {
                        log.warn("Failed to release production lock during rollback: projectId={}", projectId, e);
                    }
                }
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
                            panelInfo.remove("gridStatus");
                            panelInfo.remove("gridImages");
                            panelInfo.remove("fusionImageUrl");
                            panelInfo.remove("shots");
                            panelInfo.remove("gridRejectionFeedback");
                            panelInfo.remove("gridPageCount");
                            panelInfo.remove("totalShots");
                            panelInfo.remove("totalDuration");
                            panel.setPanelInfo(panelInfo);
                            panelRepository.updateById(panel);
                        }
                    }
                }
                // 回滚 MERGING/COMPLETED 时清除合并结果
                if (from == ProjectStatus.MERGING || from == ProjectStatus.COMPLETED) {
                    Project proj = projectRepository.findByProjectId(projectId);
                    if (proj != null) {
                        Map<String, Object> projInfo = proj.getProjectInfo();
                        if (projInfo != null) {
                            projInfo.remove("finalVideoUrl");
                            projInfo.remove("mergeStatus");
                            proj.setProjectInfo(projInfo);
                            projectRepository.updateById(proj);
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
        } else if (status == ProjectStatus.MERGING) {
            enrichMergingStatus(dto, project);
        } else if (status == ProjectStatus.EPISODE_SCRIPT_GENERATING
                || status == ProjectStatus.EPISODE_SCRIPT_GENERATING_FAILED
                || status == ProjectStatus.STORYBOARD_GENERATING
                || status == ProjectStatus.STORYBOARD_GENERATING_FAILED
                || status == ProjectStatus.STORYBOARD_REVIEW) {
            enrichPanelStatus(dto, projectId);
        } else {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            dto.setGenerating(status.isGenerating());
            // COMPLETED 时附带合并结果
            if (status == ProjectStatus.COMPLETED) {
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

            ProjectStatus projectStatus = ProjectStatus.fromCode(project.getStatus());
            if (failedEpisode != null && projectStatus == ProjectStatus.STORYBOARD_GENERATING) {
                projectStatus = ProjectStatus.STORYBOARD_GENERATING_FAILED;
            } else if (failedEpisode == null && projectStatus == ProjectStatus.STORYBOARD_GENERATING_FAILED) {
                // 失败已恢复：有完成/审核中的 episode 则恢复到 STORYBOARD_REVIEW，否则回到 STORYBOARD_GENERATING
                projectStatus = (completedCount > 0 || reviewEpisode != null)
                        ? ProjectStatus.STORYBOARD_REVIEW : ProjectStatus.STORYBOARD_GENERATING;
                project.setStatus(projectStatus.getCode());
                projectRepository.updateById(project);
                log.info("Panel status recovered: projectId={}, STORYBOARD_GENERATING_FAILED -> {}", projectId, projectStatus.getCode());
            }

            dto.setStatusCode(projectStatus.getCode());
            dto.setStatusDescription(projectStatus.getDescription());
            dto.setGenerating(projectStatus.isGenerating());
            dto.setFailed(projectStatus.isFailed());
            dto.setReview(projectStatus.isReview());

            boolean allConfirmed = completedCount == totalEpisodes && projectStatus == ProjectStatus.STORYBOARD_REVIEW;
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

    // ==================== 阶段触发 ====================

    private void triggerNextStage(String projectId, ProjectStatus status) {
        log.info("triggerNextStage: projectId={}, status={}", projectId, status);
        switch (status) {
            case OUTLINE_GENERATING:
                CompletableFuture.runAsync(() -> {
                    try {
                        scriptService.generateScriptOutline(projectId);
                    } catch (Exception e) {
                        log.error("Script outline generation failed async: projectId={}, error={}", projectId, e.getMessage(), e);
                        safeAdvanceOnFailure(projectId, "script_failed", "OUTLINE_GENERATING", e);
                    }
                });
                break;

            case CHARACTER_EXTRACTING:
                CompletableFuture.runAsync(() -> {
                    try {
                        characterExtractService.extractCharacters(projectId);
                    } catch (Exception e) {
                        log.error("Character extraction failed async: projectId={}, error={}", projectId, e.getMessage(), e);
                        safeAdvanceOnFailure(projectId, "characters_failed", "CHARACTER_EXTRACTING", e);
                    }
                });
                break;

            case IMAGE_GENERATING:
                generateAllCharacterImagesAsync(projectId);
                break;

            case EPISODE_SCRIPT_GENERATING:
                CompletableFuture.runAsync(() -> {
                    try {
                        storyboardService.generateEpisodeScriptAndStoryboard(projectId);
                    } catch (Exception e) {
                        log.error("Episode script/storyboard generation failed: projectId={}, error={}", projectId, e.getMessage(), e);
                        // 根据当前实际状态选择正确的失败事件
                        try {
                            Project p = projectRepository.findByProjectId(projectId);
                            String currentStatus = p != null ? p.getStatus() : "";
                            if (ProjectStatus.STORYBOARD_GENERATING.getCode().equals(currentStatus)) {
                                safeAdvanceOnFailure(projectId, "storyboard_failed", currentStatus, e);
                            } else {
                                safeAdvanceOnFailure(projectId, "episode_script_failed", currentStatus, e);
                            }
                        } catch (Exception ex2) {
                            safeAdvanceOnFailure(projectId, "episode_script_failed", "EPISODE_SCRIPT_GENERATING", e);
                        }
                    }
                });
                break;

            case PRODUCING:
                // Auto-start strict-serial production orchestrator
                // Note: startOrResume removed - manual control required
                break;

            default:
                break;
        }
    }

    /**
     * 异步任务失败后的兜底状态更新。
     * 服务方法的 REQUIRES_NEW 事务回滚后，其内部的 advancePipeline 不会提交。
     * 此方法在事务回滚后的独立上下文中执行，确保失败状态写入数据库。
     */
    private void safeAdvanceOnFailure(String projectId, String failedEvent, String expectedStatus, Exception original) {
        try {
            pipelineServiceSelf.advancePipeline(projectId, failedEvent);
            log.info("Status updated to FAILED on recovery: projectId={}, event={}", projectId, failedEvent);
        } catch (Exception ex) {
            log.error("Failed to update status after {} failure: projectId={}, currentStatus may be stuck. "
                    + "Use retry event to recover.", expectedStatus, projectId, ex);
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