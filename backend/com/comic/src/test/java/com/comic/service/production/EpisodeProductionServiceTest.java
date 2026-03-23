package com.comic.service.production;

import com.comic.dto.model.SceneAnalysisResultModel;
import com.comic.dto.model.SceneGroupModel;
import com.comic.dto.model.VideoPromptModel;
import com.comic.dto.model.VideoTaskGroupModel;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Project;
import com.comic.entity.VideoProductionTask;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.repository.VideoProductionTaskRepository;
import com.comic.service.story.StoryboardService;
import com.comic.dto.response.ProductionStatusResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 视频生产管线 Mock 单元测试
 * 不启动 Spring 容器，全部依赖用 Mockito mock，用假数据走通完整流程
 */
@ExtendWith(MockitoExtension.class)
class EpisodeProductionServiceTest {

    @Mock private EpisodeRepository episodeRepository;
    @Mock private EpisodeProductionRepository productionRepository;
    @Mock private VideoProductionTaskRepository videoTaskRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private SceneAnalysisService sceneAnalysisService;
    @Mock private SceneGridGenService sceneGridGenService;
    @Mock private GridSplitService gridSplitService;
    @Mock private StoryboardEnhancementService storyboardEnhancementService;
    @Mock private VideoPromptBuilderService videoPromptBuilderService;
    @Mock private VideoProductionQueueService videoQueueService;
    @Mock private SubtitleService subtitleService;
    @Mock private VideoCompositionService videoCompositionService;
    @Mock private StoryboardService storyboardService;
    @Mock private ApplicationContext applicationContext;

    @Spy private ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @InjectMocks
    private EpisodeProductionService productionService;

    // ---- 假数据常量 ----
    private static final Long EPISODE_ID = 100L;
    private static final String PROJECT_ID = "PROJ-001";
    private static final String VISUAL_STYLE = "ANIME";

    private static final String STORYBOARD_JSON =
            "{\n" +
            "  \"panels\": [\n" +
            "    {\"scene\": \"教室\", \"characters\": [\"小明\"], \"description\": \"小明走进教室\", \"dialogue\": \"大家好\"},\n" +
            "    {\"scene\": \"教室\", \"characters\": [\"小明\", \"小红\"], \"description\": \"小红招手\", \"dialogue\": \"小明，这边！\"},\n" +
            "    {\"scene\": \"操场\", \"characters\": [\"小明\"], \"description\": \"小明跑到操场\", \"dialogue\": \"下课了！\"}\n" +
            "  ]\n" +
            "}";

    private Episode episode;
    private Project project;

    @BeforeEach
    void setUp() {
        // 构造假 Episode
        episode = new Episode();
        episode.setId(EPISODE_ID);
        episode.setProjectId(PROJECT_ID);
        episode.setEpisodeNum(1);
        episode.setTitle("第一集");
        episode.setStatus("DONE");
        episode.setStoryboardJson(STORYBOARD_JSON);
        episode.setProductionStatus("NOT_STARTED");
        episode.setProductionProgress(0);

        // 构造假 Project
        project = new Project();
        project.setId(1L);
        project.setProjectId(PROJECT_ID);
        project.setVisualStyle(VISUAL_STYLE);
        project.setStatus("PRODUCING");

        // self() 代理：返回被测实例自身
        lenient().when(applicationContext.getBean(EpisodeProductionService.class))
                .thenReturn(productionService);
    }

    // ---- 辅助方法 ----

    private EpisodeProduction createProduction() {
        EpisodeProduction p = new EpisodeProduction();
        p.setId(1L);
        p.setProductionId("PROD-TEST001");
        p.setEpisodeId(EPISODE_ID);
        p.setStatus("ANALYZING");
        p.setCurrentStage("SCENE_ANALYSIS");
        p.setProgressPercent(5);
        p.setProgressMessage("开始场景分析...");
        p.setRetryCount(0);
        p.setTotalPanels(3);
        p.setCompletedPanels(0);
        return p;
    }

