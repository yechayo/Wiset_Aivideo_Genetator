package com.comic.service.production;

import com.comic.dto.response.ProductionStatusResponse;
import com.comic.dto.model.SceneAnalysisResultModel;
import com.comic.dto.model.VideoSegmentModel;
import com.comic.dto.model.VideoTaskGroupModel;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Project;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.repository.VideoProductionTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 单集视频生产主流程编排服务
 * 负责协调整个视频生产流程
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EpisodeProductionService {

    private final EpisodeRepository episodeRepository;
    private final EpisodeProductionRepository productionRepository;
    private final VideoProductionTaskRepository videoTaskRepository;
    private final ProjectRepository projectRepository;
    private final SceneAnalysisService sceneAnalysisService;
    private final SceneGridGenService sceneGridGenService;
    private final VideoPromptBuilderService videoPromptBuilderService;
    private final VideoProductionQueueService videoQueueService;
    private final SubtitleService subtitleService;
    private final VideoCompositionService videoCompositionService;
    private final ObjectMapper objectMapper;

    /**
     * 启动单集视频生产
     *
     * @param episodeId 剧集ID
     * @return 生产任务ID
     */
    @Transactional
    public String startProduction(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("剧集不存在: " + episodeId);
        }

        if (episode.getStoryboardJson() == null || episode.getStoryboardJson().isEmpty()) {
            throw new IllegalArgumentException("剧集分镜数据为空，请先生成分镜");
        }

        // 检查是否已有生产任务
        EpisodeProduction existing = productionRepository.findByEpisodeId(episodeId);
        if (existing != null && !"COMPLETED".equals(existing.getStatus()) && !"FAILED".equals(existing.getStatus())) {
            throw new IllegalStateException("剧集已有进行中的生产任务: " + existing.getProductionId());
        }

        // 创建生产记录
        EpisodeProduction production = new EpisodeProduction();
        production.setProductionId("PROD-" + UUID.randomUUID().toString().substring(0, 8));
        production.setEpisodeId(episodeId);
        production.setStatus("ANALYZING");
        production.setCurrentStage("SCENE_ANALYSIS");
        production.setProgressPercent(5);
        production.setProgressMessage("开始场景分析...");
        production.setRetryCount(0);
        production.setStartedAt(LocalDateTime.now());

        productionRepository.insert(production);

        // 更新Episode状态
        episode.setProductionStatus("IN_PROGRESS");
        episode.setProductionProgress(5);
        episodeRepository.updateById(episode);

        // 异步执行生产流程
        executeProductionFlow(episode, production);

        log.info("视频生产已启动: episodeId={}, productionId={}", episodeId, production.getProductionId());
        return production.getProductionId();
    }

    /**
     * 执行生产流程（异步）
     */
    @Async
    public void executeProductionFlow(Episode episode, EpisodeProduction production) {
        try {
            String projectId = episode.getProjectId();

            // 阶段1: 场景分析
            updateProductionStatus(production, "ANALYZING", "SCENE_ANALYSIS", 10, "正在分析场景...");
            SceneAnalysisResultModel sceneAnalysis = sceneAnalysisService.analyzeScenes(episode.getId());
            production.setSceneAnalysisJson(objectMapper.writeValueAsString(sceneAnalysis));
            production.setTotalPanels(sceneAnalysis.getTotalPanelCount());
            productionRepository.updateById(production);

            // 阶段2: 场景九宫格生成
            updateProductionStatus(production, "GRID_GENERATING", "GRID_GENERATION", 15, "正在生成场景参考图...");
            String sceneGridUrl = generateFirstSceneGrid(projectId, episode.getId(), sceneAnalysis);
            production.setSceneGridUrl(sceneGridUrl);
            productionRepository.updateById(production);

            // 阶段3: 视频提示词构建
            updateProductionStatus(production, "BUILDING_PROMPTS", "PROMPT_BUILDING", 20, "正在构建视频提示词...");
            Project project = projectRepository.findByProjectId(projectId);
            List<VideoTaskGroupModel> taskGroups = videoPromptBuilderService.buildPromptsForPanels(
                    episode.getStoryboardJson(),
                    sceneAnalysis.getSceneGroups(),
                    project.getVisualStyle()
            );
            production.setTotalVideoGroups(taskGroups.size());
            productionRepository.updateById(production);

            // 阶段4: 视频生成
            updateProductionStatus(production, "GENERATING", "VIDEO_GENERATION", 25, "正在生成视频...");
            videoQueueService.submitVideoTasks(
                    production.getProductionId(),
                    episode.getId(),
                    taskGroups
            );

            // 等待视频生成完成（通过轮询检查）
            waitForVideoGeneration(production);

            // 阶段5: 字幕生成
            updateProductionStatus(production, "GENERATING_SUBS", "SUBTITLE_GENERATION", 90, "正在生成字幕...");
            String subtitleUrl = subtitleService.generateSubtitles(episode.getStoryboardJson(), getVideoSegments(episode.getId()));
            production.setSubtitleUrl(subtitleUrl);
            productionRepository.updateById(production);

            // 阶段6: 视频合成
            updateProductionStatus(production, "COMPOSING", "VIDEO_COMPOSITION", 95, "正在合成最终视频...");
            String finalVideoUrl = videoCompositionService.composeVideo(
                    getVideoSegments(episode.getId()),
                    subtitleUrl
            );
            production.setFinalVideoUrl(finalVideoUrl);
            productionRepository.updateById(production);

            // 完成
            updateProductionStatus(production, "COMPLETED", "COMPLETED", 100, "视频生产完成！");
            production.setCompletedAt(LocalDateTime.now());
            productionRepository.updateById(production);

            // 更新Episode
            episode.setProductionStatus("COMPLETED");
            episode.setProductionProgress(100);
            episode.setFinalVideoUrl(finalVideoUrl);
            episodeRepository.updateById(episode);

            log.info("视频生产完成: productionId={}, url={}", production.getProductionId(), finalVideoUrl);

        } catch (Exception e) {
            log.error("视频生产失败: productionId={}", production.getProductionId(), e);

            // 标记失败
            production.setStatus("FAILED");
            production.setProgressPercent(0);
            production.setErrorMessage(e.getMessage());
            production.setCompletedAt(LocalDateTime.now());
            production.setRetryCount(production.getRetryCount() + 1);
            productionRepository.updateById(production);

            // 更新Episode
            episode.setProductionStatus("FAILED");
            episodeRepository.updateById(episode);
        }
    }

    /**
     * 等待视频生成完成
     */
    private void waitForVideoGeneration(EpisodeProduction production) throws InterruptedException {
        int maxWait = 60; // 最多等待60分钟
        int elapsed = 0;

        while (elapsed < maxWait) {
            EpisodeProduction latest = productionRepository.findByProductionId(production.getProductionId());
            int completed = latest.getCompletedPanels();

            if (completed >= latest.getTotalPanels()) {
                return;
            }

            // 更新进度
            int progress = 25 + (int) ((completed * 60) / latest.getTotalPanels());
            updateProductionStatus(production, "GENERATING", "VIDEO_GENERATION",
                    Math.min(progress, 85),
                    String.format("正在生成视频... %d/%d", completed, latest.getTotalPanels()));

            Thread.sleep(60000); // 每分钟检查一次
            elapsed++;
        }

        throw new RuntimeException("视频生成超时");
    }

    /**
     * 为第一个场景生成九宫格参考图
     */
    private String generateFirstSceneGrid(String projectId, Long episodeId, SceneAnalysisResultModel sceneAnalysis) {
        if (sceneAnalysis.getSceneGroups() == null || sceneAnalysis.getSceneGroups().isEmpty()) {
            return null;
        }

        return sceneGridGenService.generateSceneGrid(episodeId, sceneAnalysis.getSceneGroups().get(0));
    }

    /**
     * 获取视频片段列表
     */
    private List<VideoSegmentModel> getVideoSegments(Long episodeId) {
        List<com.comic.entity.VideoProductionTask> tasks = videoTaskRepository.findByEpisodeId(episodeId);

        return tasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getVideoUrl() != null)
                .map(t -> new VideoSegmentModel(
                        t.getVideoUrl(),
                        t.getTargetDuration().floatValue(),
                        t.getPanelIndex()
                ))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 更新生产状态
     */
    private void updateProductionStatus(EpisodeProduction production, String status, String stage, int progress, String message) {
        production.setStatus(status);
        production.setCurrentStage(stage);
        production.setProgressPercent(progress);
        production.setProgressMessage(message);
        production.setUpdatedAt(LocalDateTime.now());
        productionRepository.updateById(production);

        // 同步更新Episode
        Episode episode = episodeRepository.selectById(production.getEpisodeId());
        if (episode != null) {
            episode.setProductionProgress(progress);
            episodeRepository.updateById(episode);
        }
    }

    /**
     * 获取生产状态（供前端轮询）
     */
    public ProductionStatusResponse getProductionStatus(Long episodeId) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            ProductionStatusResponse dto = new ProductionStatusResponse();
            dto.setStatus("NOT_STARTED");
            dto.setProgressPercent(0);
            return dto;
        }

        ProductionStatusResponse dto = new ProductionStatusResponse();
        dto.setProductionId(production.getProductionId());
        dto.setEpisodeId(episodeId);
        dto.setStatus(production.getStatus());
        dto.setCurrentStage(production.getCurrentStage());
        dto.setProgressPercent(production.getProgressPercent());
        dto.setProgressMessage(production.getProgressMessage());
        dto.setTotalPanels(production.getTotalPanels());
        dto.setCompletedPanels(production.getCompletedPanels());
        dto.setSceneGridUrl(production.getSceneGridUrl());
        dto.setFinalVideoUrl(production.getFinalVideoUrl());
        dto.setErrorMessage(production.getErrorMessage());

        return dto;
    }

    /**
     * 重试失败的生产
     */
    @Transactional
    public void retryProduction(Long episodeId) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            throw new IllegalArgumentException("生产任务不存在");
        }

        if (!"FAILED".equals(production.getStatus())) {
            throw new IllegalStateException("只能重试失败的生产任务");
        }

        if (production.getRetryCount() >= 3) {
            throw new IllegalStateException("已达到最大重试次数");
        }

        // 重置状态并重新开始
        Episode episode = episodeRepository.selectById(episodeId);
        executeProductionFlow(episode, production);
    }

    /**
     * 为项目启动生产（从PipelineService调用）
     */
    public void startProductionForProject(String projectId) {
        // 查找项目的第一个待生产剧集
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode episode : episodes) {
            if ("DONE".equals(episode.getStatus()) && "NOT_STARTED".equals(episode.getProductionStatus())) {
                startProduction(episode.getId());
                return;
            }
        }

        throw new IllegalStateException("没有找到可以生产的剧集");
    }
}
