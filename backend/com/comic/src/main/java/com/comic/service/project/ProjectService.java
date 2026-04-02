package com.comic.service.project;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.exception.BusinessException;
import com.comic.constant.CharacterInfoKeys;
import com.comic.constant.EpisodeInfoKeys;
import com.comic.constant.ProjectInfoKeys;
import com.comic.statemachine.enums.ProjectMilestone;
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
import com.comic.service.redis.ProgressService;
import com.comic.service.script.ScriptService;
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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final ProgressService progressService;

    @Lazy
    @Autowired
    private com.comic.service.production.PanelProductionService panelProductionService;


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
        project.setStatus(ProjectMilestone.DRAFT.getCode());

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

        String milestone = project.getStatus();
        ProjectMilestone pm = ProjectMilestone.fromCode(milestone);

        // 基于 milestone + 数据存在性推导有效状态和前端步骤
        String effectiveState;
        int frontendStep;
        boolean isGenerating = false;
        boolean isReview = false;
        boolean isFailed = false;

        List<Integer> completedSteps = new ArrayList<>();
        List<String> availableActions = new ArrayList<>();

        switch (pm) {
            case DRAFT:
                if (outlineExists(project)) {
                    effectiveState = "outline_review";
                    frontendStep = 1;
                    isReview = true;
                    availableActions = Arrays.asList("confirm_outline", "revise_outline");
                } else {
                    effectiveState = "draft";
                    frontendStep = 1;
                    availableActions = Arrays.asList("generate_outline");
                }
                break;

            case OUTLINE_CONFIRMED:
                if (episodesExist(projectId)) {
                    effectiveState = "episode_review";
                    frontendStep = 2;
                    isReview = true;
                    availableActions = Arrays.asList("confirm_episodes");
                } else {
                    effectiveState = "outline_confirmed";
                    frontendStep = 2;
                    availableActions = Arrays.asList("generate_episodes");
                }
                completedSteps.add(1);
                break;

            case EPISODE_CONFIRMED: {
                boolean charsExist = charactersExist(projectId);
                boolean imagesDone = allCharacterImagesDone(projectId);
                if (charsExist && imagesDone) {
                    effectiveState = "asset_review";
                    frontendStep = 3;
                    isReview = true;
                    availableActions = Arrays.asList("confirm_assets");
                } else if (charsExist) {
                    effectiveState = "asset_image_pending";
                    frontendStep = 3;
                    availableActions = Arrays.asList("generate_images");
                } else {
                    effectiveState = "episode_confirmed";
                    frontendStep = 3;
                    availableActions = Arrays.asList("extract_characters");
                }
                completedSteps.add(1);
                completedSteps.add(2);
                break;
            }

            case ASSET_CONFIRMED: {
                // 面板生产阶段
                int[] panelStats = getPanelStats(projectId);
                int total = panelStats[0];
                int completed = panelStats[1];
                int failed = panelStats[2];

                if (total > 0 && completed == total) {
                    effectiveState = "panel_review";
                    frontendStep = 4;
                    isReview = true;
                    availableActions = Arrays.asList("confirm_panels");
                } else if (total > 0) {
                    effectiveState = "panel_producing";
                    frontendStep = 4;
                    isGenerating = failed == 0;
                    availableActions = Arrays.asList("retry_failed_panels");
                } else {
                    effectiveState = "asset_confirmed";
                    frontendStep = 4;
                    availableActions = Arrays.asList("generate_panels");
                }
                completedSteps.add(1);
                completedSteps.add(2);
                completedSteps.add(3);
                break;
            }

            case PANEL_CONFIRMED:
                effectiveState = "panel_confirmed";
                frontendStep = 5;
                availableActions = Arrays.asList("start_assembling");
                completedSteps.add(1);
                completedSteps.add(2);
                completedSteps.add(3);
                completedSteps.add(4);
                // 如果有最终视频 URL，附带结果
                Map<String, Object> info5 = project.getProjectInfo();
                break;

            case COMPLETED:
                effectiveState = "completed";
                frontendStep = 6;
                completedSteps.add(1);
                completedSteps.add(2);
                completedSteps.add(3);
                completedSteps.add(4);
                completedSteps.add(5);
                completedSteps.add(6);
                break;

            default:
                effectiveState = "draft";
                frontendStep = 1;
        }

        // Redis 推导：error 存在且无 generating 锁 → 失败；generating 锁存在 → 生成中
        String redisError = progressService.getError(projectId);
        boolean redisGenerating = progressService.isGenerating(projectId);
        if (redisError != null && !redisError.isEmpty() && !redisGenerating) {
            isFailed = true;
            // 失败时追加 retry action
            if (!availableActions.contains("retry")) {
                List<String> withRetry = new ArrayList<>(availableActions);
                withRetry.add("retry");
                availableActions = withRetry;
            }
        }
        if (redisGenerating) {
            isGenerating = true;
        }

        ProjectStatusResponse dto = new ProjectStatusResponse();
        dto.setProjectId(project.getProjectId());
        dto.setStatusCode(effectiveState);
        dto.setStatusDescription(getStateDescription(effectiveState));
        dto.setCurrentStep(frontendStep);
        dto.setGenerating(isGenerating);
        dto.setFailed(isFailed);
        dto.setReview(isReview);
        dto.setCompletedSteps(completedSteps);
        dto.setAvailableActions(availableActions);
        if (redisError != null) {
            dto.setErrorMessage(redisError);
        }

        // COMPLETED / PANEL_CONFIRMED 时附带合并结果
        if (pm == ProjectMilestone.COMPLETED || pm == ProjectMilestone.PANEL_CONFIRMED) {
            Map<String, Object> pInfo = project.getProjectInfo();
            if (pInfo != null) {
                dto.setFinalVideoUrl(strVal(pInfo, "finalVideoUrl"));
                dto.setMergeStatus(strVal(pInfo, "mergeStatus"));
            }
        }

        return dto;
    }

    // ===== 数据存在性检查 =====

    private boolean outlineExists(Project project) {
        Map<String, Object> info = project.getProjectInfo();
        if (info == null) return false;
        Object script = info.get("script");
        if (script instanceof Map) {
            Object outline = ((Map<?, ?>) script).get("outline");
            return outline != null && !outline.toString().trim().isEmpty();
        }
        return false;
    }

    private boolean episodesExist(String projectId) {
        List<?> episodes = episodeRepository.findByProjectId(projectId);
        return episodes != null && !episodes.isEmpty();
    }

    private boolean charactersExist(String projectId) {
        List<?> characters = characterRepository.findByProjectId(projectId);
        return characters != null && !characters.isEmpty();
    }

    private boolean allCharacterImagesDone(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        for (Character c : characters) {
            Map<String, Object> info = c.getCharacterInfo();
            if (info == null) return false;
            String threeView = info.get("threeViewGridStatus") != null ? info.get("threeViewGridStatus").toString() : null;
            if (!"COMPLETED".equals(threeView)) return false;
            String role = info.get("role") != null ? info.get("role").toString() : null;
            if (!"配角".equals(role)) {
                String expression = info.get("expressionGridStatus") != null ? info.get("expressionGridStatus").toString() : null;
                if (!"COMPLETED".equals(expression)) return false;
            }
        }
        return true;
    }

    /**
     * 返回 [total, completed, failed]
     */
    private int[] getPanelStats(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        int total = 0, completed = 0, failed = 0;
        for (Episode ep : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(ep.getId());
            for (Panel panel : panels) {
                total++;
                Map<String, Object> pinfo = panel.getPanelInfo();
                if (pinfo == null) continue;
                String videoStatus = pinfo.get("videoStatus") != null ? pinfo.get("videoStatus").toString() : null;
                if ("completed".equals(videoStatus)) completed++;
                else if ("failed".equals(videoStatus)) failed++;
            }
        }
        return new int[]{total, completed, failed};
    }

    private String getStateDescription(String state) {
        switch (state) {
            case "draft": return "草稿";
            case "outline_review": return "大纲已生成，请审核";
            case "outline_confirmed": return "大纲已确认";
            case "episode_review": return "分集剧本已生成，请审核";
            case "episode_confirmed": return "分集剧本已确认";
            case "asset_review": return "素材已就绪，请确认";
            case "asset_image_pending": return "角色图片生成中";
            case "asset_confirmed": return "素材已确认";
            case "panel_producing": return "面板生产中";
            case "panel_review": return "面板已就绪，请确认";
            case "panel_confirmed": return "面板已确认";
            case "completed": return "已完成";
            default: return state;
        }
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
        ProjectMilestone milestone = ProjectMilestone.fromCode(project.getStatus());
        Map<String, Object> info = project.getProjectInfo();

        ProjectListItemResponse dto = new ProjectListItemResponse();
        dto.setProjectId(project.getProjectId());
        dto.setStoryPrompt(getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT));
        dto.setGenre(getProjectInfoStr(project, ProjectInfoKeys.GENRE));
        dto.setTargetAudience(getProjectInfoStr(project, ProjectInfoKeys.TARGET_AUDIENCE));
        dto.setTotalEpisodes(getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES));
        dto.setEpisodeDuration(getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION));
        dto.setVisualStyle(getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE));
        dto.setStatusCode(milestone.getCode());
        dto.setStatusDescription(milestone.getDescription());
        dto.setCurrentStep(milestone.ordinal() + 1);
        dto.setGenerating(false);
        dto.setFailed(false);
        dto.setReview(false);
        dto.setCompletedSteps(completedStepsForMilestone(milestone));
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());

        return dto;
    }

    private List<Integer> completedStepsForMilestone(ProjectMilestone milestone) {
        List<Integer> steps = new ArrayList<>();
        int step = milestone.ordinal(); // 0-based
        for (int i = 1; i <= step; i++) {
            steps.add(i);
        }
        return steps;
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private String generateProjectId() {
        return "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
    }
}