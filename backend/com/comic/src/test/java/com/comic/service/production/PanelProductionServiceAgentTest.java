package com.comic.service.production;

import com.comic.constant.ProjectInfoKeys;
import com.comic.config.AiServiceConfiguration;
import com.comic.entity.*;
import com.comic.repository.*;
import com.comic.service.oss.OssService;
import com.comic.service.redis.ProgressService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PanelProductionService.resolveShots() after storyboard-path unification.
 * All shot generation should route through StoryboardAgentService.
 */
@ExtendWith(MockitoExtension.class)
class PanelProductionServiceAgentTest {

    @Mock private PanelRepository panelRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private com.comic.ai.PanelPromptBuilder panelPromptBuilder;
    @Mock private com.comic.ai.ComicCommentaryPanelPromptBuilder comicCommentaryPanelPromptBuilder;
    @Mock private AiServiceConfiguration aiServiceConfig;
    @Mock private com.comic.ai.video.VideoGenerationService videoGenerationService;
    @Mock private com.comic.ai.video.ViduVideoService viduVideoService;
    @Mock private com.comic.ai.video.ViduReference2VideoService viduReference2VideoService;
    @Mock private OssService ossService;
    @Mock private ApplicationContext applicationContext;
    @Mock private StoryboardAgentService storyboardAgentService;
    @Mock private ProgressService progressService;
    @Mock private StateChangeEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private PanelProductionService service;

    // ==================== Test data fixtures ====================

    private static final String CONTENT = "小明走在回家的路上，突然下起了大雨。";
    private static final String CHARACTERS = "小明";
    private static final int TARGET_DURATION = 30;
    private static final String VISUAL_STYLE = "anime";
    private static final String NARRATION_PERSPECTIVE = "third_person";
    private static final String PROJECT_ID = "proj-001";
    private static final String TITLE = "第一集";
    private static final int EPISODE_NUM = 1;

    @BeforeEach
    void setUp() throws Exception {
        service = new PanelProductionService(
                panelRepository,
                episodeRepository,
                projectRepository,
                characterRepository,
                panelPromptBuilder,
                comicCommentaryPanelPromptBuilder,
                aiServiceConfig,
                videoGenerationService,
                viduVideoService,
                viduReference2VideoService,
                ossService,
                applicationContext,
                storyboardAgentService,
                progressService,
                eventPublisher,
                transactionManager);
    }

    // ==================== Test 1: Refinement + locked shots + non-comic → agent path ====================