    private SceneAnalysisResultModel createSceneAnalysis() {
        SceneGroupModel group1 = new SceneGroupModel();
        group1.setSceneId("SCENE-0");
        group1.setStartPanelIndex(0);
        group1.setEndPanelIndex(1);
        group1.setLocation("教室");
        group1.setCharacters(new ArrayList<>(Arrays.asList("小明", "小红")));

        SceneGroupModel group2 = new SceneGroupModel();
        group2.setSceneId("SCENE-1");
        group2.setStartPanelIndex(2);
        group2.setEndPanelIndex(2);
        group2.setLocation("操场");
        group2.setCharacters(new ArrayList<>(Arrays.asList("小明")));

        SceneAnalysisResultModel result = new SceneAnalysisResultModel();
        result.setSceneGroups(new ArrayList<>(Arrays.asList(group1, group2)));
        result.setTotalPanelCount(3);
        return result;
    }

    private List<VideoTaskGroupModel> createTaskGroups() {
        VideoTaskGroupModel group1 = new VideoTaskGroupModel();
        group1.setGroupId("GROUP-001");
        group1.setPanelIndexes(new ArrayList<>(Arrays.asList(0, 1)));
        group1.setTotalDuration(5);
        List<VideoPromptModel> prompts1 = new ArrayList<>();
        prompts1.add(new VideoPromptModel("小明走进教室", 3, 0));
        prompts1.add(new VideoPromptModel("小红招手", 2, 1));
        group1.setPrompts(prompts1);

        VideoTaskGroupModel group2 = new VideoTaskGroupModel();
        group2.setGroupId("GROUP-002");
        group2.setPanelIndexes(new ArrayList<>(Arrays.asList(2)));
        group2.setTotalDuration(5);
        List<VideoPromptModel> prompts2 = new ArrayList<>();
        prompts2.add(new VideoPromptModel("小明跑到操场", 5, 2));
        group2.setPrompts(prompts2);

        return new ArrayList<>(Arrays.asList(group1, group2));
    }

    private List<List<String>> createFusedUrls2D() {
        List<List<String>> urls = new ArrayList<>();
        urls.add(new ArrayList<>(Arrays.asList(
                "https://mock/fused-p0.png", "https://mock/fused-p1.png", "https://mock/fused-p2.png",
                "https://mock/fused-p3.png", "https://mock/fused-p4.png", "https://mock/fused-p5.png",
                "https://mock/fused-p6.png", "https://mock/fused-p7.png", "https://mock/fused-p8.png")));
        urls.add(new ArrayList<>(Arrays.asList(
                "https://mock/fused2-p0.png", "https://mock/fused2-p1.png", "https://mock/fused2-p2.png",
                "https://mock/fused2-p3.png", "https://mock/fused2-p4.png", "https://mock/fused2-p5.png",
                "https://mock/fused2-p6.png", "https://mock/fused2-p7.png", "https://mock/fused2-p8.png")));
        return urls;
    }

    private List<String> createFusedUrls() {
        return new ArrayList<>(Arrays.asList("https://mock/fused-0.png", "https://mock/fused-1.png"));
    }

    private List<String> createGridUrls() {
        return new ArrayList<>(Arrays.asList("https://mock/grid-0.png", "https://mock/grid-1.png"));
    }

    private SceneAnalysisResultModel createSingleSceneAnalysis(int startIndex, int endIndex) {
        SceneGroupModel group = new SceneGroupModel();
        group.setSceneId("SCENE-SINGLE");
        group.setStartPanelIndex(startIndex);
        group.setEndPanelIndex(endIndex);
        group.setLocation("鍗曚竴鍦烘櫙");
        group.setCharacters(new ArrayList<>(Arrays.asList("灏忔槑")));

        SceneAnalysisResultModel result = new SceneAnalysisResultModel();
        result.setSceneGroups(new ArrayList<>(Arrays.asList(group)));
        result.setTotalPanelCount(endIndex - startIndex + 1);
        return result;
    }

