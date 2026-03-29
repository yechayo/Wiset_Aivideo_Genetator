package com.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.common.*;
import com.comic.dto.request.AdvanceRequest;
import com.comic.dto.request.ProjectCreateRequest;
import com.comic.dto.response.PaginatedResponse;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectProductionSummaryResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.*;
import com.comic.repository.*;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final UserRepository userRepository;
    private final ProjectStateMachineService stateMachineService;

    @PostMapping
    @Operation(summary = "创建项目")
    public Result<Map<String, String>> createProject(@RequestBody ProjectCreateRequest dto,
                                                     @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();

        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setUserId(userId);
        project.setDeleted(false);
        project.setStatus(ProjectStatus.DRAFT.getCode());

        Map<String, Object> info = new HashMap<>();
        info.put(ProjectInfoKeys.STORY_PROMPT, dto.getStoryPrompt());
        info.put(ProjectInfoKeys.GENRE, dto.getGenre());
        info.put(ProjectInfoKeys.TARGET_AUDIENCE, dto.getTargetAudience());
        info.put(ProjectInfoKeys.TOTAL_EPISODES, dto.getTotalEpisodes());
        info.put(ProjectInfoKeys.EPISODE_DURATION, dto.getEpisodeDuration());
        info.put(ProjectInfoKeys.VISUAL_STYLE, dto.getVisualStyle());
        project.setProjectInfo(info);

        projectRepository.insert(project);

        Map<String, String> result = new HashMap<>();
        result.put("projectId", project.getProjectId());
        return Result.ok(result);
    }

    @GetMapping("/{projectId}")
    @Operation(description = "返回的是原始项目数据（包含 projectInfo 里的故事提示、类型、风格等创作配置字段），适合需要展示项目基本信息的页面（如项目详情编辑页）")
    public Result<Project> getProjectStatus(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
        }
        return Result.ok(project);
    }

    @GetMapping("/{projectId}/status")
    @Operation(summary = "获取项目状态详情", description = "返回的是状态机解析后的前端驱动数据，专门用于控制前端步骤条、按钮可用状态、进度展示，不包含创作配置内容")
    public Result<ProjectStatusResponse> getProjectStatusDetail(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
        }

        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectStatusResponse dto = new ProjectStatusResponse();
        dto.setProjectId(project.getProjectId());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setAvailableActions(status.getAvailableActions());

        if (status == ProjectStatus.PANEL_GENERATING) {
            enrichPanelProductionStatus(dto, projectId);
        } else if (status == ProjectStatus.VIDEO_ASSEMBLING) {
            dto.setStatusCode("VIDEO_ASSEMBLING");
            dto.setStatusDescription("视频拼接剪辑中");
            dto.setGenerating(true);
            // 获取最终视频 URL
            enrichFinalVideoUrl(dto, projectId);
        } else if (status == ProjectStatus.PANEL_REVIEW
                || status == ProjectStatus.PANEL_GENERATING_FAILED) {
            enrichPanelStatus(dto, projectId);
        } else if (status == ProjectStatus.COMPLETED) {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            // 获取最终视频 URL
            enrichFinalVideoUrl(dto, projectId);
        } else {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            dto.setGenerating(status.isGenerating());
        }

        // PANEL_CONFIRMED 状态需要设置 panelTotalEpisodes
        if (status == ProjectStatus.PANEL_CONFIRMED) {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            dto.setPanelTotalEpisodes(episodes.size());
        }

        return Result.ok(dto);
    }

    /**
     * 从 Episode 表中获取最终视频 URL
     */
    private void enrichFinalVideoUrl(ProjectStatusResponse dto, String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            for (Episode ep : episodes) {
                Map<String, Object> epInfo = ep.getEpisodeInfo();
                if (epInfo != null) {
                    String finalVideoUrl = (String) epInfo.get("finalVideoUrl");
                    if (finalVideoUrl != null && !finalVideoUrl.isEmpty()) {
                        dto.setFinalVideoUrl(finalVideoUrl);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误，不影响主流程
        }
    }

    @GetMapping("/{projectId}/production/summary")
    @Operation(summary = "获取项目生产摘要", description = "PANEL_GENERATING 阶段专用：当前 Panel、进度、阻塞原因")
    public Result<ProjectProductionSummaryResponse> getProductionSummary(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
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

                if (summary.getCurrentPanelId() == null) {
                    summary.setCurrentEpisodeId(episode.getId());
                    summary.setCurrentPanelId(panel.getId());
                    summary.setCurrentPanelIndex(currentIndex);

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
        return Result.ok(summary);
    }

    @GetMapping
    @Operation(summary = "项目列表（分页）")
    public Result<PaginatedResponse<ProjectListItemResponse>> getProjects(
            @Parameter(description = "状态筛选") @RequestParam(required = false) String status,
            @Parameter(description = "排序字段") @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @Parameter(description = "排序方向") @RequestParam(required = false, defaultValue = "desc") String sortOrder,
            @Parameter(description = "页码") @RequestParam(required = false, defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(required = false, defaultValue = "20") int size,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();

        IPage<Project> projectPage = projectRepository.findPage(userId, status, sortBy, sortOrder, page, size);

        List<ProjectListItemResponse> items = new ArrayList<>();
        for (Project project : projectPage.getRecords()) {
            items.add(toListItemDTO(project));
        }

        return Result.ok(PaginatedResponse.of(items, projectPage.getTotal(), (int) projectPage.getCurrent(), (int) projectPage.getSize()));
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "全量更新项目")
    public Result<Void> updateProject(@PathVariable String projectId,
                                     @RequestBody ProjectCreateRequest dto) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
        }
        Map<String, Object> info = project.getProjectInfo();
        if (info == null) {
            info = new HashMap<>();
        }
        if (dto.getStoryPrompt() != null) info.put(ProjectInfoKeys.STORY_PROMPT, dto.getStoryPrompt());
        if (dto.getGenre() != null) info.put(ProjectInfoKeys.GENRE, dto.getGenre());
        if (dto.getTargetAudience() != null) info.put(ProjectInfoKeys.TARGET_AUDIENCE, dto.getTargetAudience());
        if (dto.getTotalEpisodes() != null) info.put(ProjectInfoKeys.TOTAL_EPISODES, dto.getTotalEpisodes());
        if (dto.getEpisodeDuration() != null) info.put(ProjectInfoKeys.EPISODE_DURATION, dto.getEpisodeDuration());
        if (dto.getVisualStyle() != null) info.put(ProjectInfoKeys.VISUAL_STYLE, dto.getVisualStyle());
        project.setProjectInfo(info);
        projectRepository.updateById(project);
        return Result.ok();
    }

    @PatchMapping("/{projectId}")
    @Operation(summary = "部分更新项目")
    public Result<Void> partialUpdateProject(@PathVariable String projectId,
                                              @RequestBody ProjectCreateRequest dto) {
        return updateProject(projectId, dto);
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "删除项目（逻辑删除）")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
        }
        project.setDeleted(true);
        projectRepository.updateById(project);
        return Result.ok();
    }

    @PostMapping("/{projectId}/status/advance")
    @Operation(summary = "推进/回退状态 (已弃用，请使用具体的业务接口)", deprecated = true)
    public Result<Void> advancePipeline(@PathVariable String projectId,
                                        @RequestBody AdvanceRequest request) {
        if ("backward".equals(request.getDirection())) {
            return Result.fail("状态回退已弃用，请通过具体业务接口操作");
        }

        ProjectEventType eventType = mapEventToStateMachineEvent(request.getEvent());
        if (eventType != null) {
            stateMachineService.sendEvent(projectId, eventType);
            return Result.ok();
        }

        return Result.fail("未知的事件类型: " + request.getEvent());
    }

    @PostMapping("/{projectId}/status/sync")
    @Operation(summary = "同步项目状态", description = "根据实际分镜完成情况自动同步项目状态，用于数据库直接修改后修复状态")
    public Result<Map<String, Object>> syncProjectStatus(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
        }

        ProjectStatus currentStatus = ProjectStatus.fromCode(project.getStatus());

        // 只在分镜相关阶段才需要同步
        if (currentStatus != ProjectStatus.PANEL_GENERATING
                && currentStatus != ProjectStatus.PANEL_REVIEW
                && currentStatus != ProjectStatus.PANEL_GENERATING_FAILED) {
            return Result.fail("当前状态无需同步: " + currentStatus.getCode());
        }

        // 检查所有分镜状态
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        int totalPanels = 0;
        int completedPanels = 0;
        int failedPanels = 0;
        int pendingPanels = 0;

        for (Episode episode : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(episode.getId());
            totalPanels += panels.size();
            for (Panel p : panels) {
                String status = getPanelStatus(p);
                if ("completed".equals(status)) {
                    completedPanels++;
                } else if ("failed".equals(status)) {
                    failedPanels++;
                } else {
                    pendingPanels++;
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalPanels", totalPanels);
        result.put("completedPanels", completedPanels);
        result.put("failedPanels", failedPanels);
        result.put("pendingPanels", pendingPanels);

        // 如果没有任何分镜，不更新状态
        if (totalPanels == 0) {
            result.put("message", "没有任何分镜，无需同步");
            return Result.ok(result);
        }

        // 根据分镜状态决定项目状态
        ProjectStatus newStatus;
        if (completedPanels == totalPanels) {
            newStatus = ProjectStatus.PANEL_REVIEW;
            result.put("message", "所有分镜已完成，状态更新为 PANEL_REVIEW");
        } else if (failedPanels > 0 && pendingPanels == 0) {
            newStatus = ProjectStatus.PANEL_GENERATING_FAILED;
            result.put("message", "存在失败分镜且无待处理分镜，状态更新为 PANEL_GENERATING_FAILED");
        } else if (completedPanels > 0 || pendingPanels > 0) {
            newStatus = ProjectStatus.PANEL_GENERATING;
            result.put("message", "存在进行中或待处理的分镜，状态更新为 PANEL_GENERATING");
        } else {
            result.put("message", "状态无需更改");
            return Result.ok(result);
        }

        if (!newStatus.getCode().equals(project.getStatus())) {
            project.setStatus(newStatus.getCode());
            projectRepository.updateById(project);
            result.put("oldStatus", currentStatus.getCode());
            result.put("newStatus", newStatus.getCode());
            result.put("updated", true);
        } else {
            result.put("updated", false);
        }

        return Result.ok(result);
    }

    @PostMapping("/{projectId}/video/assemble")
    @Operation(summary = "开始视频拼接", description = "将所有分镜视频按顺序拼接成完整视频")
    public Result<Map<String, String>> startVideoAssembly(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail("项目不存在");
        }

        ProjectStatus currentStatus = ProjectStatus.fromCode(project.getStatus());
        if (currentStatus != ProjectStatus.PANEL_CONFIRMED) {
            return Result.fail("当前状态不能开始视频拼接，需要 PANEL_CONFIRMED 状态");
        }

        stateMachineService.sendEvent(projectId, ProjectEventType.START_VIDEO_ASSEMBLY);

        Map<String, String> result = new HashMap<>();
        result.put("message", "视频拼接已开始");
        result.put("projectId", projectId);
        return Result.ok(result);
    }

    // ==================== 私有辅助方法 ====================

    private ProjectEventType mapEventToStateMachineEvent(String event) {
        if (event == null) return null;
        switch (event) {
            case "generate_outline":
                return ProjectEventType.GENERATE_OUTLINE;
            case "confirm_outline":
                return ProjectEventType.GENERATE_EPISODES;
            case "confirm_script":
                return ProjectEventType.CONFIRM_SCRIPT;
            case "start_character_extraction":
                return ProjectEventType.EXTRACT_CHARACTERS;
            case "confirm_characters":
                return ProjectEventType.CONFIRM_CHARACTERS;
            case "start_image_generation":
                return ProjectEventType.GENERATE_IMAGES;
            case "confirm_images":
                return ProjectEventType.CONFIRM_IMAGES;
            case "start_panels":
                return ProjectEventType.START_PANEL;
            case "start_video_assembly":
                return ProjectEventType.START_VIDEO_ASSEMBLY;
            default:
                return null;
        }
    }

    private ProjectListItemResponse toListItemDTO(Project project) {
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

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private String getPanelStatus(Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) return "pending";

        String videoStatus = strVal(info, "videoStatus");
        String comicStatus = strVal(info, "comicStatus");
        String bgStatus = strVal(info, "backgroundStatus");

        if ("completed".equals(videoStatus)) return "completed";
        if ("failed".equals(videoStatus) || "failed".equals(comicStatus) || "failed".equals(bgStatus)) return "failed";
        return "pending";
    }

    private void enrichPanelProductionStatus(ProjectStatusResponse dto, String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);

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
                dto.setStatusCode("PANEL_GENERATING");
                dto.setStatusDescription("准备开始分镜生产");
                dto.setGenerating(false);
                return;
            }

            if (completedPanels == totalPanels) {
                dto.setStatusCode("PANEL_GENERATING");
                dto.setStatusDescription("所有分镜生产完成");
                dto.setGenerating(false);
                dto.setProductionProgress(100);
                return;
            }

            int progress = (int) ((completedPanels * 100.0) / totalPanels);
            dto.setProductionProgress(progress);

            if (generatingPanels > 0) {
                dto.setStatusCode("PANEL_GENERATING");
                dto.setStatusDescription("分镜生产中 (" + completedPanels + "/" + totalPanels + ")");
                dto.setGenerating(true);
            } else if (failedPanels > 0) {
                dto.setStatusCode("PANEL_GENERATING");
                dto.setStatusDescription("部分分镜生产失败 (" + failedPanels + " 个失败)");
                dto.setGenerating(false);
            } else {
                dto.setStatusCode("PANEL_GENERATING");
                dto.setStatusDescription(hasPending ? "准备开始分镜生产" : "分镜生产中");
                dto.setGenerating(false);
            }
        } catch (Exception e) {
            log.warn("Failed to enrich panel production status: projectId={}, error={}", projectId, e.getMessage());
            dto.setStatusCode("PANEL_GENERATING");
            dto.setStatusDescription("分镜生产中");
            dto.setGenerating(true);
        }
    }

    private String getPanelOverallStatus(Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) return "pending";

        String videoStatus = strVal(info, "videoStatus");
        String comicStatus = strVal(info, "comicStatus");
        String bgStatus = strVal(info, "backgroundStatus");
        String bgUrl = strVal(info, "backgroundUrl");

        if ("completed".equals(videoStatus)) return "completed";
        if ("failed".equals(videoStatus) || "failed".equals(comicStatus) || "failed".equals(bgStatus)) return "failed";
        if ("generating".equals(videoStatus) || "generating".equals(comicStatus) || "generating".equals(bgStatus)) return "in_progress";
        if (bgUrl != null || strVal(info, "comicUrl") != null) return "in_progress";
        return "pending";
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
                String panelStatus = getEpisodeInfoStr(ep, "panelStatus");
                if (failedEpisode == null
                        && ("PANEL_FAILED".equals(panelStatus)
                            || isPanelGeneratingWithError(ep)
                            || isStaleGenerating(ep))) {
                    failedEpisode = ep;
                }
                if (generatingEpisode == null
                        && "PANEL_GENERATING".equals(panelStatus)
                        && !isPanelGeneratingWithError(ep)
                        && !isStaleGenerating(ep)) {
                    generatingEpisode = ep;
                }
                if (reviewEpisode == null && "PANEL_DONE".equals(panelStatus)) {
                    reviewEpisode = ep;
                }
                if (draftEpisode == null && (panelStatus == null || "DRAFT".equals(panelStatus))) {
                    draftEpisode = ep;
                }
            }

            Episode currentEpisode = failedEpisode != null ? failedEpisode
                    : generatingEpisode != null ? generatingEpisode
                    : reviewEpisode != null ? reviewEpisode
                    : draftEpisode;

            int completedCount = 0;
            for (Episode ep : episodes) {
                String panelStatus = getEpisodeInfoStr(ep, "panelStatus");
                if ("PANEL_CONFIRMED".equals(panelStatus)) {
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
        // TODO: 应该查询 Panel 表检查状态，而不是 episodeInfo
        String panelStatus = getEpisodeInfoStr(episode, "panelStatus");
        if (!"PANEL_GENERATING".equals(panelStatus)) {
            return false;
        }
        String errorMsg = getEpisodeInfoStr(episode, EpisodeInfoKeys.ERROR_MSG);
        return errorMsg != null && !errorMsg.trim().isEmpty();
    }

    private boolean isStaleGenerating(Episode episode) {
        if (episode == null) {
            return false;
        }
        // TODO: 应该查询 Panel 表检查状态，而不是 episodeInfo
        String panelStatus = getEpisodeInfoStr(episode, "panelStatus");
        if (!"PANEL_GENERATING".equals(panelStatus)) {
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
