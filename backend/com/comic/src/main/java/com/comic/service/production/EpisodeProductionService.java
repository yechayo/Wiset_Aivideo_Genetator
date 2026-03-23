package com.comic.service.production;

import com.comic.common.BusinessException;
import com.comic.dto.response.GridInfoResponse;
import com.comic.dto.response.PipelineStageDTO;
import com.comic.dto.response.ProductionPipelineResponse;
import com.comic.dto.response.ProductionStatusResponse;
import com.comic.dto.response.VideoSegmentInfoResponse;
import com.comic.dto.model.SceneAnalysisResultModel;
import com.comic.dto.model.SceneGroupModel;
import com.comic.dto.model.VideoPromptModel;
import com.comic.dto.model.VideoSegmentModel;
import com.comic.dto.model.VideoTaskGroupModel;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.repository.VideoProductionTaskRepository;
import com.comic.service.story.StoryboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;

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
    private final CharacterRepository characterRepository;
    private final SceneAnalysisService sceneAnalysisService;
    private final StoryboardEnhancementService storyboardEnhancementService;
    private final SceneGridGenService sceneGridGenService;
    private final GridSplitService gridSplitService;
    private final VideoPromptBuilderService videoPromptBuilderService;
    private final VideoProductionQueueService videoQueueService;
    private final SubtitleService subtitleService;
    private final VideoCompositionService videoCompositionService;
    private final StoryboardService storyboardService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    private EpisodeProductionService self() {
        return applicationContext.getBean(EpisodeProductionService.class);
    }

    /**
     * 启动单集视频生产
     *
     * @param episodeId 剧集ID
     * @return 生产任务ID
     */
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
        if (existing != null) {
            String existingStatus = existing.getStatus();
            if ("COMPLETED".equals(existingStatus)) {
                throw new IllegalStateException("剧集已完成生产，不可重复生产: " + existing.getProductionId());
            }
            if (!"FAILED".equals(existingStatus)) {
                // 中间状态（如 GRID_FUSION_PENDING），清除旧记录重新开始
                log.warn("清除中间态生产记录重新开始: episodeId={}, oldStatus={}, productionId={}",
                        episodeId, existingStatus, existing.getProductionId());
                productionRepository.deleteById(existing.getId());
                // 同时清理旧的视频分段任务，避免新旧任务混合导致合成结果污染
                videoTaskRepository.deleteByEpisodeId(episodeId);
            }
            // FAILED 状态允许重试，直接走下面的新建流程
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
        self().executeProductionFlow(episode, production);

        log.info("视频生产已启动: episodeId={}, productionId={}", episodeId, production.getProductionId());
        return production.getProductionId();
    }

    /**
     * 执行生产流程（异步）
     * 阶段1-2: 场景分析 + 九宫格生成（所有场景组），完成后暂停等待前端融合
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
            storyboardEnhancementService.enhanceEpisodeStoryboard(episode.getId());

            // 阶段2: 为每个场景组生成九宫格图
            List<SceneGroupModel> sceneGroups = sceneAnalysis.getSceneGroups();
            List<String> gridUrls = new ArrayList<>();
            int totalGridPages = resolveTotalGridPages(sceneGroups);
            int generatedGridPages = 0;

            if (!sceneGroups.isEmpty()) {
                updateProductionStatus(production, "GRID_GENERATING", "GRID_GENERATION",
                        11, String.format("正在生成场景参考图... (%d/%d)", 0, Math.max(totalGridPages, 1)));
            }

            for (int i = 0; i < sceneGroups.size(); i++) {
                SceneGroupModel sceneGroup = sceneGroups.get(i);
                List<String> sceneGroupPageUrls = sceneGridGenService.generateSceneGridPages(episode.getId(), sceneGroup);
                for (String gridUrl : sceneGroupPageUrls) {
                    gridUrls.add(gridUrl);
                    generatedGridPages++;

                // 每生成一张就落库，供前端轮询时实时展示
                if (production.getSceneGridUrl() == null || production.getSceneGridUrl().isEmpty()) {
                    production.setSceneGridUrl(gridUrl);
                }
                production.setSceneGridUrls(toJsonUrlList(gridUrls));
                productionRepository.updateById(production);

                int progress = 11 + (int) (generatedGridPages * 6.0 / Math.max(totalGridPages, 1));
                updateProductionStatus(production, "GRID_GENERATING", "GRID_GENERATION",
                        progress,
                        String.format("正在生成场景参考图... (%d/%d)", generatedGridPages, Math.max(totalGridPages, 1)));

                log.info("场景网格生成完成: sceneGroup={}/{}, url={}", i + 1, sceneGroups.size(), gridUrl);
            }
            }

            // 保存所有网格图URL
            production.setSceneGridUrl(gridUrls.isEmpty() ? null : gridUrls.get(0)); // 兼容旧字段
            production.setSceneGridUrls(toJsonUrlList(gridUrls));
            productionRepository.updateById(production);

            // 暂停等待前端融合
            updateProductionStatus(production, "GRID_FUSION_PENDING", "GRID_FUSION", 17, "等待图片融合...");
            log.info("管线暂停，等待前端融合: productionId={}, totalPages={}", production.getProductionId(), gridUrls.size());

        } catch (Exception e) {
            log.error("视频生产失败: productionId={}", production.getProductionId(), e);
            handleProductionFailure(production, episode, e);
        }
    }

    /**
     * 继续生产流程（异步）
     * 阶段3-7: 提示词构建 → 视频生成 → 字幕 → 合成 → 完成
     */
    @Async
    public void continueProductionFlow(Episode episode, EpisodeProduction production) {
        try {
            String projectId = episode.getProjectId();

            // 阶段3: 视频提示词构建
            updateProductionStatus(production, "BUILDING_PROMPTS", "PROMPT_BUILDING", 20, "正在构建视频提示词...");
            SceneAnalysisResultModel sceneAnalysis = objectMapper.readValue(
                    production.getSceneAnalysisJson(), SceneAnalysisResultModel.class);
            Project project = projectRepository.findByProjectId(projectId);
            List<VideoTaskGroupModel> taskGroups = videoPromptBuilderService.buildPromptsForPanels(
                    episode.getStoryboardJson(),
                    sceneAnalysis.getSceneGroups(),
                    project.getVisualStyle(),
                    projectId
            );

            // 注入融合参考图URL到每个prompt和任务组（逐格融合：二维数组按 pageIndex+panelIndex 定位）
            List<List<String>> allFusedUrls = parseFusedGridUrls(production.getFusedGridUrls());

            if (allFusedUrls == null || allFusedUrls.isEmpty()) {
                // 兼容旧逻辑：单一融合图
                String fusedUrl = production.getFusedReferenceUrl();
                if (fusedUrl != null && !fusedUrl.isEmpty()) {
                    for (VideoTaskGroupModel group : taskGroups) {
                        group.setFusedReferenceImageUrl(fusedUrl);
                    }
                }
            } else {
                // 按场景组分配：每个场景组对应一页，每页有9个融合图
                for (VideoTaskGroupModel group : taskGroups) {
                    int sceneGroupIndex = resolveSceneGroupIndex(group, sceneAnalysis.getSceneGroups());
                    int sceneGroupPageOffset = resolveSceneGroupPageOffset(sceneAnalysis.getSceneGroups(), sceneGroupIndex);
                    group.setFusedReferenceImageUrl(resolveFusedUrl(allFusedUrls, sceneGroupPageOffset, 0));
                    // 为每个 prompt 注入对应的格子融合图
                    if (group.getPrompts() != null) {
                        SceneGroupModel targetSceneGroup = sceneGroupIndex >= 0
                                && sceneGroupIndex < sceneAnalysis.getSceneGroups().size()
                                ? sceneAnalysis.getSceneGroups().get(sceneGroupIndex)
                                : null;
                        for (VideoPromptModel prompt : group.getPrompts()) {
                            PageCellAddress address = resolvePromptPageCellAddress(
                                    prompt.getPanelIndex(),
                                    targetSceneGroup,
                                    sceneGroupPageOffset
                            );
                            prompt.setFusedReferenceImageUrl(
                                    resolveFusedUrl(allFusedUrls, address.getPageIndex(), address.getCellIndex())
                            );
                        }
                    }
                }
            }

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
            handleProductionFailure(production, episode, e);
        }
    }

    /**
     * 处理生产失败
     */
    private void handleProductionFailure(EpisodeProduction production, Episode episode, Exception e) {
        production.setStatus("FAILED");
        production.setProgressPercent(0);
        production.setErrorMessage(e.getMessage());
        production.setCompletedAt(LocalDateTime.now());
        production.setRetryCount(production.getRetryCount() + 1);
        productionRepository.updateById(production);

        episode.setProductionStatus("FAILED");
        episodeRepository.updateById(episode);
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

            if (latest.getTotalPanels() == null || latest.getTotalPanels() <= 0) {
                throw new RuntimeException("总分镜数无效，无法计算生成进度");
            }

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
     * 获取视频片段列表
     */
    private List<VideoSegmentModel> getVideoSegments(Long episodeId) {
        List<com.comic.entity.VideoProductionTask> tasks = videoTaskRepository.findByEpisodeId(episodeId);

        return tasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getVideoUrl() != null)
                .map(t -> new VideoSegmentModel(
                        t.getVideoUrl(),
                t.getTargetDuration() != null ? t.getTargetDuration().floatValue() : 2.0f,
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
        dto.setFusedReferenceImageUrl(production.getFusedReferenceUrl());

        return dto;
    }

    /**
     * 获取视频片段列表（P1-7）
     */
    public List<VideoSegmentInfoResponse> getVideoSegmentInfos(Long episodeId) {
        List<com.comic.entity.VideoProductionTask> tasks = videoTaskRepository.findByEpisodeId(episodeId);
        return tasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getVideoUrl() != null)
                .sorted((a, b) -> Integer.compare(
                        a.getPanelIndex() != null ? a.getPanelIndex() : 0,
                        b.getPanelIndex() != null ? b.getPanelIndex() : 0))
                .map(t -> {
                    VideoSegmentInfoResponse info = new VideoSegmentInfoResponse();
                    info.setPanelIndex(t.getPanelIndex());
                    info.setVideoUrl(t.getVideoUrl());
                    info.setTargetDuration(t.getTargetDuration());
                    info.setVideoPrompt(t.getVideoPrompt());
                    info.setSceneDescription(t.getSceneDescription());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取网格拆分/融合所需信息（支持多页）
     */
    public GridInfoResponse getGridInfo(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("剧集不存在: " + episodeId);
        }

        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            throw new IllegalArgumentException("生产任务不存在");
        }

        GridInfoResponse response = new GridInfoResponse();
        response.setSceneGridUrl(production.getSceneGridUrl());

        // 解析场景分析结果
        List<SceneGroupModel> sceneGroups = new ArrayList<>();
        if (production.getSceneAnalysisJson() != null) {
            try {
                SceneAnalysisResultModel analysis = objectMapper.readValue(
                        production.getSceneAnalysisJson(), SceneAnalysisResultModel.class);
                if (analysis.getSceneGroups() != null) {
                    sceneGroups = analysis.getSceneGroups();
                }
            } catch (Exception e) {
                log.warn("解析场景分析JSON失败: {}", e.getMessage());
            }
        }

        // 构建多页网格信息
        List<GridPageDescriptor> pageDescriptors = buildGridPageDescriptors(sceneGroups);
        if (!pageDescriptors.isEmpty()) {
            response.setGridRows(pageDescriptors.get(0).getRows());
            response.setGridColumns(pageDescriptors.get(0).getCols());
        }

        List<String> gridUrls = parseJsonUrlList(production.getSceneGridUrls());
        List<List<String>> allFusedUrls = parseFusedGridUrls(production.getFusedGridUrls());
        List<GridInfoResponse.GridPageInfo> gridPages = new ArrayList<>();
        if (gridUrls != null && !gridUrls.isEmpty()) {
            for (int i = 0; i < gridUrls.size(); i++) {
                GridInfoResponse.GridPageInfo page = new GridInfoResponse.GridPageInfo();
                page.setSceneGridUrl(gridUrls.get(i));
                GridPageDescriptor descriptor = i < pageDescriptors.size() ? pageDescriptors.get(i) : null;
                page.setSceneGroupIndex(descriptor != null ? descriptor.getSceneGroupIndex() : i);
                // 逐格融合：每页返回该页的 fusedPanels 列表
                List<String> pageFused = (i < allFusedUrls.size()) ? allFusedUrls.get(i) : null;
                page.setFusedPanels(pageFused);
                page.setFused(pageFused != null && !pageFused.isEmpty()
                        && pageFused.stream().anyMatch(u -> u != null && !u.isEmpty()));
                if (descriptor != null
                        && descriptor.getSceneGroupIndex() >= 0
                        && descriptor.getSceneGroupIndex() < sceneGroups.size()) {
                    SceneGroupModel sceneGroup = sceneGroups.get(descriptor.getSceneGroupIndex());
                    page.setLocation(sceneGroup.getLocation());
                    page.setCharacters(sceneGroup.getCharacters());
                    page.setGridRows(descriptor.getRows());
                    page.setGridColumns(descriptor.getCols());
                } else if (!sceneGroups.isEmpty()) {
                    page.setLocation(sceneGroups.get(0).getLocation());
                    page.setCharacters(sceneGroups.get(0).getCharacters());
                }
                gridPages.add(page);
            }
        } else if (production.getSceneGridUrl() != null) {
            // 兼容旧数据：只有单张网格图
            GridInfoResponse.GridPageInfo page = new GridInfoResponse.GridPageInfo();
            page.setSceneGridUrl(production.getSceneGridUrl());
            page.setSceneGroupIndex(0);
            page.setFused(production.getFusedReferenceUrl() != null && !production.getFusedReferenceUrl().isEmpty());
            if (!sceneGroups.isEmpty()) {
                page.setLocation(sceneGroups.get(0).getLocation());
                page.setCharacters(sceneGroups.get(0).getCharacters());
            }
            if (!pageDescriptors.isEmpty()) {
                page.setGridRows(pageDescriptors.get(0).getRows());
                page.setGridColumns(pageDescriptors.get(0).getCols());
            }
            gridPages.add(page);
        }
        response.setGridPages(gridPages);
        response.setTotalPages(gridPages.size());

        // 收集角色
        List<String> characterNames = new ArrayList<>();
        for (SceneGroupModel group : sceneGroups) {
            if (group.getCharacters() != null) {
                characterNames.addAll(group.getCharacters());
            }
        }

        // 查询角色参考图
        String projectId = episode.getProjectId();
        List<Character> confirmedCharacters = characterRepository.findConfirmedByProjectId(projectId);
        Map<String, Character> characterMap = confirmedCharacters.stream()
                .collect(Collectors.toMap(Character::getName, c -> c, (a, b) -> a));

        List<GridInfoResponse.CharacterReferenceInfo> refs = characterNames.stream()
                .distinct()
                .filter(characterMap::containsKey)
                .map(name -> {
                    Character c = characterMap.get(name);
                    GridInfoResponse.CharacterReferenceInfo info = new GridInfoResponse.CharacterReferenceInfo();
                    info.setCharacterName(name);
                    info.setThreeViewGridUrl(c.getThreeViewGridUrl());
                    info.setExpressionGridUrl(c.getExpressionGridUrl());
                    return info;
                })
                .collect(Collectors.toList());

        response.setCharacterReferences(refs);
        return response;
    }

    /**
     * Execute backend split for one grid page and bind row-major cells to storyboard panels.
     */
    public GridSplitService.SplitPageResult splitGridPageForFusion(Long episodeId, int pageIndex) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }

        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            throw new IllegalArgumentException("Production record not found.");
        }
        if (!"GRID_FUSION_PENDING".equals(production.getStatus())) {
            throw new BusinessException("Grid split is only allowed when status is GRID_FUSION_PENDING.");
        }

        List<String> gridUrls = resolveGridUrls(production);
        if (gridUrls == null || gridUrls.isEmpty()) {
            throw new BusinessException("No scene grid page URL available.");
        }
        if (pageIndex < 0 || pageIndex >= gridUrls.size()) {
            throw new BusinessException("pageIndex out of range. totalPages=" + gridUrls.size());
        }

        List<SceneGroupModel> sceneGroups = resolveSceneGroups(production);
        List<GridPageDescriptor> pageDescriptors = buildGridPageDescriptors(sceneGroups);
        GridPageDescriptor descriptor = pageIndex < pageDescriptors.size() ? pageDescriptors.get(pageIndex) : null;

        int rows = descriptor != null ? descriptor.getRows() : 3;
        int cols = descriptor != null ? descriptor.getCols() : 3;
        int cellsPerPage = Math.max(rows * cols, 1);

        List<JsonNode> storyboardPanels = parseStoryboardPanels(episode);
        int startPanelIndex;
        int validPanelCount;
        if (descriptor != null
                && descriptor.getSceneGroupIndex() >= 0
                && descriptor.getSceneGroupIndex() < sceneGroups.size()) {
            SceneGroupModel sceneGroup = sceneGroups.get(descriptor.getSceneGroupIndex());
            int sceneGroupStart = sceneGroup.getStartPanelIndex() != null ? sceneGroup.getStartPanelIndex() : 0;
            int sceneCellsPerPage = Math.max(resolveCellsPerPage(sceneGroup), 1);
            int pageInGroup = Math.max(descriptor.getPageInGroup(), 0);
            int panelCountInGroup = Math.max(sceneGroup.getPanelCount(), 0);
            int consumed = pageInGroup * sceneCellsPerPage;

            startPanelIndex = sceneGroupStart + consumed;
            validPanelCount = Math.max(0, Math.min(sceneCellsPerPage, panelCountInGroup - consumed));
        } else {
            startPanelIndex = pageIndex * cellsPerPage;
            validPanelCount = Math.max(0, Math.min(cellsPerPage, storyboardPanels.size() - startPanelIndex));
        }

        List<JsonNode> pagePanels = new ArrayList<>();
        for (int i = 0; i < validPanelCount; i++) {
            int panelIndex = startPanelIndex + i;
            if (panelIndex >= 0 && panelIndex < storyboardPanels.size()) {
                pagePanels.add(storyboardPanels.get(panelIndex));
            }
        }

        GridSplitService.PageSplitTask task = new GridSplitService.PageSplitTask();
        task.setPageIndex(pageIndex);
        task.setGridImageUrl(gridUrls.get(pageIndex));
        task.setRows(rows);
        task.setCols(cols);
        task.setStartPanelIndex(startPanelIndex);
        task.setPanels(pagePanels);
        task.setObjectKeyPrefix("episode-" + episodeId + "/grid-split");

        GridSplitService.SplitBatchResult batchResult =
                gridSplitService.splitAndUploadPages(Collections.singletonList(task));
        if (batchResult == null || batchResult.getPages() == null || batchResult.getPages().isEmpty()) {
            throw new BusinessException("Grid split returned empty result.");
        }
        return batchResult.getPages().get(0);
    }

    private List<String> resolveGridUrls(EpisodeProduction production) {
        List<String> gridUrls = parseJsonUrlList(production.getSceneGridUrls());
        if (gridUrls != null && !gridUrls.isEmpty()) {
            return gridUrls;
        }
        if (production.getSceneGridUrl() == null || production.getSceneGridUrl().isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Collections.singletonList(production.getSceneGridUrl()));
    }

    private List<SceneGroupModel> resolveSceneGroups(EpisodeProduction production) {
        if (production == null
                || production.getSceneAnalysisJson() == null
                || production.getSceneAnalysisJson().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            SceneAnalysisResultModel analysis = objectMapper.readValue(
                    production.getSceneAnalysisJson(), SceneAnalysisResultModel.class);
            if (analysis.getSceneGroups() == null) {
                return Collections.emptyList();
            }
            return analysis.getSceneGroups();
        } catch (Exception e) {
            log.warn("Failed to parse sceneAnalysisJson when building split task: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<JsonNode> parseStoryboardPanels(Episode episode) {
        if (episode == null || episode.getStoryboardJson() == null || episode.getStoryboardJson().isEmpty()) {
            throw new BusinessException("Storyboard JSON is empty.");
        }
        try {
            JsonNode root = objectMapper.readTree(episode.getStoryboardJson());
            JsonNode panelsNode = root.get("panels");
            if (panelsNode == null || !panelsNode.isArray()) {
                throw new BusinessException("Invalid storyboard JSON: panels is missing or not an array.");
            }
            List<JsonNode> panels = new ArrayList<>();
            for (JsonNode panel : panelsNode) {
                panels.add(panel);
            }
            return panels;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse storyboard JSON for split.");
        }
    }

    /**
     * 前端融合完成后恢复管线（旧接口兼容）
     */
    public void resumeAfterFusion(Long episodeId, String fusedReferenceImageUrl) {
        EpisodeProduction production = productionRepository.findByEpisodeId(episodeId);
        if (production == null) {
            throw new IllegalArgumentException("生产任务不存在");
        }

        if (!"GRID_FUSION_PENDING".equals(production.getStatus())) {
            throw new IllegalStateException("当前状态不允许提交融合结果: " + production.getStatus());
        }

        production.setFusedReferenceUrl(fusedReferenceImageUrl);
        production.setFusedGridUrls(toJsonUrlList(Collections.singletonList(fusedReferenceImageUrl)));
        productionRepository.updateById(production);

        log.info("融合图已保存，恢复管线: episodeId={}, fusedUrl={}", episodeId, fusedReferenceImageUrl);

        if (!productionRepository.tryMarkFusionResumed(episodeId)) {
            log.warn("融合恢复已被其他请求触发，忽略重复请求: episodeId={}", episodeId);
            return;
        }

        Episode episode = episodeRepository.selectById(episodeId);
        self().continueProductionFlow(episode, production);
    }

    /**
     * 提交单页融合结果（逐格融合：每页9个URL）
     * 当所有页的所有格子都融合完成后自动恢复管线
     */
    public int submitFusionPage(Long episodeId, int pageIndex, List<String> panelFusedUrls) {
        EpisodeProduction production;
        try {
            production = productionRepository.findByEpisodeId(episodeId);
        } catch (Exception e) {
            log.warn("查询生产记录失败（可能正在生产中）: episodeId={}", episodeId);
            throw new BusinessException("正在处理中，请稍后再试");
        }
        if (production == null) {
            throw new IllegalArgumentException("生产任务不存在");
        }

        if (!"GRID_FUSION_PENDING".equals(production.getStatus())) {
            throw new BusinessException("融合阶段已结束，无需再次提交（当前状态: " + production.getStatus() + "）");
        }

        // 获取总页数并校验页码边界
        List<String> gridUrls = parseJsonUrlList(production.getSceneGridUrls());
        int totalPages = (gridUrls != null && !gridUrls.isEmpty()) ? gridUrls.size() : 1;
        if (pageIndex < 0 || pageIndex >= totalPages) {
            throw new BusinessException("pageIndex 越界，当前总页数: " + totalPages);
        }
        List<Integer> expectedCellsByPage = resolveExpectedGridCellCountsByPage(production, totalPages);

        // 解析已有融合数据（二维数组结构）
        List<List<String>> allFusedUrls = parseFusedGridUrls(production.getFusedGridUrls());

        // 确保外层列表足够长
        while (allFusedUrls.size() <= pageIndex) {
            allFusedUrls.add(new ArrayList<>());
        }
        // 设置当前页的融合URL
        allFusedUrls.set(pageIndex, new ArrayList<>(panelFusedUrls));

        // 保存（二维数组格式）
        production.setFusedGridUrls(toJsonFusedUrls(allFusedUrls));
        // 兼容旧字段
        production.setFusedReferenceUrl(
                allFusedUrls.get(0).isEmpty() ? null : allFusedUrls.get(0).get(0));
        productionRepository.updateById(production);

        // 计算总已融合格子数
        int totalFused = 0;
        for (int i = 0; i < totalPages; i++) {
            if (i < allFusedUrls.size() && allFusedUrls.get(i) != null) {
                totalFused += (int) allFusedUrls.get(i).stream()
                        .filter(url -> url != null && !url.isEmpty())
                        .count();
            }
        }
        int totalExpectedCells = expectedCellsByPage.stream().mapToInt(Integer::intValue).sum();
        production.setTotalFusedPanels(totalExpectedCells);
        production.setFusedPanels(totalFused);
        productionRepository.updateById(production);

        log.info("融合页已保存: episodeId={}, page={}/{}, panelsThisPage={}, totalFused={}",
                episodeId, pageIndex + 1, totalPages, panelFusedUrls.size(), totalFused);

        // 检查是否所有页的所有格子都融合完成
        boolean allDone = true;
        for (int i = 0; i < totalPages; i++) {
            int expectedCells = i < expectedCellsByPage.size() ? expectedCellsByPage.get(i) : 9;
            if (i >= allFusedUrls.size() || allFusedUrls.get(i) == null
                    || allFusedUrls.get(i).size() < expectedCells) {
                allDone = false;
                break;
            }
            for (int j = 0; j < expectedCells; j++) {
                String url = allFusedUrls.get(i).get(j);
                if (url == null || url.isEmpty()) {
                    allDone = false;
                    break;
                }
            }
            if (!allDone) break;
        }

        if (allDone) {
            if (productionRepository.tryMarkFusionResumed(episodeId)) {
                log.info("所有融合页已完成，自动恢复管线: episodeId={}", episodeId);
                Episode episode = episodeRepository.selectById(episodeId);
                self().continueProductionFlow(episode, production);
            } else {
                log.warn("融合恢复已被其他请求触发，忽略重复恢复: episodeId={}", episodeId);
            }
        }

        return totalFused;
    }

    /**
     * 解析二维融合URL数组
     * 格式: [["url1","url2",...],["url1","url2",...]]
     */
    private List<List<String>> parseFusedGridUrls(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<List<String>>>() {});
        } catch (Exception e) {
            log.warn("解析融合URL二维数组失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 将二维URL列表序列化为JSON
     */
    private String toJsonFusedUrls(List<List<String>> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            log.warn("序列化融合URL二维数组失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 重试失败的生产
     */
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

        // 清理旧任务，避免历史分段污染本轮生产
        videoTaskRepository.deleteByEpisodeId(episodeId);

        // 重置状态并重新开始
        Episode episode = episodeRepository.selectById(episodeId);
        production.setStatus("ANALYZING");
        production.setCurrentStage("SCENE_ANALYSIS");
        production.setProgressPercent(5);
        production.setProgressMessage("开始场景分析...");
        production.setErrorMessage(null);
        production.setFinalVideoUrl(null);
        production.setSubtitleUrl(null);
        production.setCompletedAt(null);
        production.setStartedAt(LocalDateTime.now());
        production.setCompletedPanels(0);
        production.setCompletedVideoGroups(0);
        production.setSceneGridUrl(null);
        production.setSceneGridUrls(null);
        production.setFusedReferenceUrl(null);
        production.setFusedGridUrls(null);
        productionRepository.updateById(production);

        episode.setProductionStatus("IN_PROGRESS");
        episode.setProductionProgress(5);
        episode.setFinalVideoUrl(null);
        episodeRepository.updateById(episode);

        self().executeProductionFlow(episode, production);
    }

    /**
     * 解析JSON数组格式的URL列表
     */
    private List<String> parseJsonUrlList(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析URL列表JSON失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将URL列表序列化为JSON数组
     */
    private String toJsonUrlList(List<String> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            log.warn("序列化URL列表失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 根据任务组的第一个分镜索引确定其所属的场景组索引
     */
    private int resolveSceneGroupIndex(VideoTaskGroupModel group, List<SceneGroupModel> sceneGroups) {
        if (group.getPanelIndexes() == null || group.getPanelIndexes().isEmpty() || sceneGroups == null) {
            return 0;
        }
        int firstPanel = group.getPanelIndexes().get(0);
        for (int i = 0; i < sceneGroups.size(); i++) {
            SceneGroupModel sg = sceneGroups.get(i);
            if (sg.getStartPanelIndex() != null && sg.getEndPanelIndex() != null
                    && firstPanel >= sg.getStartPanelIndex() && firstPanel <= sg.getEndPanelIndex()) {
                return i;
            }
        }
        return 0;
    }

    private int resolveTotalGridPages(List<SceneGroupModel> sceneGroups) {
        if (sceneGroups == null || sceneGroups.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (SceneGroupModel group : sceneGroups) {
            total += resolvePageCountForSceneGroup(group);
        }
        return total;
    }

    private int resolveCellsPerPage(SceneGroupModel sceneGroup) {
        int panelCount = Math.max(sceneGroup != null ? sceneGroup.getPanelCount() : 0, 0);
        return panelCount <= 6 ? 6 : 9;
    }

    private int resolveGridRows(SceneGroupModel sceneGroup) {
        int panelCount = Math.max(sceneGroup != null ? sceneGroup.getPanelCount() : 0, 0);
        return panelCount <= 6 ? 2 : 3;
    }

    private int resolveGridCols() {
        return 3;
    }

    private int resolvePageCountForSceneGroup(SceneGroupModel sceneGroup) {
        int panelCount = Math.max(sceneGroup != null ? sceneGroup.getPanelCount() : 0, 0);
        int cellsPerPage = Math.max(resolveCellsPerPage(sceneGroup), 1);
        return Math.max(1, (panelCount + cellsPerPage - 1) / cellsPerPage);
    }

    private int resolveSceneGroupPageOffset(List<SceneGroupModel> sceneGroups, int sceneGroupIndex) {
        if (sceneGroups == null || sceneGroups.isEmpty() || sceneGroupIndex <= 0) {
            return 0;
        }
        int offset = 0;
        int safeIndex = Math.min(sceneGroupIndex, sceneGroups.size());
        for (int i = 0; i < safeIndex; i++) {
            offset += resolvePageCountForSceneGroup(sceneGroups.get(i));
        }
        return offset;
    }

    private PageCellAddress resolvePromptPageCellAddress(
            Integer panelIndex,
            SceneGroupModel sceneGroup,
            int sceneGroupPageOffset
    ) {
        int safePanelIndex = panelIndex != null ? panelIndex : 0;
        if (sceneGroup == null) {
            return new PageCellAddress(sceneGroupPageOffset, 0);
        }

        int start = sceneGroup.getStartPanelIndex() != null ? sceneGroup.getStartPanelIndex() : safePanelIndex;
        int relativeIndex = Math.max(0, safePanelIndex - start);
        int cellsPerPage = Math.max(resolveCellsPerPage(sceneGroup), 1);
        int pageInGroup = relativeIndex / cellsPerPage;
        int cellInPage = relativeIndex % cellsPerPage;
        return new PageCellAddress(sceneGroupPageOffset + pageInGroup, cellInPage);
    }

    private String resolveFusedUrl(List<List<String>> allFusedUrls, int pageIndex, int cellIndex) {
        if (allFusedUrls == null || pageIndex < 0 || pageIndex >= allFusedUrls.size()) {
            return null;
        }
        List<String> pageFusedUrls = allFusedUrls.get(pageIndex);
        if (pageFusedUrls == null || cellIndex < 0 || cellIndex >= pageFusedUrls.size()) {
            return null;
        }
        String url = pageFusedUrls.get(cellIndex);
        return (url == null || url.isEmpty()) ? null : url;
    }

    private List<Integer> resolveExpectedGridCellCountsByPage(EpisodeProduction production, int totalPages) {
        List<Integer> counts = new ArrayList<>();
        if (production != null && production.getSceneAnalysisJson() != null && !production.getSceneAnalysisJson().isEmpty()) {
            try {
                SceneAnalysisResultModel analysis = objectMapper.readValue(
                        production.getSceneAnalysisJson(), SceneAnalysisResultModel.class);
                if (analysis.getSceneGroups() != null) {
                    for (SceneGroupModel group : analysis.getSceneGroups()) {
                        int pageCount = resolvePageCountForSceneGroup(group);
                        int cellsPerPage = Math.max(resolveCellsPerPage(group), 1);
                        for (int i = 0; i < pageCount; i++) {
                            counts.add(cellsPerPage);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析场景分页配置失败，回退默认每页9格: {}", e.getMessage());
            }
        }

        if (counts.isEmpty()) {
            for (int i = 0; i < totalPages; i++) {
                counts.add(9);
            }
            return counts;
        }

        while (counts.size() < totalPages) {
            counts.add(9);
        }
        if (counts.size() > totalPages) {
            return new ArrayList<>(counts.subList(0, totalPages));
        }
        return counts;
    }

    private List<GridPageDescriptor> buildGridPageDescriptors(List<SceneGroupModel> sceneGroups) {
        List<GridPageDescriptor> descriptors = new ArrayList<>();
        if (sceneGroups == null || sceneGroups.isEmpty()) {
            return descriptors;
        }

        for (int sceneGroupIndex = 0; sceneGroupIndex < sceneGroups.size(); sceneGroupIndex++) {
            SceneGroupModel sceneGroup = sceneGroups.get(sceneGroupIndex);
            int rows = resolveGridRows(sceneGroup);
            int cols = resolveGridCols();
            int pageCount = resolvePageCountForSceneGroup(sceneGroup);
            for (int pageInGroup = 0; pageInGroup < pageCount; pageInGroup++) {
                descriptors.add(new GridPageDescriptor(sceneGroupIndex, pageInGroup, rows, cols));
            }
        }
        return descriptors;
    }

    private static final class PageCellAddress {
        private final int pageIndex;
        private final int cellIndex;

        private PageCellAddress(int pageIndex, int cellIndex) {
            this.pageIndex = pageIndex;
            this.cellIndex = cellIndex;
        }

        private int getPageIndex() {
            return pageIndex;
        }

        private int getCellIndex() {
            return cellIndex;
        }
    }

    private static final class GridPageDescriptor {
        private final int sceneGroupIndex;
        private final int pageInGroup;
        private final int rows;
        private final int cols;

        private GridPageDescriptor(int sceneGroupIndex, int pageInGroup, int rows, int cols) {
            this.sceneGroupIndex = sceneGroupIndex;
            this.pageInGroup = pageInGroup;
            this.rows = rows;
            this.cols = cols;
        }

        private int getSceneGroupIndex() {
            return sceneGroupIndex;
        }

        private int getPageInGroup() {
            return pageInGroup;
        }

        private int getRows() {
            return rows;
        }

        private int getCols() {
            return cols;
        }
    }

    /**
     * 为项目启动生产（从PipelineService调用）
     * 先确保所有 DRAFT 状态的剧集生成分镜脚本，再启动第一个可生产的剧集
     */
    public void startProductionForProject(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);

        // 先为所有 DRAFT 状态的剧集生成分镜脚本
        for (Episode episode : episodes) {
            if ("DRAFT".equals(episode.getStatus()) || "FAILED".equals(episode.getStatus())) {
                log.info("剧集分镜未生成，先生成分镜: episodeId={}, status={}", episode.getId(), episode.getStatus());
                try {
                    storyboardService.generateStoryboard(episode.getId());
                    log.info("剧集分镜生成完成: episodeId={}", episode.getId());
                } catch (Exception e) {
                    log.error("剧集分镜生成失败: episodeId={}, error={}", episode.getId(), e.getMessage(), e);
                    throw new IllegalStateException("剧集分镜生成失败: " + e.getMessage(), e);
                }
            }
        }

        // 重新查询（上面生成分镜后状态已变更）
        episodes = episodeRepository.findByProjectId(projectId);
        for (Episode episode : episodes) {
            String prodStatus = episode.getProductionStatus();
            if ("DONE".equals(episode.getStatus())
                    && (prodStatus == null || "NOT_STARTED".equals(prodStatus) || "FAILED".equals(prodStatus))) {
                startProduction(episode.getId());
                return;
            }
        }

        log.warn("没有找到可生产的剧集: projectId={}, episodes={}", projectId,
                episodes.stream().map(e -> String.format("[id=%d,status=%s,prodStatus=%s]",
                        e.getId(), e.getStatus(), e.getProductionStatus())).collect(java.util.stream.Collectors.toList()));
        throw new IllegalStateException("没有找到可以生产的剧集");
    }

    /**
     * 获取项目生产管线全链路状态（供 Step5 页面可视化）
     *
     * @param projectId 项目ID
     * @return 管线全链路状态
     */
    public ProductionPipelineResponse getProductionPipeline(String projectId) {
        ProductionPipelineResponse response = new ProductionPipelineResponse();

        // 查找第一个可生产的 episode
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        log.info("[Pipeline] 查询管线状态: projectId={}, episodes.size={}", projectId, episodes.size());
        for (Episode ep : episodes) {
            log.info("[Pipeline]   episode: id={}, num={}, status={}, prodStatus={}",
                    ep.getId(), ep.getEpisodeNum(), ep.getStatus(), ep.getProductionStatus());
        }
        Episode targetEpisode = null;

        // 优先找 IN_PROGRESS 的
        for (Episode ep : episodes) {
            if ("IN_PROGRESS".equals(ep.getProductionStatus())) {
                targetEpisode = ep;
                break;
            }
        }
        // 其次找 NOT_STARTED 或 DONE 但未开始生产的
        if (targetEpisode == null) {
            for (Episode ep : episodes) {
                if ("NOT_STARTED".equals(ep.getProductionStatus())
                        || ("DONE".equals(ep.getStatus()) && ep.getProductionStatus() == null)) {
                    targetEpisode = ep;
                    break;
                }
            }
        }
        // 再找 COMPLETED 的
        if (targetEpisode == null) {
            for (Episode ep : episodes) {
                if ("COMPLETED".equals(ep.getProductionStatus())) {
                    targetEpisode = ep;
                    break;
                }
            }
        }
        // 最后找 FAILED 的
        if (targetEpisode == null) {
            for (Episode ep : episodes) {
                if ("FAILED".equals(ep.getProductionStatus())) {
                    targetEpisode = ep;
                    break;
                }
            }
        }
        // 兜底：取第一个 episode（status=DRAFT 且 productionStatus=null 等未覆盖的场景）
        if (targetEpisode == null && !episodes.isEmpty()) {
            targetEpisode = episodes.get(0);
        }

        if (targetEpisode == null) {
            // 没有任何剧集，返回全 pending
            response.setEpisodeId(null);
            response.setEpisodeStatus("DRAFT");
            response.setProductionStatus("NOT_STARTED");
            response.setStages(buildAllPendingStages(null));
            return response;
        }

        response.setEpisodeId(String.valueOf(targetEpisode.getId()));
        response.setEpisodeTitle(targetEpisode.getTitle());
        response.setEpisodeStatus(targetEpisode.getStatus());
        response.setProductionStatus(targetEpisode.getProductionStatus());

        // 读取 EpisodeProduction
        EpisodeProduction production = productionRepository.findByEpisodeId(targetEpisode.getId());

        // 组装管线阶段
        List<PipelineStageDTO> stages = buildPipelineStages(targetEpisode, production);
        response.setStages(stages);
        response.setFinalVideoUrl(targetEpisode.getFinalVideoUrl());

        if (production != null) {
            List<String> gridUrls = parseJsonUrlList(production.getSceneGridUrls());
            if (gridUrls == null || gridUrls.isEmpty()) {
                if (production.getSceneGridUrl() != null && !production.getSceneGridUrl().isEmpty()) {
                    gridUrls = Collections.singletonList(production.getSceneGridUrl());
                } else {
                    gridUrls = Collections.emptyList();
                }
            }
            response.setSceneGridUrls(gridUrls);
        } else {
            response.setSceneGridUrls(Collections.emptyList());
        }

        if (production != null) {
            response.setErrorMessage(production.getErrorMessage());
        } else if ("FAILED".equals(targetEpisode.getStatus())) {
            response.setErrorMessage(targetEpisode.getErrorMsg());
        }

        return response;
    }

    /**
     * 组装管线阶段列表
     */
    private List<PipelineStageDTO> buildPipelineStages(Episode episode, EpisodeProduction production) {
        List<PipelineStageDTO> stages = new ArrayList<>();

        String episodeStatus = episode.getStatus(); // DRAFT, GENERATING, DONE, FAILED
        String prodStatus = production != null ? production.getStatus() : null; // ANALYZING, GRID_GENERATING, etc.
        String prodErrorMessage = production != null ? production.getErrorMessage() : null;
        int prodProgress = production != null && production.getProgressPercent() != null ? production.getProgressPercent() : 0;
        String prodMessage = production != null ? production.getProgressMessage() : null;

        // 1. 分镜生成 — 基于 Episode.status
        stages.add(buildStoryboardStage(episodeStatus, episode.getErrorMsg()));

        if ("DRAFT".equals(episodeStatus) || "FAILED".equals(episodeStatus) && prodStatus == null) {
            // 分镜还没完成，后续阶段全 pending
            List<PipelineStageDTO> remaining = buildRemainingPendingStages(1);
            stages.addAll(remaining);
            return stages;
        }

        // 分镜已完成，查看生产状态
        if (production == null || prodStatus == null) {
            // 分镜完成但未开始生产
            List<PipelineStageDTO> remaining = buildRemainingPendingStages(1);
            stages.addAll(remaining);
            return stages;
        }

        // 2-9: 根据 EpisodeProduction.status 映射
        // 阶段顺序: analyzing, grid_generating, grid_fusion, prompt_building, video_generating, subtitle, composition, completed
        String[][] stageDefinitions = {
            {"analyzing",       "场景分析"},
            {"grid_generating", "九宫格生成"},
            {"grid_fusion",     "图片融合"},
            {"prompt_building", "提示词构建"},
            {"video_generating","视频生成"},
            {"subtitle",        "字幕生成"},
            {"composition",     "视频合成"},
            {"completed",       "完成"},
        };

        // 找到当前活跃阶段
        int activeIndex = -1;
        boolean isFailed = "FAILED".equals(prodStatus);
        boolean isWaitingUser = "GRID_FUSION_PENDING".equals(prodStatus);

        if ("COMPLETED".equals(prodStatus)) {
            activeIndex = stageDefinitions.length; // 全部完成
        } else if ("FAILED".equals(prodStatus)) {
            // 找到失败对应的阶段
            activeIndex = mapProductionStatusToIndex(prodStatus, production.getCurrentStage());
        } else if ("GRID_FUSION_PENDING".equals(prodStatus)) {
            activeIndex = 2; // grid_fusion is index 2
        } else {
            activeIndex = mapProductionStatusToIndex(prodStatus, production.getCurrentStage());
        }

        for (int i = 0; i < stageDefinitions.length; i++) {
            PipelineStageDTO stage = new PipelineStageDTO();
            stage.setKey(stageDefinitions[i][0]);
            stage.setName(stageDefinitions[i][1]);

            if (i < activeIndex) {
                // 已完成
                stage.setDisplayStatus("completed");
                stage.setProgress(100);
                stage.setMessage("已完成");
            } else if (i == activeIndex) {
                // 当前阶段
                if (isFailed) {
                    stage.setDisplayStatus("failed");
                    stage.setProgress(prodProgress);
                    stage.setMessage(prodErrorMessage != null ? prodErrorMessage : "处理失败");
                } else if (isWaitingUser) {
                    stage.setDisplayStatus("waiting_user");
                    stage.setProgress(prodProgress);
                    stage.setMessage("等待操作");
                } else {
                    stage.setDisplayStatus("active");
                    stage.setProgress(prodProgress);
                    stage.setMessage(prodMessage != null ? prodMessage : "处理中...");
                }
            } else {
                // 未开始
                stage.setDisplayStatus("pending");
                stage.setProgress(0);
                stage.setMessage("等待中");
            }

            stages.add(stage);
        }

        return stages;
    }

    /**
     * 构建分镜阶段
     */
    private PipelineStageDTO buildStoryboardStage(String episodeStatus, String errorMsg) {
        PipelineStageDTO stage = new PipelineStageDTO();
        stage.setKey("storyboard");
        stage.setName("分镜生成");

        switch (episodeStatus) {
            case "DRAFT":
                stage.setDisplayStatus("active");
                stage.setProgress(0);
                stage.setMessage("准备生成分镜...");
                break;
            case "GENERATING":
                stage.setDisplayStatus("active");
                stage.setProgress(50);
                stage.setMessage("正在生成分镜...");
                break;
            case "FAILED":
                stage.setDisplayStatus("failed");
                stage.setProgress(0);
                stage.setMessage(errorMsg != null ? errorMsg : "分镜生成失败");
                break;
            case "DONE":
            default:
                stage.setDisplayStatus("completed");
                stage.setProgress(100);
                stage.setMessage("已完成");
                break;
        }

        return stage;
    }

    /**
     * 构建全 pending 阶段列表（从第 startIdx 个后续阶段开始）
     */
    private List<PipelineStageDTO> buildRemainingPendingStages(int completedCount) {
        List<PipelineStageDTO> stages = new ArrayList<>();
        String[][] remaining = {
            {"analyzing",       "场景分析"},
            {"grid_generating", "九宫格生成"},
            {"grid_fusion",     "图片融合"},
            {"prompt_building", "提示词构建"},
            {"video_generating","视频生成"},
            {"subtitle",        "字幕生成"},
            {"composition",     "视频合成"},
            {"completed",       "完成"},
        };

        for (String[] def : remaining) {
            PipelineStageDTO stage = new PipelineStageDTO();
            stage.setKey(def[0]);
            stage.setName(def[1]);
            stage.setDisplayStatus("pending");
            stage.setProgress(0);
            stage.setMessage("等待中");
            stages.add(stage);
        }

        return stages;
    }

    /**
     * 将 EpisodeProduction.status 映射到阶段索引
     */
    private int mapProductionStatusToIndex(String status, String currentStage) {
        switch (status) {
            case "ANALYZING":
                return 0;
            case "GRID_GENERATING":
                return 1;
            case "GRID_FUSION_PENDING":
                return 2;
            case "BUILDING_PROMPTS":
                return 3;
            case "GENERATING":
                return 4;
            case "GENERATING_SUBS":
                return 5;
            case "COMPOSING":
                return 6;
            case "COMPLETED":
                return 8; // 全部完成
            case "FAILED":
            default:
                // 根据 currentStage 判断
                if (currentStage != null) {
                    switch (currentStage) {
                        case "SCENE_ANALYSIS": return 0;
                        case "GRID_GENERATION": return 1;
                        case "GRID_FUSION": return 2;
                        case "PROMPT_BUILDING": return 3;
                        case "VIDEO_GENERATION": return 4;
                        case "SUBTITLE_GENERATION": return 5;
                        case "VIDEO_COMPOSITION": return 6;
                        default: return 0;
                    }
                }
                return 0;
        }
    }

    /**
     * 构建全 pending 阶段列表（无 episode 场景）
     */
    private List<PipelineStageDTO> buildAllPendingStages(Long episodeId) {
        List<PipelineStageDTO> stages = new ArrayList<>();

        PipelineStageDTO storyboard = new PipelineStageDTO();
        storyboard.setKey("storyboard");
        storyboard.setName("分镜生成");
        storyboard.setDisplayStatus("pending");
        storyboard.setProgress(0);
        storyboard.setMessage("等待中");
        stages.add(storyboard);

        stages.addAll(buildRemainingPendingStages(1));

        return stages;
    }
}
