package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.ProjectCreateRequest;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Project;
import com.comic.entity.User;
import com.comic.repository.ProjectRepository;
import com.comic.repository.UserRepository;
import com.comic.service.script.ScriptService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.service.ProjectStateMachineService;
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
 * 项目管理接口（使用状态机）
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ProjectController {

    private final ProjectStateMachineService stateMachineService;
    private final ScriptService scriptService;
    private final ProjectRepository projectRepository;
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

        // 创建项目实体
        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setUserId(userId);
        project.setStoryPrompt(dto.getStoryPrompt());
        project.setGenre(dto.getGenre());
        project.setTargetAudience(dto.getTargetAudience());
        project.setTotalEpisodes(dto.getTotalEpisodes());
        project.setEpisodeDuration(dto.getEpisodeDuration());
        project.setVisualStyle(dto.getVisualStyle());
        project.setStatus("DRAFT");

        projectRepository.insert(project);

        // 初始化状态机
        stateMachineService.getStateMachine(project.getProjectId());

        Map<String, String> result = new HashMap<>();
        result.put("projectId", project.getProjectId());
        return Result.ok(result);
    }

    /**
     * 获取项目信息
     * GET /api/projects/{projectId}
     */
    @GetMapping("/{projectId}")
    public Result<Project> getProjectStatus(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail(404, "项目不存在");
        }
        return Result.ok(project);
    }

    /**
     * 获取项目列表
     * GET /api/projects
     */
    @GetMapping
    @Operation(summary = "获取项目列表", description = "获取当前用户创建的所有项目")
    public Result<java.util.List<ProjectListItemResponse>> getProjects(@Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername());
        String userId = user.getId().toString();
        java.util.List<Project> projects = projectRepository.findAllByUserId(userId);

        java.util.List<ProjectListItemResponse> result = new java.util.ArrayList<>();
        for (Project project : projects) {
            result.add(toListItemDTO(project));
        }

        return Result.ok(result);
    }

    /**
     * 触发剧本大纲生成
     * POST /api/projects/{projectId}/generate-script
     */
    @PostMapping("/{projectId}/generate-script")
    @Operation(summary = "生成剧本大纲", description = "生成剧本大纲（Markdown格式）")
    public Result<Void> generateScriptOutline(@PathVariable String projectId) {
        // 发送事件到状态机
        Map<String, Object> headers = new HashMap<>();
        headers.put("projectId", projectId);
        stateMachineService.sendEvent(projectId, ProjectEventType.GENERATE_OUTLINE, headers);
        return Result.ok();
    }

    /**
     * 生成指定章节的剧集
     * POST /api/projects/{projectId}/generate-episodes
     */
    @PostMapping("/{projectId}/generate-episodes")
    @Operation(summary = "生成章节剧集", description = "生成指定章节的剧集内容")
    public Result<Void> generateEpisodes(@PathVariable String projectId,
                                          @RequestBody Map<String, Object> body) {
        String chapter = (String) body.get("chapter");
        Integer episodeCount = parseOptionalPositiveInt(body.get("episodeCount"));
        String modificationSuggestion = (String) body.get("modificationSuggestion");

        if (chapter == null || chapter.isEmpty()) {
            return Result.fail("章节不能为空");
        }

        // 直接调用 ScriptService（内部会更新状态）
        scriptService.generateScriptEpisodes(projectId, chapter, episodeCount, modificationSuggestion);
        return Result.ok();
    }

    /**
     * 批量生成所有剧集
     * POST /api/projects/{projectId}/generate-all-episodes
     */
    @PostMapping("/{projectId}/generate-all-episodes")
    @Operation(summary = "批量生成全部章节", description = "按顺序生成所有剩余章节的剧集")
    public Result<Void> generateAllEpisodes(@PathVariable String projectId) {
        scriptService.generateAllEpisodes(projectId);
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
        // 发送事件到状态机
        Map<String, Object> headers = new HashMap<>();
        headers.put("projectId", projectId);
        stateMachineService.sendEvent(projectId, ProjectEventType.CONFIRM_SCRIPT, headers);
        return Result.ok();
    }

    /**
     * 修改剧本
     * POST /api/projects/{projectId}/revise-script
     */
    @PostMapping("/{projectId}/revise-script")
    @Operation(summary = "修改剧本", description = "修改大纲或指定章节的剧集")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody Map<String, Object> body) {
        String revisionNote = (String) body.get("revisionNote");
        String currentOutline = (String) body.get("currentOutline");
        String chapter = (String) body.get("chapter");
        Integer episodeCount = parseOptionalPositiveInt(body.get("episodeCount"));

        if (chapter != null && !chapter.isEmpty()) {
            scriptService.reviseEpisodes(projectId, chapter, episodeCount, revisionNote);
        } else {
            // 发送修改大纲事件到状态机
            Map<String, Object> headers = new HashMap<>();
            headers.put("projectId", projectId);
            headers.put("revisionNote", revisionNote);
            headers.put("currentOutline", currentOutline);
            stateMachineService.sendEvent(projectId, ProjectEventType.REQUEST_OUTLINE_REVISION, headers);
        }
        return Result.ok();
    }

    /**
     * 直接保存大纲
     * PATCH /api/projects/{projectId}/script-outline
     */
    @PatchMapping("/{projectId}/script-outline")
    @Operation(summary = "保存大纲", description = "直接保存用户编辑的大纲内容")
    public Result<Void> updateScriptOutline(@PathVariable String projectId,
                                            @RequestBody Map<String, String> body) {
        String outline = body.get("outline");
        if (outline == null || outline.trim().isEmpty()) {
            return Result.fail("大纲内容不能为空");
        }
        scriptService.updateScriptOutline(projectId, outline.trim());
        return Result.ok();
    }

    // ===== 私有方法 =====

    private ProjectListItemResponse toListItemDTO(Project project) {
        ProjectListItemResponse dto = new ProjectListItemResponse();
        dto.setProjectId(project.getProjectId());
        dto.setStoryPrompt(project.getStoryPrompt());
        dto.setGenre(project.getGenre());
        dto.setTargetAudience(project.getTargetAudience());
        dto.setTotalEpisodes(project.getTotalEpisodes());
        dto.setEpisodeDuration(project.getEpisodeDuration());
        dto.setVisualStyle(project.getVisualStyle());
        dto.setStatusCode(project.getStatus());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());
        return dto;
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

    private String generateProjectId() {
        return "PROJ-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
