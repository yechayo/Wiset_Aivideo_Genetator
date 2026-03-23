package com.comic.controller;

import com.comic.common.Result;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
import com.comic.service.story.StoryboardService;
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
    private final StoryboardService storyboardService;

    /**
     * 提交分镜生成任务
     * POST /api/story/generate
     * Body: {"episodeId": 1}
     */
    @PostMapping("/generate")
    @Operation(summary = "生成分镜", description = "提交分镜生成任务。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Map<String, String>> generateStoryboard(@RequestBody Map<String, Object> body) {
        Long episodeId = parseLongValue(body.get("episodeId"));
        if (episodeId == null) {
            return Result.fail("episodeId 不能为空");
        }

        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            return Result.fail(404, "找不到该集数");
        }
        if (episode.getStoryboardJson() != null && !episode.getStoryboardJson().trim().isEmpty()) {
            return Result.fail("当前剧集已有分镜，请使用修改分镜接口");
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

    // ================= 分镜流程编排接口 =================

    /**
     * 启动分镜生成流程
     * POST /api/story/start-storyboard
     */
    @PostMapping("/start-storyboard")
    @Operation(summary = "启动分镜生成", description = "从素材锁定状态启动逐集分镜生成流程")
    public Result<String> startStoryboard(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        if (projectId == null) {
            return Result.fail("projectId 不能为空");
        }
        try {
            storyboardService.startStoryboardGeneration(projectId);
            return Result.ok("分镜生成已启动");
        } catch (Exception e) {
            return Result.fail("启动分镜生成失败: " + e.getMessage());
        }
    }

    /**
     * 确认当前集分镜
     * POST /api/story/confirm-storyboard
     */
    @PostMapping("/confirm-storyboard")
    @Operation(summary = "确认分镜", description = "确认当前集的分镜，自动继续下一集")
    public Result<String> confirmStoryboard(@RequestBody Map<String, Object> body) {
        Long episodeId = parseLongValue(body.get("episodeId"));
        if (episodeId == null) {
            return Result.fail("episodeId 不能为空");
        }
        try {
            storyboardService.confirmEpisodeStoryboard(episodeId);
            return Result.ok("分镜已确认");
        } catch (Exception e) {
            return Result.fail("确认分镜失败: " + e.getMessage());
        }
    }

    /**
     * 基于反馈修改当前集分镜
     * POST /api/story/revise-storyboard
     */
    @PostMapping("/revise-storyboard")
    @Operation(summary = "修改分镜", description = "基于用户反馈增量修改当前集分镜")
    public Result<String> reviseStoryboard(@RequestBody Map<String, Object> body) {
        Long episodeId = parseLongValue(body.get("episodeId"));
        String feedback = body.get("feedback") != null ? body.get("feedback").toString() : null;

        if (episodeId == null) {
            return Result.fail("episodeId 不能为空");
        }
        if (feedback == null || feedback.trim().isEmpty()) {
            return Result.fail("反馈意见不能为空");
        }
        try {
            storyboardService.reviseEpisodeStoryboard(episodeId, feedback);
            return Result.ok("分镜修改已提交");
        } catch (Exception e) {
            return Result.fail("修改分镜失败: " + e.getMessage());
        }
    }

    /**
     * 重试失败的分镜生成
     * POST /api/story/retry-storyboard
     */
    @PostMapping("/retry-storyboard")
    @Operation(summary = "重试分镜生成", description = "重试当前失败集的分镜生成")
    public Result<String> retryStoryboard(@RequestBody Map<String, Object> body) {
        Long episodeId = parseLongValue(body.get("episodeId"));
        if (episodeId == null) {
            return Result.fail("episodeId 不能为空");
        }
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            return Result.fail(404, "找不到该集数");
        }
        if (episode.getStoryboardJson() != null && !episode.getStoryboardJson().trim().isEmpty()) {
            return Result.fail("当前剧集已有分镜，请使用修改分镜接口");
        }
        try {
            storyboardService.retryFailedStoryboard(episodeId);
            return Result.ok("重试已提交");
        } catch (Exception e) {
            return Result.fail("重试分镜失败: " + e.getMessage());
        }
    }

    /**
     * 从分镜审核进入生产
     * POST /api/story/start-production
     */
    @PostMapping("/start-production")
    @Operation(summary = "开始生产", description = "所有集分镜确认后，开始视频生产")
    public Result<String> startProduction(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        if (projectId == null) {
            return Result.fail("projectId 不能为空");
        }
        try {
            storyboardService.startProductionFromStoryboard(projectId);
            return Result.ok("生产已启动");
        } catch (Exception e) {
            return Result.fail("启动生产失败: " + e.getMessage());
        }
    }

    private Long parseLongValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(rawValue));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
