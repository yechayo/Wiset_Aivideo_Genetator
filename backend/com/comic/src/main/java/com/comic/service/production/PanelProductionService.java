package com.comic.service.production;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.video.VideoGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.response.VideoStatusResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.oss.OssService;
import com.comic.service.panel.GridImageService;
import com.comic.service.pipeline.ProjectStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单分镜视频生产服务
 * 负责：九宫格生成（GridImageService）→ 审核确认 → 融合参考图 → 视频
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PanelProductionService {

    private final PanelRepository panelRepository;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final PanelPromptBuilder panelPromptBuilder;
    private final VideoGenerationService videoGenerationService;
    private final OssService ossService;
    private final ApplicationContext applicationContext;

    @Lazy
    @Autowired
    private ProjectStatusBroadcaster broadcaster;

    @Lazy
    @Autowired
    private GridImageService gridImageService;

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
     * 获取单 Panel 完整生产状态（grid-based）
     */
    public Map<String, Object> getProductionStatus(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在");
        Map<String, Object> panelInfo = panel.getPanelInfo();
        Map<String, Object> status = new HashMap<>();
        status.put("panelId", panel.getId());
        status.put("gridStatus", panelInfo.getOrDefault("gridStatus", "pending"));
        status.put("gridImages", panelInfo.getOrDefault("gridImages", new ArrayList<>()));
        status.put("fusionImageUrl", panelInfo.get("fusionImageUrl"));
        status.put("shots", panelInfo.get("shots"));
        status.put("totalShots", panelInfo.getOrDefault("totalShots", 0));
        status.put("totalDuration", panelInfo.getOrDefault("totalDuration", 0));
        status.put("gridPageCount", panelInfo.getOrDefault("gridPageCount", 0));
        status.put("gridRejectionFeedback", panelInfo.get("gridRejectionFeedback"));
        status.put("videoStatus", panelInfo.getOrDefault("videoStatus", "pending"));
        status.put("videoUrl", panelInfo.get("videoUrl"));
        status.put("videoTaskId", panelInfo.get("videoTaskId"));
        status.put("offPeak", panelInfo.getOrDefault("offPeak", false));
        return status;
    }

    /**
     * 批量获取所有 Panel 生产状态
     */
    public List<Map<String, Object>> getBatchProductionStatus(Long episodeId) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        return panels.stream().map(p -> getProductionStatus(p.getId())).collect(Collectors.toList());
    }

    // ==================== 九宫格审核 ====================

    public void approveGrid(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在");
        Map<String, Object> info = panel.getPanelInfo();
        info.put("gridStatus", "approved");
        info.put("gridRejectionFeedback", null);
        updatePanelInfo(panel, info);
    }

    public void rejectGrid(Long panelId, String reason) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在");
        Map<String, Object> info = panel.getPanelInfo();
        info.put("gridStatus", "rejected");
        info.put("gridRejectionFeedback", reason);
        updatePanelInfo(panel, info);
    }

    public void regenerateGrid(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在");
        Map<String, Object> info = panel.getPanelInfo();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
        if (shots != null) {
            for (Map<String, Object> shot : shots) shot.remove("splitImageUrl");
        }
        info.put("gridImages", new ArrayList<>());
        info.put("gridStatus", "generating");
        info.put("fusionImageUrl", null);
        info.put("errorMessage", null);
        updatePanelInfo(panel, info);
        gridImageService.generateGridsForPanel(panelId);
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
     * 生成视频（异步）- 使用融合参考图 + 多镜头提示词
     */
    public void generateVideoByPanelId(Long panelId) {
        generateVideoByPanelId(panelId, false);
    }

    public void generateVideoByPanelId(Long panelId, boolean offPeak) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();
        String gridStatus = info != null ? getStr(info, "gridStatus") : null;
        if (!"approved".equals(gridStatus)) {
            throw new BusinessException("九宫格未审核通过，请先审核");
        }
        self().doGenerateVideoByPanelId(panelId, offPeak);
    }

    @Async
    public void doGenerateVideoByPanelId(Long panelId, boolean offPeak) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) throw new BusinessException("分镜不存在");
            Map<String, Object> info = panel.getPanelInfo();
            String fusionImageUrl = getStr(info, "fusionImageUrl");
            if (fusionImageUrl == null) {
                throw new BusinessException("融合参考图不存在，请先生成九宫格");
            }

            info.put("videoStatus", "generating");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);

            // 构建多镜头提示词
            String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");
            String prompt = panelPromptBuilder.buildMultiShotPrompt(visualStyle, info);

            // 从 shots 计算总时长
            int totalDuration = 0;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
            if (shots != null) {
                for (Map<String, Object> shot : shots) {
                    Object dur = shot.get("duration");
                    if (dur instanceof Number) totalDuration += ((Number) dur).intValue();
                }
            }
            if (totalDuration <= 0) totalDuration = 5;

            String taskId = videoGenerationService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak);
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

    private void updatePanelInfo(Panel panel, Map<String, Object> info) {
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
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
}
