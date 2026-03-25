package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.ComicReviseRequest;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelReviseRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.*;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
import com.comic.service.panel.PanelService;
import com.comic.service.production.ComicGenerationService;
import com.comic.service.production.PanelProductionService;
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
    private final PanelProductionService panelProductionService;
    private final ComicGenerationService comicGenerationService;

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

    // ================= 生产状态查询 =================

    @GetMapping("/{panelId}/production-status")
    @Operation(summary = "单 Panel 完整生产状态")
    public Result<PanelProductionStatusResponse> getProductionStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelProductionService.getProductionStatus(panelId));
    }

    @GetMapping("/production-statuses")
    @Operation(summary = "批量获取所有 Panel 生产状态")
    public Result<List<PanelProductionStatusResponse>> getBatchProductionStatuses(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(panelProductionService.getBatchProductionStatus(episodeId));
    }

    // ================= 背景图生成 =================

    @GetMapping("/{panelId}/background")
    @Operation(summary = "获取背景图状态")
    public Result<PanelBackgroundResponse> getBackgroundStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelProductionService.getBackgroundStatusByPanelId(panelId));
    }

    @PostMapping("/{panelId}/background")
    @Operation(summary = "生成背景图（自动匹配角色）")
    public Result<Void> generateBackground(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelProductionService.generateBackgroundByPanelId(panelId);
        return Result.ok();
    }

    @PostMapping("/{panelId}/background/regenerate")
    @Operation(summary = "重新生成背景图")
    public Result<Void> regenerateBackground(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelProductionService.generateBackgroundByPanelId(panelId);
        return Result.ok();
    }

    // ================= 四宫格漫画（AI 融合，审核点） =================

    @GetMapping("/{panelId}/comic")
    @Operation(summary = "获取四宫格状态")
    public Result<ComicStatusResponse> getComicStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(comicGenerationService.getComicStatus(panelId));
    }

    @PostMapping("/{panelId}/comic")
    @Operation(summary = "生成四宫格漫画")
    public Result<Void> generateComic(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        comicGenerationService.generateComic(panelId);
        return Result.ok();
    }

    @PostMapping("/{panelId}/comic/approve")
    @Operation(summary = "审核通过四宫格")
    public Result<Void> approveComic(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        comicGenerationService.approveComic(panelId);
        return Result.ok();
    }

    @PostMapping("/{panelId}/comic/revise")
    @Operation(summary = "退回重生成四宫格")
    public Result<Void> reviseComic(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody ComicReviseRequest request) {
        comicGenerationService.reviseComic(panelId, request.getFeedback());
        return Result.ok();
    }

    // ================= AI 视频生成 =================

    @GetMapping("/{panelId}/video")
    @Operation(summary = "获取视频状态")
    public Result<VideoStatusResponse> getVideoStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelProductionService.getVideoStatusByPanelId(panelId));
    }

    @PostMapping("/{panelId}/video")
    @Operation(summary = "生成视频（四宫格 → 视频大模型）")
    public Result<Void> generateVideo(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelProductionService.generateVideoByPanelId(panelId);
        return Result.ok();
    }

    @PostMapping("/{panelId}/video/retry")
    @Operation(summary = "重试失败的视频生成")
    public Result<Void> retryVideo(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelProductionService.retryVideoByPanelId(panelId);
        return Result.ok();
    }
}