package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.response.GridInfoResponse;
import com.comic.dto.response.ProductionPipelineResponse;
import com.comic.dto.response.ProductionStartResponse;
import com.comic.dto.response.ProductionStatusResponse;
import com.comic.dto.response.VideoSegmentInfoResponse;
import com.comic.service.oss.OssService;
import com.comic.service.production.EpisodeProductionService;
import com.comic.service.production.GridSplitService;

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
     * 提交融合结果并恢复管线
     * POST /api/episodes/{episodeId}/submit-fusion
     */
    @PostMapping("/{episodeId}/submit-fusion")
    @Operation(summary = "提交融合结果", description = "提交融合图URL并恢复视频生产管线")
    public Result<Void> submitFusion(@PathVariable Long episodeId,
                                     @RequestBody Map<String, String> body) {
        String fusedReferenceImageUrl = body.get("fusedReferenceImageUrl");
        if (fusedReferenceImageUrl == null || fusedReferenceImageUrl.isEmpty()) {
            throw new IllegalArgumentException("fusedReferenceImageUrl 不能为空");
        }
        productionService.resumeAfterFusion(episodeId, fusedReferenceImageUrl);
        return Result.ok();
    }

    /**
     * 提交单页融合结果（P1-1多页融合）
     * POST /api/episodes/{episodeId}/submit-fusion-page
     */
    @PostMapping("/{episodeId}/submit-fusion-page")
    @Operation(summary = "提交单页融合结果", description = "提交单页融合图URL，全部完成后自动恢复管线")
    public Result<Map<String, Object>> submitFusionPage(@PathVariable Long episodeId,
                                                         @RequestBody Map<String, Object> body) {
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
        int totalFused = productionService.submitFusionPage(episodeId, pageIndex, panelFusedUrls);
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
}
