package com.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.common.Result;
import com.comic.dto.request.AdvanceRequest;
import com.comic.dto.request.ProjectCreateRequest;
import com.comic.dto.response.PaginatedResponse;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectProductionSummaryResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.entity.User;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.repository.UserRepository;
import com.comic.service.project.ProjectService;
import com.comic.statemachine.service.ProjectMilestoneStateMachineService;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import com.comic.statemachine.enums.ProjectMilestone;
import com.comic.service.production.PanelProductionService;
import com.comic.service.production.VideoCompositionService;
import com.comic.service.panel.PanelService;
import com.comic.service.script.ScriptService;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "bearerAuth")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMilestoneStateMachineService milestoneStateMachineService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final PanelProductionService panelProductionService;
    private final VideoCompositionService videoCompositionService;
    private final PanelService panelService;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;

    @PostMapping
    @Operation(summary = "创建项目")
    public Result<Map<String, String>> createProject(@RequestBody ProjectCreateRequest dto,
                                                     @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();

        String projectId = projectService.createProject(
            userId,
            dto.getStoryPrompt(),
            dto.getGenre(),
            dto.getTargetAudience(),
            dto.getTotalEpisodes(),
            dto.getEpisodeDuration(),
            dto.getVisualStyle(),
            dto.getImageProvider(),
            dto.getVideoProvider(),
            dto.getVideoModel(),
            dto.getProductionMode(),
            dto.getNarrationPerspective(),
            dto.getNarrationVoiceId(),
            dto.getProtagonistVoiceId(),
            dto.getVideoRefMode(),
            dto.getScriptStyle()
        );

        Map<String, String> result = new HashMap<>();
        result.put("projectId", projectId);
        return Result.ok(result);
    }

    @GetMapping("/{projectId}")
    @Operation(description = "返回的是原始项目数据（包含 projectInfo 里的故事提示、类型、风格等创作配置字段），适合需要展示项目基本信息的页面（如项目详情编辑页）")
    public Result<Project> getProjectStatus(@PathVariable String projectId) {
        Project project = projectService.getProjectStatus(projectId);
        return Result.ok(project);
    }

    @GetMapping("/{projectId}/status")
    @Operation(summary = "获取项目状态详情", description = "返回的是状态机解析后的前端驱动数据，专门用于控制前端步骤条、按钮可用状态、进度展示，不包含创作配置内容")
    public Result<ProjectStatusResponse> getProjectStatusDetail(@PathVariable String projectId) {
        return Result.ok(projectService.getProjectStatusDetail(projectId));
    }

    @GetMapping("/{projectId}/production/summary")
    @Operation(summary = "获取项目生产摘要", description = "PRODUCING 阶段专用：当前 Panel、进度、阻塞原因")
    public Result<ProjectProductionSummaryResponse> getProductionSummary(@PathVariable String projectId) {
        return Result.ok(projectService.getProductionSummary(projectId));
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

        IPage<Project> projectPage = projectService.getProjectPage(userId, status, sortBy, sortOrder, page, size);

        List<ProjectListItemResponse> items = new java.util.ArrayList<>();
        for (Project project : projectPage.getRecords()) {
            items.add(projectService.toListItemDTO(project));
        }

        return Result.ok(PaginatedResponse.of(items, projectPage.getTotal(), (int) projectPage.getCurrent(), (int) projectPage.getSize()));
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "全量更新项目")
    public Result<Void> updateProject(@PathVariable String projectId,
                                     @RequestBody ProjectCreateRequest dto) {
        projectService.updateProject(projectId, dto);
        return Result.ok();
    }

    @PatchMapping("/{projectId}")
    @Operation(summary = "部分更新项目")
    public Result<Void> partialUpdateProject(@PathVariable String projectId,
                                              @RequestBody ProjectCreateRequest dto) {
        projectService.updateProject(projectId, dto);
        return Result.ok();
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "删除项目（逻辑删除）")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        projectService.logicalDeleteProject(projectId);
        return Result.ok();
    }

    @PostMapping("/{projectId}/status/advance")
    @Operation(summary = "推进/回退状态")
    public Result<Void> advancePipeline(@PathVariable String projectId,
                                        @RequestBody AdvanceRequest request) {
        if ("backward".equals(request.getDirection())) {
            String event = request.getEvent();
            ProjectMilestoneEventType rollbackEvent;
            if (event.contains("outline")) {
                rollbackEvent = ProjectMilestoneEventType.ROLLBACK_TO_DRAFT;
            } else if (event.contains("episode")) {
                rollbackEvent = ProjectMilestoneEventType.ROLLBACK_TO_OUTLINE_CONFIRMED;
            } else if (event.contains("character") || event.contains("asset")) {
                rollbackEvent = ProjectMilestoneEventType.ROLLBACK_TO_EPISODE_CONFIRMED;
            } else if (event.contains("panel")) {
                rollbackEvent = ProjectMilestoneEventType.ROLLBACK_TO_ASSET_CONFIRMED;
            } else {
                throw new IllegalArgumentException("Unknown rollback event: " + event);
            }
            milestoneStateMachineService.sendEvent(projectId, rollbackEvent);
        } else {
            String event = request.getEvent();
            ProjectMilestoneEventType mapped;
            switch (event != null ? event : "") {
                // ===== 生成类 action（不经过状态机，直接调用 service） =====
                case "generate_outline":
                    scriptService.generateScriptOutline(projectId);
                    return Result.ok();
                case "generate_episodes":
                    scriptService.generateAllEpisodes(projectId);
                    return Result.ok();
                case "extract_characters":
                    characterExtractService.extractCharacters(projectId);
                    return Result.ok();
                case "generate_images":
                    characterImageGenerationService.confirmImages(projectId);
                    return Result.ok();
                case "generate_panels":
                    panelProductionService.generateEpisodeScripts(projectId);
                    return Result.ok();
                case "generate_grids":
                    panelProductionService.generateGridImagesForProject(projectId);
                    return Result.ok();

                // ===== 确认类 action（经过状态机） =====
                case "confirm_outline":
                    mapped = ProjectMilestoneEventType.CONFIRM_OUTLINE;
                    break;
                case "confirm_episodes":
                    mapped = ProjectMilestoneEventType.CONFIRM_EPISODE;
                    break;
                case "confirm_assets":
                    mapped = ProjectMilestoneEventType.CONFIRM_ASSETS;
                    break;
                case "confirm_panels":
                    mapped = ProjectMilestoneEventType.CONFIRM_PANELS;
                    break;
                case "retry":
                case "production_completed":
                    mapped = ProjectMilestoneEventType.CONFIRM_PANELS;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown pipeline event: " + event);
            }
            milestoneStateMachineService.sendEvent(projectId, mapped);
        }
        return Result.ok();
    }

    @PostMapping("/{projectId}/panels/retry-failed")
    @Operation(summary = "批量重试项目下所有失败的面板视频")
    public Result<Map<String, Object>> retryFailedPanels(@PathVariable String projectId) {
        int retried = panelProductionService.retryAllFailedVideos(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("retried", retried);
        return Result.ok(result);
    }

    @PostMapping("/{projectId}/videos/merge")
    @Operation(summary = "合并所有剧集视频为完整视频")
    public Result<Map<String, String>> mergePanelVideos(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) return Result.fail("项目不存在");
        boolean isComicCommentary = "comic_commentary".equals(
                project.getProjectInfo().getOrDefault("productionMode", ""));
        String productionMode = isComicCommentary ? "comic_commentary" : "normal";

        // 获取项目所有剧集，按 episodeNum 排序
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        episodes.sort(Comparator.comparing((Episode ep) -> {
            Map<String, Object> info = ep.getEpisodeInfo();
            Object num = info != null ? info.get("episodeNum") : null;
            return num instanceof Number ? ((Number) num).intValue() : 0;
        }));

        List<String> episodeVideoUrls = new java.util.ArrayList<>();
        int composed = 0, skipped = 0;

        for (Episode episode : episodes) {
            Map<String, Object> epInfo = episode.getEpisodeInfo();
            String composedUrl = epInfo != null ? (String) epInfo.get("composedVideoUrl") : null;
            String composedStatus = epInfo != null ? (String) epInfo.get("composedVideoStatus") : null;

            if (composedUrl != null && "completed".equals(composedStatus)) {
                // 已有分集合成视频，直接使用
                episodeVideoUrls.add(composedUrl);
                composed++;
            } else {
                // 未合成，先合成该集
                try {
                    panelService.composeEpisode(episode.getId(), productionMode);
                    // 重新读取合成后的 URL
                    Episode refreshed = episodeRepository.selectById(episode.getId());
                    Map<String, Object> refreshedInfo = refreshed.getEpisodeInfo();
                    String newUrl = refreshedInfo != null ? (String) refreshedInfo.get("composedVideoUrl") : null;
                    if (newUrl != null) {
                        episodeVideoUrls.add(newUrl);
                    } else {
                        log.warn("剧集 {} 合成后未找到视频URL，跳过", episode.getId());
                        skipped++;
                    }
                } catch (Exception e) {
                    log.warn("剧集 {} 合成失败，跳过: {}", episode.getId(), e.getMessage());
                    skipped++;
                }
            }
        }

        if (episodeVideoUrls.isEmpty()) {
            return Result.fail("没有任何可合并的剧集视频");
        }

        log.info("合并项目视频: projectId={}, 剧集数={}, 已合成={}, 跳过={}", projectId, episodes.size(), composed, skipped);

        // 执行拼接（分集视频已各自去掉前5帧，此处直接 concat）
        String finalVideoUrl = videoCompositionService.mergePanelVideos(episodeVideoUrls);

        // 存储合并结果到 projectInfo
        if (project != null) {
            Map<String, Object> info = project.getProjectInfo();
            if (info == null) {
                info = new HashMap<>();
            }
            info.put("finalVideoUrl", finalVideoUrl);
            info.put("mergeStatus", "completed");
            project.setProjectInfo(info);
            projectRepository.updateById(project);
        }

        // 推进状态 MERGING → COMPLETED（已完成的项目重新拼接时跳过状态机）
        ProjectMilestone currentMilestone = milestoneStateMachineService.getCurrentMilestone(projectId);
        if (currentMilestone != ProjectMilestone.COMPLETED) {
            milestoneStateMachineService.sendEvent(projectId, ProjectMilestoneEventType._ASSEMBLE_DONE);
        }

        Map<String, String> result = new HashMap<>();
        result.put("finalVideoUrl", finalVideoUrl);
        return Result.ok(result);
    }
}