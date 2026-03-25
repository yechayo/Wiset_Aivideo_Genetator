package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.ScriptReviseRequest;
import com.comic.service.script.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/script")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ScriptController {

    private final ScriptService scriptService;

    @GetMapping
    @Operation(summary = "获取剧本内容")
    public Result<?> getScriptContent(@PathVariable String projectId) {
        return Result.ok(scriptService.getScriptContent(projectId));
    }

    @PostMapping("/generate")
    @Operation(summary = "生成大纲")
    public Result<Void> generateOutline(@PathVariable String projectId) {
        scriptService.generateScriptOutline(projectId);
        return Result.ok();
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认剧本（自动分批生成剧集）")
    public Result<Void> confirmScript(@PathVariable String projectId) {
        scriptService.confirmScript(projectId);
        return Result.ok();
    }

    @PostMapping("/revise")
    @Operation(summary = "修改剧本")
    public Result<Void> reviseScript(@PathVariable String projectId,
                                     @RequestBody ScriptReviseRequest request) {
        scriptService.reviseOutline(projectId, request.getRevisionNote(), request.getCurrentOutline());
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