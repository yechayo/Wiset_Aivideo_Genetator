package com.comic.service.production;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.ComicCommentaryPanelPromptBuilder;
import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.text.DeepSeekTextService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.ai.video.ViduVideoService;
import com.comic.config.AiServiceConfiguration;
import com.comic.constant.ProjectInfoKeys;
import com.comic.exception.BusinessException;
import com.comic.constant.CharacterInfoKeys;
import com.comic.dto.response.VideoStatusResponse;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.oss.OssService;
import com.comic.service.panel.GridImageService;
import com.comic.util.ProjectProductionMode;
import com.comic.service.redis.ProgressService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单分镜视频生产服务
 * 负责：九宫格生成（GridImageService）→ 审核确认 → 融合参考图 → 视频
 * 负责：分集剧本 + 分镜生成（原 StoryboardService）
 */
@Service
@Slf4j
public class PanelProductionService {

    private final PanelRepository panelRepository;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final CharacterRepository characterRepository;
    private final PanelPromptBuilder panelPromptBuilder;
    private final ComicCommentaryPanelPromptBuilder comicCommentaryPanelPromptBuilder;
    private final AiServiceConfiguration aiServiceConfig;
    private final VideoGenerationService videoGenerationService;
    private final ViduVideoService viduVideoService;
    private final OssService ossService;
    private final ApplicationContext applicationContext;
    private final DeepSeekTextService deepSeekTextService;
    private final ProgressService progressService;
    private final StateChangeEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;

    @Lazy
    @Autowired
    private GridImageService gridImageService;

    @Autowired
    private NarrationAllocator narrationAllocator;

    @Autowired
    public PanelProductionService(PanelRepository panelRepository,
                                   EpisodeRepository episodeRepository,
                                   ProjectRepository projectRepository,
                                   CharacterRepository characterRepository,
                                   PanelPromptBuilder panelPromptBuilder,
                                   ComicCommentaryPanelPromptBuilder comicCommentaryPanelPromptBuilder,
                                   AiServiceConfiguration aiServiceConfig,
                                   VideoGenerationService videoGenerationService,
                                   ViduVideoService viduVideoService,
                                   OssService ossService,
                                   ApplicationContext applicationContext,
                                   DeepSeekTextService deepSeekTextService,
                                   ProgressService progressService,
                                   StateChangeEventPublisher eventPublisher,
                                   PlatformTransactionManager transactionManager) {
        this.panelRepository = panelRepository;
        this.episodeRepository = episodeRepository;
        this.projectRepository = projectRepository;
        this.characterRepository = characterRepository;
        this.panelPromptBuilder = panelPromptBuilder;
        this.comicCommentaryPanelPromptBuilder = comicCommentaryPanelPromptBuilder;
        this.aiServiceConfig = aiServiceConfig;
        this.videoGenerationService = videoGenerationService;
        this.viduVideoService = viduVideoService;
        this.ossService = ossService;
        this.applicationContext = applicationContext;
        this.deepSeekTextService = deepSeekTextService;
        this.progressService = progressService;
        this.eventPublisher = eventPublisher;
        this.transactionManager = transactionManager;
    }

    private PanelProductionService self() {
        return applicationContext.getBean(PanelProductionService.class);
    }

    // ==================== Provider 分发辅助 ====================

