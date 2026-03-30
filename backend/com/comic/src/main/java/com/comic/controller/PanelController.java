package com.comic.controller;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.common.Result;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.*;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.panel.PanelService;
import com.comic.service.production.PanelProductionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes/{episodeId}/panels")
@RequiredArgsConstructor
@Tag(name = "分镜管理与生产")
@SecurityRequirement(name = "bearerAuth")
public class PanelController {

    private final PanelService panelService;
    private final PanelProductionService panelProductionService;
    private final ProjectRepository projectRepository;
    private final PanelRepository panelRepository;

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

    // ================= 生产状态查询 =================

    @GetMapping("/{panelId}/production-status")
    @Operation(summary = "单 Panel 完整生产状态")
    public Result<Map<String, Object>> getProductionStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelProductionService.getProductionStatus(panelId));
    }

    @GetMapping("/production-statuses")
    @Operation(summary = "批量获取所有 Panel 生产状态")
    public Result<List<Map<String, Object>>> getBatchProductionStatuses(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(panelProductionService.getBatchProductionStatus(episodeId));
    }

    // ================= 九宫格审核 =================

    @PutMapping("/{panelId}/grid/approve")
    @Operation(summary = "审核通过九宫格")
    public Result<Void> approveGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelProductionService.approveGrid(panelId);
        return Result.ok();
    }

    @PutMapping("/{panelId}/grid/reject")
    @Operation(summary = "驳回九宫格")
    public Result<Void> rejectGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody Map<String, String> body) {
        panelProductionService.rejectGrid(panelId, body.getOrDefault("reason", ""));
        return Result.ok();
    }

    @PostMapping("/{panelId}/grid/regenerate")
    @Operation(summary = "重新生成九宫格")
    public Result<Void> regenerateGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelProductionService.regenerateGrid(panelId);
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
    @Operation(summary = "生成视频（九宫格融合图 → 视频大模型）")
    public Result<Void> generateVideo(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean offPeak = body != null && Boolean.TRUE.equals(body.get("offPeak"));
        panelProductionService.generateVideoByPanelId(panelId, offPeak);
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
        if (project != null && (ProjectStatus.PRODUCING.getCode().equals(project.getStatus())
                || ProjectStatus.COMPLETED.getCode().equals(project.getStatus()))) {
            throw new BusinessException("当前项目正在生产或拼接中，无法变更分镜");
        }
    }
}
