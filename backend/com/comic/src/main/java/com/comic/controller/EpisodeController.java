package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.response.ProductionStartResponse;
import com.comic.dto.response.ProductionStatusResponse;
import com.comic.service.production.EpisodeProductionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 单集视频生产接口
 */
@RestController
@RequestMapping("/api/episodes")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EpisodeController {

    private final EpisodeProductionService productionService;

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
}
