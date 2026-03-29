package com.comic.controller;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.common.Result;
import com.comic.dto.request.ComicReviseRequest;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelReviseRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.*;
import com.comic.entity.Episode;
import com.comic.entity.Job;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.JobRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.job.JobQueueService;
import com.comic.service.panel.PanelService;
import com.comic.service.production.ComicGenerationService;
import com.comic.service.production.PanelProductionService;
import com.comic.service.panel.PanelGenerationService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.service.ProjectStateMachineService;
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
    private final PanelGenerationService panelGenerationService;
    private final JobQueueService jobQueueService;
    private final EpisodeRepository episodeRepository;
    private final JobRepository jobRepository;
    private final PanelProductionService panelProductionService;
    private final ComicGenerationService comicGenerationService;
    private final ProjectRepository projectRepository;
    private final ProjectStateMachineService stateMachineService;

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
        guardPanelNotInProduction(projectId);
        panelService.updatePanel(projectId, episodeId, panelId, request);
        return Result.ok();
    }

    @DeleteMapping("/{panelId}")
    @Operation(summary = "删除分镜")
    public Result<Void> deletePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        guardPanelNotInProduction(projectId);
        panelService.deletePanel(projectId, episodeId, panelId);
        return Result.ok();
    }

    // ================= 分镜生成流程（从 StoryController 迁入） =================

    @PostMapping("/generate")
    @Operation(summary = "AI 生成分镜", description = "LLM 生成 + 镜头增强")
    public Result<Void> generatePanels(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            return Result.fail(404, "剧集不存在");
        }
        // 通过状态机触发分镜生成
        Map<String, Object> headers = new HashMap<>();
        headers.put("episodeId", episodeId);
        stateMachineService.sendEvent(projectId, ProjectEventType.START_PANEL, headers);
        return Result.ok();
    }


    @GetMapping("/generate/{jobId}/status")
    @Operation(summary = "分镜生成任务状态")
    public Result<Map<String, Object>> getGenerateStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable String jobId) {
        Map<String, Object> status = new HashMap<>();
        status.put("jobId", jobId);

        // 优先查 job 表（真实任务状态）
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            // job 不存在，可能是旧数据被清理了，返回 unknown 让前端重新请求
            status.put("status", "unknown");
            return Result.ok(status);
        }

        switch (job.getStatus()) {
            case "PENDING":
                status.put("status", "pending");
                status.put("progress", job.getProgress());
                status.put("message", job.getProgressMsg());
                break;
            case "RUNNING":
                status.put("status", "processing");
                status.put("progress", job.getProgress());
                status.put("message", job.getProgressMsg());
                break;
            case "SUCCESS":
                status.put("status", "completed");
                break;
            case "FAILED":
                status.put("status", "failed");
                if (job.getErrorMsg() != null) {
                    status.put("errorMessage", job.getErrorMsg());
                }
                break;
            default:
                status.put("status", "unknown");
                break;
        }
        return Result.ok(status);
    }

    @PostMapping("/{panelId}/confirm")
    @Operation(summary = "确认分镜（单集确认后继续下一集）")
    public Result<Void> confirmPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        // 通过状态机触发分镜确认
        Map<String, Object> headers = new HashMap<>();
        headers.put("episodeId", episodeId);
        stateMachineService.sendEvent(projectId, ProjectEventType.CONFIRM_PANEL, headers);
        return Result.ok();
    }

    @PostMapping("/{panelId}/revise")
    @Operation(summary = "修改分镜（整集重新生成）")
    public Result<Void> revisePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody PanelReviseRequest request) {
        // 通过状态机触发分镜修改
        Map<String, Object> headers = new HashMap<>();
        headers.put("episodeId", episodeId);
        headers.put("feedback", request.getFeedback());
        stateMachineService.sendEvent(projectId, ProjectEventType.REVISE_PANEL, headers);
        return Result.ok();
    }

    @PostMapping("/{panelId}/revise-single")
    @Operation(summary = "AI 修改单个分镜")
    public Result<Void> reviseSinglePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody PanelReviseRequest request) {
        panelGenerationService.reviseSinglePanel(panelId, request.getFeedback());
        return Result.ok();
    }

    @PostMapping("/{panelId}/retry")
    @Operation(summary = "重试失败的生成")
    public Result<Void> retryPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelGenerationService.retryFailedPanels(episodeId);
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

    // ================= 边界保护 =================

    /**
     * 检查项目是否正在生产中，如果是则阻止分镜变更操作
     */
    private void guardPanelNotInProduction(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null && (ProjectStatus.PANEL_GENERATING.getCode().equals(project.getStatus())
                || ProjectStatus.VIDEO_ASSEMBLING.getCode().equals(project.getStatus()))) {
            throw new BusinessException("当前项目正在生产或拼接中，无法变更分镜");
        }
    }
}