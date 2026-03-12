package com.comic.controller;

import com.comic.common.Result;
import com.comic.entity.Job;
import com.comic.repository.JobRepository;
import com.comic.service.job.JobQueueService;
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
public class JobController {

    private final JobQueueService jobQueueService;
    private final JobRepository jobRepository;

    /**
     * 订阅任务进度（SSE）
     * 前端用 EventSource 连接：new EventSource('/api/jobs/{jobId}/progress')
     */
    @GetMapping(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String jobId) {
        return jobQueueService.registerProgressListener(jobId);
    }

    /**
     * 查询任务状态（轮询备选）
     * GET /api/jobs/{jobId}
     */
    @GetMapping("/{jobId}")
    public Result<Job> getJob(@PathVariable String jobId) {
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            return Result.fail(404, "找不到该任务");
        }
        return Result.ok(job);
    }
}
