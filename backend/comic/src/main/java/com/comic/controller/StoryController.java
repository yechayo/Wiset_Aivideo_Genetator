package com.comic.controller;

import com.comic.common.Result;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 故事生成接口（Java 8 版）
 */
@RestController
@RequestMapping("/api/story")
@RequiredArgsConstructor
@Tag(name = "故事生成")
@SecurityRequirement(name = "bearerAuth")
public class StoryController {

    private final JobQueueService jobQueueService;
    private final EpisodeRepository episodeRepository;

    /**
     * 提交分镜生成任务
     * POST /api/story/generate
     * Body: {"episodeId": 1}
     */
    @PostMapping("/generate")
    @Operation(summary = "生成分镜", description = "提交分镜生成任务。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Map<String, String>> generateStoryboard(@RequestBody Map<String, Long> body) {
        Long episodeId = body.get("episodeId");
        if (episodeId == null) {
            return Result.fail("episodeId 不能为空");
        }
        String jobId = jobQueueService.submitStoryboardJob(episodeId);
        Map<String, String> result = new HashMap<>();
        result.put("jobId", jobId);
        return Result.ok(result);
    }

    /**
     * 查看分镜结果
     * GET /api/story/storyboard/{episodeId}
     */
    @GetMapping("/storyboard/{episodeId}")
    @Operation(summary = "查看分镜结果", description = "获取指定剧集的分镜数据。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Episode> getStoryboard(
            @Parameter(description = "剧集ID", required = true) @PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            return Result.fail(404, "找不到该集数");
        }
        return Result.ok(episode);
    }

    /**
     * 获取集数列表
     * GET /api/story/episodes?projectId=xxx
     */
    @GetMapping("/episodes")
    @Operation(summary = "获取剧集列表", description = "获取项目下的所有剧集。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<?> getEpisodes(
            @Parameter(description = "项目ID", required = true) @RequestParam String projectId) {
        return Result.ok(episodeRepository.findByProjectId(projectId));
    }
}
