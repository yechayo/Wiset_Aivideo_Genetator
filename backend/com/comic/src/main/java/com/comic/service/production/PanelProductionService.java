package com.comic.service.production;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.response.PanelBackgroundResponse;
import com.comic.dto.response.PanelProductionStatusResponse;
import com.comic.dto.response.VideoStatusResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.pipeline.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 单分镜视频生产服务
 * 负责：背景图 → 四宫格漫画（ComicGenerationService）→ 视频
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PanelProductionService {

    private final PanelRepository panelRepository;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final PanelPromptBuilder panelPromptBuilder;
    private final ImageGenerationService imageGenerationService;
    private final VideoGenerationService videoGenerationService;
    private final ApplicationContext applicationContext;
    private final StringRedisTemplate stringRedisTemplate;

    @Lazy
    @Autowired
    private PipelineService pipelineService;

    private PanelProductionService self() {
        return applicationContext.getBean(PanelProductionService.class);
    }

    // ==================== 项目级生产编排 ====================

    /**
     * 启动或恢复项目级串行生产。
     * 事件驱动：每次调用驱动一个步骤，步骤完成后回调此方法继续。
     */
    public void startOrResume(String projectId) {
        String lockKey = "lock:production:" + projectId;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 5, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Production already in progress: projectId={}", projectId);
            return;
        }
        try {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null || !ProjectStatus.PRODUCING.getCode().equals(project.getStatus())) {
                return;
            }
            Panel currentPanel = findNextIncompletePanel(projectId);
            if (currentPanel == null) {
                // All panels video-completed → trigger project completion
                pipelineService.advancePipeline(projectId, "production_completed");
                return;
            }
            drivePanelStep(projectId, currentPanel);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    /**
     * 查找项目中第一个视频未完成的 Panel（按 episode.id, panel.id 排序）
     */
    Panel findNextIncompletePanel(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode episode : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(episode.getId());
            for (Panel panel : panels) {
                if (!isPanelVideoCompleted(panel)) {
                    return panel;
                }
            }
        }
        return null;
    }

    /**
     * 获取 Panel 所属的 projectId
     */
    public String getProjectIdByPanelId(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) return null;
        Episode episode = episodeRepository.selectById(panel.getEpisodeId());
        return episode != null ? episode.getProjectId() : null;
    }

    /**
     * 驱动当前 Panel 的下一步生产步骤
     */
    private void drivePanelStep(String projectId, Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) info = new HashMap<>();
        String bgUrl = getStr(info, "backgroundUrl");
        String bgStatus = getStr(info, "backgroundStatus");
        String comicStatus = getStr(info, "comicStatus");
        String videoStatus = getStr(info, "videoStatus");

        // Step 1: Background
        if (bgUrl == null && !"generating".equals(bgStatus) && !"failed".equals(bgStatus)) {
            log.info("Orchestrator: starting background for panelId={}", panel.getId());
            generateBackgroundByPanelId(panel.getId());
            return;
        }

        // If background generating → just wait
        if (bgUrl == null && "generating".equals(bgStatus)) {
            log.info("Orchestrator: background generating, waiting for panelId={}", panel.getId());
            return;
        }

        // If background failed → pause
        if (bgUrl == null && "failed".equals(bgStatus)) {
            log.info("Orchestrator: background failed, paused for panelId={}", panel.getId());
            return;
        }

        // Step 2: Comic
        if (comicStatus == null && !"generating".equals(comicStatus) && !"failed".equals(comicStatus) && !"pending_review".equals(comicStatus)) {
            try {
                ComicGenerationService comicService = applicationContext.getBean(ComicGenerationService.class);
                log.info("Orchestrator: starting comic for panelId={}", panel.getId());
                comicService.generateComic(panel.getId());
            } catch (Exception e) {
                log.error("Orchestrator: failed to start comic for panelId={}", panel.getId(), e);
            }
            return;
        }

        // If comic generating → wait
        if ("generating".equals(comicStatus)) {
            log.info("Orchestrator: comic generating, waiting for panelId={}", panel.getId());
            return;
        }

        // If comic failed → pause
        if ("failed".equals(comicStatus)) {
            log.info("Orchestrator: comic failed, paused for panelId={}", panel.getId());
            return;
        }

        // Step 3: pending_review → PAUSE (wait for human approve)
        if ("pending_review".equals(comicStatus)) {
            log.info("Orchestrator: awaiting comic approval for panelId={}", panel.getId());
            return;
        }

        // Step 4: Video (comic approved)
        if ("approved".equals(comicStatus) && !"generating".equals(videoStatus) && !"failed".equals(videoStatus) && !"completed".equals(videoStatus)) {
            log.info("Orchestrator: starting video for panelId={}", panel.getId());
            generateVideoByPanelId(panel.getId());
            return;
        }

        // If video generating → wait
        if ("generating".equals(videoStatus)) {
            log.info("Orchestrator: video generating, waiting for panelId={}", panel.getId());
            return;
        }

        // If video failed → pause
        if ("failed".equals(videoStatus)) {
            log.info("Orchestrator: video failed, paused for panelId={}", panel.getId());
            return;
        }

        // Video completed → this shouldn't happen (findNextIncompletePanel should skip)
        log.warn("Orchestrator: panel already completed but was selected, panelId={}", panel.getId());
    }

    private boolean isPanelVideoCompleted(Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) return false;
        return "completed".equals(getStr(info, "videoStatus"));
    }

    // ==================== 生产状态 ====================

    /**
     * 获取单 Panel 完整生产状态
     */
    public PanelProductionStatusResponse getProductionStatus(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        PanelProductionStatusResponse response = new PanelProductionStatusResponse();
        response.setPanelId(panelId);
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) {
            response.setOverallStatus("pending");
            response.setCurrentStage("background");
            return response;
        }
        String bgUrl = getStr(info, "backgroundUrl");
        response.setBackgroundUrl(bgUrl);
        response.setBackgroundStatus(bgUrl != null ? "completed" : "pending");
        String comicUrl = getStr(info, "comicUrl");
        String comicStatus = getStr(info, "comicStatus");
        response.setComicUrl(comicUrl);
        response.setComicStatus(comicStatus != null ? comicStatus : (comicUrl != null ? "approved" : "pending"));
        String videoUrl = getStr(info, "videoUrl");
        String videoStatus = getStr(info, "videoStatus");
        response.setVideoUrl(videoUrl);
        response.setVideoStatus(videoStatus != null ? videoStatus : (videoUrl != null ? "completed" : "pending"));
        response.setOverallStatus(determineOverallStatus(response));
        response.setCurrentStage(determineCurrentStage(response));
        return response;
    }

    /**
     * 批量获取所有 Panel 生产状态
     */
    public List<PanelProductionStatusResponse> getBatchProductionStatus(Long episodeId) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        return panels.stream().map(p -> getProductionStatus(p.getId())).collect(Collectors.toList());
    }

    // ==================== 背景图 ====================

    /**
     * 获取背景图状态
     */
    public PanelBackgroundResponse getBackgroundStatusByPanelId(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        PanelBackgroundResponse response = new PanelBackgroundResponse();
        response.setPanelId(panelId);
        Map<String, Object> info = panel.getPanelInfo();
        String bgUrl = info != null ? getStr(info, "backgroundUrl") : null;
        if (bgUrl != null) {
            response.setBackgroundUrl(bgUrl);
            response.setStatus("completed");
        } else {
            response.setStatus("pending");
        }
        return response;
    }

    /**
     * 生成背景图（异步）
     */
    public void generateBackgroundByPanelId(Long panelId) {
        checkNotGenerating(panelId, "backgroundStatus", "背景图");
        self().doGenerateBackgroundByPanelId(panelId);
    }

    @Async
    public void doGenerateBackgroundByPanelId(Long panelId) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) throw new BusinessException("分镜不存在");
            CharacterPromptManager.VisualStyle style = getProjectStyle(panel);
            String prompt = panelPromptBuilder.buildBackgroundPrompt(style, panel.getPanelInfo());
            String imageUrl = imageGenerationService.generate(prompt, 2848, 1600, "anime");
            Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
            info.put("backgroundUrl", imageUrl);
            info.put("backgroundStatus", "completed");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            log.info("背景图生成完成: panelId={}", panelId);
            // Resume orchestrator after background completes
            String projId = getProjectIdByPanelId(panelId);
            if (projId != null) {
                self().startOrResume(projId);
            }
        } catch (Exception e) {
            log.error("背景图生成失败: panelId={}", panelId, e);
            updatePanelState(panelId, "backgroundStatus", "failed", e.getMessage());
            throw new BusinessException("背景图生成失败: " + e.getMessage());
        }
    }

    // ==================== 视频 ====================

    /**
     * 获取视频状态
     */
    public VideoStatusResponse getVideoStatusByPanelId(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        VideoStatusResponse response = new VideoStatusResponse();
        response.setPanelId(panelId);
        Map<String, Object> info = panel.getPanelInfo();
        String videoUrl = info != null ? getStr(info, "videoUrl") : null;
        String videoStatus = info != null ? getStr(info, "videoStatus") : null;
        String taskId = info != null ? getStr(info, "videoTaskId") : null;
        String errorMsg = info != null ? getStr(info, "errorMessage") : null;
        response.setVideoUrl(videoUrl);
        response.setStatus(videoStatus != null ? videoStatus : (videoUrl != null ? "completed" : "pending"));
        response.setTaskId(taskId);
        response.setErrorMessage(errorMsg);
        return response;
    }

    /**
     * 生成视频（异步）
     */
    public void generateVideoByPanelId(Long panelId) {
        checkNotGenerating(panelId, "videoStatus", "视频");
        self().doGenerateVideoByPanelId(panelId);
    }

    @Async
    public void doGenerateVideoByPanelId(Long panelId) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) throw new BusinessException("分镜不存在");
            Map<String, Object> info = panel.getPanelInfo();
            String comicUrl = getStr(info, "comicUrl");
            String comicStatus = getStr(info, "comicStatus");
            if (!"approved".equals(comicStatus) || comicUrl == null) {
                throw new BusinessException("四宫格漫画未审核通过，请先审核");
            }
            info.put("videoStatus", "generating");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            CharacterPromptManager.VisualStyle style = getProjectStyle(panel);
            String prompt = panelPromptBuilder.buildVideoPrompt(style, panel.getPanelInfo());
            String taskId = videoGenerationService.generateAsync(prompt, 5, "16:9", comicUrl);
            info.put("videoTaskId", taskId);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            self().pollNewVideoTask(panelId, taskId);
            log.info("视频生成已提交: panelId={}, taskId={}", panelId, taskId);
        } catch (Exception e) {
            log.error("视频生成失败: panelId={}", panelId, e);
            updatePanelState(panelId, "videoStatus", "failed", e.getMessage());
            throw new BusinessException("视频生成失败: " + e.getMessage());
        }
    }

    @Async
    public void pollNewVideoTask(Long panelId, String taskId) {
        try {
            for (int i = 0; i < 120; i++) {
                VideoGenerationService.TaskStatus status = videoGenerationService.getTaskStatus(taskId);
                if (status == null) { Thread.sleep(5000); continue; }
                switch (status.getStatus()) {
                    case "completed":
                        String videoUrl = status.getVideoUrl();
                        if (videoUrl == null) videoUrl = videoGenerationService.downloadVideo(status.getTaskId());
                        Panel panel = panelRepository.selectById(panelId);
                        if (panel != null) {
                            Map<String, Object> info = panel.getPanelInfo();
                            info.put("videoUrl", videoUrl);
                            info.put("videoStatus", "completed");
                            info.put("errorMessage", null);
                            panel.setPanelInfo(info);
                            panelRepository.updateById(panel);
                        }
                        log.info("视频生成完成: panelId={}", panelId);
                        // Resume orchestrator after video completes
                        String projId = getProjectIdByPanelId(panelId);
                        if (projId != null) {
                            self().startOrResume(projId);
                        }
                        return;
                    case "failed":
                        updatePanelState(panelId, "videoStatus", "failed", status.getErrorMessage());
                        return;
                    default:
                        Thread.sleep(5000);
                        break;
                }
            }
            updatePanelState(panelId, "videoStatus", "failed", "视频生成超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频任务轮询异常: panelId={}", panelId, e);
        }
    }

    /**
     * 重试失败的视频生成
     */
    public void retryVideoByPanelId(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();
        String videoStatus = getStr(info, "videoStatus");
        if (!"failed".equals(videoStatus)) {
            throw new BusinessException("当前状态不可重试，仅支持重试失败的视频");
        }
        info.put("videoStatus", "pending");
        info.put("errorMessage", null);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
        generateVideoByPanelId(panelId);
    }

    // ==================== 内部辅助方法 ====================

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * 获取项目的视觉风格
     */
    private CharacterPromptManager.VisualStyle getProjectStyle(Panel panel) {
        try {
            Episode episode = episodeRepository.selectById(panel.getEpisodeId());
            if (episode == null) return CharacterPromptManager.VisualStyle.ANIME;
            Project project = projectRepository.findByProjectId(episode.getProjectId());
            if (project == null) return CharacterPromptManager.VisualStyle.ANIME;
            Map<String, Object> info = project.getProjectInfo();
            String styleCode = info != null ? String.valueOf(info.get("visualStyle")) : null;
            return CharacterPromptManager.VisualStyle.fromCode(styleCode);
        } catch (Exception e) {
            log.warn("获取项目视觉风格失败，使用默认ANIME: panelId={}", panel.getId(), e);
            return CharacterPromptManager.VisualStyle.ANIME;
        }
    }

    private void checkNotGenerating(Long panelId, String statusKey, String label) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();
        String status = info != null ? getStr(info, statusKey) : null;
        if ("generating".equals(status)) {
            throw new BusinessException(label + "正在生成中，请稍后");
        }
    }

    private void updatePanelState(Long panelId, String stateKey, String stateValue, String errorMsg) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) return;
        Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
        info.put(stateKey, stateValue);
        if (errorMsg != null) info.put("errorMessage", errorMsg);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
    }

    private String determineOverallStatus(PanelProductionStatusResponse r) {
        if ("completed".equals(r.getVideoStatus())) return "completed";
        if ("failed".equals(r.getVideoStatus()) || "failed".equals(r.getComicStatus()) || "failed".equals(r.getBackgroundStatus())) return "failed";
        if ("generating".equals(r.getVideoStatus()) || "generating".equals(r.getComicStatus()) || "generating".equals(r.getBackgroundStatus())) return "in_progress";
        if (r.getBackgroundUrl() != null || r.getComicUrl() != null) return "in_progress";
        return "pending";
    }

    private String determineCurrentStage(PanelProductionStatusResponse r) {
        if ("completed".equals(r.getVideoStatus())) return "video";
        if ("generating".equals(r.getVideoStatus())) return "video";
        if ("approved".equals(r.getComicStatus())) return "video";
        if ("generating".equals(r.getComicStatus())) return "comic";
        if ("pending".equals(r.getComicStatus()) && r.getBackgroundUrl() != null) return "comic";
        if ("generating".equals(r.getBackgroundStatus())) return "background";
        return "background";
    }
}