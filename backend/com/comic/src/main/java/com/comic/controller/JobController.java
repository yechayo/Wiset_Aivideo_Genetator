package com.comic.controller;

import com.comic.common.Result;
import com.comic.entity.Job;
import com.comic.repository.JobRepository;
import com.comic.service.job.JobQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务管理接口（Java 8 版）
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@Tag(name = "任务管理")
@SecurityRequirement(name = "bearerAuth")
public class JobController {

    private final JobQueueService jobQueueService;
    private final JobRepository jobRepository;

    /**
     * 订阅任务进度（SSE）
     * 前端用 EventSource 连接：new EventSource('/api/jobs/{jobId}/progress')
     */
    @GetMapping(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅任务进度", description = "通过SSE实时获取任务进度。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public SseEmitter streamProgress(
            @Parameter(description = "任务ID", required = true) @PathVariable String jobId) {
        return jobQueueService.registerProgressListener(jobId);
    }

    /**
     * 查询任务状态（轮询备选）
     * GET /api/jobs/{jobId}
     */
    @GetMapping("/{jobId}")
    @Operation(summary = "查询任务状态", description = "查询任务的当前状态（轮询方式）。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Job> getJob(
            @Parameter(description = "任务ID", required = true) @PathVariable String jobId) {
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            return Result.fail(404, "找不到该任务");
        }
        return Result.ok(job);
    }
}
