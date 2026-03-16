package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.ProjectCreateDTO;
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
     * 使用 @AuthenticationPrincipal 从 Token 中获取当前用户信息
     */
    @PostMapping
    @Operation(summary = "创建项目", description = "创建新的漫画项目。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Map<String, String>> createProject(@RequestBody ProjectCreateDTO dto,
                                                     @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        // 从 Token 中获取用户名，查询数据库获取用户信息
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();

        String projectId = pipelineService.createProject(
            userId,
            dto.getStoryPrompt(),
            dto.getGenre(),
            dto.getTargetAudience(),
            dto.getTotalEpisodes(),
            dto.getEpisodeDuration()
        );

        Map<String, String> result = new java.util.HashMap<>();
        result.put("projectId", projectId);
        return Result.ok(result);
    }

    /**
     * 获取项目状态
     * GET /api/projects/{projectId}
     */
    @GetMapping("/{projectId}")
    public Result<Project> getProjectStatus(@PathVariable String projectId) {
        Project project = pipelineService.getProjectStatus(projectId);
        return Result.ok(project);
    }

    /**
     * 获取当前用户的所有项目列表
     * GET /api/projects
     */
    @GetMapping
    @Operation(summary = "获取项目列表", description = "获取当前用户创建的所有项目")
    public Result<java.util.List<Project>> getProjects(@Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();
        java.util.List<Project> projects = pipelineService.getProjectsByUserId(userId);
        return Result.ok(projects);
    }

    /**
     * 触发剧本大纲生成
     * POST /api/projects/{projectId}/generate-script
     * 复用原有接口，改为生成大纲
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
     * Body: { "chapter": "第1章：觉醒（第1-4集）", "episodeCount": 4, "modificationSuggestion": "可选" }
     */
    @PostMapping("/{projectId}/generate-episodes")
    @Operation(summary = "生成章节剧集", description = "生成指定章节的剧集内容。必须按顺序生成章节。")
    public Result<Void> generateEpisodes(@PathVariable String projectId,
                                          @RequestBody Map<String, Object> body) {
        String chapter = (String) body.get("chapter");
        Integer episodeCount = body.get("episodeCount") != null ? (Integer) body.get("episodeCount") : 4;
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
     * Body: { "revisionNote": "修改意见", "chapter": "可选，指定修改的章节", "episodeCount": 4 }
     * - 不传 chapter：修改大纲
     * - 传 chapter：修改指定章节的分集
     */
    @PostMapping("/{projectId}/revise-script")
    @Operation(summary = "修改剧本", description = "修改大纲或指定章节的剧集。不传chapter则修改大纲，传chapter则修改该章节的分集")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody Map<String, Object> body) {
        String revisionNote = (String) body.get("revisionNote");
        String chapter = (String) body.get("chapter");
        Integer episodeCount = body.get("episodeCount") != null ? (Integer) body.get("episodeCount") : 4;

        if (chapter != null && !chapter.isEmpty()) {
            // 修改指定章节的分集
            scriptService.reviseEpisodes(projectId, chapter, episodeCount, revisionNote);
        } else {
            // 修改大纲
            scriptService.reviseOutline(projectId, revisionNote);
        }
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
}
