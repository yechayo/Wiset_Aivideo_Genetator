package com.comic.service.production;

import com.comic.ai.video.SeedanceVideoService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.dto.model.VideoPromptModel;
import com.comic.dto.model.VideoTaskGroupModel;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.VideoProductionTask;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.VideoProductionTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 视频生产队列服务
 * 管理视频生成任务的提交、轮询和重试
 *
 * 支持：
 * - 文生视频
 * - 图生视频（首帧）
 * - 图生视频（首尾帧）- 连续视频生成
 * - 样片模式 + 正式视频生成
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoProductionQueueService {

    private final VideoGenerationService videoGenerationService;
    private final SeedanceVideoService seedanceVideoService;  // 使用 Seedance 特定服务
    private final VideoProductionTaskRepository taskRepository;
    private final EpisodeProductionRepository productionRepository;

    private static final String GROUP_ID_SEPARATOR = "::";

    // 并发控制：限制同时处理的视频任务数
    private final Semaphore processingSemaphore = new Semaphore(1);  // 视频生成资源消耗大，限制为1

    // 任务执行器
    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "video-production-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("视频生产队列服务已启动");
    }

    /**
     * 提交视频生成任务
     *
     * @param productionId 生产任务ID
     * @param episodeId 剧集ID
     * @param taskGroups 任务组列表
     */
    public void submitVideoTasks(String productionId, Long episodeId, List<VideoTaskGroupModel> taskGroups) {
        EpisodeProduction production = productionRepository.findByProductionId(productionId);
        if (production == null) {
            throw new IllegalArgumentException("生产任务不存在: " + productionId);
        }

        // 为每个任务组创建任务记录
        for (VideoTaskGroupModel group : taskGroups) {
            String runtimeGroupId = productionId + GROUP_ID_SEPARATOR + group.getGroupId();
            group.setGroupId(runtimeGroupId);
            for (VideoPromptModel prompt : group.getPrompts()) {
                VideoProductionTask task = new VideoProductionTask();
                task.setTaskId("VTASK-" + UUID.randomUUID().toString().substring(0, 8));
                task.setEpisodeId(episodeId);
                task.setPanelIndex(prompt.getPanelIndex());
                task.setTaskGroup(runtimeGroupId);
                task.setVideoPrompt(prompt.getPromptText());
                task.setReferenceImageUrl(prompt.getReferenceImageUrl());
                task.setTargetDuration(prompt.getDuration());
                task.setStatus("PENDING");
                task.setRetryCount(0);

                taskRepository.insert(task);
            }
        }

        // 异步顺序处理任务组，确保上一组结果可作为下一组连续性输入
        executorService.submit(() -> {
            for (VideoTaskGroupModel taskGroup : taskGroups) {
                processTaskGroup(productionId, episodeId, taskGroup);
            }
        });

        log.info("视频生成任务已提交: productionId={}, groups={}", productionId, taskGroups.size());
    }

    /**
     * 处理单个任务组
     */
    private void processTaskGroup(String productionId, Long episodeId, VideoTaskGroupModel group) {
        try {
            List<VideoProductionTask> tasks = taskRepository.findByTaskGroup(group.getGroupId());

            if (tasks.isEmpty()) {
                log.warn("任务组没有找到任务: groupId={}", group.getGroupId());
                return;
            }

            // 防重复：如果任务组内所有任务已经是 COMPLETED，跳过
            if (tasks.stream().allMatch(t -> "COMPLETED".equals(t.getStatus()))) {
                log.info("任务组已完成，跳过: groupId={}", group.getGroupId());
                return;
            }

            // 恢复路径可能只有 groupId，需要从任务记录中补齐提示词/时长/参考图
            hydrateTaskGroupIfMissing(productionId, group, tasks);

            // 获取第一个任务作为代表
            VideoProductionTask representativeTask = tasks.get(0);

            // 更新状态为处理中
            for (VideoProductionTask task : tasks) {
                task.setStatus("PROCESSING");
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.updateById(task);
            }

            // 获取上一段视频的URL用于连续性
            String previousVideoUrl = getPreviousVideoUrl(episodeId, representativeTask.getPanelIndex(), group.getGroupId());

            // 获取该任务组中每个任务各自对应的融合参考图
            String fusedRefUrl = getFusedReferenceForTask(group);

            // 尝试生成视频（带重试）
            VideoGenerationResult result = generateVideoWithRetry(representativeTask, group, previousVideoUrl, fusedRefUrl);

            // 保存结果
            for (VideoProductionTask task : tasks) {
                task.setVideoUrl(result.videoUrl);
                task.setVideoTaskId(result.videoTaskId);
                task.setStatus("COMPLETED");
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.updateById(task);
            }

            // 更新生产进度
            updateProductionProgress(productionId, tasks.size());

            log.info("任务组处理完成: groupId={}, url={}", group.getGroupId(), result.videoUrl);

        } catch (Exception e) {
            log.error("任务组处理失败: groupId={}", group.getGroupId(), e);

            // 标记任务失败
            List<VideoProductionTask> tasks = taskRepository.findByTaskGroup(group.getGroupId());
            for (VideoProductionTask task : tasks) {
                if (task.getRetryCount() < 3) {
                    // 重试
                    task.setStatus("PENDING");
                    task.setRetryCount(task.getRetryCount() + 1);
                } else {
                    // 达到最大重试次数
                    task.setStatus("FAILED");
                    task.setErrorMessage(e.getMessage());
                }
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.updateById(task);
            }
        }
    }

    /**
     * 恢复场景下补齐任务组关键字段，避免空 prompts 导致 NPE 或无效请求。
     */
    private void hydrateTaskGroupIfMissing(String productionId, VideoTaskGroupModel group, List<VideoProductionTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        tasks.sort(Comparator.comparing(
                t -> t.getPanelIndex() == null ? Integer.MAX_VALUE : t.getPanelIndex()));

        if (group.getPrompts() == null || group.getPrompts().isEmpty()) {
            List<VideoPromptModel> prompts = new ArrayList<>();
            for (VideoProductionTask task : tasks) {
                VideoPromptModel prompt = new VideoPromptModel();
                prompt.setPromptText(task.getVideoPrompt());
                prompt.setPanelIndex(task.getPanelIndex());
                prompt.setDuration(task.getTargetDuration());
                prompt.setReferenceImageUrl(task.getReferenceImageUrl());
                prompts.add(prompt);
            }
            group.setPrompts(prompts);
        }

        if (group.getTotalDuration() == null || group.getTotalDuration() <= 0) {
            int totalDuration = tasks.stream()
                    .map(VideoProductionTask::getTargetDuration)
                    .filter(d -> d != null && d > 0)
                    .mapToInt(Integer::intValue)
                    .sum();
            if (totalDuration <= 0) {
                totalDuration = Math.max(tasks.size(), 1) * 2;
            }
            group.setTotalDuration(totalDuration);
        }

        if (group.getFusedReferenceImageUrl() == null || group.getFusedReferenceImageUrl().isEmpty()) {
            EpisodeProduction production = productionRepository.findByProductionId(productionId);
            if (production != null && production.getFusedReferenceUrl() != null
                    && !production.getFusedReferenceUrl().isEmpty()) {
                group.setFusedReferenceImageUrl(production.getFusedReferenceUrl());
            }
        }
    }

    /**
     * 生成视频（带重试）
     * 支持：文生视频、图生视频、首尾帧生成
     */
    private VideoGenerationResult generateVideoWithRetry(VideoProductionTask task, VideoTaskGroupModel group, String previousVideoUrl, String fusedRefUrl) {
        int maxRetries = 3;
        Exception lastError = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            boolean acquired = false;
            try {
                // 并发控制
                processingSemaphore.acquire();
                acquired = true;
                log.info("开始生成视频: groupId={}, attempt={}", group.getGroupId(), attempt + 1);

                // 构建组合提示词
                String combinedPrompt = buildCombinedPrompt(group);

                // 使用 Seedance 特定服务生成视频
                // 优先使用任务级别的融合参考图（逐格融合），回退到组级别
                String refImageUrl = fusedRefUrl != null ? fusedRefUrl : group.getFusedReferenceImageUrl();
                String videoTaskId = seedanceVideoService.generateVideo(
                        combinedPrompt,
                        group.getTotalDuration(),
                        "16:9",  // 默认16:9宽高比
                        refImageUrl,  // 首帧（融合参考图）
                        previousVideoUrl,  // 尾帧（上一段视频URL，用于连续性）
                        true,  // 生成音频
                        false  // 不使用样片模式
                );

                // 轮询等待完成
                String videoUrl = waitForCompletion(videoTaskId);

                log.info("视频生成成功: groupId={}, videoTaskId={}, url={}",
                        group.getGroupId(), videoTaskId, videoUrl);
                return new VideoGenerationResult(videoTaskId, videoUrl);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("视频生成被中断", e);
            } catch (Exception e) {
                lastError = e;
                log.warn("视频生成失败，尝试重试: attempt={}, error={}", attempt + 1, e.getMessage());

                if (attempt < maxRetries - 1) {
                    try {
                        // 指数退避
                        Thread.sleep(10000 * (attempt + 1));  // 增加等待时间
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("视频生成被中断", ie);
                    }
                }
            } finally {
                if (acquired) {
                    processingSemaphore.release();
                }
            }
        }

        throw new RuntimeException("视频生成失败，已重试" + maxRetries + "次", lastError);
    }

    /**
     * 视频生成结果
     */
    private static class VideoGenerationResult {
        String videoTaskId;
        String videoUrl;

        VideoGenerationResult(String videoTaskId, String videoUrl) {
            this.videoTaskId = videoTaskId;
            this.videoUrl = videoUrl;
        }
    }

    /**
     * 构建组合提示词
     * 将组内所有分镜的提示词组合在一起
     */
    private String buildCombinedPrompt(VideoTaskGroupModel group) {
        if (group.getPrompts() == null || group.getPrompts().isEmpty()) {
            throw new IllegalStateException("任务组提示词为空: groupId=" + group.getGroupId());
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < group.getPrompts().size(); i++) {
            VideoPromptModel prompt = group.getPrompts().get(i);
            sb.append("第").append(i + 1).append("镜：").append(prompt.getPromptText());

            if (i < group.getPrompts().size() - 1) {
                sb.append(" → ");
            }
        }

        return sb.toString();
    }

    /**
     * 等待视频生成完成
     */
    private String waitForCompletion(String videoTaskId) {
        int maxWaitTime = 600; // 10分钟
        int checkInterval = 5;  // 5秒检查一次
        int elapsed = 0;

        while (elapsed < maxWaitTime) {
            VideoGenerationService.TaskStatus status = videoGenerationService.getTaskStatus(videoTaskId);

            if (status.isCompleted()) {
                return status.getVideoUrl();
            }

            if (status.isFailed()) {
                throw new RuntimeException("视频生成失败: " + status.getErrorMessage());
            }

            try {
                Thread.sleep(checkInterval * 1000L);
                elapsed += checkInterval;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待视频生成被中断", e);
            }
        }

        throw new RuntimeException("视频生成超时");
    }

    /**
     * 更新生产进度
     */
    private void updateProductionProgress(String productionId, int completedCount) {
        EpisodeProduction production = productionRepository.findByProductionId(productionId);
        if (production == null) {
            return;
        }

        // 基于数据库中已完成视频任务数重算，而非累加，避免重复处理同一任务组导致进度回退
        int actualCompleted = taskRepository.countCompletedByEpisodeId(production.getEpisodeId());
        production.setCompletedPanels(actualCompleted);

        // 计算进度百分比
        if (production.getTotalPanels() > 0) {
            int progress = (int) ((actualCompleted * 80) / production.getTotalPanels()) + 10; // 10-90%用于视频生成
            production.setProgressPercent(Math.min(progress, 90));
            production.setProgressMessage(String.format("视频生成中... %d/%d", actualCompleted, production.getTotalPanels()));
        }

        production.setUpdatedAt(LocalDateTime.now());
        productionRepository.updateById(production);
    }

    /**
     * 定时轮询待处理任务
     * 处理因服务重启等原因遗留的PENDING任务
     */
    @Scheduled(fixedDelay = 30000) // 每30秒执行一次
    public void pollPendingTasks() {
        try {
            LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(10);
            List<VideoProductionTask> pendingTasks = taskRepository.findRecoverableTasks(staleBefore);

            // 将卡住的 PROCESSING 任务回收为 PENDING，避免永久卡死
            for (VideoProductionTask task : pendingTasks) {
                if ("PROCESSING".equals(task.getStatus())) {
                    task.setStatus("PENDING");
                    task.setUpdatedAt(LocalDateTime.now());
                    taskRepository.updateById(task);
                }
            }

            if (!pendingTasks.isEmpty()) {
                log.info("发现待处理任务: {}", pendingTasks.size());

                // 按任务组分组
                ConcurrentHashMap<String, List<VideoProductionTask>> groupedTasks = new ConcurrentHashMap<>();
                for (VideoProductionTask task : pendingTasks) {
                    groupedTasks.computeIfAbsent(task.getTaskGroup(), k -> new ArrayList<>()).add(task);
                }

                // 处理每个任务组
                for (List<VideoProductionTask> tasks : groupedTasks.values()) {
                    if (!tasks.isEmpty()) {
                        VideoProductionTask firstTask = tasks.get(0);

                        // 构建VideoTaskGroup
                        VideoTaskGroupModel group = new VideoTaskGroupModel();
                        group.setGroupId(firstTask.getTaskGroup());

                        executorService.submit(() -> {
                            EpisodeProduction production = productionRepository.findByEpisodeId(firstTask.getEpisodeId());
                            if (production != null) {
                                processTaskGroup(production.getProductionId(), firstTask.getEpisodeId(), group);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            log.error("轮询待处理任务失败", e);
        }
    }

    /**
     * 获取待处理任务数量
     */
    public int getPendingTaskCount() {
        List<VideoProductionTask> pendingTasks = taskRepository.findRecoverableTasks(LocalDateTime.now().minusMinutes(10));
        return pendingTasks.size();
    }

    /**
     * 获取可用槽位
     */
    public int getAvailableSlots() {
        return processingSemaphore.availablePermits();
    }

    /**
     * 获取当前任务之前的上一个已完成视频URL（用于连续性）
     */
    private String getPreviousVideoUrl(Long episodeId, int currentPanelIndex, String currentGroupId) {
        String productionPrefix = extractProductionPrefix(currentGroupId);
        // 查找同一剧集、分镜索引更小的已完成任务
        List<VideoProductionTask> completedTasks = taskRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VideoProductionTask>()
                        .eq(VideoProductionTask::getEpisodeId, episodeId)
                        .eq(VideoProductionTask::getStatus, "COMPLETED")
                        .isNotNull(VideoProductionTask::getVideoUrl)
                        .likeRight(VideoProductionTask::getTaskGroup, productionPrefix + GROUP_ID_SEPARATOR)
                        .lt(VideoProductionTask::getPanelIndex, currentPanelIndex)
                        .orderByDesc(VideoProductionTask::getPanelIndex)
                        .last("LIMIT 1")
        );
        if (!completedTasks.isEmpty()) {
            return completedTasks.get(0).getVideoUrl();
        }
        return null;
    }

    private String extractProductionPrefix(String runtimeGroupId) {
        int idx = runtimeGroupId.indexOf(GROUP_ID_SEPARATOR);
        if (idx <= 0) {
            return runtimeGroupId;
        }
        return runtimeGroupId.substring(0, idx);
    }

    /**
     * 从任务组中获取代表任务的融合参考图URL
     * 每个prompt可以有自己的fusedReferenceImageUrl（逐格融合时由前端逐格设置）
     */
    private String getFusedReferenceForTask(VideoTaskGroupModel group) {
        if (group.getPrompts() != null && !group.getPrompts().isEmpty()) {
            // 优先取第一个prompt的融合参考图（同一个组内通常共享或取第一个格子的）
            VideoPromptModel firstPrompt = group.getPrompts().get(0);
            if (firstPrompt.getFusedReferenceImageUrl() != null
                    && !firstPrompt.getFusedReferenceImageUrl().isEmpty()) {
                return firstPrompt.getFusedReferenceImageUrl();
            }
        }
        return null;
    }
}
