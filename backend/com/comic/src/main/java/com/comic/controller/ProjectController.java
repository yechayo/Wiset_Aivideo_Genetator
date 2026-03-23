package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.ProjectCreateRequest;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Project;
import com.comic.entity.User;
import com.comic.repository.UserRepository;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.script.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 项目管理接口
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ProjectController {

    private final PipelineService pipelineService;
    private final ScriptService scriptService;
    private final UserRepository userRepository;

    /**
     * 创建项目
     * POST /api/projects
     */
    @PostMapping
    @Operation(summary = "创建项目", description = "创建新的漫画项目。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
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

    /**
     * 获取项目信息
     * GET /api/projects/{projectId}
     * 返回项目基本信息
     */
    @GetMapping("/{projectId}")
    public Result<Project> getProjectStatus(@PathVariable String projectId) {
        Project project = pipelineService.getProjectStatus(projectId);
        return Result.ok(project);
    }

    /**
     * 获取项目状态详情
     * GET /api/projects/{projectId}/status
     * 返回完整状态信息，包含步骤映射和可用操作
     */
    @GetMapping("/{projectId}/status")
    @Operation(summary = "获取项目状态详情", description = "返回项目当前状态的完整信息，包含前端步骤映射、已完成步骤和可用操作")
    public Result<ProjectStatusResponse> getProjectStatusDetail(@PathVariable String projectId) {
        ProjectStatusResponse statusDetail = pipelineService.getProjectStatusDetail(projectId);
        return Result.ok(statusDetail);
    }

    /**
     * 获取当前用户的所有项目列表
     * GET /api/projects
     */
    @GetMapping
    @Operation(summary = "获取项目列表", description = "获取当前用户创建的所有项目（含状态映射信息）")
    public Result<java.util.List<ProjectListItemResponse>> getProjects(@Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();
        java.util.List<ProjectListItemResponse> projects = pipelineService.getProjectsByUserId(userId);
        return Result.ok(projects);
    }

    /**
     * 触发剧本大纲生成
     * POST /api/projects/{projectId}/generate-script
     */
    @PostMapping("/{projectId}/generate-script")
    @Operation(summary = "生成剧本大纲", description = "生成剧本大纲（Markdown格式），包含角色、物品、章节结构")
    public Result<Void> generateScriptOutline(@PathVariable String projectId) {
        scriptService.generateScriptOutline(projectId);
        return Result.ok();
    }

    /**
     * 生成指定章节的剧集
     * POST /api/projects/{projectId}/generate-episodes
     */
    @PostMapping("/{projectId}/generate-episodes")
    @Operation(summary = "生成章节剧集", description = "生成指定章节的剧集内容。必须按顺序生成章节。")
    public Result<Void> generateEpisodes(@PathVariable String projectId,
                                          @RequestBody Map<String, Object> body) {
        String chapter = (String) body.get("chapter");
        Integer episodeCount = parseOptionalPositiveInt(body.get("episodeCount"));
        String modificationSuggestion = (String) body.get("modificationSuggestion");

        if (chapter == null || chapter.isEmpty()) {
            return Result.fail("章节不能为空");
        }

        scriptService.generateScriptEpisodes(projectId, chapter, episodeCount, modificationSuggestion);
        return Result.ok();
    }

    /**
     * 获取剧本内容
     * GET /api/projects/{projectId}/script
     */
    @GetMapping("/{projectId}/script")
    public Result<?> getScriptContent(@PathVariable String projectId) {
        return Result.ok(scriptService.getScriptContent(projectId));
    }

    /**
     * 确认剧本
     * POST /api/projects/{projectId}/confirm-script
     */
    @PostMapping("/{projectId}/confirm-script")
    public Result<Void> confirmScript(@PathVariable String projectId) {
        scriptService.confirmScript(projectId);
        return Result.ok();
    }

    /**
     * 修改剧本（大纲或分集）
     * POST /api/projects/{projectId}/revise-script
     */
    @PostMapping("/{projectId}/revise-script")
    @Operation(summary = "修改剧本", description = "修改大纲或指定章节的剧集。不传chapter则修改大纲，传chapter则修改该章节的分集")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody Map<String, Object> body) {
        String revisionNote = (String) body.get("revisionNote");
        String currentOutline = (String) body.get("currentOutline");
        String chapter = (String) body.get("chapter");
        Integer episodeCount = parseOptionalPositiveInt(body.get("episodeCount"));

        if (chapter != null && !chapter.isEmpty()) {
            scriptService.reviseEpisodes(projectId, chapter, episodeCount, revisionNote);
        } else {
            scriptService.reviseOutline(projectId, revisionNote, currentOutline);
        }
        return Result.ok();
    }

    /**
     * 直接保存用户编辑的大纲
     * PATCH /api/projects/{projectId}/script-outline
     */
    @PatchMapping("/{projectId}/script-outline")
    @Operation(summary = "保存大纲", description = "直接保存用户编辑的大纲内容，不触发 AI 重新生成。保存后会删除已生成的剧集。")
    public Result<Void> updateScriptOutline(@PathVariable String projectId,
                                            @RequestBody Map<String, String> body) {
        String outline = body.get("outline");
        if (outline == null || outline.trim().isEmpty()) {
            return Result.fail("大纲内容不能为空");
        }
        scriptService.updateScriptOutline(projectId, outline.trim());
        return Result.ok();
    }

    /**
     * 批量生成所有剩余章节的剧集
     * POST /api/projects/{projectId}/generate-all-episodes
     */
    @PostMapping("/{projectId}/generate-all-episodes")
    @Operation(summary = "批量生成全部章节", description = "按顺序生成所有剩余章节的剧集。如果某章生成失败则停止。")
    public Result<Void> generateAllEpisodes(@PathVariable String projectId) {
        scriptService.generateAllEpisodes(projectId);
        return Result.ok();
    }

    /**
     * 推进流水线
     * POST /api/projects/{projectId}/advance
     */
    @PostMapping("/{projectId}/advance")
    public Result<Void> advancePipeline(@PathVariable String projectId,
                                        @RequestBody Map<String, String> body) {
        String event = body.get("event");
        pipelineService.advancePipeline(projectId, event);
        return Result.ok();
    }

    private Integer parseOptionalPositiveInt(Object rawValue) {
        if (rawValue == null) {
            return null;
        }

        try {
            int value = Integer.parseInt(String.valueOf(rawValue));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
