package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.EpisodeCreateRequest;
import com.comic.dto.request.EpisodeUpdateRequest;
import com.comic.dto.response.EpisodeListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.service.episode.EpisodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes")
@RequiredArgsConstructor
@Tag(name = "剧集管理")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeController {

    private final EpisodeService episodeService;

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
}