package com.comic.service.production;

import com.comic.ai.image.ImageGenerationService;
import com.comic.ai.video.VideoGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.model.VideoSegmentModel;
import com.comic.dto.request.FusionRequest;
import com.comic.dto.request.ProduceRequest;
import com.comic.dto.request.TransitionRequest;
import com.comic.dto.response.*;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Panel;
import com.comic.entity.VideoProductionTask;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.VideoProductionTaskRepository;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 单分镜视频生产服务
 * 负责单个分镜的完整生产流程：背景图 → 融合图 → 过渡融合图 → 视频 → 尾帧
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PanelProductionService {

    private final EpisodeRepository episodeRepository;
    private final EpisodeProductionRepository productionRepository;
    private final VideoProductionTaskRepository videoTaskRepository;
    private final PanelRepository panelRepository;
    private final ImageGenerationService imageGenerationService;
    private final VideoGenerationService videoGenerationService;
    private final VideoCompositionService videoCompositionService;
    private final OssService ossService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    private PanelProductionService self() {
        return applicationContext.getBean(PanelProductionService.class);
    }

    // ==================== 背景图 ====================

    /**
     * 获取单分镜背景图状态
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public PanelBackgroundResponse getBackgroundStatus(Long episodeId, Integer panelIndex) {
        EpisodeProduction production = getOrCreateProduction(episodeId);
        List<String> urls = parseUrlList(production.getBackgroundUrls());
        PanelBackgroundResponse response = new PanelBackgroundResponse();
        response.setPanelIndex(panelIndex);
        if (panelIndex >= 0 && panelIndex < urls.size() && urls.get(panelIndex) != null) {
            response.setBackgroundUrl(urls.get(panelIndex));
            response.setStatus("completed");
        } else {
            response.setStatus("pending");
        }
        response.setPrompt(getPanelPrompt(episodeId, panelIndex));
        return response;
    }

    /**
     * 生成单分镜背景图（异步）
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void generateBackground(Long episodeId, Integer panelIndex) {
        self().doGenerateBackground(episodeId, panelIndex);
    }

    @Async
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void doGenerateBackground(Long episodeId, Integer panelIndex) {
        try {
            EpisodeProduction production = getOrCreateProduction(episodeId);
            List<String> urls = parseUrlList(production.getBackgroundUrls());
            ensureListSize(urls, panelIndex + 1);

            String prompt = getPanelPrompt(episodeId, panelIndex);
            String imageUrl = imageGenerationService.generate(prompt, 1280, 720, "anime");

            urls.set(panelIndex, imageUrl);
            production.setBackgroundUrls(toJsonUrlList(urls));
            productionRepository.updateById(production);

            log.info("背景图生成完成: episodeId={}, panelIndex={}", episodeId, panelIndex);
        } catch (Exception e) {
            log.error("背景图生成失败: episodeId={}, panelIndex={}", episodeId, panelIndex, e);
            throw new BusinessException("背景图生成失败: " + e.getMessage());
        }
    }

    // ==================== 融合图 ====================

    /**
     * 获取单分镜融合图状态
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public PanelFusionResponse getFusionStatus(Long episodeId, Integer panelIndex) {
        EpisodeProduction production = getOrCreateProduction(episodeId);
        List<String> fusionUrls = parseUrlList(production.getFusionUrls());
        List<String> bgUrls = parseUrlList(production.getBackgroundUrls());

        PanelFusionResponse response = new PanelFusionResponse();
        response.setPanelIndex(panelIndex);

        if (panelIndex >= 0 && panelIndex < fusionUrls.size() && fusionUrls.get(panelIndex) != null) {
            response.setFusionUrl(fusionUrls.get(panelIndex));
            response.setStatus("completed");
        } else {
            response.setStatus("pending");
        }

        if (panelIndex >= 0 && panelIndex < bgUrls.size()) {
            response.setReferenceBackground(bgUrls.get(panelIndex));
        }
        response.setCharacterRefs(getCharacterRefs(episodeId, panelIndex));
        return response;
    }

    /**
     * 生成单分镜融合图（异步）
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void generateFusion(Long episodeId, Integer panelIndex, FusionRequest request) {
        self().doGenerateFusion(episodeId, panelIndex, request);
    }

    @Async
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void doGenerateFusion(Long episodeId, Integer panelIndex, FusionRequest request) {
        try {
            String backgroundUrl = request != null && request.getBackgroundUrl() != null
                    ? request.getBackgroundUrl()
                    : getBackgroundUrl(episodeId, panelIndex);

            if (backgroundUrl == null) {
                throw new BusinessException("背景图不存在，请先生成背景图");
            }

            List<String> characterRefs = request != null && request.getCharacterRefs() != null
                    ? request.getCharacterRefs()
                    : getCharacterRefs(episodeId, panelIndex);

            // 使用背景图+角色参考图生成融合图
            StringBuilder fusionPrompt = new StringBuilder(getPanelPrompt(episodeId, panelIndex));
            if (!characterRefs.isEmpty()) {
                fusionPrompt.append(", with characters");
            }

            String fusionUrl;
            if (!characterRefs.isEmpty()) {
                fusionUrl = imageGenerationService.generateWithReference(
                        fusionPrompt.toString(), backgroundUrl, 1280, 720);
            } else {
                fusionUrl = imageGenerationService.generate(
                        fusionPrompt.toString(), 1280, 720, "anime");
            }

            EpisodeProduction production = getOrCreateProduction(episodeId);
            List<String> urls = parseUrlList(production.getFusionUrls());
            ensureListSize(urls, panelIndex + 1);
            urls.set(panelIndex, fusionUrl);
            production.setFusionUrls(toJsonUrlList(urls));
            productionRepository.updateById(production);

            log.info("融合图生成完成: episodeId={}, panelIndex={}", episodeId, panelIndex);
        } catch (Exception e) {
            log.error("融合图生成失败: episodeId={}, panelIndex={}", episodeId, panelIndex, e);
            throw new BusinessException("融合图生成失败: " + e.getMessage());
        }
    }

    // ==================== 过渡融合图 ====================

    /**
     * 获取单分镜过渡融合图状态
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public PanelTransitionResponse getTransitionStatus(Long episodeId, Integer panelIndex) {
        EpisodeProduction production = getOrCreateProduction(episodeId);
        List<String> transitionUrls = parseUrlList(production.getTransitionUrls());
        List<String> fusionUrls = parseUrlList(production.getFusionUrls());

        PanelTransitionResponse response = new PanelTransitionResponse();
        response.setPanelIndex(panelIndex);

        if (panelIndex >= 0 && panelIndex < transitionUrls.size() && transitionUrls.get(panelIndex) != null) {
            response.setTransitionUrl(transitionUrls.get(panelIndex));
            response.setStatus("completed");
        } else {
            response.setStatus("pending");
        }

        if (panelIndex >= 0 && panelIndex < fusionUrls.size()) {
            response.setSourceFusionUrl(fusionUrls.get(panelIndex));
        }

        // 获取上一个分镜的尾帧
        String tailFrameUrl = getTailFrameUrl(episodeId, panelIndex - 1);
        response.setSourceTailFrameUrl(tailFrameUrl);
        return response;
    }

    /**
     * 生成过渡融合图（异步）
     * 融合图 + 上一个分镜的尾帧 → 过渡融合图
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void generateTransition(Long episodeId, Integer panelIndex, TransitionRequest request) {
        self().doGenerateTransition(episodeId, panelIndex, request);
    }

    @Async
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void doGenerateTransition(Long episodeId, Integer panelIndex, TransitionRequest request) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String fusionUrl = request != null && request.getFusionUrl() != null
                        ? request.getFusionUrl()
                        : getFusionUrl(episodeId, panelIndex);

                if (fusionUrl == null) {
                    throw new BusinessException("融合图不存在，请先生成融合图");
                }

                String tailFrameUrl = getTailFrameUrl(episodeId, panelIndex - 1);

                String transitionUrl;
                if (tailFrameUrl != null) {
                    // 融合图 + 尾帧 → 过渡融合图
                    String prompt = "Blend and transition smoothly, maintaining style consistency";
                    transitionUrl = imageGenerationService.generateWithReference(
                            prompt, fusionUrl, 1280, 720);
                } else {
                    // 第一个分镜，过渡融合图 = 融合图
                    transitionUrl = fusionUrl;
                }

                EpisodeProduction production = getOrCreateProduction(episodeId);
                List<String> urls = parseUrlList(production.getTransitionUrls());
                ensureListSize(urls, panelIndex + 1);
                urls.set(panelIndex, transitionUrl);
                production.setTransitionUrls(toJsonUrlList(urls));
                productionRepository.updateById(production);

                log.info("过渡融合图生成完成: episodeId={}, panelIndex={}", episodeId, panelIndex);
                return;
            } catch (Exception e) {
                log.warn("过渡融合图生成失败 (attempt {}/{}): episodeId={}, panelIndex={}, error={}",
                        attempt, maxRetries, episodeId, panelIndex, e.getMessage());
                if (attempt == maxRetries) {
                    throw new BusinessException("过渡融合图生成失败（已重试" + maxRetries + "次）: " + e.getMessage());
                }
            }
        }
    }

    // ==================== 尾帧 ====================

    /**
     * 获取分镜尾帧URL
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public PanelTailFrameResponse getTailFrame(Long episodeId, Integer panelIndex) {
        PanelTailFrameResponse response = new PanelTailFrameResponse();
        response.setPanelIndex(panelIndex);

        // 查找该分镜的视频任务
        List<VideoProductionTask> tasks = videoTaskRepository.findByEpisodeIdAndPanelIndex(episodeId, panelIndex);
        Optional<VideoProductionTask> completedTask = tasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()))
                .findFirst();

        if (completedTask.isPresent()) {
            response.setTailFrameUrl(completedTask.get().getLastFrameUrl());
            response.setSourceVideoUrl(completedTask.get().getVideoUrl());
            response.setStatus("completed");
        } else {
            response.setStatus("pending");
        }
        return response;
    }

    /**
     * 获取指定分镜的尾帧URL（供内部使用）
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public String getTailFrameUrl(Long episodeId, Integer panelIndex) {
        if (panelIndex < 0) return null;
        List<VideoProductionTask> tasks = videoTaskRepository.findByEpisodeIdAndPanelIndex(episodeId, panelIndex);
        return tasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()))
                .map(VideoProductionTask::getLastFrameUrl)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ==================== 视频任务状态 ====================

    /**
     * 获取视频生成任务状态
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public PanelVideoTaskResponse getVideoTaskStatus(Long episodeId, Integer panelIndex) {
        PanelVideoTaskResponse response = new PanelVideoTaskResponse();
        response.setPanelIndex(panelIndex);

        List<VideoProductionTask> tasks = videoTaskRepository.findByEpisodeIdAndPanelIndex(episodeId, panelIndex);
        if (tasks.isEmpty()) {
            response.setStatus("pending");
            return response;
        }

        VideoProductionTask task = tasks.get(0);
        response.setVideoUrl(task.getVideoUrl());
        response.setTaskId(task.getVideoTaskId());
        response.setErrorMessage(task.getErrorMessage());
        response.setDuration(task.getTargetDuration());

        switch (task.getStatus()) {
            case "COMPLETED":
                response.setStatus("completed");
                break;
            case "FAILED":
                response.setStatus("failed");
                break;
            case "PROCESSING":
                response.setStatus("generating");
                break;
            default:
                response.setStatus("pending");
        }
        return response;
    }

    // ==================== 单分镜一键生产 ====================

    /**
     * 单分镜一键生产（异步）
     * 背景 → 融合 → 过渡融合 → 视频 → 尾帧
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void produceSinglePanel(Long episodeId, Integer panelIndex, ProduceRequest request) {
        self().doProduceSinglePanel(episodeId, panelIndex, request);
    }

    @Async
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void doProduceSinglePanel(Long episodeId, Integer panelIndex, ProduceRequest request) {
        try {
            // 检查上一个分镜是否完成
            if (panelIndex > 0) {
                String prevTailFrame = getTailFrameUrl(episodeId, panelIndex - 1);
                if (prevTailFrame == null) {
                    log.warn("上一个分镜({})未完成，无法生产当前分镜({})", panelIndex - 1, panelIndex);
                    throw new BusinessException("上一个分镜尚未完成，请先完成分镜 #" + panelIndex);
                }
            }

            // 阶段1: 背景图
            String backgroundUrl = request != null && request.getBackgroundUrl() != null
                    ? request.getBackgroundUrl()
                    : getBackgroundUrl(episodeId, panelIndex);

            if (backgroundUrl == null) {
                log.info("生成背景图: episodeId={}, panelIndex={}", episodeId, panelIndex);
                doGenerateBackground(episodeId, panelIndex);
                // 等待背景图生成完成
                backgroundUrl = waitForBackground(episodeId, panelIndex, 120);
            }

            // 阶段2: 融合图
            String fusionUrl = getFusionUrl(episodeId, panelIndex);
            if (fusionUrl == null) {
                log.info("生成融合图: episodeId={}, panelIndex={}", episodeId, panelIndex);
                FusionRequest fusionReq = new FusionRequest();
                fusionReq.setBackgroundUrl(backgroundUrl);
                fusionReq.setCharacterRefs(request != null ? request.getCharacterRefs() : null);
                doGenerateFusion(episodeId, panelIndex, fusionReq);
                fusionUrl = waitForFusion(episodeId, panelIndex, 120);
            }

            // 阶段3: 过渡融合图
            String transitionUrl = getTransitionUrl(episodeId, panelIndex);
            if (transitionUrl == null) {
                log.info("生成过渡融合图: episodeId={}, panelIndex={}", episodeId, panelIndex);
                TransitionRequest transReq = new TransitionRequest();
                transReq.setFusionUrl(fusionUrl);
                doGenerateTransition(episodeId, panelIndex, transReq);
                transitionUrl = waitForTransition(episodeId, panelIndex, 120);
            }

            // 阶段4: 视频生成
            log.info("生成视频: episodeId={}, panelIndex={}", episodeId, panelIndex);
            generateVideo(episodeId, panelIndex, transitionUrl);

            log.info("单分镜生产完成: episodeId={}, panelIndex={}", episodeId, panelIndex);
        } catch (Exception e) {
            log.error("单分镜生产失败: episodeId={}, panelIndex={}", episodeId, panelIndex, e);
            throw new BusinessException("单分镜生产失败: " + e.getMessage());
        }
    }

    // ==================== 一键生成所有分镜 ====================

    /**
     * 一键生成所有分镜视频（异步）
     * 按顺序逐个执行，每个分镜完成后再执行下一个
     */
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void produceAllPanels(Long episodeId, Integer startFrom) {
        self().doProduceAllPanels(episodeId, startFrom);
    }

    @Async
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void doProduceAllPanels(Long episodeId, Integer startFrom) {
        try {
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode == null) {
                throw new BusinessException("剧集不存在");
            }

            int totalPanels = getTotalPanels(episode);
            if (totalPanels == 0) {
                throw new BusinessException("无分镜数据");
            }

            int startIndex = startFrom != null ? startFrom : 0;
            log.info("开始一键生成所有分镜: episodeId={}, totalPanels={}, startFrom={}",
                    episodeId, totalPanels, startIndex);

            for (int i = startIndex; i < totalPanels; i++) {
                try {
                    log.info("处理分镜 {}/{}: episodeId={}", i + 1, totalPanels, episodeId);
                    doProduceSinglePanel(episodeId, i, null);
                    log.info("分镜 {} 完成: episodeId={}", i, episodeId);
                } catch (Exception e) {
                    log.error("分镜 {} 生成失败，停止后续生成: episodeId={}, error={}",
                            i, episodeId, e.getMessage());
                    throw new BusinessException("分镜 #" + (i + 1) + " 生成失败: " + e.getMessage());
                }
            }

            log.info("所有分镜视频生成完成: episodeId={}", episodeId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("一键生成失败: episodeId={}", episodeId, e);
            throw new BusinessException("一键生成失败: " + e.getMessage());
        }
    }

    // ==================== 合成最终视频 ====================

    /**
     * 合成所有分镜视频为最终视频
     */
    @Transactional
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public CompositionResultResponse synthesizeEpisode(Long episodeId) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            throw new BusinessException("生产记录不存在");
        }

        List<VideoProductionTask> tasks = videoTaskRepository.findByEpisodeId(episodeId);
        List<VideoProductionTask> completedTasks = tasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getVideoUrl() != null)
                .sorted(Comparator.comparingInt(VideoProductionTask::getPanelIndex))
                .collect(Collectors.toList());

        if (completedTasks.isEmpty()) {
            throw new BusinessException("没有已完成的视频片段");
        }

        List<VideoSegmentModel> segments = completedTasks.stream()
                .map(t -> new VideoSegmentModel(t.getVideoUrl(), t.getTargetDuration() != null ? t.getTargetDuration().floatValue() : 5f, t.getPanelIndex()))
                .collect(Collectors.toList());

        String finalVideoUrl = videoCompositionService.composeVideo(segments, null);

        production.setFinalVideoUrl(finalVideoUrl);
        production.setStatus("COMPLETED");
        production.setCompletedAt(java.time.LocalDateTime.now());
        production.setProgressPercent(100);
        production.setProgressMessage("视频合成完成");
        productionRepository.updateById(production);

        CompositionResultResponse response = new CompositionResultResponse();
        response.setFinalVideoUrl(finalVideoUrl);
        response.setTotalSegments(completedTasks.size());
        response.setStatus("completed");
        return response;
    }

    // ==================== 获取完整分镜状态 ====================

    // ==================== 新版：基于 panelId 的生产状态 ====================

    /**
     * 获取单 Panel 完整生产状态（基于 panelId）
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
        response.setOverallStatus(determineNewOverallStatus(response));
        response.setCurrentStage(determineNewCurrentStage(response));
        return response;
    }

    /**
     * 批量获取所有 Panel 生产状态
     */
    public List<PanelProductionStatusResponse> getBatchProductionStatus(Long episodeId) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        return panels.stream().map(p -> getProductionStatus(p.getId())).collect(Collectors.toList());
    }

    // ==================== 新版：基于 panelId 的背景图 ====================

    /**
     * 获取背景图状态（基于 panelId）
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
     * 生成背景图（基于 panelId，异步）
     */
    public void generateBackgroundByPanelId(Long panelId) {
        self().doGenerateBackgroundByPanelId(panelId);
    }

    @Async
    public void doGenerateBackgroundByPanelId(Long panelId) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) throw new BusinessException("分镜不存在");
            String prompt = extractSceneDescription(panel);
            String imageUrl = imageGenerationService.generate(prompt, 1280, 720, "anime");
            Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
            info.put("backgroundUrl", imageUrl);
            info.put("backgroundStatus", "completed");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            log.info("背景图生成完成: panelId={}", panelId);
        } catch (Exception e) {
            log.error("背景图生成失败: panelId={}", panelId, e);
            updatePanelState(panelId, "backgroundStatus", "failed", e.getMessage());
            throw new BusinessException("背景图生成失败: " + e.getMessage());
        }
    }

    // ==================== 新版：基于 panelId 的视频 ====================

    /**
     * 获取视频状态（基于 panelId）
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
     * 生成视频（基于 panelId，异步）
     */
    public void generateVideoByPanelId(Long panelId) {
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
            String prompt = extractSceneDescription(panel);
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

    // ==================== 新版内部辅助方法 ====================

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }

    private String extractSceneDescription(Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) return "";
        String desc = getStr(info, "sceneDescription");
        if (desc != null && !desc.isEmpty()) return desc;
        desc = getStr(info, "image_prompt_hint");
        return desc != null ? desc : "";
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

    private String determineNewOverallStatus(PanelProductionStatusResponse r) {
        if ("completed".equals(r.getVideoStatus())) return "completed";
        if ("failed".equals(r.getVideoStatus()) || "failed".equals(r.getComicStatus()) || "failed".equals(r.getBackgroundStatus())) return "failed";
        if ("generating".equals(r.getVideoStatus()) || "generating".equals(r.getComicStatus()) || "generating".equals(r.getBackgroundStatus())) return "in_progress";
        if (r.getBackgroundUrl() != null || r.getComicUrl() != null) return "in_progress";
        return "pending";
    }

    private String determineNewCurrentStage(PanelProductionStatusResponse r) {
        if ("completed".equals(r.getVideoStatus())) return "video";
        if ("generating".equals(r.getVideoStatus())) return "video";
        if ("approved".equals(r.getComicStatus())) return "video";
        if ("generating".equals(r.getComicStatus())) return "comic";
        if ("pending".equals(r.getComicStatus()) && r.getBackgroundUrl() != null) return "comic";
        if ("generating".equals(r.getBackgroundStatus())) return "background";
        return "background";
    }

    // ==================== 内部辅助方法 ====================

    private String getEpisodeInfoStr(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private EpisodeProduction getOrCreateProduction(Long episodeId) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            throw new BusinessException("生产记录不存在，请先启动生产流程");
        }
        return production;
    }

    private String getPanelPrompt(Long episodeId, Integer panelIndex) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null || getEpisodeInfoStr(episode, "storyboardJson") == null) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(getEpisodeInfoStr(episode, "storyboardJson"));
            com.fasterxml.jackson.databind.JsonNode panels = root.get("panels");
            if (panels != null && panelIndex < panels.size()) {
                com.fasterxml.jackson.databind.JsonNode panel = panels.get(panelIndex);
                String scene = getTextOrDefault(panel, "scene", "");
                String background = "";
                com.fasterxml.jackson.databind.JsonNode bgNode = panel.get("background");
                if (bgNode != null) {
                    background = getTextOrDefault(bgNode, "scene_desc", "");
                }
                return !background.isEmpty() ? background : scene;
            }
        } catch (Exception e) {
            log.warn("解析分镜提示词失败: episodeId={}, panelIndex={}", episodeId, panelIndex, e);
        }
        return "";
    }

    private String getTextOrDefault(com.fasterxml.jackson.databind.JsonNode node, String field, String defaultVal) {
        com.fasterxml.jackson.databind.JsonNode child = node.get(field);
        if (child == null || child.isNull()) return defaultVal;
        return child.asText(defaultVal);
    }

    private List<String> getCharacterRefs(Long episodeId, Integer panelIndex) {
        // TODO: 从角色表获取角色参考图
        return Collections.emptyList();
    }

    private String getBackgroundUrl(Long episodeId, Integer panelIndex) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) return null;
        List<String> urls = parseUrlList(production.getBackgroundUrls());
        return getUrlFromList(urls, panelIndex);
    }

    private String getFusionUrl(Long episodeId, Integer panelIndex) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) return null;
        List<String> urls = parseUrlList(production.getFusionUrls());
        return getUrlFromList(urls, panelIndex);
    }

    private String getTransitionUrl(Long episodeId, Integer panelIndex) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) return null;
        List<String> urls = parseUrlList(production.getTransitionUrls());
        return getUrlFromList(urls, panelIndex);
    }

    private void generateVideo(Long episodeId, Integer panelIndex, String referenceImageUrl) {
        String prompt = getPanelPrompt(episodeId, panelIndex);
        String taskId = videoGenerationService.generateAsync(prompt, 5, "16:9", referenceImageUrl);

        // 创建视频任务记录
        VideoProductionTask task = new VideoProductionTask();
        task.setTaskId(taskId);
        task.setEpisodeId(episodeId);
        task.setPanelIndex(panelIndex);
        task.setSceneDescription(prompt);
        task.setVideoPrompt(prompt);
        task.setReferenceImageUrl(referenceImageUrl);
        task.setTargetDuration(5);
        task.setStatus("PROCESSING");
        videoTaskRepository.insert(task);

        // 异步轮询视频生成状态
        self().pollVideoTask(task.getId());
    }

    @Async
    @Deprecated // TODO: 后续迭代删除 — 已被 panelId 系列方法替代
    public void pollVideoTask(Long taskId) {
        try {
            int maxAttempts = 120;
            for (int i = 0; i < maxAttempts; i++) {
                VideoProductionTask task = videoTaskRepository.selectById(taskId);
                if (task == null) break;

                if ("COMPLETED".equals(task.getStatus())) break;
                if ("FAILED".equals(task.getStatus())) break;

                VideoGenerationService.TaskStatus status = videoGenerationService.getTaskStatus(task.getVideoTaskId());
                if (status == null) {
                    Thread.sleep(5000);
                    continue;
                }

                switch (status.getStatus()) {
                    case "completed":
                        String videoUrl = status.getVideoUrl();
                        if (videoUrl == null) videoUrl = videoGenerationService.downloadVideo(status.getTaskId());
                        task.setVideoUrl(videoUrl);
                        task.setStatus("COMPLETED");
                        task.setLastFrameUrl(status.getLastFrameUrl());
                        videoTaskRepository.updateById(task);
                        return;
                    case "failed":
                        task.setStatus("FAILED");
                        task.setErrorMessage(status.getErrorMessage());
                        videoTaskRepository.updateById(task);
                        return;
                    default:
                        Thread.sleep(5000);
                        break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频任务轮询异常: taskId={}", taskId, e);
        }
    }

    private String waitForBackground(Long episodeId, Integer panelIndex, int timeoutSeconds) throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            String url = getBackgroundUrl(episodeId, panelIndex);
            if (url != null) return url;
            Thread.sleep(1000);
        }
        throw new BusinessException("背景图生成超时");
    }

    private String waitForFusion(Long episodeId, Integer panelIndex, int timeoutSeconds) throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            String url = getFusionUrl(episodeId, panelIndex);
            if (url != null) return url;
            Thread.sleep(1000);
        }
        throw new BusinessException("融合图生成超时");
    }

    private String waitForTransition(Long episodeId, Integer panelIndex, int timeoutSeconds) throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            String url = getTransitionUrl(episodeId, panelIndex);
            if (url != null) return url;
            Thread.sleep(1000);
        }
        throw new BusinessException("过渡融合图生成超时");
    }

    private int getTotalPanels(Episode episode) {
        if (getEpisodeInfoStr(episode, "storyboardJson") == null) return 0;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(getEpisodeInfoStr(episode, "storyboardJson"));
            com.fasterxml.jackson.databind.JsonNode panels = root.get("panels");
            return panels != null ? panels.size() : 0;
        } catch (Exception e) {
            log.warn("解析分镜总数失败: episodeId={}", episode.getId(), e);
            return 0;
        }
    }

    // ==================== JSON URL List 辅助方法 ====================

    private List<String> parseUrlList(String json) {
        if (json == null || json.isEmpty() || "null".equals(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJsonUrlList(List<String> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void ensureListSize(List<String> list, int size) {
        while (list.size() < size) {
            list.add(null);
        }
    }

    private String getUrlFromList(List<String> urls, int index) {
        if (index >= 0 && index < urls.size()) {
            return urls.get(index);
        }
        return null;
    }

}
