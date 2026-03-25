package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelReviseRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.PanelListItemResponse;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
import com.comic.service.panel.PanelService;
import com.comic.service.story.StoryboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes/{episodeId}/panels")
@RequiredArgsConstructor
@Tag(name = "分镜管理与生产")
@SecurityRequirement(name = "bearerAuth")
public class PanelController {

    private final PanelService panelService;
    private final StoryboardService storyboardService;
    private final JobQueueService jobQueueService;
    private final EpisodeRepository episodeRepository;

    // ================= 分镜 CRUD =================

    @GetMapping
    @Operation(summary = "分镜列表")
    public Result<List<PanelListItemResponse>> getPanels(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(panelService.getPanels(projectId, episodeId));
    }

    @GetMapping("/{panelId}")
    @Operation(summary = "分镜详情")
    public Result<PanelListItemResponse> getPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelService.getPanel(projectId, episodeId, panelId));
    }

    @PostMapping
    @Operation(summary = "创建分镜")
    public Result<PanelListItemResponse> createPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @RequestBody PanelCreateRequest request) {
        return Result.ok(panelService.createPanel(projectId, episodeId, request));
    }

    @PutMapping("/{panelId}")
    @Operation(summary = "更新分镜")
    public Result<Void> updatePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody PanelUpdateRequest request) {
        panelService.updatePanel(projectId, episodeId, panelId, request);
        return Result.ok();
    }

    @DeleteMapping("/{panelId}")
    @Operation(summary = "删除分镜")
    public Result<Void> deletePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelService.deletePanel(projectId, episodeId, panelId);
        return Result.ok();
    }

    // ================= 分镜生成流程（从 StoryController 迁入） =================

    @PostMapping("/generate")
    @Operation(summary = "AI 生成分镜", description = "LLM 生成 + 镜头增强")
    public Result<Map<String, String>> generatePanels(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            return Result.fail(404, "剧集不存在");
        }
        String jobId = jobQueueService.submitStoryboardJob(episodeId);
        Map<String, String> result = new HashMap<>();
        result.put("jobId", jobId);
        return Result.ok(result);
    }

    @GetMapping("/generate/{jobId}/status")
    @Operation(summary = "分镜生成任务状态")
    public Result<Map<String, Object>> getGenerateStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable String jobId) {
        Map<String, Object> status = new HashMap<>();
        status.put("jobId", jobId);
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            return Result.fail(404, "剧集不存在");
        }
        String epStatus = episode.getStatus();
        if ("STORYBOARD_DONE".equals(epStatus) || "STORYBOARD_CONFIRMED".equals(epStatus)) {
            status.put("status", "completed");
        } else if ("STORYBOARD_FAILED".equals(epStatus)) {
            status.put("status", "failed");
            Map<String, Object> info = episode.getEpisodeInfo();
            if (info != null && info.get("errorMsg") != null) {
                status.put("errorMessage", info.get("errorMsg").toString());
            }
        } else if ("STORYBOARD_GENERATING".equals(epStatus)) {
            status.put("status", "processing");
        } else {
            status.put("status", "pending");
        }
        return Result.ok(status);
    }

    @PostMapping("/{panelId}/confirm")
    @Operation(summary = "确认分镜内容")
    public Result<Void> confirmPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        storyboardService.confirmEpisodeStoryboard(episodeId);
        return Result.ok();
    }

    @PostMapping("/{panelId}/revise")
    @Operation(summary = "修改分镜")
    public Result<Void> revisePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody PanelReviseRequest request) {
        storyboardService.reviseEpisodeStoryboard(episodeId, request.getFeedback());
        return Result.ok();
    }

    @PostMapping("/{panelId}/retry")
    @Operation(summary = "重试失败的生成")
    public Result<Void> retryPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        storyboardService.retryFailedStoryboard(episodeId);
        return Result.ok();
    }
}