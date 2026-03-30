package com.comic.service.production;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.response.PanelBackgroundResponse;
import com.comic.dto.response.PanelProductionStatusResponse;
import com.comic.dto.response.VideoStatusResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.oss.OssService;
import com.comic.service.pipeline.ProjectStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final OssService ossService;
    private final ApplicationContext applicationContext;

    @Lazy
    @Autowired
    private ProjectStatusBroadcaster broadcaster;

    private PanelProductionService self() {
        return applicationContext.getBean(PanelProductionService.class);
    }

    // ==================== 项目级生产编排 ====================

    /**
     * 获取 Panel 所属的 projectId
     */
    public String getProjectIdByPanelId(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) return null;
        Episode episode = episodeRepository.selectById(panel.getEpisodeId());
        return episode != null ? episode.getProjectId() : null;
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

        // 视频元数据
        String videoTaskId = getStr(info, "videoTaskId");
        response.setVideoTaskId(videoTaskId);
        Boolean offPeak = info.containsKey("offPeak") ? (Boolean) info.get("offPeak") : null;
        response.setOffPeak(offPeak);
        Integer videoDuration = getInt(info, "videoDuration");
        if (videoDuration == null && info.containsKey("duration")) {
            videoDuration = info.get("duration") instanceof Number ? ((Number) info.get("duration")).intValue() : null;
        }
        response.setVideoDuration(videoDuration);

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
            String projId = getProjectIdByPanelId(panelId);
            if (projId != null) {
                broadcaster.broadcast(projId, "PRODUCING", "PRODUCING");
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
        generateVideoByPanelId(panelId, false);
    }

    public void generateVideoByPanelId(Long panelId, boolean offPeak) {
        checkNotGenerating(panelId, "videoStatus", "视频");
        self().doGenerateVideoByPanelId(panelId, offPeak);
    }

    @Async
    public void doGenerateVideoByPanelId(Long panelId, boolean offPeak) {
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
            int panelDuration = info.containsKey("duration") ? ((Number) info.get("duration")).intValue() : 5;
            String taskId = videoGenerationService.generateAsync(prompt, panelDuration, "16:9", comicUrl, offPeak);
            info.put("videoTaskId", taskId);
            info.put("offPeak", offPeak);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            self().pollNewVideoTask(panelId, taskId, offPeak);
            log.info("视频生成已提交: panelId={}, taskId={}, offPeak={}", panelId, taskId, offPeak);
        } catch (Exception e) {
            log.error("视频生成失败: panelId={}", panelId, e);
            updatePanelState(panelId, "videoStatus", "failed", e.getMessage());
            throw new BusinessException("视频生成失败: " + e.getMessage());
        }
    }

    @Async
    public void pollNewVideoTask(Long panelId, String taskId, boolean offPeak) {
        // 错峰模式：48小时内生成，轮询间隔60秒，最多2880次(48h)；即时模式：5秒间隔，最多720次(1h)
        int intervalSeconds = offPeak ? 60 : 5;
        int maxPolls = offPeak ? 2880 : 120;
        try {
            for (int i = 0; i < maxPolls; i++) {
                VideoGenerationService.TaskStatus status = videoGenerationService.getTaskStatus(taskId);
                if (status == null) { Thread.sleep(intervalSeconds * 1000L); continue; }
                switch (status.getStatus()) {
                    case "completed":
                        String videoUrl = status.getVideoUrl();
                        if (videoUrl == null) videoUrl = videoGenerationService.downloadVideo(status.getTaskId());
                        // 将 Vidu 返回的临时 URL 上传到阿里云 OSS，获得永久 URL
                        boolean videoUrlPermanent = false;
                        try {
                            String ossVideoUrl = ossService.uploadVideoFromUrl(videoUrl, null);
                            log.info("视频已上传到OSS: panelId={}, 原URL={}, OSS URL={}", panelId, videoUrl, ossVideoUrl);
                            videoUrl = ossVideoUrl;
                            videoUrlPermanent = true;
                        } catch (Exception e) {
                            log.error("视频上传OSS失败，仍使用临时URL: panelId={}, url={}", panelId, videoUrl, e);
                            // 上传失败仍使用临时 URL，标记为非永久，后续可重试
                        }
                        Panel panel = panelRepository.selectById(panelId);
                        if (panel != null) {
                            Map<String, Object> info = panel.getPanelInfo();
                            info.put("videoUrl", videoUrl);
                            info.put("videoUrlPermanent", videoUrlPermanent);
                            info.put("videoStatus", "completed");
                            info.put("errorMessage", null);
                            panel.setPanelInfo(info);
                            panelRepository.updateById(panel);
                        }
                        log.info("视频生成完成: panelId={}", panelId);
                        String projId = getProjectIdByPanelId(panelId);
                        if (projId != null) {
                            broadcaster.broadcast(projId, "PRODUCING", "PRODUCING");
                        }
                        return;
                    case "failed":
                        updatePanelState(panelId, "videoStatus", "failed", status.getErrorMessage());
                        return;
                    default:
                        Thread.sleep(intervalSeconds * 1000L);
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

    private Integer getInt(Map<String, Object> info, String key) {
        Object v = info.get(key);
        if (v == null) return null;
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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