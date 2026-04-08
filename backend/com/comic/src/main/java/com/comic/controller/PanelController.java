package com.comic.controller;

import com.comic.exception.BusinessException;
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
            @PathVariable Long panelId,
            @RequestBody(required = false) Map<String, String> body) {
        String customHint = body != null ? body.get("customHint") : null;
        panelProductionService.regenerateGrid(panelId, customHint);
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

    @GetMapping("/{panelId}/video/prompt")
    @Operation(summary = "获取视频生成提示词")
    public Result<Map<String, Object>> getVideoPrompt(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        String prompt = panelProductionService.getVideoPrompt(panelId);
        Map<String, Object> result = new HashMap<>();
        result.put("prompt", prompt);
        return Result.ok(result);
    }

    @PostMapping("/{panelId}/video/prompt/enhance")
    @Operation(summary = "增强视频生成提示词（消耗1积分）")
    public Result<Map<String, Object>> enhanceVideoPrompt(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        String enhancedPrompt = panelProductionService.enhanceVideoPrompt(panelId);
        Map<String, Object> result = new HashMap<>();
        result.put("prompt", enhancedPrompt);
        return Result.ok(result);
    }

    @PostMapping("/{panelId}/video")
    @Operation(summary = "生成视频（九宫格融合图 → 视频大模型）")
    public Result<Void> generateVideo(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean offPeak = body != null && Boolean.TRUE.equals(body.get("offPeak"));
        String customPrompt = body != null ? (String) body.get("customPrompt") : null;
        String videoModel = body != null ? (String) body.get("videoModel") : null;
        panelProductionService.generateVideoByPanelId(panelId, offPeak, customPrompt, videoModel);
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

    // ===== TTS 旁白生成 =====

    @PostMapping("/{panelId}/tts")
    @Operation(summary = "为单个分镜生成 TTS 旁白")
    public Result<Map<String, Object>> generatePanelTts(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelService.generateTts(panelId));
    }

    @PostMapping("/tts/batch")
    @Operation(summary = "批量生成 TTS 旁白")
    public Result<Map<String, Object>> batchGenerateTts(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(panelService.batchGenerateTts(episodeId));
    }

    // ===== 音视频合并 =====

    @PostMapping("/{panelId}/merge-audio")
    @Operation(summary = "合并面板视频与TTS旁白")
    public Result<Map<String, Object>> mergeAudio(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelService.mergeAudio(panelId));
    }

    @PostMapping("/merge-audio/batch")
    @Operation(summary = "批量合并视频与TTS旁白")
    public Result<Map<String, Object>> batchMergeAudio(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(panelService.batchMergeAudio(episodeId));
    }

    @PostMapping("/compose-episode")
    @Operation(summary = "一键合成：拼接所有面板为一集完整视频")
    public Result<Map<String, Object>> composeEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Project project = projectRepository.findByProjectId(projectId);
        String productionMode = project != null
                ? (String) project.getProjectInfo().getOrDefault("productionMode", "")
                : "";
        return Result.ok(panelService.composeEpisode(episodeId, productionMode));
    }

    // ================= 边界保护 =================

    /**
     * 检查项目是否正在生产中，如果是则阻止分镜变更操作
     */
    private void guardPanelNotInProduction(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        String status = project != null ? project.getStatus() : null;
        if (status != null && ("asset_confirmed".equals(status)
                || "panel_confirmed".equals(status)
                || "completed".equals(status))) {
            throw new BusinessException("当前项目正在生产或拼接中，无法变更分镜");
        }
    }
}