    private String createStoryboardJsonWithPanelCount(int panelCount) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode panels = objectMapper.createArrayNode();
        for (int i = 0; i < panelCount; i++) {
            ObjectNode panel = objectMapper.createObjectNode();
            panel.put("panel_id", "ep1_p" + (i + 1));
            panel.put("shot_type", "MEDIUM_SHOT");
            panel.put("camera_angle", "eye_level");
            panel.put("composition", "panel-" + i);

            ObjectNode background = objectMapper.createObjectNode();
            background.put("scene_desc", "scene-a");
            background.put("time_of_day", "day");
            background.put("atmosphere", "neutral");
            panel.set("background", background);

            panels.add(panel);
        }
        root.set("panels", panels);
        return objectMapper.writeValueAsString(root);
    }

    private VideoProductionTask createCompletedTask() {
        VideoProductionTask task = new VideoProductionTask();
        task.setStatus("COMPLETED");
        task.setVideoUrl("https://mock/video-segment.mp4");
        task.setTargetDuration(5);
        task.setPanelIndex(0);
        return task;
    }

    /**
     * mock waitForVideoGeneration 轮询：直接返回已完成状态
     */
    private void mockVideoGenerationCompleted(int totalPanels) {
        final EpisodeProduction completed = new EpisodeProduction();
        completed.setTotalPanels(totalPanels);
        completed.setCompletedPanels(totalPanels);
        when(productionRepository.findByProductionId(anyString())).thenReturn(completed);
    }

    // ======================== 测试用例 ========================

    @Test
    @DisplayName("startProduction - 正常创建生产记录并触发异步流程")
    void testStartProduction_createsProductionRecord() {
        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(null);
        when(productionRepository.insert(any())).thenReturn(1);
        when(productionRepository.updateById(any())).thenReturn(1);
        when(episodeRepository.updateById(any())).thenReturn(1);
        // startProduction 内部调用 self().executeProductionFlow（同步执行）
        when(sceneAnalysisService.analyzeScenes(EPISODE_ID)).thenReturn(createSceneAnalysis());
        when(sceneGridGenService.generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class)))
                .thenReturn(
                        new ArrayList<>(Arrays.asList("https://mock/grid-0.png")),
                        new ArrayList<>(Arrays.asList("https://mock/grid-1.png"))
                );

        String productionId = productionService.startProduction(EPISODE_ID);

        assertNotNull(productionId);
        assertTrue(productionId.startsWith("PROD-"));

        // 验证 production 记录被插入
        verify(productionRepository).insert(any(EpisodeProduction.class));

        // 验证 executeProductionFlow 被触发（场景分析 + 九宫格生成）
        verify(sceneAnalysisService).analyzeScenes(EPISODE_ID);
        verify(sceneGridGenService, atLeastOnce()).generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class));

        // 验证最终状态到达 GRID_FUSION_PENDING（阶段1-2完成后暂停）
        ArgumentCaptor<EpisodeProduction> captor = ArgumentCaptor.forClass(EpisodeProduction.class);
        verify(productionRepository, atLeastOnce()).updateById(captor.capture());
        List<EpisodeProduction> updates = captor.getAllValues();
        EpisodeProduction finalUpdate = updates.get(updates.size() - 1);
        assertEquals("GRID_FUSION_PENDING", finalUpdate.getStatus());
    }

    @Test
    @DisplayName("startProduction - 剧集不存在时抛异常")
    void testStartProduction_episodeNotFound() {
        when(episodeRepository.selectById(999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> productionService.startProduction(999L));
    }

    @Test
    @DisplayName("startProduction - 分镜为空时抛异常")
    void testStartProduction_emptyStoryboard() {
        episode.setStoryboardJson(null);
        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);

        assertThrows(IllegalArgumentException.class,
                () -> productionService.startProduction(EPISODE_ID));
    }

    @Test
    @DisplayName("startProduction - 已完成的生产不允许重复启动")
    void testStartProduction_alreadyCompleted() {
        EpisodeProduction existing = new EpisodeProduction();
        existing.setStatus("COMPLETED");
        existing.setProductionId("PROD-OLD");

        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(existing);
        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);

        assertThrows(IllegalStateException.class,
                () -> productionService.startProduction(EPISODE_ID));
    }

    @Test
    @DisplayName("executeProductionFlow - 阶段1-2：场景分析 -> 九宫格生成 -> GRID_FUSION_PENDING")
    void testExecuteProductionFlow_phases1and2() throws Exception {
        EpisodeProduction production = createProduction();
        SceneAnalysisResultModel analysis = createSceneAnalysis();

        when(productionRepository.updateById(any())).thenReturn(1);
        when(sceneAnalysisService.analyzeScenes(EPISODE_ID)).thenReturn(analysis);
        when(sceneGridGenService.generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class)))
                .thenReturn(
                        new ArrayList<>(Arrays.asList("https://mock/grid-0.png")),
                        new ArrayList<>(Arrays.asList("https://mock/grid-1.png"))
                );

        // 同步执行（测试中 @Async 不生效）
        productionService.executeProductionFlow(episode, production);

        // 验证场景分析被调用
        verify(sceneAnalysisService).analyzeScenes(EPISODE_ID);

        // 验证生成了 2 张九宫格
        verify(sceneGridGenService, times(2)).generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class));

        // 验证最终状态为 GRID_FUSION_PENDING
        ArgumentCaptor<EpisodeProduction> captor = ArgumentCaptor.forClass(EpisodeProduction.class);
        verify(productionRepository, atLeastOnce()).updateById(captor.capture());

        // 最后一次 update 的状态应该是 GRID_FUSION_PENDING
        List<EpisodeProduction> allUpdates = captor.getAllValues();
        EpisodeProduction lastUpdate = allUpdates.get(allUpdates.size() - 1);
        assertEquals("GRID_FUSION_PENDING", lastUpdate.getStatus());
        assertEquals("GRID_FUSION", lastUpdate.getCurrentStage());
    }

    @Test
    @DisplayName("executeProductionFlow - 场景分析后会执行分镜增强")
    void testExecuteProductionFlow_shouldEnhanceStoryboardBeforeGridGeneration() throws Exception {
        EpisodeProduction production = createProduction();
        SceneAnalysisResultModel analysis = createSceneAnalysis();

        when(productionRepository.updateById(any())).thenReturn(1);
        when(sceneAnalysisService.analyzeScenes(EPISODE_ID)).thenReturn(analysis);
        when(sceneGridGenService.generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class)))
                .thenReturn(
                        new ArrayList<>(Arrays.asList("https://mock/grid-0.png")),
                        new ArrayList<>(Arrays.asList("https://mock/grid-1.png"))
                );

        productionService.executeProductionFlow(episode, production);

        verify(storyboardEnhancementService, times(1)).enhanceEpisodeStoryboard(EPISODE_ID);
    }

    @Test
    @DisplayName("executeProductionFlow - 多场景组 sceneGridUrls 正确累积")
    void testExecuteProductionFlow_multipleSceneGroups() throws Exception {
        EpisodeProduction production = createProduction();
        SceneAnalysisResultModel analysis = createSceneAnalysis();

        when(productionRepository.updateById(any())).thenReturn(1);
        when(sceneAnalysisService.analyzeScenes(EPISODE_ID)).thenReturn(analysis);
        when(sceneGridGenService.generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class)))
                .thenReturn(
                        new ArrayList<>(Arrays.asList("https://mock/grid-0.png")),
                        new ArrayList<>(Arrays.asList("https://mock/grid-1.png"))
                );

        productionService.executeProductionFlow(episode, production);

        // 验证 sceneAnalysisJson 被保存
        ArgumentCaptor<EpisodeProduction> captor = ArgumentCaptor.forClass(EpisodeProduction.class);
        verify(productionRepository, atLeastOnce()).updateById(captor.capture());

        // 找到设置了 sceneGridUrls 的那次更新
        EpisodeProduction withGridUrls = null;
        for (EpisodeProduction p : captor.getAllValues()) {
            if (p.getSceneGridUrls() != null) {
                withGridUrls = p;
                break;
            }
        }
        assertNotNull(withGridUrls, "应该有一次更新包含 sceneGridUrls");

        @SuppressWarnings("unchecked")
        List<String> urls = objectMapper.readValue(withGridUrls.getSceneGridUrls(), List.class);
        assertEquals(2, urls.size());
        assertEquals("https://mock/grid-0.png", urls.get(0));
        assertEquals("https://mock/grid-1.png", urls.get(1));
    }

    @Test
    @DisplayName("continueProductionFlow - 阶段3-7完整流程到 COMPLETED")
    void testContinueProductionFlow_fullPipeline() throws Exception {
        EpisodeProduction production = createProduction();
        production.setStatus("BUILDING_PROMPTS");
        production.setCurrentStage("PROMPT_BUILDING");
        production.setSceneAnalysisJson(objectMapper.writeValueAsString(createSceneAnalysis()));
        production.setFusedGridUrls(objectMapper.writeValueAsString(createFusedUrls2D()));
        production.setTotalPanels(3);

        List<VideoTaskGroupModel> taskGroups = createTaskGroups();

        when(productionRepository.updateById(any())).thenReturn(1);
        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(projectRepository.findByProjectId(PROJECT_ID)).thenReturn(project);
        when(videoPromptBuilderService.buildPromptsForPanels(
                anyString(), anyList(), eq(VISUAL_STYLE), eq(PROJECT_ID)))
                .thenReturn(taskGroups);
        mockVideoGenerationCompleted(3);
        when(videoTaskRepository.findByEpisodeId(EPISODE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(createCompletedTask())));
        when(subtitleService.generateSubtitles(anyString(), anyList()))
                .thenReturn("https://mock/subtitle.srt");
        when(videoCompositionService.composeVideo(anyList(), anyString()))
                .thenReturn("https://mock/final-video.mp4");
        when(episodeRepository.updateById(any())).thenReturn(1);

        // 同步执行
        productionService.continueProductionFlow(episode, production);

        // 验证各阶段都被调用
        verify(videoPromptBuilderService).buildPromptsForPanels(
                anyString(), anyList(), eq(VISUAL_STYLE), eq(PROJECT_ID));
        verify(videoQueueService).submitVideoTasks(anyString(), eq(EPISODE_ID), eq(taskGroups));
        verify(subtitleService).generateSubtitles(anyString(), anyList());
        verify(videoCompositionService).composeVideo(anyList(), eq("https://mock/subtitle.srt"));

        // 验证最终状态为 COMPLETED
        ArgumentCaptor<EpisodeProduction> prodCaptor = ArgumentCaptor.forClass(EpisodeProduction.class);
        verify(productionRepository, atLeastOnce()).updateById(prodCaptor.capture());
        List<EpisodeProduction> allProdUpdates = prodCaptor.getAllValues();
        EpisodeProduction finalProd = allProdUpdates.get(allProdUpdates.size() - 1);
        assertEquals("COMPLETED", finalProd.getStatus());
        assertEquals("https://mock/final-video.mp4", finalProd.getFinalVideoUrl());
        assertEquals("https://mock/subtitle.srt", finalProd.getSubtitleUrl());

        // 验证 Episode 状态更新
        ArgumentCaptor<Episode> epCaptor = ArgumentCaptor.forClass(Episode.class);
        verify(episodeRepository, atLeastOnce()).updateById(epCaptor.capture());
        List<Episode> allEpUpdates = epCaptor.getAllValues();
        Episode finalEp = allEpUpdates.get(allEpUpdates.size() - 1);
        assertEquals("COMPLETED", finalEp.getProductionStatus());
        assertEquals("https://mock/final-video.mp4", finalEp.getFinalVideoUrl());
    }

    @Test
    @DisplayName("continueProductionFlow - 多页融合图按场景组正确分配")
    void testContinueProductionFlow_withMultiPageFusion() throws Exception {
        EpisodeProduction production = createProduction();
        production.setStatus("BUILDING_PROMPTS");
        production.setSceneAnalysisJson(objectMapper.writeValueAsString(createSceneAnalysis()));
        // fusedGridUrls 改为二维数组格式：每页9个URL
        List<List<String>> fusedGridUrls2D = new ArrayList<>();
        fusedGridUrls2D.add(new ArrayList<>(Arrays.asList("https://mock/fused-page0-p0.png", "https://mock/fused-page0-p1.png",
                "https://mock/fused-page0-p2.png", "https://mock/fused-page0-p3.png", "https://mock/fused-page0-p4.png",
                "https://mock/fused-page0-p5.png", "https://mock/fused-page0-p6.png", "https://mock/fused-page0-p7.png", "https://mock/fused-page0-p8.png")));
        fusedGridUrls2D.add(new ArrayList<>(Arrays.asList("https://mock/fused-page1-p0.png", "https://mock/fused-page1-p1.png",
                "https://mock/fused-page1-p2.png", "https://mock/fused-page1-p3.png", "https://mock/fused-page1-p4.png",
                "https://mock/fused-page1-p5.png", "https://mock/fused-page1-p6.png", "https://mock/fused-page1-p7.png", "https://mock/fused-page1-p8.png")));
        production.setFusedGridUrls(objectMapper.writeValueAsString(fusedGridUrls2D));
        production.setTotalPanels(3);

        List<VideoTaskGroupModel> taskGroups = createTaskGroups();

        when(productionRepository.updateById(any())).thenReturn(1);
        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(projectRepository.findByProjectId(PROJECT_ID)).thenReturn(project);
        when(videoPromptBuilderService.buildPromptsForPanels(
                anyString(), anyList(), eq(VISUAL_STYLE), eq(PROJECT_ID)))
                .thenReturn(taskGroups);
        mockVideoGenerationCompleted(3);
        when(videoTaskRepository.findByEpisodeId(EPISODE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(createCompletedTask())));
        when(subtitleService.generateSubtitles(anyString(), anyList()))
                .thenReturn("https://mock/sub.srt");
        when(videoCompositionService.composeVideo(anyList(), anyString()))
                .thenReturn("https://mock/final.mp4");
        when(episodeRepository.updateById(any())).thenReturn(1);

        productionService.continueProductionFlow(episode, production);

        // 验证融合图被正确分配到任务组
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VideoTaskGroupModel>> taskCaptor = ArgumentCaptor.forClass(List.class);
        verify(videoQueueService).submitVideoTasks(anyString(), eq(EPISODE_ID), taskCaptor.capture());

        List<VideoTaskGroupModel> submittedGroups = taskCaptor.getValue();
        // group1 (panels 0-1) 对应 sceneGroup 0 -> 第0页的第0个格子
        assertEquals("https://mock/fused-page0-p0.png", submittedGroups.get(0).getFusedReferenceImageUrl());
        // group2 (panels 2) 对应 sceneGroup 1 -> 第1页的第0个格子
        assertEquals("https://mock/fused-page1-p0.png", submittedGroups.get(1).getFusedReferenceImageUrl());

        // 验证每个prompt也被注入了对应格子的融合图
        assertNotNull(submittedGroups.get(0).getPrompts());
        assertEquals("https://mock/fused-page0-p0.png",
                submittedGroups.get(0).getPrompts().get(0).getFusedReferenceImageUrl());
    }

    @Test
    @DisplayName("getProductionStatus - 正确返回各字段")
    void testGetProductionStatus() {
        EpisodeProduction production = createProduction();
        production.setStatus("GRID_GENERATING");
        production.setCurrentStage("GRID_GENERATION");
        production.setProgressPercent(15);
        production.setProgressMessage("正在生成场景参考图...");
        production.setFinalVideoUrl("https://mock/video.mp4");

        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(production);

        ProductionStatusResponse response = productionService.getProductionStatus(EPISODE_ID);

        assertEquals("PROD-TEST001", response.getProductionId());
        assertEquals(EPISODE_ID, response.getEpisodeId());
        assertEquals("GRID_GENERATING", response.getStatus());
        assertEquals("GRID_GENERATION", response.getCurrentStage());
        assertEquals(15, response.getProgressPercent());
        assertEquals("正在生成场景参考图...", response.getProgressMessage());
    }

    @Test
    @DisplayName("getProductionStatus - 无生产记录时返回 NOT_STARTED")
    void testGetProductionStatus_notStarted() {
        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(null);

        ProductionStatusResponse response = productionService.getProductionStatus(EPISODE_ID);

        assertEquals("NOT_STARTED", response.getStatus());
        assertEquals(0, response.getProgressPercent());
    }

    @Test
    @DisplayName("submitFusionPage - 提交逐格融合URL并触发恢复检测")
    @SuppressWarnings("unchecked")
    void testSubmitFusionPage_autoResume() throws Exception {
        EpisodeProduction production = createProduction();
        production.setStatus("GRID_FUSION_PENDING");
        // 只有1页网格图，提交1页9个格子即可触发自动恢复
        production.setSceneGridUrls(objectMapper.writeValueAsString(
                new ArrayList<>(Arrays.asList("https://mock/grid-0.png"))));
        production.setFusedGridUrls(null);

        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(production);
        when(productionRepository.updateById(any())).thenReturn(1);
        // tryMarkFusionResumed 返回 false，避免进入 continueProductionFlow
        when(productionRepository.tryMarkFusionResumed(EPISODE_ID)).thenReturn(false);

        // 提交第 0 页的9个格子URL
        List<String> panelUrls = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            panelUrls.add("https://mock/fused-panel-" + i + ".png");
        }
        int fused = productionService.submitFusionPage(EPISODE_ID, 0, panelUrls);
        assertEquals(9, fused);

        // 验证 fusedGridUrls 已更新为二维数组格式
        ArgumentCaptor<EpisodeProduction> captor = ArgumentCaptor.forClass(EpisodeProduction.class);
        verify(productionRepository, atLeastOnce()).updateById(captor.capture());
        List<List<String>> savedUrls = objectMapper.readValue(
                captor.getValue().getFusedGridUrls(), List.class);
        assertEquals(1, savedUrls.size());
        assertEquals(9, savedUrls.get(0).size());
        assertEquals("https://mock/fused-panel-0.png", savedUrls.get(0).get(0));

        // 验证 tryMarkFusionResumed 被调用
        verify(productionRepository).tryMarkFusionResumed(EPISODE_ID);
    }

    @Test
    @DisplayName("splitGridPageForFusion - 构建切图任务并调用后端切图服务")
    @SuppressWarnings("unchecked")
    void testSplitGridPageForFusion_shouldBuildTaskAndCallSplitter() throws Exception {
        EpisodeProduction production = createProduction();
        production.setStatus("GRID_FUSION_PENDING");
        production.setSceneGridUrls(objectMapper.writeValueAsString(createGridUrls()));
        production.setSceneAnalysisJson(objectMapper.writeValueAsString(createSceneAnalysis()));

        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(production);

        GridSplitService.SplitPageResult pageResult = new GridSplitService.SplitPageResult();
        pageResult.setPageIndex(1);
        pageResult.setRows(2);
        pageResult.setCols(3);
        pageResult.setSkipped(false);
        pageResult.setCells(new ArrayList<>());

        GridSplitService.SplitBatchResult batchResult = new GridSplitService.SplitBatchResult();
        batchResult.setSuccessPages(1);
        batchResult.setSkippedPages(0);
        batchResult.setPages(new ArrayList<>(Arrays.asList(pageResult)));

        when(gridSplitService.splitAndUploadPages(anyList())).thenReturn(batchResult);

        GridSplitService.SplitPageResult result = productionService.splitGridPageForFusion(EPISODE_ID, 1);
        assertEquals(1, result.getPageIndex());
        assertEquals(2, result.getRows());
        assertEquals(3, result.getCols());

        ArgumentCaptor<List<GridSplitService.PageSplitTask>> tasksCaptor = ArgumentCaptor.forClass(List.class);
        verify(gridSplitService, times(1)).splitAndUploadPages(tasksCaptor.capture());

        List<GridSplitService.PageSplitTask> tasks = tasksCaptor.getValue();
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        GridSplitService.PageSplitTask task = tasks.get(0);
        assertEquals(1, task.getPageIndex());
        assertEquals("https://mock/grid-1.png", task.getGridImageUrl());
        assertEquals(2, task.getRows());
        assertEquals(3, task.getCols());
        assertEquals(2, task.getStartPanelIndex());
        assertEquals(1, task.getPanels().size());
        assertTrue(task.getObjectKeyPrefix().contains("episode-" + EPISODE_ID));
    }

    @Test
    @DisplayName("splitGridPageForFusion - pageIndex越界抛出业务异常")
    void testSplitGridPageForFusion_outOfRange() throws Exception {
        EpisodeProduction production = createProduction();
        production.setStatus("GRID_FUSION_PENDING");
        production.setSceneGridUrls(objectMapper.writeValueAsString(
                new ArrayList<>(Arrays.asList("https://mock/grid-only.png"))
        ));
        production.setSceneAnalysisJson(objectMapper.writeValueAsString(createSceneAnalysis()));

        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(production);

        assertThrows(Exception.class, () -> productionService.splitGridPageForFusion(EPISODE_ID, 3));
    }

    @Test
    @DisplayName("splitGridPageForFusion - 同场景组多页时按pageInGroup正确偏移startPanelIndex")
    @SuppressWarnings("unchecked")
    void testSplitGridPageForFusion_multiPageOffset() throws Exception {
        episode.setStoryboardJson(createStoryboardJsonWithPanelCount(12));

        EpisodeProduction production = createProduction();
        production.setStatus("GRID_FUSION_PENDING");
        production.setSceneGridUrls(objectMapper.writeValueAsString(
                new ArrayList<>(Arrays.asList("https://mock/grid-0.png", "https://mock/grid-1.png"))
        ));
        production.setSceneAnalysisJson(objectMapper.writeValueAsString(createSingleSceneAnalysis(0, 11)));

        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(production);

        GridSplitService.SplitPageResult pageResult = new GridSplitService.SplitPageResult();
        pageResult.setPageIndex(1);
        pageResult.setRows(3);
        pageResult.setCols(3);
        pageResult.setSkipped(false);
        pageResult.setCells(new ArrayList<>());

        GridSplitService.SplitBatchResult batchResult = new GridSplitService.SplitBatchResult();
        batchResult.setSuccessPages(1);
        batchResult.setSkippedPages(0);
        batchResult.setPages(new ArrayList<>(Arrays.asList(pageResult)));
        when(gridSplitService.splitAndUploadPages(anyList())).thenReturn(batchResult);

        GridSplitService.SplitPageResult result = productionService.splitGridPageForFusion(EPISODE_ID, 1);
        assertEquals(1, result.getPageIndex());
        assertEquals(3, result.getRows());
        assertEquals(3, result.getCols());

        ArgumentCaptor<List<GridSplitService.PageSplitTask>> tasksCaptor = ArgumentCaptor.forClass(List.class);
        verify(gridSplitService, times(1)).splitAndUploadPages(tasksCaptor.capture());
        List<GridSplitService.PageSplitTask> tasks = tasksCaptor.getValue();
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        GridSplitService.PageSplitTask task = tasks.get(0);
        assertEquals(1, task.getPageIndex());
        assertEquals(9, task.getStartPanelIndex());
        assertEquals(3, task.getPanels().size());
        assertEquals("ep1_p10", task.getPanels().get(0).path("panel_id").asText());
    }

    @Test
    @DisplayName("retryProduction - 失败后重试正确重置状态并重新执行")
    void testRetryProduction() {
        EpisodeProduction production = createProduction();
        production.setStatus("FAILED");
        production.setErrorMessage("视频生成超时");
        production.setRetryCount(1);
        production.setFinalVideoUrl("https://mock/old-video.mp4");
        production.setSceneGridUrl("https://mock/old-grid.png");
        production.setSceneGridUrls("[\"https://mock/old-grid.png\"]");
        production.setFusedReferenceUrl("https://mock/old-fused.png");
        production.setFusedGridUrls("[\"https://mock/old-fused.png\"]");

        when(productionRepository.findByEpisodeId(EPISODE_ID)).thenReturn(production);
        when(productionRepository.updateById(any())).thenReturn(1);
        when(episodeRepository.selectById(EPISODE_ID)).thenReturn(episode);
        when(episodeRepository.updateById(any())).thenReturn(1);
        doNothing().when(videoTaskRepository).deleteByEpisodeId(EPISODE_ID);

        // retryProduction 通过 self().executeProductionFlow() 同步执行流程
        when(sceneAnalysisService.analyzeScenes(EPISODE_ID)).thenReturn(createSceneAnalysis());
        when(sceneGridGenService.generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class)))
                .thenReturn(
                        new ArrayList<>(Arrays.asList("https://mock/grid-new-0.png")),
                        new ArrayList<>(Arrays.asList("https://mock/grid-new-1.png"))
                );

        productionService.retryProduction(EPISODE_ID);

        // 验证旧视频任务被清理
        verify(videoTaskRepository).deleteByEpisodeId(EPISODE_ID);

        // 验证流程被重新触发（场景分析和九宫格生成被调用）
        verify(sceneAnalysisService).analyzeScenes(EPISODE_ID);
        verify(sceneGridGenService, atLeastOnce()).generateSceneGridPages(eq(EPISODE_ID), any(SceneGroupModel.class));

        // 验证最终状态到达 GRID_FUSION_PENDING（retryProduction 重置后走完阶段1-2）
        ArgumentCaptor<EpisodeProduction> captor = ArgumentCaptor.forClass(EpisodeProduction.class);
        verify(productionRepository, atLeastOnce()).updateById(captor.capture());
        List<EpisodeProduction> updates = captor.getAllValues();
        EpisodeProduction finalProd = updates.get(updates.size() - 1);
        assertEquals("GRID_FUSION_PENDING", finalProd.getStatus());
    }
}
