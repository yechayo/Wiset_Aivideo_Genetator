package com.comic.service.production;

import com.comic.ai.text.DeepSeekTextService;
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
 * Tests for PanelProductionService.resolveShots() — the routing decision method
 * that chooses between the original DeepSeek path and the new storyboard agent path.
 *
 * RED phase: these tests are written before resolveShots() exists in production code.
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
    @Mock private DeepSeekTextService deepSeekTextService;
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
                deepSeekTextService,
                storyboardAgentService,
                progressService,
                eventPublisher,
                transactionManager);
    }

    // ==================== Test 1: Refinement + locked shots + non-comic → original DeepSeek path ====================

    @Test
    void resolveShots_shouldUseOriginalPath_whenRefinementWithLockedShots() {
        // Given: refinement mode with locked shots, non-comic
        List<Map<String, Object>> lockedShots = buildLockedShots(2);
        List<Map<String, Object>> newShots = buildShots(3, 3);

        when(deepSeekTextService.generateStoryboard(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), anyString(), eq(lockedShots)))
                .thenReturn(newShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                false, null, NARRATION_PERSPECTIVE,
                lockedShots, true,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: deepSeekTextService was called, agent was NOT
        verify(deepSeekTextService).generateStoryboard(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), anyString(), eq(lockedShots));
        verifyNoInteractions(storyboardAgentService);
        assertNotNull(result);
    }

    // ==================== Test 2: Refinement + locked shots + comic mode → DeepSeek panel-aware path ====================

    @Test
    void resolveShots_shouldUseOriginalPath_whenRefinementComicMode() {
        // Given: refinement mode with locked shots, comic mode
        List<Map<String, Object>> lockedShots = buildLockedShots(2);
        List<List<Map<String, Object>>> panelGroups = buildPanelGroups(2, 2);

        when(deepSeekTextService.generatePanelAwareStoryboard(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                anyString(), eq(NARRATION_PERSPECTIVE), eq(lockedShots)))
                .thenReturn(panelGroups);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                true, null, NARRATION_PERSPECTIVE,
                lockedShots, true,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: deepSeekTextService panel-aware was called, agent was NOT
        verify(deepSeekTextService).generatePanelAwareStoryboard(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                anyString(), eq(NARRATION_PERSPECTIVE), eq(lockedShots));
        verifyNoInteractions(storyboardAgentService);
        assertNotNull(result);
    }

    // ==================== Test 3: Normal mode + non-comic → agent path ====================

    @Test
    void resolveShots_shouldUseAgentPath_whenNormalMode() {
        // Given: normal generation mode, non-comic
        List<Map<String, Object>> agentShots = buildShots(6, 1);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), anyString()))
                .thenReturn(agentShots);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                false, null, NARRATION_PERSPECTIVE,
                Collections.emptyList(), false,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: agent was called, deepSeekTextService generateStoryboard was NOT
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(false), anyString());
        verify(deepSeekTextService, never()).generateStoryboard(
                anyString(), anyString(), anyInt(), anyString(),
                anyBoolean(), anyString(), anyList());
        assertNotNull(result);
    }

    // ==================== Test 4: Normal mode + comic → agent path with narration ====================

    @Test
    void resolveShots_shouldUseAgentPath_whenComicMode() {
        // Given: normal generation mode, comic mode
        List<Map<String, Object>> agentShots = buildShotsWithNarration(3);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE)))
                .thenReturn(agentShots);
        // narration refinement disabled for simplicity in this test
        when(deepSeekTextService.isNarrationRefinementEnabled()).thenReturn(false);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                true, null, NARRATION_PERSPECTIVE,
                Collections.emptyList(), false,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: agent was called, deepSeekTextService generateStoryboard was NOT
        verify(storyboardAgentService).generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE));
        verify(deepSeekTextService, never()).generatePanelAwareStoryboard(
                anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyString(), anyList());
        assertNotNull(result);
    }

    // ==================== Test 5: Agent returns empty shots → throw RuntimeException ====================

    @Test
    void resolveShots_shouldThrowException_whenAgentReturnsEmptyShots() {
        // Given: agent returns empty list
        when(storyboardAgentService.generate(
                anyString(), anyString(), anyInt(), anyString(),
                anyBoolean(), anyString()))
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

    // ==================== Test 6: Comic mode + agent path + narration refinement enabled ====================

    @Test
    void resolveShots_shouldRunNarrationRefinement_whenComicMode() {
        // Given: comic mode, narration refinement enabled
        List<Map<String, Object>> agentShots = buildShotsWithNarration(4);
        List<List<Map<String, Object>>> wrappedPanelGroups = wrapShotsIntoPanelGroups(agentShots, 2);
        List<List<Map<String, Object>>> refinedPanelGroups = buildRefinedPanelGroups(2, 2);

        when(storyboardAgentService.generate(
                eq(CONTENT), eq(CHARACTERS), eq(TARGET_DURATION), eq(VISUAL_STYLE),
                eq(true), eq(NARRATION_PERSPECTIVE)))
                .thenReturn(agentShots);
        when(deepSeekTextService.isNarrationRefinementEnabled()).thenReturn(true);
        when(deepSeekTextService.refineNarrationsSequentially(
                anyList(), eq(CONTENT), eq(NARRATION_PERSPECTIVE)))
                .thenReturn(refinedPanelGroups);

        // When
        List<Map<String, Object>> result = service.resolveShots(
                CONTENT, CHARACTERS, TARGET_DURATION, VISUAL_STYLE,
                true, null, NARRATION_PERSPECTIVE,
                Collections.emptyList(), false,
                Collections.emptyMap(), PROJECT_ID, TITLE, EPISODE_NUM);

        // Then: narration refinement was called
        verify(deepSeekTextService).isNarrationRefinementEnabled();
        verify(deepSeekTextService).refineNarrationsSequentially(
                anyList(), eq(CONTENT), eq(NARRATION_PERSPECTIVE));
        assertNotNull(result);
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

    private List<List<Map<String, Object>>> buildPanelGroups(int groupCount, int shotsPerGroup) {
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        int shotNum = 1;
        for (int g = 0; g < groupCount; g++) {
            List<Map<String, Object>> group = new ArrayList<>();
            for (int s = 0; s < shotsPerGroup; s++) {
                Map<String, Object> shot = new HashMap<>();
                shot.put("shotNumber", shotNum++);
                shot.put("description", "Panel " + (g + 1) + " Shot " + (s + 1));
                group.add(shot);
            }
            groups.add(group);
        }
        return groups;
    }

    private List<List<Map<String, Object>>> wrapShotsIntoPanelGroups(List<Map<String, Object>> shots, int groupSize) {
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        for (int i = 0; i < shots.size(); i += groupSize) {
            List<Map<String, Object>> group = new ArrayList<>();
            for (int j = i; j < Math.min(i + groupSize, shots.size()); j++) {
                group.add(shots.get(j));
            }
            groups.add(group);
        }
        return groups;
    }

    private List<List<Map<String, Object>>> buildRefinedPanelGroups(int groupCount, int shotsPerGroup) {
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        int shotNum = 1;
        for (int g = 0; g < groupCount; g++) {
            List<Map<String, Object>> group = new ArrayList<>();
            for (int s = 0; s < shotsPerGroup; s++) {
                Map<String, Object> shot = new HashMap<>();
                shot.put("shotNumber", shotNum++);
                shot.put("description", "精修后分镜 " + shotNum);
                shot.put("narration", "精修后旁白 " + shotNum);
                group.add(shot);
            }
            groups.add(group);
        }
        return groups;
    }
}
