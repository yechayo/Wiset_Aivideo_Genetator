package com.comic.controller;

import com.comic.common.BusinessException;
import com.comic.common.Result;
import com.comic.dto.request.EpisodeCreateRequest;
import com.comic.dto.request.EpisodeUpdateRequest;
import com.comic.dto.response.EpisodeListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.service.episode.EpisodeService;
import com.comic.service.panel.GridImageService;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.storyboard.StoryboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes")
@RequiredArgsConstructor
@Tag(name = "剧集管理")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeController {

    private final EpisodeService episodeService;
    private final StoryboardService storyboardService;
    private final GridImageService gridImageService;
    private final PipelineService pipelineService;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;

    @GetMapping
    @Operation(summary = "剧集列表（分页）")
    public Result<PaginatedResponse<EpisodeListItemResponse>> getEpisodes(
            @PathVariable String projectId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "名称模糊搜索") @RequestParam(required = false) String name) {
        return Result.ok(episodeService.getEpisodesPage(projectId, name, page, size));
    }

    @GetMapping("/{episodeId}")
    @Operation(summary = "剧集详情")
    public Result<EpisodeListItemResponse> getEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(episodeService.getEpisode(projectId, episodeId));
    }

    @PostMapping
    @Operation(summary = "创建剧集")
    public Result<EpisodeListItemResponse> createEpisode(
            @PathVariable String projectId,
            @RequestBody EpisodeCreateRequest request) {
        return Result.ok(episodeService.createEpisode(projectId, request));
    }

    @PutMapping("/{episodeId}")
    @Operation(summary = "更新剧集")
    public Result<Void> updateEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @RequestBody EpisodeUpdateRequest request) {
        episodeService.updateEpisode(projectId, episodeId, request);
        return Result.ok();
    }

    @DeleteMapping("/{episodeId}")
    @Operation(summary = "删除剧集")
    public Result<Void> deleteEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        episodeService.deleteEpisode(projectId, episodeId);
        return Result.ok();
    }

    // ================= 剧集剧本与分镜 =================

    @PostMapping("/{episodeId}/script")
    @Operation(summary = "生成分集剧本与分镜")
    public Result<Void> generateEpisodeScript(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        storyboardService.generateEpisodeScriptAndStoryboard(projectId);
        return Result.ok();
    }

    @GetMapping("/{episodeId}/script")
    @Operation(summary = "获取分集剧本信息")
    public Result<Map<String, Object>> getEpisodeScript(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        EpisodeListItemResponse episode = episodeService.getEpisode(projectId, episodeId);
        return Result.ok(episode.getEpisodeInfo());
    }

    // ================= 整集九宫格审核 =================

    @GetMapping("/{episodeId}/grid")
    @Operation(summary = "获取整集九宫格状态")
    public Result<Map<String, Object>> getEpisodeGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo() != null ? episode.getEpisodeInfo() : new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        result.put("gridStatus", info.getOrDefault("gridStatus", "pending"));
        result.put("gridImages", info.getOrDefault("gridImages", new ArrayList<>()));
        result.put("splitShots", info.getOrDefault("splitShots", new ArrayList<>()));
        result.put("gridRejectionFeedback", info.get("gridRejectionFeedback"));
        result.put("shots", info.getOrDefault("shots", new ArrayList<>()));
        result.put("gridPageCount", info.getOrDefault("gridPageCount", 0));
        return Result.ok(result);
    }

    @Transactional
    @PutMapping("/{episodeId}/grid/approve")
    @Operation(summary = "审核通过整集九宫格，触发分组创建Panel")
    public Result<Void> approveEpisodeGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();
        String gridStatus = (String) info.getOrDefault("gridStatus", "pending");
        if (!"generated".equals(gridStatus)) {
            throw new BusinessException("当前状态不可审核: " + gridStatus + "，请先重新生成九宫格");
        }

        // 设置 gridStatus = approved
        info.put("gridStatus", "approved");
        info.put("gridRejectionFeedback", null);
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);

        // 读取 splitShots
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> splitShots = (List<Map<String, Object>>) info.get("splitShots");
        String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");

        if (splitShots == null || splitShots.isEmpty()) {
            throw new BusinessException("切割分镜数据为空，无法创建 Panel");
        }

        // 删除已有 Panel（重新分组）
        List<Panel> existingPanels = panelRepository.findByEpisodeId(episodeId);
        for (Panel p : existingPanels) {
            p.setDeleted(true);
            panelRepository.updateById(p);
        }

        // 贪心分组 splitShots（16s 一组）
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(splitShots, 16);

        // 每组创建 Panel
        List<String> charRefUrls = gridImageService.getCharacterReferenceUrlsForEpisode(episodeId);
        for (List<Map<String, Object>> group : groups) {
            Panel panel = new Panel();
            panel.setEpisodeId(episodeId);
            panel.setStatus("pending");
            panel.setDeleted(false);
            Map<String, Object> panelInfo = new HashMap<>();
            panelInfo.put("shots", group);
            panelInfo.put("totalShots", group.size());
            panelInfo.put("totalDuration",
                group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum());
            panelInfo.put("gridStatus", "approved");
            panelInfo.put("videoStatus", "pending");
            panelInfo.put("visualStyle", visualStyle);
            panelInfo.put("gridImages", new ArrayList<String>());

            // 生成融合图
            try {
                BufferedImage fusionImage = gridImageService.createFusionImageForPanel(group, charRefUrls);
                String fusionUrl = gridImageService.uploadFusionImageForPanel(fusionImage, episodeId, group);
                panelInfo.put("fusionImageUrl", fusionUrl);
            } catch (Exception e) {
                // 融合图生成失败不阻断流程，但记录 error 级别日志
                log.error("Panel 融合图生成失败: episodeId={}, shots={}", episodeId, group.size(), e);
            }

            panel.setPanelInfo(panelInfo);
            panelRepository.insert(panel);
        }

        // 检查是否所有 Episode 的九宫格都已审核通过
        checkAndAdvanceAllGridsApproved(projectId);

        return Result.ok();
    }

    @PutMapping("/{episodeId}/grid/reject")
    @Operation(summary = "拒绝整集九宫格")
    public Result<Void> rejectEpisodeGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @RequestBody Map<String, String> body) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();
        String gridStatus = (String) info.getOrDefault("gridStatus", "pending");
        if (!"generated".equals(gridStatus)) {
            throw new BusinessException("当前状态不可拒绝: " + gridStatus);
        }
        info.put("gridStatus", "rejected");
        info.put("gridRejectionFeedback", body.getOrDefault("reason", ""));
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);
        return Result.ok();
    }

    @PostMapping("/{episodeId}/grid/regenerate")
    @Operation(summary = "重新生成整集九宫格（不重新生成分镜脚本）")
    public Result<Void> regenerateEpisodeGrid(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
        String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");

        if (shots == null || shots.isEmpty()) {
            throw new BusinessException("分镜数据为空，无法重新生成九宫格");
        }

        // 重置状态
        info.put("gridStatus", "generating");
        info.put("gridImages", new ArrayList<>());
        info.put("splitShots", new ArrayList<>());
        info.put("gridRejectionFeedback", null);
        info.put("errorMessage", null);
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);

        // 异步重新生成
        gridImageService.generateGridsForEpisode(episodeId, shots, visualStyle);

        return Result.ok();
    }

    private void checkAndAdvanceAllGridsApproved(String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            boolean allApproved = episodes.stream().allMatch(ep -> {
                Map<String, Object> info = ep.getEpisodeInfo();
                return info != null && "approved".equals(info.get("gridStatus"));
            });
            if (allApproved && !episodes.isEmpty()) {
                pipelineService.advancePipeline(projectId, "all_grids_approved");
            }
        } catch (Exception e) {
            // 不阻断主流程
        }
    }
}
