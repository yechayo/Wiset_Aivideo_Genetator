package com.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.common.Result;
import com.comic.dto.request.AdvanceRequest;
import com.comic.dto.request.ProjectCreateRequest;
import com.comic.dto.response.PaginatedResponse;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectProductionSummaryResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Project;
import com.comic.entity.User;
import com.comic.repository.UserRepository;
import com.comic.service.pipeline.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ProjectController {

    private final PipelineService pipelineService;
    private final UserRepository userRepository;

    @PostMapping
    @Operation(summary = "创建项目")
    public Result<Map<String, String>> createProject(@RequestBody ProjectCreateRequest dto,
                                                     @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();

        String projectId = pipelineService.createProject(
            userId,
            dto.getStoryPrompt(),
            dto.getGenre(),
            dto.getTargetAudience(),
            dto.getTotalEpisodes(),
            dto.getEpisodeDuration(),
            dto.getVisualStyle()
        );

        Map<String, String> result = new HashMap<>();
        result.put("projectId", projectId);
        return Result.ok(result);
    }

    @GetMapping("/{projectId}")
    public Result<Project> getProjectStatus(@PathVariable String projectId) {
        Project project = pipelineService.getProjectStatus(projectId);
        return Result.ok(project);
    }

    @GetMapping("/{projectId}/status")
    @Operation(summary = "获取项目状态详情")
    public Result<ProjectStatusResponse> getProjectStatusDetail(@PathVariable String projectId) {
        return Result.ok(pipelineService.getProjectStatusDetail(projectId));
    }

    @GetMapping("/{projectId}/production/summary")
    @Operation(summary = "获取项目生产摘要", description = "PRODUCING 阶段专用：当前 Panel、进度、阻塞原因")
    public Result<ProjectProductionSummaryResponse> getProductionSummary(@PathVariable String projectId) {
        return Result.ok(pipelineService.getProductionSummary(projectId));
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

        IPage<Project> projectPage = pipelineService.getProjectPage(userId, status, sortBy, sortOrder, page, size);

        List<ProjectListItemResponse> items = new java.util.ArrayList<>();
        for (Project project : projectPage.getRecords()) {
            items.add(pipelineService.toListItemDTO(project));
        }

        return Result.ok(PaginatedResponse.of(items, projectPage.getTotal(), (int) projectPage.getCurrent(), (int) projectPage.getSize()));
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "全量更新项目")
    public Result<Void> updateProject(@PathVariable String projectId,
                                     @RequestBody ProjectCreateRequest dto) {
        pipelineService.updateProject(projectId, dto);
        return Result.ok();
    }

    @PatchMapping("/{projectId}")
    @Operation(summary = "部分更新项目")
    public Result<Void> partialUpdateProject(@PathVariable String projectId,
                                              @RequestBody ProjectCreateRequest dto) {
        pipelineService.updateProject(projectId, dto);
        return Result.ok();
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "删除项目（逻辑删除）")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        pipelineService.logicalDeleteProject(projectId);
        return Result.ok();
    }

    @PostMapping("/{projectId}/status/advance")
    @Operation(summary = "推进/回退状态")
    public Result<Void> advancePipeline(@PathVariable String projectId,
                                        @RequestBody AdvanceRequest request) {
        pipelineService.advancePipeline(projectId, request.getDirection(), request.getEvent());
        return Result.ok();
    }
}