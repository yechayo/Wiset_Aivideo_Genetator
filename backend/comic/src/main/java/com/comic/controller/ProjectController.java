package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.ProjectCreateDTO;
import com.comic.entity.Project;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 项目管理接口
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final PipelineService pipelineService;
    private final ScriptService scriptService;

    /**
     * 创建项目
     * POST /api/projects
     */
    @PostMapping
    public Result<Map<String, String>> createProject(@RequestBody ProjectCreateDTO dto,
                                                     @RequestHeader("X-User-Id") String userId) {
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
     * 触发剧本生成
     * POST /api/projects/{projectId}/generate-script
     */
    @PostMapping("/{projectId}/generate-script")
    public Result<String> generateScript(@PathVariable String projectId) {
        String seriesId = scriptService.generateScript(projectId);
        return Result.ok(seriesId);
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
     * 要求修改剧本
     * POST /api/projects/{projectId}/revise-script
     */
    @PostMapping("/{projectId}/revise-script")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody Map<String, String> body) {
        String revisionNote = body.get("revisionNote");
        scriptService.reviseScript(projectId, revisionNote);
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
