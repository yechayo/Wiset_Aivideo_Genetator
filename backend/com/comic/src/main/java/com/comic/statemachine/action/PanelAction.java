package com.comic.statemachine.action;

import com.comic.dto.model.VideoSegmentModel;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.service.panel.PanelGenerationService;
import com.comic.service.production.PanelProductionService;
import com.comic.service.production.ComicGenerationService;
import com.comic.service.production.VideoCompositionService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 分镜相关的 Action
 * 整合完整生产流程：分镜文本 → 背景图 → 融合图 → 视频
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PanelAction {

    private final PanelGenerationService panelGenerationService;
    private final PanelProductionService panelProductionService;
    private final ComicGenerationService comicGenerationService;
    private final VideoCompositionService videoCompositionService;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;
    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * 开始完整的生产流程（异步）
     * 流程：分镜文本生成 → 背景图生成 → 融合图生成 → 视频生成
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID，如果为null则生成所有剧集
     */
    public void startFullProduction(String projectId, Long episodeId) {
        log.info("Action: Start full panel production for project={}, episodeId={}", projectId, episodeId);
        stateMachineService.persistState(projectId, ProjectState.PANEL_GENERATING);
        eventPublisher.publishTaskStart(projectId, "panel_full_production");

        CompletableFuture.runAsync(() -> {
            try {
                if (episodeId != null) {
                    // 生成指定剧集的完整流程
                    produceSingleEpisode(projectId, episodeId);
                } else {
                    // 获取项目的所有剧集，按顺序生成
                    List<Episode> episodes = episodeRepository.findByProjectId(projectId);
                    for (Episode episode : episodes) {
                        try {
                            produceSingleEpisode(projectId, episode.getId());
                        } catch (Exception e) {
                            log.warn("Panel production failed for episodeId={}: {}", episode.getId(), e.getMessage());
                            // 单集失败不影响其他剧集
                        }
                    }
                }
                // 当前剧集生产完成，发送事件进入审核
                stateMachineService.sendEvent(projectId, ProjectEventType._PANEL_DONE);
                eventPublisher.publishTaskComplete(projectId, "panel_full_production", null);
            } catch (Exception e) {
                log.error("Panel production failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "分镜生产失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.PANEL_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.PANEL_GENERATING_FAILED);
            }
        });
    }

    /**
     * 单集完整生产流程
     */
    private void produceSingleEpisode(String projectId, Long episodeId) {
        log.info("Starting production for episodeId={}", episodeId);

        // Step 1: 生成分镜文本描述
        log.info("Step 1: Generate panel text for episodeId={}", episodeId);
        String panelJson = panelGenerationService.generatePanels(episodeId);
        eventPublisher.publishProgress(projectId, 25, "分镜文本生成完成");

        // Step 2: 获取所有分镜
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        log.info("Found {} panels for episodeId={}", panels.size(), episodeId);

        int totalPanels = panels.size();
        int completedPanels = 0;

        // Step 3-5: 对每个分镜依次生成 背景图 → 融合图 → 视频
        for (Panel panel : panels) {
            Long panelId = panel.getId();
            log.info("Processing panelId={}", panelId);

            // Step 3: 生成背景图
            try {
                panelProductionService.generateBackgroundByPanelId(panelId);
                log.info("Background generated for panelId={}", panelId);
            } catch (Exception e) {
                log.warn("Background generation failed for panelId={}: {}", panelId, e.getMessage());
            }

            // Step 4: 生成融合图（四宫格漫画）
            try {
                comicGenerationService.generateComic(panelId);
                log.info("Comic generated for panelId={}", panelId);
            } catch (Exception e) {
                log.warn("Comic generation failed for panelId={}: {}", panelId, e.getMessage());
            }

            // Step 5: 自动审核融合图，然后生成视频
            try {
                // 自动审核通过
                comicGenerationService.approveComic(panelId);

                // 生成视频
                panelProductionService.generateVideoByPanelId(panelId);
                log.info("Video generated for panelId={}", panelId);
            } catch (Exception e) {
                log.warn("Video generation failed for panelId={}: {}", panelId, e.getMessage());
            }

            // 更新进度
            completedPanels++;
            int progress = (int) (25 + (completedPanels * 75.0 / totalPanels));
            eventPublisher.publishProgress(projectId, progress,
                    String.format("生产进度: %d/%d 分镜", completedPanels, totalPanels));
        }

        log.info("Production completed for episodeId={}, panels={}", episodeId, totalPanels);
    }

    /**
     * 单集生产完成
     */
    public void onEpisodeProductionComplete(String projectId) {
        log.info("Action: Episode production completed for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.PANEL_REVIEW);
        eventPublisher.publishProgress(projectId, 100, "单集分镜生产完成，请审核");
    }

    /**
     * 修改并重新生产（重新生成指定剧集的分镜和生产流程）
     */
    public void reviseAndReproduce(String projectId, Long episodeId, String feedback) {
        log.info("Action: Revise and reproduce for project={}, episodeId={}", projectId, episodeId);
        eventPublisher.publishTaskStart(projectId, "panel_revision");

        CompletableFuture.runAsync(() -> {
            try {
                // 使用反馈重新生成分镜文本
                String newPanelJson = panelGenerationService.revisePanels(episodeId, feedback);

                // 重新执行完整生产流程
                produceSingleEpisode(projectId, episodeId);

                stateMachineService.sendEvent(projectId, ProjectEventType._PANEL_DONE);
                eventPublisher.publishTaskComplete(projectId, "panel_revision", null);
            } catch (Exception e) {
                log.error("Panel revision failed: projectId={}, episodeId={}", projectId, episodeId, e);
                eventPublisher.publishFailure(projectId, "分镜修改失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.PANEL_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.PANEL_GENERATING_FAILED);
            }
        });
    }

    /**
     * 确认当前分镜，继续下一集
     */
    public void confirmAndContinue(String projectId, Long episodeId) {
        log.info("Action: Confirm panel and continue for project={}, episodeId={}", projectId, episodeId);
        try {
            // 标记当前剧集已确认
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode != null) {
                setPanelStatus(episode, "PANEL_CONFIRMED");
                episodeRepository.updateById(episode);
            }

            // 检查是否还有未确认的剧集
            List<Episode> allEpisodes = episodeRepository.findByProjectId(projectId);
            boolean allConfirmed = true;
            Episode nextEpisode = null;

            for (Episode ep : allEpisodes) {
                if (!"PANEL_CONFIRMED".equals(getPanelStatus(ep))) {
                    allConfirmed = false;
                    if (nextEpisode == null) {
                        nextEpisode = ep;
                    }
                }
            }

            if (allConfirmed) {
                // 所有剧集已确认，进入视频拼接阶段
                stateMachineService.persistState(projectId, ProjectState.PANEL_CONFIRMED);
                log.info("All episodes confirmed, project={} ready for video assembly", projectId);
            } else {
                // 还有未确认的剧集，保持当前状态，等待用户继续
                log.info("Episode confirmed, {} episodes remaining for project={}",
                        allEpisodes.stream().filter(e -> !"PANEL_CONFIRMED".equals(getPanelStatus(e))).count(), projectId);
            }

            eventPublisher.publishTaskComplete(projectId, "panel_confirmation", null);
        } catch (Exception e) {
            log.error("Panel confirmation failed: projectId={}, episodeId={}", projectId, episodeId, e);
            eventPublisher.publishFailure(projectId, "分镜确认失败: " + e.getMessage());
        }
    }

    /**
     * 开始视频拼接（所有分镜确认后）
     */
    public void startVideoAssembly(String projectId) {
        log.info("Action: Start video assembly for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.VIDEO_ASSEMBLING);
        eventPublisher.publishTaskStart(projectId, "video_assembly");

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 获取所有剧集
                List<Episode> episodes = episodeRepository.findByProjectId(projectId);
                log.info("Found {} episodes for project={}", episodes.size(), projectId);

                // 2. 按剧集顺序拼接视频
                for (Episode episode : episodes) {
                    assembleEpisodeVideo(projectId, episode.getId(), episodes.size());
                }

                log.info("Video assembly completed for project={}", projectId);
                stateMachineService.sendEvent(projectId, ProjectEventType._VIDEO_ASSEMBLY_DONE);
                eventPublisher.publishTaskComplete(projectId, "video_assembly", null);
            } catch (Exception e) {
                log.error("Video assembly failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "视频拼接失败: " + e.getMessage());
                // 回退到 PANEL_CONFIRMED，允许用户修复后重试
                stateMachineService.persistState(projectId, ProjectState.PANEL_CONFIRMED);
            }
        });
    }

    /**
     * 拼接单集视频
     */
    private void assembleEpisodeVideo(String projectId, Long episodeId, int totalEpisodes) {
        try {
            // 获取该集的所有分镜，按顺序排列
            List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
            if (panels.isEmpty()) {
                throw new RuntimeException("第" + episodeId + "集没有分镜数据");
            }

            Integer episodeNum = getEpisodeNum(episodeRepository.selectById(episodeId));

            // 收集所有视频片段
            List<VideoSegmentModel> videoSegments = new ArrayList<>();
            int segmentIndex = 0;

            for (Panel panel : panels) {
                Map<String, Object> info = panel.getPanelInfo();
                if (info == null) continue;

                String videoUrl = info.get("videoUrl") != null ? info.get("videoUrl").toString() : null;
                String videoStatus = info.get("videoStatus") != null ? info.get("videoStatus").toString() : null;

                if ("completed".equals(videoStatus) && videoUrl != null && !videoUrl.isEmpty()) {
                    // 获取分镜时长，默认5秒
                    Number durationNum = info.get("duration") instanceof Number ? (Number) info.get("duration") : null;
                    int duration = durationNum != null ? durationNum.intValue() : 5;

                    VideoSegmentModel segment = new VideoSegmentModel();
                    segment.setUrl(videoUrl);
                    segment.setDuration(duration);
                    segment.setPanelIndex(segmentIndex++);
                    videoSegments.add(segment);
                }
            }

            if (videoSegments.isEmpty()) {
                throw new RuntimeException("第" + episodeNum + "集没有已完成的视频片段");
            }

            log.info("拼接第{}集: {} 个视频片段", episodeNum, videoSegments.size());

            // 调用视频合成服务
            String finalVideoUrl = videoCompositionService.composeVideo(videoSegments, null);

            // 保存最终视频URL到episode
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode != null) {
                Map<String, Object> epInfo = episode.getEpisodeInfo();
                if (epInfo == null) {
                    epInfo = new java.util.HashMap<>();
                    episode.setEpisodeInfo(epInfo);
                }
                epInfo.put("finalVideoUrl", finalVideoUrl);
                epInfo.put("videoAssemblyStatus", "completed");
                episodeRepository.updateById(episode);
            }

            log.info("第{}集视频拼接完成: {}", episodeNum, finalVideoUrl);

        } catch (Exception e) {
            log.error("Episode video assembly failed: episodeId={}", episodeId, e);
            Integer episodeNum = getEpisodeNum(episodeRepository.selectById(episodeId));
            throw new RuntimeException("第" + episodeNum + "集视频拼接失败: " + e.getMessage());
        }
    }

    /**
     * 获取集数编号
     */
    private Integer getEpisodeNum(Episode episode) {
        if (episode == null) return 0;
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info != null) {
            Object num = info.get("episodeNum");
            if (num instanceof Number) {
                return ((Number) num).intValue();
            }
        }
        return Math.toIntExact(episode.getId());
    }

    /**
     * 视频拼接完成
     */
    public void onVideoAssemblyComplete(String projectId) {
        log.info("Action: Video assembly completed for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.COMPLETED);
        eventPublisher.publishProgress(projectId, 100, "项目完成！");
    }

    /**
     * 重试失败的分镜
     */
    public void retry(String projectId, Long episodeId) {
        log.info("Action: Retry panel production for project={}, episodeId={}", projectId, episodeId);
        eventPublisher.publishTaskStart(projectId, "panel_retry");

        CompletableFuture.runAsync(() -> {
            try {
                produceSingleEpisode(projectId, episodeId);
                stateMachineService.sendEvent(projectId, ProjectEventType._PANEL_DONE);
                eventPublisher.publishTaskComplete(projectId, "panel_retry", null);
            } catch (Exception e) {
                log.error("Panel retry failed: projectId={}, episodeId={}", projectId, episodeId, e);
                eventPublisher.publishFailure(projectId, "分镜重试失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.PANEL_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.PANEL_GENERATING_FAILED);
            }
        });
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> epInfo(Episode episode) {
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info == null) {
            info = new java.util.HashMap<>();
            episode.setEpisodeInfo(info);
        }
        return info;
    }

    private String getPanelStatus(Episode episode) {
        Object status = epInfo(episode).get("panelStatus");
        return status != null ? status.toString() : null;
    }

    private void setPanelStatus(Episode episode, String status) {
        epInfo(episode).put("panelStatus", status);
    }
}
