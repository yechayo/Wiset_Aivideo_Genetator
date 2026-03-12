package com.comic.controller;

import com.comic.common.Result;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
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
public class StoryController {

    private final JobQueueService jobQueueService;
    private final EpisodeRepository episodeRepository;

    /**
     * 提交分镜生成任务
     * POST /api/story/generate
     * Body: {"episodeId": 1}
     */
    @PostMapping("/generate")
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
    public Result<Episode> getStoryboard(@PathVariable Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            return Result.fail(404, "找不到该集数");
        }
        return Result.ok(episode);
    }

    /**
     * 获取集数列表
     * GET /api/story/episodes?seriesId=xxx
     */
    @GetMapping("/episodes")
    public Result<?> getEpisodes(@RequestParam String seriesId) {
        return Result.ok(episodeRepository.findBySeriesId(seriesId));
    }
}
