package com.comic.controller;

import com.comic.exception.BusinessException;
import com.comic.common.Result;
import com.comic.dto.request.EpisodeCreateRequest;
import com.comic.dto.request.EpisodeUpdateRequest;
import com.comic.dto.response.EpisodeListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.episode.EpisodeService;
import com.comic.service.panel.GridImageService;
import com.comic.service.panel.PanelService;
import com.comic.service.production.PanelProductionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes")
@RequiredArgsConstructor
@Tag(name = "剧集管理")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class EpisodeController {

    private final EpisodeService episodeService;
    private final PanelProductionService panelProductionService;
    private final GridImageService gridImageService;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final ProjectRepository projectRepository;

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
    @Operation(summary = "生成分集剧本与分镜（单集）")
    public Result<Void> generateEpisodeScript(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        panelProductionService.generateSingleEpisodeScript(projectId, episodeId);
        return Result.ok();
    }

    @PostMapping("/scripts/generate-all")
    @Operation(summary = "批量生成所有剧集剧本与分镜")
    public Result<Void> generateAllEpisodeScripts(@PathVariable String projectId) {
        panelProductionService.generateEpisodeScripts(projectId);
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

    // ================= 单分镜编辑 =================

    @PutMapping("/{episodeId}/shots/{shotIndex}")
    @Operation(summary = "更新单个分镜文本")
    public Result<Void> updateShot(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable int shotIndex,
            @RequestBody Map<String, Object> updates) {
        panelProductionService.updateShot(projectId, episodeId, shotIndex, updates);
        return Result.ok();
    }

    // ================= 分镜文本审核 =================

    @PutMapping("/{episodeId}/panel/approve")
    @Operation(summary = "审核通过分镜文本", description = "审核通过单集分镜脚本，全部通过后触发九宫格生成")
    public Result<Void> approvePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info == null) info = new HashMap<>();

        // 支持脚本阶段审核：检查 shots 或 panelPlan 是否存在
        Object shots = info.get("shots");
        String panelPlan = (String) info.get("panelPlan");
        boolean hasData = (shots instanceof java.util.List && !((java.util.List<?>) shots).isEmpty())
                || (panelPlan != null && !panelPlan.isEmpty());
        if (!hasData) {
            throw new BusinessException("分镜数据为空，无法审核");
        }

        info.put("panelApproved", true);
        info.put("panelRejectionReason", null);
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);

        log.info("分镜文本审核通过: episodeId={}", episodeId);

        // 检查是否所有 episode 的分镜都已审核通过 → 触发九宫格批量生成
        // 新流程：九宫格由用户在 Step 4b 中手动逐集触发，不再自动批量生成
        // checkAndStartGridGeneration(projectId);

        return Result.ok();
    }

    @PutMapping("/{episodeId}/panel/reject")
    @Operation(summary = "退回分镜文本", description = "退回单集分镜脚本，附带修改意见")
    public Result<Void> rejectPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @RequestBody Map<String, String> body) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info == null) info = new HashMap<>();

        // 支持脚本阶段退回
        Object shots = info.get("shots");
        String panelPlan = (String) info.get("panelPlan");
        boolean hasData = (shots instanceof java.util.List && !((java.util.List<?>) shots).isEmpty())
                || (panelPlan != null && !panelPlan.isEmpty());
        if (!hasData) {
            throw new BusinessException("分镜数据为空，无法退回");
        }
        info.put("panelApproved", false);
        info.put("panelRejectionReason", body.getOrDefault("reason", ""));
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);

        log.info("分镜文本已退回: episodeId={}, reason={}", episodeId, body.getOrDefault("reason", ""));
        return Result.ok();
    }

    @PutMapping("/{episodeId}/panel/reject-to-script")
    @Operation(summary = "退回脚本阶段", description = "将已通过脚本审核的剧集退回4a，清除九宫格相关数据")
    public Result<Void> rejectToScript(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info == null) info = new HashMap<>();

        info.put("panelApproved", false);
        info.put("gridStatus", "pending");
        info.remove("gridImages");
        info.remove("splitShots");
        info.put("gridRejectionFeedback", null);
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);

        log.info("剧集退回脚本阶段: episodeId={}", episodeId);
        return Result.ok();
    }

    /**
     * 检查所有 episode 的分镜文本是否都已审核通过，如果是则批量触发九宫格生成
     */
    private void checkAndStartGridGeneration(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        if (episodes.isEmpty()) return;

        boolean allApproved = episodes.stream().allMatch(ep -> {
            Map<String, Object> info = ep.getEpisodeInfo();
            if (info == null) return false;
            Object approved = info.get("panelApproved");
            if (approved == null) return false;
            return Boolean.TRUE.equals(approved);
        });

        if (allApproved) {
            log.info("所有分镜文本已审核通过，开始批量生成九宫格: projectId={}", projectId);
            for (Episode ep : episodes) {
                Map<String, Object> info = ep.getEpisodeInfo();
                if (info == null) continue;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
                String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");
                String gridPromptHint = info.get("gridPromptHint") instanceof String
                    ? ((String) info.get("gridPromptHint")).trim()
                    : null;
                if (shots != null && !shots.isEmpty()) {
                    info.put("gridStatus", "generating");
                    ep.setEpisodeInfo(info);
                    episodeRepository.updateById(ep);
                    gridImageService.generateGridsForEpisode(
                        ep.getId(),
                        shots,
                        visualStyle,
                        panelProductionService.getImageProvider(projectId),
                        (gridPromptHint != null && !gridPromptHint.isEmpty()) ? gridPromptHint : null
                    );
                }
            }
        }
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
        result.put("gridPromptHint", info.getOrDefault("gridPromptHint", ""));
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

        // 判断项目是否使用参考图视频模式
        Project project = projectRepository.findByProjectId(projectId);
        boolean videoRefMode = project != null
            && Boolean.TRUE.equals(project.getProjectInfo().get("videoRefMode"));

        if (splitShots == null || splitShots.isEmpty()) {
            throw new BusinessException("切割分镜数据为空，无法创建 Panel");
        }

        // 删除已有 Panel（重新分组）
        List<Panel> existingPanels = panelRepository.findByEpisodeId(episodeId);
        for (Panel p : existingPanels) {
            panelRepository.deleteById(p.getId());
        }

        // 分组 splitShots 创建 Panel
        List<List<Map<String, Object>>> groups;
        boolean hasPanelIndex = splitShots.stream()
            .anyMatch(s -> s.containsKey("panelIndex") && s.get("panelIndex") != null);
        if (hasPanelIndex) {
            // 按 panelIndex 分组（AI 预分组，解说模式）
            Map<Integer, List<Map<String, Object>>> groupMap = new LinkedHashMap<>();
            for (Map<String, Object> shot : splitShots) {
                int idx = shot.get("panelIndex") != null ? ((Number) shot.get("panelIndex")).intValue() : 1;
                groupMap.computeIfAbsent(idx, k -> new ArrayList<>()).add(shot);
            }
            groups = new ArrayList<>(groupMap.values());
        } else {
            // fallback: 贪心分组（实时动画模式或旧数据）
            groups = PanelService.greedyGroup(splitShots, 10);
        }

        // 每组创建 Panel
        List<GridImageService.CharRef> charRefsWithNames = gridImageService.getCharacterReferencesWithNamesForEpisode(episodeId);
        int splitShotStartIndex = 0;
        for (List<Map<String, Object>> group : groups) {
            Panel panel = new Panel();
            panel.setEpisodeId(episodeId);
            panel.setStatus("pending");
            panel.setDeleted(false);
            Map<String, Object> panelInfo = new HashMap<>();
            panelInfo.put("shots", group);
            panelInfo.put("splitShotStartIndex", splitShotStartIndex);
            panelInfo.put("totalShots", group.size());
            panelInfo.put("totalDuration",
                group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum());
            panelInfo.put("gridStatus", "approved");
            panelInfo.put("videoStatus", "pending");
            panelInfo.put("visualStyle", visualStyle);
            panelInfo.put("gridImages", new ArrayList<String>());
            panelInfo.put("videoRefMode", videoRefMode);

            if (!videoRefMode) {
                // 首帧视频模式：生成融合参考图
                try {
                    BufferedImage fusionImage = gridImageService.createFusionImageForPanelWithNames(group, charRefsWithNames);
                    String fusionUrl = gridImageService.uploadFusionImageForPanel(fusionImage, episodeId, group);
                    panelInfo.put("fusionImageUrl", fusionUrl);
                } catch (Exception e) {
                    log.error("Panel 融合图生成失败: episodeId={}, shots={}", episodeId, group.size(), e);
                }
            } else {
                // 参考图视频模式：跳过融合图
                panelInfo.put("fusionImageUrl", null);
            }

            panel.setPanelInfo(panelInfo);
            panelRepository.insert(panel);
            splitShotStartIndex += group.size();
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
            @PathVariable Long episodeId,
            @RequestBody(required = false) Map<String, Object> body) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("剧集不存在");
        Map<String, Object> info = episode.getEpisodeInfo();

        String effectiveCustomHint = info.get("gridPromptHint") instanceof String
            ? (String) info.get("gridPromptHint")
            : null;
        String requestedHint = body != null && body.get("customHint") instanceof String
            ? (String) body.get("customHint")
            : null;
        if (requestedHint != null) {
            String normalized = requestedHint.trim();
            if (normalized.isEmpty()) {
                info.remove("gridPromptHint");
                effectiveCustomHint = null;
            } else {
                info.put("gridPromptHint", normalized);
                effectiveCustomHint = normalized;
            }
        }

        // 处理完整的自定义 prompt（用户直接编辑的 prompt - 单页兼容）
        String requestedFullPrompt = body != null && body.get("fullPrompt") instanceof String
            ? (String) body.get("fullPrompt")
            : null;
        if (requestedFullPrompt != null) {
            String normalized = requestedFullPrompt.trim();
            if (normalized.isEmpty()) {
                info.remove("gridPromptOverride");
            } else {
                info.put("gridPromptOverride", normalized);
            }
        }

        // 处理多页自定义 prompts（每页一个 prompt）
        @SuppressWarnings("unchecked")
        List<String> requestedGridPrompts = body != null && body.get("gridPrompts") instanceof List
            ? (List<String>) body.get("gridPrompts")
            : null;

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

        // 清理已有的 Panel，避免重新审核后重复创建
        List<Panel> existingPanels = panelRepository.findByEpisodeId(episodeId);
        for (Panel p : existingPanels) {
            panelRepository.deleteById(p.getId());
        }

        // 异步重新生成（传入多页 prompts）
        gridImageService.generateGridsForEpisode(
            episodeId,
            shots,
            visualStyle,
            panelProductionService.getImageProvider(projectId),
            (effectiveCustomHint != null && !effectiveCustomHint.trim().isEmpty()) ? effectiveCustomHint.trim() : null,
            requestedGridPrompts
        );

        return Result.ok();
    }

    private void checkAndAdvanceAllGridsApproved(String projectId) {
        // 新设计中面板审核通过后不再需要推进状态机（面板生产是用户手动触发的）
    }
}
