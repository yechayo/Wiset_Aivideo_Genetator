package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.AutoProduceRequest;
import com.comic.dto.request.FusionRequest;
import com.comic.dto.request.ProduceRequest;
import com.comic.dto.request.TransitionRequest;
import com.comic.dto.response.*;
import com.comic.service.oss.OssService;
import com.comic.service.production.EpisodeProductionService;
import com.comic.service.production.GridSplitService;
import com.comic.service.production.PanelProductionService;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 单集视频生产接口
 */
@RestController
@RequestMapping("/api/episodes")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EpisodeController {

    private final EpisodeProductionService productionService;
    private final OssService ossService;
    private final PanelProductionService panelProductionService;

    /**
     * 启动视频生产
     * POST /api/episodes/{episodeId}/produce
     */
    @PostMapping("/{episodeId}/produce")
    @Operation(summary = "启动视频生产", description = "为指定剧集启动视频生成流程")
    public Result<ProductionStartResponse> startProduction(@PathVariable Long episodeId) {
        String productionId = productionService.startProduction(episodeId);
        return Result.ok(new ProductionStartResponse(productionId));
    }

    /**
     * 获取项目生产管线全链路状态
     * GET /api/episodes/project/{projectId}/pipeline
     */
    @GetMapping("/project/{projectId}/pipeline")
    @Operation(summary = "获取管线状态", description = "获取项目下第一个可生产剧集的管线全链路状态（Step5页面可视化用）")
    public Result<ProductionPipelineResponse> getProductionPipeline(@PathVariable String projectId) {
        ProductionPipelineResponse pipeline = productionService.getProductionPipeline(projectId);
        return Result.ok(pipeline);
    }

    /**
     * 获取生产状态
     * GET /api/episodes/{episodeId}/production-status
     */
    @GetMapping("/{episodeId}/production-status")
    @Operation(summary = "获取生产状态", description = "获取剧集视频生产的当前状态和进度")
    public Result<ProductionStatusResponse> getProductionStatus(@PathVariable Long episodeId) {
        ProductionStatusResponse status = productionService.getProductionStatus(episodeId);
        return Result.ok(status);
    }

    /**
     * 重试失败的生产
     * POST /api/episodes/{episodeId}/retry-production
     */
    @PostMapping("/{episodeId}/retry-production")
    @Operation(summary = "重试视频生产", description = "重试失败的剧集生产任务")
    public Result<Void> retryProduction(@PathVariable Long episodeId) {
        productionService.retryProduction(episodeId);
        return Result.ok();
    }

    /**
     * 获取网格拆分/融合所需信息
     * GET /api/episodes/{episodeId}/grid-info
     */
    @GetMapping("/{episodeId}/grid-info")
    @Operation(summary = "获取网格信息", description = "获取九宫格URL、网格尺寸和角色参考图信息，用于前端拆分和融合")
    public Result<GridInfoResponse> getGridInfo(@PathVariable Long episodeId) {
        GridInfoResponse gridInfo = productionService.getGridInfo(episodeId);
        return Result.ok(gridInfo);
    }

    /**
     * 上传融合图
     * POST /api/episodes/{episodeId}/fusion-image
     */
    @PostMapping("/{episodeId}/fusion-image")
    @Operation(summary = "上传融合图", description = "上传前端Canvas导出的融合图PNG到OSS，返回公网URL")
    public Result<String> uploadFusionImage(@PathVariable Long episodeId,
                                           @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String ossUrl = ossService.uploadMultipartFile(file, "fusion");
        return Result.ok(ossUrl);
    }

    /**
     * 提交单页融合结果（P1-1多页融合）
     * POST /api/episodes/{episodeId}/submit-fusion-page
     */
    @PostMapping("/{episodeId}/submit-fusion-page")
    @Operation(summary = "提交单页融合结果", description = "提交单页融合图URL，全部完成后自动恢复管线")
    public Result<Map<String, Object>> submitFusionPage(@PathVariable Long episodeId,
                                                         @RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Object rawPageIndex = body.getOrDefault("pageIndex", 0);
        int pageIndex;
        if (rawPageIndex instanceof Number) {
            pageIndex = ((Number) rawPageIndex).intValue();
        } else {
            try {
                pageIndex = Integer.parseInt(String.valueOf(rawPageIndex));
            } catch (Exception e) {
                throw new IllegalArgumentException("pageIndex 格式不正确");
            }
        }
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex 不能小于0");
        }
        // 接收每格的融合图URL列表（最多9个）
        List<String> panelFusedUrls = (List<String>) body.get("panelFusedUrls");
        if (panelFusedUrls == null || panelFusedUrls.isEmpty()) {
            throw new IllegalArgumentException("panelFusedUrls 不能为空");
        }
        // 解析 autoContinue 参数（默认 true，保持向后兼容）
        boolean autoContinue = body.get("autoContinue") != null
                ? Boolean.parseBoolean(String.valueOf(body.get("autoContinue")))
                : true;
        int totalFused = productionService.submitFusionPage(episodeId, pageIndex, panelFusedUrls, autoContinue);
        Map<String, Object> result = new HashMap<>();
        result.put("totalFused", totalFused);
        result.put("pageIndex", pageIndex);
        return Result.ok(result);
    }

    /**
     * 后端执行单页网格切图并返回行优先绑定结果（P6）。
     * POST /api/episodes/{episodeId}/split-grid-page
     */
    @PostMapping("/{episodeId}/split-grid-page")
    @Operation(summary = "后端切分单页网格", description = "按行优先顺序切分单页网格并绑定分镜元数据")
    public Result<GridSplitService.SplitPageResult> splitGridPage(@PathVariable Long episodeId,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        int pageIndex = 0;
        if (body != null && body.containsKey("pageIndex")) {
            Object rawPageIndex = body.get("pageIndex");
            if (rawPageIndex instanceof Number) {
                pageIndex = ((Number) rawPageIndex).intValue();
            } else {
                try {
                    pageIndex = Integer.parseInt(String.valueOf(rawPageIndex));
                } catch (Exception e) {
                    throw new IllegalArgumentException("pageIndex 格式不正确");
                }
            }
        }
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex 不能小于0");
        }
        GridSplitService.SplitPageResult result = productionService.splitGridPageForFusion(episodeId, pageIndex);
        return Result.ok(result);
    }

    /**
     * 获取视频片段列表（P1-7）
     * GET /api/episodes/{episodeId}/video-segments
     */
    @GetMapping("/{episodeId}/video-segments")
    @Operation(summary = "获取视频片段列表", description = "获取已完成视频生成的所有片段信息")
    public Result<List<VideoSegmentInfoResponse>> getVideoSegments(@PathVariable Long episodeId) {
        List<VideoSegmentInfoResponse> segments = productionService.getVideoSegmentInfos(episodeId);
        return Result.ok(segments);
    }

    /**
     * 获取所有面板状态（原子化模式用）
     * GET /api/episodes/{episodeId}/panel-states
     */
    @GetMapping("/{episodeId}/panel-states")
    @Operation(summary = "获取面板状态", description = "获取所有分镜格子的融合和视频生成状态")
    public Result<List<PanelStateResponse>> getPanelStates(@PathVariable Long episodeId) {
        List<PanelStateResponse> states = productionService.getPanelStates(episodeId);
        return Result.ok(states);
    }

    /**
     * 单格场景图重生成
     * POST /api/episodes/{episodeId}/panels/{panelIndex}/regenerate-scene
     */
    @PostMapping("/{episodeId}/panels/{panelIndex}/regenerate-scene")
    @Operation(summary = "单格场景图重生成", description = "按分镜的scene_description重新生成单格场景图")
    public Result<Void> regeneratePanelScene(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        productionService.regeneratePanelScene(episodeId, panelIndex);
        return Result.ok();
    }

    /**
     * 获取背景生成状态
     * GET /api/episodes/{episodeId}/panels/{panelIndex}/background
     */
    @GetMapping("/{episodeId}/panels/{panelIndex}/background")
    @Operation(summary = "获取背景生成状态", description = "获取指定分镜的背景生成状态")
    public Result<PanelBackgroundResponse> getBackgroundStatus(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        PanelBackgroundResponse status = panelProductionService.getBackgroundStatus(episodeId, panelIndex);
        return Result.ok(status);
    }

    /**
     * 生成背景
     * POST /api/episodes/{episodeId}/panels/{panelIndex}/background
     */
    @PostMapping("/{episodeId}/panels/{panelIndex}/background")
    @Operation(summary = "生成背景", description = "为指定分镜生成背景")
    public Result<Void> generateBackground(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        panelProductionService.generateBackground(episodeId, panelIndex);
        return Result.ok();
    }

    /**
     * 获取融合状态
     * GET /api/episodes/{episodeId}/panels/{panelIndex}/fusion
     */
    @GetMapping("/{episodeId}/panels/{panelIndex}/fusion")
    @Operation(summary = "获取融合状态", description = "获取指定分镜的融合生成状态")
    public Result<PanelFusionResponse> getFusionStatus(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        PanelFusionResponse status = panelProductionService.getFusionStatus(episodeId, panelIndex);
        return Result.ok(status);
    }

    /**
     * 生成融合
     * POST /api/episodes/{episodeId}/panels/{panelIndex}/fusion
     */
    @PostMapping("/{episodeId}/panels/{panelIndex}/fusion")
    @Operation(summary = "生成融合", description = "为指定分镜生成融合")
    public Result<Void> generateFusion(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex,
            @RequestBody FusionRequest request) {
        panelProductionService.generateFusion(episodeId, panelIndex, request);
        return Result.ok();
    }

    /**
     * 获取转场状态
     * GET /api/episodes/{episodeId}/panels/{panelIndex}/transition
     */
    @GetMapping("/{episodeId}/panels/{panelIndex}/transition")
    @Operation(summary = "获取转场状态", description = "获取指定分镜的转场生成状态")
    public Result<PanelTransitionResponse> getTransitionStatus(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        PanelTransitionResponse status = panelProductionService.getTransitionStatus(episodeId, panelIndex);
        return Result.ok(status);
    }

    /**
     * 生成转场
     * POST /api/episodes/{episodeId}/panels/{panelIndex}/transition
     */
    @PostMapping("/{episodeId}/panels/{panelIndex}/transition")
    @Operation(summary = "生成转场", description = "为指定分镜生成转场")
    public Result<Void> generateTransition(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex,
            @RequestBody TransitionRequest request) {
        panelProductionService.generateTransition(episodeId, panelIndex, request);
        return Result.ok();
    }

    /**
     * 获取尾帧
     * GET /api/episodes/{episodeId}/panels/{panelIndex}/tail-frame
     */
    @GetMapping("/{episodeId}/panels/{panelIndex}/tail-frame")
    @Operation(summary = "获取尾帧", description = "获取指定分镜的尾帧信息")
    public Result<PanelTailFrameResponse> getTailFrame(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        PanelTailFrameResponse tailFrame = panelProductionService.getTailFrame(episodeId, panelIndex);
        return Result.ok(tailFrame);
    }

    /**
     * 获取视频任务状态
     * GET /api/episodes/{episodeId}/panels/{panelIndex}/video-task
     */
    @GetMapping("/{episodeId}/panels/{panelIndex}/video-task")
    @Operation(summary = "获取视频任务状态", description = "获取指定分镜的视频生成任务状态")
    public Result<PanelVideoTaskResponse> getVideoTaskStatus(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        PanelVideoTaskResponse videoTask = panelProductionService.getVideoTaskStatus(episodeId, panelIndex);
        return Result.ok(videoTask);
    }

    /**
     * 单格一键生产
     * POST /api/episodes/{episodeId}/panels/{panelIndex}/produce
     */
    @PostMapping("/{episodeId}/panels/{panelIndex}/produce")
    @Operation(summary = "单格一键生产", description = "为指定分镜执行一键生成视频")
    public Result<Void> produceSinglePanel(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex,
            @RequestBody ProduceRequest request) {
        panelProductionService.produceSinglePanel(episodeId, panelIndex, request);
        return Result.ok();
    }

    /**
     * 自动生产所有分镜
     * POST /api/episodes/{episodeId}/auto-produce-all
     */
    @PostMapping("/{episodeId}/auto-produce-all")
    @Operation(summary = "自动生产所有分镜", description = "从指定分镜开始自动生产所有后续分镜")
    public Result<Void> produceAllPanels(
            @PathVariable Long episodeId,
            @RequestBody AutoProduceRequest request) {
        panelProductionService.produceAllPanels(episodeId, request.getStartFrom());
        return Result.ok();
    }

    /**
     * 合成最终视频
     * POST /api/episodes/{episodeId}/synthesize
     */
    @PostMapping("/{episodeId}/synthesize")
    @Operation(summary = "合成最终视频", description = "合成所有生成的分镜视频为最终成品视频")
    public Result<CompositionResultResponse> synthesizeEpisode(
            @PathVariable Long episodeId) {
        CompositionResultResponse result = panelProductionService.synthesizeEpisode(episodeId);
        return Result.ok(result);
    }

    /**
     * 获取分镜完整生产状态
     * GET /api/episodes/{episodeId}/panels/{panelIndex}/production-status
     */
    @GetMapping("/{episodeId}/panels/{panelIndex}/production-status")
    @Operation(summary = "获取分镜完整生产状态", description = "获取指定分镜的完整生产状态信息")
    public Result<PanelProductionStatusResponse> getPanelProductionStatus(
            @PathVariable Long episodeId,
            @PathVariable Integer panelIndex) {
        PanelProductionStatusResponse status = panelProductionService.getPanelProductionStatus(episodeId, panelIndex);
        return Result.ok(status);
    }
}