    @Test
    void resolveShots_shouldUseAgentPath_whenRefinementWithLockedShots_nonComic() {
        // Given: refinement mode with locked shots, non-comic
        List<Map<String, Object>> lockedShots = buildLockedShots(2);
        List<Map<String, Object>> newShots = buildShots(3, 3);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq("standard"),
                eq(lockedShots), isNull()))
                .thenReturn(newShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                false, null, NARRATION_PERSPECTIVE,
                lockedShots, true,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: storyboardAgentService was called with lockedShots
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq("standard"),
                eq(lockedShots), isNull());
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(3, ((Number) result.get(0).get("shotNumber")).intValue());
        assertEquals(4, ((Number) result.get(1).get("shotNumber")).intValue());
        assertEquals(5, ((Number) result.get(2).get("shotNumber")).intValue());
        for (Map<String, Object> shot : result) {
            String sceneDescription = (String) shot.get("sceneDescription");
            String visualDescription = (String) shot.get("visualDescription");
            assertNotNull(sceneDescription);
            assertTrue(sceneDescription.contains("（场景）"));
            assertTrue(sceneDescription.contains("（出场）"));
            assertEquals(sceneDescription, visualDescription);
        }
    }

    // ==================== Test 2: Refinement + locked shots + comic mode → agent path ====================

    @Test
    void resolveShots_shouldUseAgentPath_whenRefinementWithLockedShots_comicMode() {
        // Given: refinement mode with locked shots, comic mode
        List<Map<String, Object>> lockedShots = buildLockedShots(2);
        List<Map<String, Object>> newShots = buildShotsWithNarration(4);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE), eq("standard"),
                eq(lockedShots), isNull()))
                .thenReturn(newShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                true, null, NARRATION_PERSPECTIVE,
                lockedShots, true,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE), eq("standard"),
                eq(lockedShots), isNull());
        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals(1, ((Number) result.get(0).get("shotNumber")).intValue());
        assertEquals(4, ((Number) result.get(3).get("shotNumber")).intValue());
        for (Map<String, Object> shot : result) {
            assertTrue(shot.containsKey("narration"));
            String sceneDescription = (String) shot.get("sceneDescription");
            String visualDescription = (String) shot.get("visualDescription");
            assertNotNull(sceneDescription);
            assertTrue(sceneDescription.contains("（场景）"));
            assertTrue(sceneDescription.contains("（出场）"));
            assertEquals(sceneDescription, visualDescription);
        }
    }

    // ==================== Test 3: Normal mode + non-comic → agent path (lockedShots should be null) ====================

    @Test
    void resolveShots_shouldUseAgentPath_whenNormalMode() {
        // Given: normal generation mode, non-comic
        List<Map<String, Object>> agentShots = buildShots(6, 1);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq("standard"),
                isNull(), isNull()))
                .thenReturn(agentShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                false, null, NARRATION_PERSPECTIVE,
                buildLockedShots(1), false, // should be ignored when not refinement
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: agent was called with lockedShots = null
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq("standard"),
                isNull(), isNull());
        assertNotNull(result);
    }

    // ==================== Test 4: Normal mode + comic → agent path with narration ====================

    @Test
    void resolveShots_shouldUseAgentPath_whenComicMode() {
        // Given: normal generation mode, comic mode
        List<Map<String, Object>> agentShots = buildShotsWithNarration(3);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE), eq("standard"),
                isNull(), isNull()))
                .thenReturn(agentShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                true, null, NARRATION_PERSPECTIVE,
                Collections.emptyList(), false,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: agent was called with comicMode=true
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE), eq("standard"),
                isNull(), isNull());
        assertNotNull(result);
    }

    // ==================== Test 5: Agent returns empty shots → throw RuntimeException ====================

    @Test
    void resolveShots_shouldThrowException_whenAgentReturnsEmptyShots() {
        // Given: agent returns empty list
        when(storyboardAgentService.generate(
                anyString(), anyString(), anyInt(), anyString(),
                anyBoolean(), anyString(), anyString(),
                nullable(List.class), nullable(String.class)))
                .thenReturn(Collections.emptyList());

        // When + Then
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                service.resolveShots(
                        CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                        false, null, NARRATION_PERSPECTIVE,
                        Collections.emptyList(), false,
                        Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM)
        );

        assertTrue(ex.getMessage().contains("未生成任何分镜"),
                "Exception message should contain '未生成任何分镜' but was: " + ex.getMessage());
    }

    // ==================== Test 6: refinement args should be forwarded (revision + scriptStyle + lockedShots) ====================

    @Test
    void resolveShots_shouldForwardRevisionAndScriptStyle_whenRefinement() {
        // Given
        List<Map<String, Object>> lockedShots = buildLockedShots(2);
        List<Map<String, Object>> agentShots = buildShots(4, 1);
        String revisionNote = "请增强冲突张力，保持第一镜不变";
        Map<String, Object> projectInfo = new HashMap<>();
        projectInfo.put(ProjectInfoKeys.SCRIPT_STYLE, ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq(ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU),
                eq(lockedShots), eq(revisionNote)))
                .thenReturn(agentShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                false, revisionNote, NARRATION_PERSPECTIVE,
                lockedShots, true,
                projectInfo, PROJECT_ID, TITLE, EPISODE_NUM);

        // Then
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq(ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU),
                eq(lockedShots), eq(revisionNote));
        assertNotNull(result);
    }

    // ==================== Test 7: resolveShots output should be labeled before return ====================

    @Test
    void resolveShots_shouldLabelSceneDescriptionAndSyncVisualDescription_beforeReturn() {
        // Given: agent returns raw shot text without labels
        Map<String, Object> rawShot = new HashMap<>();
        rawShot.put("shotNumber", 1);
        rawShot.put("scene", "雨夜街道");
        rawShot.put("sceneDescription", "雨夜街道，小明奔跑，阿华在路口出现。");
        rawShot.put("speaker", "小明");
        rawShot.put("dialogue", "快跑！");
        List<Map<String, Object>> agentShots = Collections.singletonList(rawShot);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), eq(NARRATION_PERSPECTIVE), eq("standard"),
                isNull(), isNull()))
                .thenReturn(agentShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                false, null, NARRATION_PERSPECTIVE,
                Collections.emptyList(), false,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: output should already be labeled and visualDescription should mirror sceneDescription
        assertNotNull(result);
        assertFalse(result.isEmpty());
        Map<String, Object> firstShot = result.get(0);
        String sceneDescription = (String) firstShot.get("sceneDescription");
        String visualDescription = (String) firstShot.get("visualDescription");

        assertNotNull(sceneDescription);
        assertTrue(sceneDescription.contains("（场景）"),
                "sceneDescription should contain （场景）, but was: " + sceneDescription);
        assertTrue(sceneDescription.contains("（出场）"),
                "sceneDescription should contain （出场）, but was: " + sceneDescription);
        assertEquals(sceneDescription, visualDescription,
                "visualDescription should equal sceneDescription");
    }

    // ==================== Helper methods for building test data ====================

    private List<Map<String, Object>> buildLockedShots(int count) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("shotNumber", i);
            shot.put("description", "锁定分镜 " + i);
            shot.put("locked", true);
            shots.add(shot);
        }
        return shots;
    }

    private List<Map<String, Object>> buildShots(int count, int startFrom) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("shotNumber", startFrom + i);
            shot.put("description", "分镜 " + (startFrom + i));
            shots.add(shot);
        }
        return shots;
    }

    private List<Map<String, Object>> buildShotsWithNarration(int count) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("description", "分镜 " + (i + 1));
            shot.put("narration", "旁白 " + (i + 1));
            shots.add(shot);
        }
        return shots;
    }

}
