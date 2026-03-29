package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.EpisodeGenerateRequest;
import com.comic.dto.request.ScriptReviseRequest;
import com.comic.service.script.ScriptService;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.enums.ProjectEventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/script")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ScriptController {

    private final ScriptService scriptService;
    private final ProjectStateMachineService stateMachineService;

    @GetMapping
    @Operation(summary = "获取剧本内容")
    public Result<?> getScriptContent(@PathVariable String projectId) {
        return Result.ok(scriptService.getScriptContent(projectId));
    }

    @PostMapping("/generate")
    @Operation(summary = "生成大纲")
    public Result<Void> generateOutline(@PathVariable String projectId) {
        // 通过状态机触发大纲生成
        stateMachineService.sendEvent(projectId, com.comic.statemachine.enums.ProjectEventType.GENERATE_OUTLINE);
        return Result.ok();
    }

    @PostMapping("/episodes/generate")
    @Operation(summary = "生成剧集")
    public Result<Void> generateEpisodes(@PathVariable String projectId,
                                         @RequestBody EpisodeGenerateRequest request) {
        // 通过状态机触发剧集生成
        Map<String, Object> headers = new HashMap<>();
        headers.put("chapter", request.getChapter());
        headers.put("episodeCount", request.getEpisodeCount());
        headers.put("modificationSuggestion", request.getModificationSuggestion());
        stateMachineService.sendEvent(projectId, ProjectEventType.GENERATE_EPISODES, headers);
        return Result.ok();
    }

    @PostMapping("/episodes/generate-all")
    @Operation(summary = "批量生成所有剩余章节的剧集")
    public Result<Void> generateAllEpisodes(@PathVariable String projectId) {
        // 通过状态机触发剧集生成
        stateMachineService.sendEvent(projectId, ProjectEventType.GENERATE_EPISODES);
        return Result.ok();
    }

    @PostMapping("/episodes/revise")
    @Operation(summary = "重新生成指定章节的剧集")
    public Result<Void> reviseEpisodes(@PathVariable String projectId,
                                       @RequestBody EpisodeGenerateRequest request) {
        // 通过状态机触发剧集重新生成
        Map<String, Object> headers = new HashMap<>();
        headers.put("chapter", request.getChapter());
        headers.put("episodeCount", request.getEpisodeCount());
        headers.put("modificationSuggestion", request.getModificationSuggestion());
        headers.put("isRevise", true);
        stateMachineService.sendEvent(projectId, ProjectEventType.GENERATE_EPISODES, headers);
        return Result.ok();
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认剧本")
    public Result<Void> confirmScript(@PathVariable String projectId) {
        // 通过状态机触发剧本确认
        stateMachineService.sendEvent(projectId, ProjectEventType.CONFIRM_SCRIPT);
        return Result.ok();
    }

    @PostMapping("/revise")
    @Operation(summary = "修改大纲")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody ScriptReviseRequest request) {
        // 通过状态机触发大纲修改
        Map<String, Object> headers = new HashMap<>();
        headers.put("revisionNote", request.getRevisionNote());
        headers.put("currentOutline", request.getCurrentOutline());
        stateMachineService.sendEvent(projectId, ProjectEventType.REQUEST_OUTLINE_REVISION, headers);
        return Result.ok();
    }

    @PatchMapping("/outline")
    @Operation(summary = "手动保存大纲")
    public Result<Void> saveOutline(@PathVariable String projectId,
                                    @RequestBody Map<String, String> body) {
        String outline = body.get("outline");
        if (outline == null || outline.trim().isEmpty()) {
            return Result.fail("大纲内容不能为空");
        }
        scriptService.updateScriptOutline(projectId, outline.trim());
        return Result.ok();
    }
}