    public String getImageProvider(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null || project.getProjectInfo() == null) return "seedream";
        Object provider = project.getProjectInfo().get(ProjectInfoKeys.IMAGE_PROVIDER);
        return provider != null ? provider.toString() : "seedream";
    }

    private String getVideoProvider(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null || project.getProjectInfo() == null) return "vidu";
        Object provider = project.getProjectInfo().get(ProjectInfoKeys.VIDEO_PROVIDER);
        return provider != null ? provider.toString() : "vidu";
    }

    private String getVideoModel(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null || project.getProjectInfo() == null) return null;
        Object model = project.getProjectInfo().get(ProjectInfoKeys.VIDEO_MODEL);
        return model != null ? model.toString() : null;
    }

    /** 按项目 productionMode 选择实时动画或漫剧解说多镜头视频 prompt */
    private String buildAutoMultiShotPrompt(Panel panel, Map<String, Object> info) {
        String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");
        List<Map<String, String>> characterInfos = gatherCharacterInfosByPanel(panel);
        Map<String, Object> prevPanelLastShot = getPreviousPanelLastShot(panel);
        String projectId = getProjectIdByPanelId(panel.getId());
        Project project = projectId != null ? projectRepository.findByProjectId(projectId) : null;
        if (ProjectProductionMode.isComicCommentary(project)) {
            String prevNarration = extractNarration(prevPanelLastShot);
            String nextNarration = getNextPanelFirstNarration(panel);
            return comicCommentaryPanelPromptBuilder.buildMultiShotPrompt(
                    visualStyle, info, characterInfos, prevPanelLastShot, prevNarration, nextNarration);
        }
        return panelPromptBuilder.buildMultiShotPrompt(visualStyle, info, characterInfos, prevPanelLastShot);
    }

    /** 提取 shot 的 narration 字段 */
    private String extractNarration(Map<String, Object> shot) {
        if (shot == null) return null;
        Object nar = shot.get("narration");
        if (nar != null) {
            String s = nar.toString().trim();
            if (!s.isEmpty() && !"无".equals(s)) return s;
        }
        String sp = shot.get("speaker") != null ? shot.get("speaker").toString() : "";
        String dlg = shot.get("dialogue") != null ? shot.get("dialogue").toString() : "";
        if (sp.contains("旁白") && dlg != null && !dlg.isEmpty() && !"无".equals(dlg.trim())) {
            return dlg.trim();
        }
        return null;
    }

    /** 获取下一个 Panel 的第一条 shot 的 narration */
    @SuppressWarnings("unchecked")
    private String getNextPanelFirstNarration(Panel currentPanel) {
        try {
            List<Panel> siblings = panelRepository.findByEpisodeId(currentPanel.getEpisodeId());
            Panel nextPanel = null;
            boolean found = false;
            for (Panel p : siblings) {
                if (found) { nextPanel = p; break; }
                if (p.getId().equals(currentPanel.getId())) found = true;
            }
            if (nextPanel == null) return null;
            Map<String, Object> nextInfo = nextPanel.getPanelInfo();
            if (nextInfo == null) return null;
            List<Map<String, Object>> nextShots = (List<Map<String, Object>>) nextInfo.get("shots");
            if (nextShots == null || nextShots.isEmpty()) return null;
            return extractNarration(nextShots.get(0));
        } catch (Exception e) {
            log.warn("获取下一个 Panel narration 失败: panelId={}, error={}", currentPanel.getId(), e.getMessage());
            return null;
        }
    }

    private String getProjectIdByPanelIdForProvider(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) return null;
        Episode episode = episodeRepository.selectById(panel.getEpisodeId());
        return episode != null ? episode.getProjectId() : null;
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
        status.put("videoModel", panelInfo.get("videoModel"));
        status.put("offPeak", panelInfo.getOrDefault("offPeak", false));
        status.put("videoProgress", panelInfo.get("videoProgress"));
        status.put("videoCredits", panelInfo.get("videoCredits"));
        status.put("ttsStatus", panelInfo.getOrDefault("ttsStatus", "pending"));
        status.put("ttsAudioUrl", panelInfo.get("ttsAudioUrl"));
        status.put("ttsCredits", panelInfo.get("ttsCredits"));
        status.put("videoWithNarrationUrl", panelInfo.get("videoWithNarrationUrl"));
        status.put("mergeStatus", panelInfo.getOrDefault("mergeStatus", "pending"));
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
        regenerateGrid(panelId, null);
    }

    public void regenerateGrid(Long panelId, String customHint) {
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
        // 清理旧的视频状态（支持从 video_failed 状态换图重试）
        info.remove("videoStatus");
        info.remove("videoTaskId");
        info.remove("videoUrl");
        info.remove("videoUrlPermanent");
        info.remove("videoProgress");
        info.remove("videoCredits");
        info.remove("customVideoPrompt");
        updatePanelInfo(panel, info);
        String projectId = getProjectIdByPanelIdForProvider(panelId);
        String imageProvider = getImageProvider(projectId != null ? projectId : "");
        log.info("Panel 九宫格生成: panelId={}, projectId={}, imageProvider={}", panelId, projectId, imageProvider);
        gridImageService.generateGridsForPanel(panelId, imageProvider, customHint);
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
        Integer progress = info != null ? getInt(info, "videoProgress") : null;
        Integer credits = info != null ? getInt(info, "videoCredits") : null;
        response.setVideoUrl(videoUrl);
        response.setStatus(videoStatus != null ? videoStatus : (videoUrl != null ? "completed" : "pending"));
        response.setTaskId(taskId);
        response.setErrorMessage(errorMsg);
        response.setProgress(progress);
        response.setCredits(credits);
        return response;
    }

    /**
     * 生成视频（异步）- 使用融合参考图 + 多镜头提示词
     */
    public void generateVideoByPanelId(Long panelId) {
        generateVideoByPanelId(panelId, false, null);
    }

    public void generateVideoByPanelId(Long panelId, boolean offPeak) {
        generateVideoByPanelId(panelId, offPeak, null);
    }

    public void generateVideoByPanelId(Long panelId, boolean offPeak, String customPrompt) {
        generateVideoByPanelId(panelId, offPeak, customPrompt, null);
    }

    public void generateVideoByPanelId(Long panelId, boolean offPeak, String customPrompt, String videoModel) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();
        String gridStatus = info != null ? getStr(info, "gridStatus") : null;
        if (!"approved".equals(gridStatus)) {
            throw new BusinessException("九宫格未审核通过，请先审核");
        }
        // 如果提供了自定义提示词，保存到 panelInfo
        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            info.put("customVideoPrompt", customPrompt);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
        }
        self().doGenerateVideoByPanelId(panelId, offPeak, videoModel);
    }

    /**
     * 获取 Panel 的视频生成提示词
     */
    public String getVideoPrompt(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();

        // 优先返回增强后的提示词
        String enhanced = (String) info.get("enhancedVideoPrompt");
        if (enhanced != null && !enhanced.trim().isEmpty()) {
            return enhanced;
        }

        // 其次返回用户自定义提示词
        String custom = (String) info.get("customVideoPrompt");
        if (custom != null && !custom.trim().isEmpty()) {
            return custom;
        }

        // 最后返回自动构建的提示词
        return buildAutoMultiShotPrompt(panel, info);
    }

    /**
     * 手动增强视频生成提示词，存入 panelInfo.enhancedVideoPrompt
     */
    public String enhanceVideoPrompt(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();

        // 先构建原始 prompt
        String originalPrompt;
        String custom = (String) info.get("customVideoPrompt");
        if (custom != null && !custom.trim().isEmpty()) {
            originalPrompt = custom;
        } else {
            originalPrompt = buildAutoMultiShotPrompt(panel, info);
        }

        String enhanced = viduVideoService.enhancePrompt(originalPrompt);
        if (!originalPrompt.equals(enhanced)) {
            info.put("enhancedVideoPrompt", enhanced);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            log.info("手动增强提示词: panelId={}, 原始长度={}, 增强后长度={}", panelId, originalPrompt.length(), enhanced.length());
        }
        return enhanced;
    }

    @Async
    public void doGenerateVideoByPanelId(Long panelId, boolean offPeak, String overrideVideoModel) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) throw new BusinessException("分镜不存在");
            Map<String, Object> info = panel.getPanelInfo();
            String fusionImageUrl = getStr(info, "fusionImageUrl");
            if (fusionImageUrl == null) {
                throw new BusinessException("融合参考图不存在，请先生成九宫格");
            }

            info.put("videoStatus", "generating");
            info.remove("videoProgress");
            info.remove("videoCredits");
            info.remove("errorMessage");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);

            // 构建提示词：优先使用增强后的提示词，其次自定义，最后自动构建
            String prompt = (String) info.get("enhancedVideoPrompt");
            if (prompt == null || prompt.trim().isEmpty()) {
                prompt = (String) info.get("customVideoPrompt");
            }
            if (prompt == null || prompt.trim().isEmpty()) {
                prompt = buildAutoMultiShotPrompt(panel, info);
            }

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

            String projectId = getProjectIdByPanelIdForProvider(panelId);
            VideoGenerationService videoService = aiServiceConfig.getVideoService(
                getVideoProvider(projectId != null ? projectId : ""));
            // 优先使用请求级别的 videoModel 覆盖，其次使用项目级配置
            String videoModel = (overrideVideoModel != null && !overrideVideoModel.trim().isEmpty())
                ? overrideVideoModel
                : (projectId != null ? getVideoModel(projectId) : null);
            String taskId = videoService.generateAsync(prompt, totalDuration, "16:9", fusionImageUrl, offPeak, videoModel);
            info.put("videoTaskId", taskId);
            info.put("offPeak", offPeak);
            if (videoModel != null && !videoModel.isEmpty()) {
                info.put("videoModel", videoModel);
            }
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            self().pollNewVideoTask(panelId, taskId, offPeak);
            log.info("视频生成已提交: panelId={}, taskId={}, offPeak={}", panelId, taskId, offPeak);
        } catch (Exception e) {
            log.error("视频生成失败: panelId={}", panelId, e);
            updatePanelState(panelId, "videoStatus", "failed", e.getMessage());
            publishPanelFailure(panelId, e.getMessage());
            throw new BusinessException("视频生成失败: " + e.getMessage());
        }
    }

    @Async
    public void pollNewVideoTask(Long panelId, String taskId, boolean offPeak) {
        // 轮询间隔5秒，错峰模式最多2880次(4h)，即时模式最多120次(10min)
        int intervalSeconds = 5;
        int maxPolls = offPeak ? 2880 : 120;

        String projectId = getProjectIdByPanelIdForProvider(panelId);
        VideoGenerationService videoService = aiServiceConfig.getVideoService(
            getVideoProvider(projectId != null ? projectId : ""));

        try {
            for (int i = 0; i < maxPolls; i++) {
                VideoGenerationService.TaskStatus status = videoService.getTaskStatus(taskId);
                if (status == null) { Thread.sleep(intervalSeconds * 1000L); continue; }

                // 更新进度和积分
                Panel progressPanel = panelRepository.selectById(panelId);
                if (progressPanel != null) {
                    Map<String, Object> info = progressPanel.getPanelInfo();
                    info.put("videoProgress", status.getProgress());
                    if (status.getCredits() != null) {
                        info.put("videoCredits", status.getCredits());
                    }
                    progressPanel.setPanelInfo(info);
                    panelRepository.updateById(progressPanel);
                }

                switch (status.getStatus()) {
                    case "completed":
                        String videoUrl = status.getVideoUrl();
                        if (videoUrl == null) videoUrl = videoService.downloadVideo(status.getTaskId());
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
                            info.put("videoProgress", 100);
                            if (status.getCredits() != null) {
                                info.put("videoCredits", status.getCredits());
                            }
                            info.put("errorMessage", null);
                            panel.setPanelInfo(info);
                            panelRepository.updateById(panel);
                        }
                        log.info("视频生成完成: panelId={}", panelId);
                        String projId = getProjectIdByPanelId(panelId);
                        if (projId != null && panel != null) {
                            eventPublisher.publishPanelVideoDone(projId, panel.getEpisodeId(), panelId, videoUrl);
                        }
                        return;
                    case "failed":
                        updatePanelState(panelId, "videoStatus", "failed", status.getErrorMessage());
                        publishPanelFailure(panelId, status.getErrorMessage());
                        return;
                    default:
                        Thread.sleep(intervalSeconds * 1000L);
                        break;
                }
            }
            updatePanelState(panelId, "videoStatus", "failed", "视频生成超时");
            publishPanelFailure(panelId, "视频生成超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频任务轮询异常: panelId={}", panelId, e);
            updatePanelState(panelId, "videoStatus", "failed", "视频生成异常");
            publishPanelFailure(panelId, "视频生成异常: " + e.getMessage());
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

    /**
     * 批量重试项目下所有失败的视频
     */
    public int retryAllFailedVideos(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        int retried = 0;
        for (Episode episode : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(episode.getId());
            for (Panel panel : panels) {
                Map<String, Object> info = panel.getPanelInfo();
                if (info != null && "failed".equals(getStr(info, "videoStatus"))) {
                    info.put("videoStatus", "pending");
                    info.put("errorMessage", null);
                    panel.setPanelInfo(info);
                    panelRepository.updateById(panel);
                    generateVideoByPanelId(panel.getId());
                    retried++;
                }
            }
        }
        progressService.clearError(projectId);
        return retried;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取同一 Episode 中前一个 Panel 的最后一个镜头信息，用于跨面板衔接
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getPreviousPanelLastShot(Panel currentPanel) {
        try {
            List<Panel> siblingPanels = panelRepository.findByEpisodeId(currentPanel.getEpisodeId());
            Panel prevPanel = null;
            for (Panel p : siblingPanels) {
                if (p.getId().equals(currentPanel.getId())) break;
                prevPanel = p;
            }
            if (prevPanel == null) return null;

            Map<String, Object> prevInfo = prevPanel.getPanelInfo();
            if (prevInfo == null) return null;

            List<Map<String, Object>> prevShots = (List<Map<String, Object>>) prevInfo.get("shots");
            if (prevShots == null || prevShots.isEmpty()) return null;

            return prevShots.get(prevShots.size() - 1);
        } catch (Exception e) {
            log.warn("获取前一个面板上下文失败: panelId={}, error={}", currentPanel.getId(), e.getMessage());
            return null;
        }
    }

    private void publishPanelFailure(Long panelId, String error) {
        String projectId = getProjectIdByPanelId(panelId);
        if (projectId != null) {
            Panel panel = panelRepository.selectById(panelId);
            Long episodeId = panel != null ? panel.getEpisodeId() : null;
            progressService.setError(projectId, "面板视频失败: " + error);
            eventPublisher.publishFailure(projectId, "面板视频失败: " + error);
            eventPublisher.publishPanelVideoFailed(projectId, episodeId, panelId, error);
        }
    }

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }

    private Integer getInt(Map<String, Object> info, String key) {
        Object v = info.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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

    /**
     * 通过 Panel 所属 Episode 收集角色信息（name, voice, appearance）
     */
    private List<Map<String, String>> gatherCharacterInfosByPanel(Panel panel) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            Episode episode = episodeRepository.selectById(panel.getEpisodeId());
            if (episode == null) return result;
            List<Character> characters = characterRepository.findByProjectId(episode.getProjectId());
            for (Character ch : characters) {
                Map<String, Object> info = ch.getCharacterInfo();
                if (info == null) continue;
                Map<String, String> ci = new HashMap<>();
                ci.put("name", (String) info.getOrDefault(CharacterInfoKeys.NAME, ""));
                ci.put("voice", (String) info.getOrDefault(CharacterInfoKeys.VOICE, ""));
                ci.put("appearance", (String) info.getOrDefault(CharacterInfoKeys.APPEARANCE, ""));
                result.add(ci);
            }
        } catch (Exception e) {
            log.warn("收集角色信息失败: panelId={}", panel.getId(), e);
        }
        return result;
    }

    // ==================== 分集剧本 + 分镜生成 ====================

    /**
     * 第一阶段（文本）：生成结构化分集剧本 + 分镜脚本文本，不生成九宫格图片。
     * 创建 Episode 记录，设置 gridStatus = "text_ready" 等待人工审核。
     *
     * 注意：不使用 @Transactional，因为每集的 episode 创建通过 TransactionTemplate 在独立事务中完成。
     */
    public void generateEpisodeScripts(String projectId) {
        log.info("[Pipeline-Text] 开始分集剧本+分镜文本生成: projectId={}", projectId);
        if (!progressService.tryLock(projectId, "episode")) {
            throw new BusinessException("该项目正在生成中，请稍后再试");
        }
        try {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null) {
                throw new BusinessException("项目不存在: " + projectId);
            }
            Map<String, Object> projectInfo = project.getProjectInfo();
            String visualStyle = (String) projectInfo.getOrDefault("visualStyle", "ANIME");
            int targetDuration = getIntFromMap(projectInfo, "episodeDuration", 60);
            @SuppressWarnings("unchecked")
            Map<String, Object> scriptMap = (Map<String, Object>) projectInfo.get("script");
            String outline = scriptMap != null ? (String) scriptMap.getOrDefault("outline", "") : "";
            String charactersDesc = getCharacterDescriptions(projectId);
            boolean comicMode = ProjectProductionMode.isComicCommentary(project);
            int totalEpisodes = getIntFromMap(projectInfo, "totalEpisodes", 1);

            // 1. 按章节拆分大纲，串行生成各章剧本
            List<ParsedChapter> chapters = parseOutlineChapters(outline, totalEpisodes);
            log.info("[Pipeline-Text] 大纲拆分为 {} 个章节: projectId={}, chapters={}", chapters.size(), projectId,
                    chapters.stream().map(c -> c.title + "(" + c.episodeCount + "集)").collect(Collectors.joining(", ")));

            List<Map<String, Object>> allScripts = new ArrayList<>();
            StringBuilder previousSummary = new StringBuilder();
            int globalEpisodeNum = 0;

            for (int ci = 0; ci < chapters.size(); ci++) {
                ParsedChapter chapter = chapters.get(ci);
                log.info("[Pipeline-Text] 调用DeepSeek生成章节剧本: projectId={}, chapter={}, episodeCount={}",
                        projectId, chapter.title, chapter.episodeCount);

                List<Map<String, Object>> chapterScripts = deepSeekTextService.generateEpisodeScript(
                    chapter.text, charactersDesc, targetDuration, visualStyle,
                    chapter.episodeCount, comicMode, previousSummary.toString());
                log.info("[Pipeline-Text] 章节 {} 生成 {} 集剧本, projectId={}", chapter.title, chapterScripts.size(), projectId);

                for (Map<String, Object> scriptItem : chapterScripts) {
                    globalEpisodeNum++;
                    allScripts.add(scriptItem);
                    eventPublisher.publishEpisodeScriptDone(projectId,
                        globalEpisodeNum,
                        (String) scriptItem.getOrDefault("title", ""),
                        totalEpisodes,
                        globalEpisodeNum);
                }

                // 构建本章摘要供下一章使用
                if (ci < chapters.size() - 1) {
                    previousSummary.append(buildChapterSummary(chapterScripts, globalEpisodeNum));
                }
            }

            totalEpisodes = allScripts.size();
            log.info("[Pipeline-Text] 全部 {} 集剧本生成完成, projectId={}", totalEpisodes, projectId);

            // 2. 收集已有 episode 的退回原因
            List<Episode> existingEpisodes = episodeRepository.findByProjectId(projectId);
            Map<Integer, String> rejectionReasons = new HashMap<>();
            for (Episode ep : existingEpisodes) {
                Map<String, Object> epInfo = ep.getEpisodeInfo();
                if (epInfo != null) {
                    String reason = (String) epInfo.get("panelRejectionReason");
                    if (reason != null && !reason.trim().isEmpty()) {
                        int epNum = getIntFromMap(epInfo, "episodeNum", 0);
                        if (epNum > 0) {
                            rejectionReasons.put(epNum, reason.trim());
                        }
                    }
                }
            }

            // 3. 并发生成分镜文本
            log.info("[Pipeline-Text] 开始并发分镜生成: projectId={}, totalEpisodes={}", projectId, totalEpisodes);
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(totalEpisodes, 5));
            try {
                List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
                for (int i = 0; i < allScripts.size(); i++) {
                    final Map<String, Object> script = allScripts.get(i);
                    final int episodeNum = i + 1;
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        generateStoryboardForEpisode(projectId, projectInfo, script, episodeNum, comicMode, rejectionReasons, visualStyle, targetDuration);
                    }, executor));
                }
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }

            // 4. 完成通知
            log.info("[Pipeline-Text] 分集剧本+分镜文本全部完成: projectId={}", projectId);
            progressService.unlock(projectId);
            progressService.clearError(projectId);
            eventPublisher.publishTaskComplete(projectId, "episode", null);

        } catch (Exception e) {
            log.error("[Pipeline-Text] 文本生成异常: projectId={}, error={}", projectId, e.getMessage(), e);
            progressService.unlock(projectId);
            progressService.setError(projectId, e.getMessage());
            eventPublisher.publishFailure(projectId, e.getMessage());
        }
    }

    /**
     * 从 outline 中按章节拆分，每章计算对应集数
     */
    private List<ParsedChapter> parseOutlineChapters(String outline, int totalEpisodes) {
        List<ParsedChapter> chapters = new ArrayList<>();
        if (outline == null || outline.isEmpty()) {
            chapters.add(new ParsedChapter("全文", outline != null ? outline : "", totalEpisodes));
            return chapters;
        }

        // 按 ### 第X章 格式切分
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^(#{3,4}\\s+第.+章[^\\n]*)", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher matcher = pattern.matcher(outline);

        List<Integer> positions = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            positions.add(matcher.start());
            titles.add(matcher.group(1).trim());
        }

        if (positions.isEmpty()) {
            chapters.add(new ParsedChapter("全文", outline, totalEpisodes));
            return chapters;
        }

        // 计算每章集数（均匀分配，最后一章取余）
        int episodesPerChapter = totalEpisodes / positions.size();
        int remainder = totalEpisodes % positions.size();

        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : outline.length();
            String chapterText = outline.substring(start, end).trim();
            int epCount = episodesPerChapter + (i < remainder ? 1 : 0);
            chapters.add(new ParsedChapter(titles.get(i), chapterText, epCount));
        }

        return chapters;
    }

    /** 章节解析结果 */
    private static class ParsedChapter {
        final String title;
        final String text;
        final int episodeCount;

        ParsedChapter(String title, String text, int episodeCount) {
            this.title = title;
            this.text = text;
            this.episodeCount = episodeCount;
        }
    }

    /**
     * 构建章节摘要，供下一章保持叙事连贯性
     */
    private String buildChapterSummary(List<Map<String, Object>> scripts, int startEpisodeNum) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scripts.size(); i++) {
            Map<String, Object> s = scripts.get(i);
            int epNum = startEpisodeNum - scripts.size() + i + 1;
            sb.append("第").append(epNum).append("集「").append(s.getOrDefault("title", "")).append("」");
            String characters = (String) s.getOrDefault("characters", "");
            if (characters != null && !characters.isEmpty()) {
                sb.append("，出场角色：").append(characters);
            }
            String content = (String) s.getOrDefault("content", "");
            if (content != null && content.length() > 100) {
                sb.append("，摘要：").append(content, 0, 100).append("…");
            } else if (content != null && !content.isEmpty()) {
                sb.append("，摘要：").append(content);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 单集分镜生成（供并发调用）
     */
    private void generateStoryboardForEpisode(String projectId, Map<String, Object> projectInfo,
                                               Map<String, Object> script, int episodeNum,
                                               boolean comicMode, Map<Integer, String> rejectionReasons,
                                               String visualStyle, int targetDuration) {
        String title = (String) script.get("title");
        String content = (String) script.get("content");
        String characters = (String) script.getOrDefault("characters", "");
        String revisionNote = rejectionReasons.get(episodeNum);

        log.info("[Pipeline-Text] 调用DeepSeek生成分镜: projectId={}, episode={}({}), comicMode={}, hasRevision={}",
                projectId, episodeNum, title, comicMode, revisionNote != null);

        List<Map<String, Object>> shots;
        try {
            if (comicMode) {
                String narrationPerspective = (String) projectInfo.get("narrationPerspective");
                List<List<Map<String, Object>>> panelGroups = deepSeekTextService.generatePanelAwareStoryboard(
                    content, characters, targetDuration, visualStyle, revisionNote, narrationPerspective);
                log.info("[Pipeline-Text] 生成 {} 个 Panel, projectId={}, episode={}", panelGroups.size(), projectId, title);

                // ===== 新增：先生成集级别旁白稿 =====
                int totalShotsEst = targetDuration / 3;
                int dialogueCountEst = Math.round(totalShotsEst / 3); // 约 1/3 为对白
                String narrationDraft = deepSeekTextService.generateNarrationDraft(
                        content, characters, targetDuration, dialogueCountEst, narrationPerspective);
                // ======================================

                shots = new ArrayList<>();

                // ===== 新增：旁白分配到各 shot =====
                for (List<Map<String, Object>> panelShots : panelGroups) {
                    narrationAllocator.sanitizeDialogue(panelShots);
                    int targetDialogue = Math.round(panelShots.size() / 3);
                    narrationAllocator.forceDialogueRatio(panelShots, targetDialogue);
                    narrationAllocator.allocate(narrationDraft, panelShots, narrationPerspective);
                    shots.addAll(panelShots);
                }
                // ===================================
            } else {
                shots = deepSeekTextService.generateStoryboard(
                    content, characters, targetDuration, visualStyle, false, revisionNote);
            }
        } catch (Exception e) {
            log.error("[Pipeline-Text] 分镜生成失败: projectId={}, episode={}, error={}", projectId, title, e.getMessage(), e);
            throw new RuntimeException("分镜生成失败(第" + episodeNum + "集 " + title + "): " + e.getMessage(), e);
        }
        log.info("[Pipeline-Text] 生成 {} 个分镜, projectId={}, episode={}", shots.size(), projectId, title);

        // 注入角色ID
        Map<String, String> nameToId = buildCharacterIdMap(projectId);
        injectCharacterIds(shots, nameToId);

        // 在独立事务中创建 episode 并设置状态为 text_ready
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Long episodeId = txTemplate.execute(status -> {
            Long eid = findOrCreateEpisode(projectId, script, shots, visualStyle, episodeNum);
            log.info("[Pipeline-Text] Episode创建/更新: episodeId={}, projectId={}, episodeNum={}", eid, projectId, episodeNum);
            deleteExistingPanels(eid);
            gridImageService.updateEpisodeGridStatus(eid, "text_ready");

            // 清除退回原因（已根据反馈重新生成）
            Episode freshEp = episodeRepository.selectById(eid);
            if (freshEp != null) {
                Map<String, Object> freshInfo = freshEp.getEpisodeInfo();
                if (freshInfo != null && freshInfo.containsKey("panelRejectionReason")) {
                    freshInfo.remove("panelRejectionReason");
                    freshInfo.put("panelApproved", false);
                    freshEp.setEpisodeInfo(freshInfo);
                    episodeRepository.updateById(freshEp);
                }
            }

            return eid;
        });

        eventPublisher.publishEpisodePanelDone(projectId, episodeId, episodeNum, shots.size());
    }

    /**
     * 第二阶段（图片）：为所有 gridStatus = "text_ready" 的集数生成九宫格图片。
     * 人工审核分镜文本后调用。不使用 progress lock，前端通过 SSE 跟踪每集进度。
     */
    public void generateGridImagesForProject(String projectId) {
        log.info("[Pipeline-Grid] 开始九宫格图片生成: projectId={}", projectId);
        try {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null) {
                throw new BusinessException("项目不存在: " + projectId);
            }

            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            String visualStyle = (String) project.getProjectInfo().getOrDefault("visualStyle", "ANIME");
            int generatedCount = 0;

            for (Episode episode : episodes) {
                Map<String, Object> info = episode.getEpisodeInfo();
                String gridStatus = info != null ? (String) info.get("gridStatus") : null;
                if (!"text_ready".equals(gridStatus)) continue;

                int episodeNum = info != null ? getIntFromMap(info, "episodeNum", 0) : 0;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> shots = info != null
                    ? (List<Map<String, Object>>) info.get("shots") : null;
                if (shots == null || shots.isEmpty()) {
                    log.warn("[Pipeline-Grid] 跳过无 shots 的集数: episodeId={}", episode.getId());
                    continue;
                }

                log.info("[Pipeline-Grid] 启动九宫格生成: episodeId={}, episodeNum={}, shotCount={}",
                    episode.getId(), episodeNum, shots.size());
                gridImageService.updateEpisodeGridStatus(episode.getId(), "generating");
                String imageProvider = getImageProvider(projectId);
                gridImageService.generateGridsForEpisode(episode.getId(), shots, visualStyle, imageProvider);
                generatedCount++;
            }

            if (generatedCount == 0) {
                log.warn("[Pipeline-Grid] 没有需要生成九宫格的集数: projectId={}", projectId);
            } else {
                log.info("[Pipeline-Grid] 已启动 {} 集九宫格生成: projectId={}", generatedCount, projectId);
            }
        } catch (Exception e) {
            log.error("[Pipeline-Grid] 九宫格生成异常: projectId={}, error={}", projectId, e.getMessage(), e);
            throw new BusinessException("启动九宫格生成失败: " + e.getMessage());
        }
    }

    // --- 分镜生成辅助方法 ---

    private String getCharacterDescriptions(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        if (characters == null || characters.isEmpty()) {
            log.warn("项目没有配置角色: projectId={}", projectId);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Character c : characters) {
            Map<String, Object> info = c.getCharacterInfo();
            if (info == null) continue;

            String name = (String) info.get("name");
            String appearance = (String) info.get("appearance");
            String personality = (String) info.get("personality");
            String role = (String) info.get("role");

            if (name == null || name.isEmpty()) continue;

            sb.append("【").append(name).append("】");
            if (role != null && !role.isEmpty()) {
                sb.append(" 角色：").append(role);
            }
            if (appearance != null && !appearance.isEmpty()) {
                sb.append(" 外貌：").append(appearance);
            }
            if (personality != null && !personality.isEmpty()) {
                sb.append(" 性格：").append(personality);
            }
            sb.append("\n");
        }

        String result = sb.toString();
        log.info("获取角色描述: projectId={}, characters={}, descLength={}",
            projectId, characters.size(), result.length());
        return result;
    }

    private Long findOrCreateEpisode(String projectId, Map<String, Object> script,
                                       List<Map<String, Object>> shots, String visualStyle,
                                       int episodeNum) {
        String title = (String) script.get("title");
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            Map<String, Object> info = ep.getEpisodeInfo();
            Object existingNum = info != null ? info.get("episodeNum") : null;
            boolean numMatch = existingNum != null && Integer.valueOf(episodeNum).equals(existingNum);
            boolean titleMatch = info != null && title != null && title.equals(info.get("title"));
            if (numMatch || titleMatch) {
                info.putAll(script);
                info.put("episodeNum", episodeNum);
                info.put("shots", shots);
                info.put("visualStyle", visualStyle);
                info.put("gridStatus", "pending");
                ep.setEpisodeInfo(info);
                episodeRepository.updateById(ep);
                return ep.getId();
            }
        }
        Episode episode = new Episode();
        episode.setProjectId(projectId);
        episode.setStatus("pending");
        episode.setDeleted(false);
        Map<String, Object> episodeInfo = new HashMap<>(script);
        episodeInfo.put("episodeNum", episodeNum);
        episodeInfo.put("shots", shots);
        episodeInfo.put("visualStyle", visualStyle);
        episodeInfo.put("gridStatus", "pending");
        episode.setEpisodeInfo(episodeInfo);
        episodeRepository.insert(episode);
        return episode.getId();
    }

    private void deleteExistingPanels(Long episodeId) {
        List<Panel> existing = panelRepository.findByEpisodeId(episodeId);
        for (Panel p : existing) {
            panelRepository.deleteById(p.getId());
        }
    }

    /**
     * 构建角色名到 charId 的映射（精确 + 模糊）
     */
    private Map<String, String> buildCharacterIdMap(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        Map<String, String> nameToId = new HashMap<>();
        for (Character c : characters) {
            Map<String, Object> info = c.getCharacterInfo();
            if (info != null) {
                String name = (String) info.get("name");
                String charId = (String) info.get("charId");
                if (name != null && charId != null) {
                    name = name.trim();
                    nameToId.put(name, charId);
                    String stripped = name.replaceAll("[（\\(][^）\\)]*[）\\)]$", "").trim();
                    if (!stripped.isEmpty() && !stripped.equals(name)) {
                        nameToId.putIfAbsent(stripped, charId);
                    }
                }
            }
        }
        log.info("构建角色ID映射: projectId={}, mapping={}", projectId, nameToId);
        return nameToId;
    }

    /**
     * 为分镜中的角色注入 charId
     */
    private void injectCharacterIds(List<Map<String, Object>> shots, Map<String, String> nameToId) {
        for (Map<String, Object> shot : shots) {
            @SuppressWarnings("unchecked")
            List<String> charNames = (List<String>) shot.get("characters");
            if (charNames == null || charNames.isEmpty()) continue;

            List<Map<String, String>> charRefs = new ArrayList<>();
            for (String charName : charNames) {
                String trimmed = charName.trim();
                String charId = resolveCharId(trimmed, nameToId);
                Map<String, String> ref = new HashMap<>();
                ref.put("name", charName);
                if (charId != null) {
                    ref.put("charId", charId);
                    log.debug("角色注入成功: name={}, charId={}", charName, charId);
                } else {
                    log.warn("角色未找到匹配: name={}", charName);
                }
                charRefs.add(ref);
            }
            shot.put("characterRefs", charRefs);
        }
    }

    /**
     * 多级模糊匹配角色名到 charId
     */
    private String resolveCharId(String name, Map<String, String> nameToId) {
        String charId = nameToId.get(name);
        if (charId != null) return charId;

        String stripped = name.replaceAll("[（\\(][^）\\)]*[）\\)]$", "").trim();
        if (!stripped.isEmpty() && !stripped.equals(name)) {
            charId = nameToId.get(stripped);
            if (charId != null) return charId;
        }

        String noSpace = name.replaceAll("\\s+", "");
        if (!noSpace.equals(name)) {
            charId = nameToId.get(noSpace);
            if (charId != null) return charId;
        }

        return null;
    }

    private int getIntFromMap(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }
}
