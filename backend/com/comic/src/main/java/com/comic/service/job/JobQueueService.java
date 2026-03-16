package com.comic.service.job;

import com.comic.entity.Job;
import com.comic.repository.JobRepository;
import com.comic.service.story.StoryboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 任务队列服务（内存队列版，Java 8）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobQueueService {

    private final JobRepository jobRepository;
    private final StoryboardService storyboardService;
    private final ObjectMapper objectMapper;

    @Value("${comic.job.worker-threads:3}")
    private int workerThreads;

    private final BlockingQueue<String> jobIdQueue = new LinkedBlockingQueue<>(200);
    private final ConcurrentHashMap<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();

    @PostConstruct
    public void startWorkers() {
        ExecutorService executor = Executors.newFixedThreadPool(workerThreads,
                r -> {
                    Thread t = new Thread(r, "job-worker");
                    t.setDaemon(true);
                    return t;
                });

        for (int i = 0; i < workerThreads; i++) {
            executor.submit(this::processLoop);
        }
        log.info("任务队列已启动，工作线程数: {}", workerThreads);
    }

    public String submitStoryboardJob(Long episodeId) {
        Job job = new Job();
        job.setJobType("GENERATE_STORYBOARD");
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setProgressMsg("等待生成...");
        job.setRetryCount(0);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("episodeId", episodeId);
            job.setInputParams(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            job.setInputParams("{}");
        }
        jobRepository.insert(job);

        if (!jobIdQueue.offer(job.getJobId())) {
            job.setStatus("FAILED");
            job.setErrorMsg("队列已满，请稍后重试");
            jobRepository.updateById(job);
            throw new RuntimeException("任务队列已满");
        }

        log.info("任务已提交: jobId={}, episodeId={}", job.getJobId(), episodeId);
        return job.getJobId();
    }

    public SseEmitter registerProgressListener(String jobId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        sseEmitters.put(jobId, emitter);
        emitter.onCompletion(() -> sseEmitters.remove(jobId));
        emitter.onTimeout(() -> sseEmitters.remove(jobId));
        emitter.onError(e -> sseEmitters.remove(jobId));

        Job job = jobRepository.selectById(jobId);
        if (job != null) {
            sendProgress(jobId, job.getProgress(), job.getProgressMsg());
        }
        return emitter;
    }

    // ================= 内部方法 =================

    private void processLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String jobId = jobIdQueue.take();
                processJob(jobId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("任务处理异常", e);
            }
        }
    }

    private void processJob(String jobId) {
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            log.warn("找不到任务: {}", jobId);
            return;
        }

        try {
            updateJobStatus(job, "RUNNING", 10, "开始生成...");

            Map params = objectMapper.readValue(job.getInputParams(), Map.class);
            Long episodeId = Long.valueOf(params.get("episodeId").toString());

            updateJobStatus(job, "RUNNING", 30, "正在调用 AI 生成分镜...");

            String result = storyboardService.generateStoryboard(episodeId);

            updateJobStatus(job, "RUNNING", 90, "保存结果...");

            job.setStatus("SUCCESS");
            job.setProgress(100);
            job.setProgressMsg("生成完成！");
            job.setResultData(result.length() > 500 ? result.substring(0, 500) : result);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.updateById(job);

            sendProgress(jobId, 100, "✅ 生成完成！");
            closeSseEmitter(jobId);

        } catch (Exception e) {
            log.error("任务执行失败: jobId={}", jobId, e);
            job.setStatus("FAILED");
            job.setProgress(0);
            job.setProgressMsg("生成失败");
            job.setErrorMsg(e.getMessage());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.updateById(job);

            sendProgress(jobId, -1, "❌ 失败: " + e.getMessage());
            closeSseEmitter(jobId);
        }
    }

    private void updateJobStatus(Job job, String status, int progress, String msg) {
        job.setStatus(status);
        job.setProgress(progress);
        job.setProgressMsg(msg);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.updateById(job);
        sendProgress(job.getJobId(), progress, msg);
    }

    private void sendProgress(String jobId, int percent, String message) {
        SseEmitter emitter = sseEmitters.get(jobId);
        if (emitter == null) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("percent", percent);
            data.put("message", message);
            emitter.send(SseEmitter.event().name("progress").data(data));
        } catch (IOException e) {
            sseEmitters.remove(jobId);
        }
    }

    private void closeSseEmitter(String jobId) {
        SseEmitter emitter = sseEmitters.remove(jobId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }
}
