package com.comic.e2e;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.service.panel.PanelGenerationService;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.pipeline.ProjectStatusBroadcaster;
import com.comic.service.production.PanelProductionService;
import com.comic.service.script.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * E2E-style tests verifying the complete state machine lifecycle.
 * Tests chain multiple transitions and verify the final state + service interactions.
 */
class ProjectStateMachineE2ETest {

    private PipelineService pipelineService;
    private ProjectRepository projectRepository;
    private EpisodeRepository episodeRepository;
    private PanelRepository panelRepository;
    private CharacterRepository characterRepository;
    private ScriptService scriptService;
    private CharacterExtractService characterExtractService;
    private CharacterImageGenerationService characterImageGenerationService;
    private PanelGenerationService panelGenerationService;
    private PanelProductionService panelProductionService;
    private ProjectStatusBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        panelRepository = mock(PanelRepository.class);
        characterRepository = mock(CharacterRepository.class);
        scriptService = mock(ScriptService.class);
        characterExtractService = mock(CharacterExtractService.class);
        characterImageGenerationService = mock(CharacterImageGenerationService.class);
        panelGenerationService = mock(PanelGenerationService.class);
        panelProductionService = mock(PanelProductionService.class);
        broadcaster = mock(ProjectStatusBroadcaster.class);

        pipelineService = new PipelineService(
            projectRepository, episodeRepository, panelRepository,
            characterRepository, scriptService, characterExtractService,
            characterImageGenerationService, broadcaster
        );

        // Inject @Lazy @Autowired dependencies via reflection
        ReflectionTestUtils.setField(pipelineService, "panelGenerationService", panelGenerationService);
        ReflectionTestUtils.setField(pipelineService, "panelProductionService", panelProductionService);
        ReflectionTestUtils.setField(pipelineService, "stringRedisTemplate", mock(StringRedisTemplate.class));

        // Use spy for pipelineServiceSelf to allow auto-advance chain
        PipelineService spySelf = spy(pipelineService);
        ReflectionTestUtils.setField(pipelineService, "pipelineServiceSelf", spySelf);
    }

    @Test
    @DisplayName("Full lifecycle: SCRIPT_REVIEW -> confirm -> CHARACTER_EXTRACTING")
    void full_lifecycle_draft_to_character_extracting() {
        Project project = createProject("e2e-1", ProjectStatus.SCRIPT_REVIEW);
        when(projectRepository.findByProjectId("e2e-1")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        pipelineService.advancePipeline("e2e-1", "confirm_script");

        assertEquals(ProjectStatus.CHARACTER_EXTRACTING.getCode(), project.getStatus());
        verify(characterExtractService, times(1)).extractCharacters(eq("e2e-1"));
    }

    @Test
    @DisplayName("Full lifecycle: CHARACTER_REVIEW -> confirm -> IMAGE_GENERATING")
    void full_lifecycle_character_review_to_image_generating() {
        Project project = createProject("e2e-2", ProjectStatus.CHARACTER_REVIEW);
        when(projectRepository.findByProjectId("e2e-2")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);
        // characterRepository.findByProjectId is called by generateAllCharacterImagesAsync in async thread
        when(characterRepository.findByProjectId("e2e-2")).thenReturn(new java.util.ArrayList<>());

        pipelineService.advancePipeline("e2e-2", "confirm_characters");

        PipelineService spySelf = (PipelineService) ReflectionTestUtils.getField(pipelineService, "pipelineServiceSelf");
        verify(spySelf).advancePipeline(eq("e2e-2"), eq("start_image_generation"));
        assertEquals(ProjectStatus.IMAGE_GENERATING.getCode(), project.getStatus());
    }

    @Test
    @DisplayName("Full lifecycle: IMAGE_REVIEW -> confirm -> ASSET_LOCKED -> PANEL_GENERATING")
    void full_lifecycle_image_review_to_panel_generating() {
        Project project = createProject("e2e-3", ProjectStatus.IMAGE_REVIEW);
        when(projectRepository.findByProjectId("e2e-3")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        pipelineService.advancePipeline("e2e-3", "confirm_images");

        // Verify the full auto-advance chain reached PANEL_GENERATING
        // (startPanelGeneration is called asynchronously inside CompletableFuture.runAsync)
        assertEquals(ProjectStatus.PANEL_GENERATING.getCode(), project.getStatus());
    }

    @Test
    @DisplayName("Full lifecycle: PANEL_REVIEW -> all_panels_confirmed -> PRODUCING (orchestrator triggered)")
    void full_lifecycle_panel_review_to_producing() {
        Project project = createProject("e2e-3b", ProjectStatus.PANEL_REVIEW);
        when(projectRepository.findByProjectId("e2e-3b")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);
        // Return episodes with panels so the gate check passes
        com.comic.entity.Episode episode = new com.comic.entity.Episode();
        episode.setId(1L);
        episode.setProjectId("e2e-3b");
        when(episodeRepository.findByProjectId("e2e-3b")).thenReturn(java.util.Collections.singletonList(episode));
        com.comic.entity.Panel panel = new com.comic.entity.Panel();
        panel.setId(1L);
        panel.setEpisodeId(1L);
        when(panelRepository.findByEpisodeId(1L)).thenReturn(java.util.Collections.singletonList(panel));

        pipelineService.advancePipeline("e2e-3b", "all_panels_confirmed");

        assertEquals(ProjectStatus.PRODUCING.getCode(), project.getStatus());
        verify(panelProductionService, times(1)).startOrResume(eq("e2e-3b"));
    }

    @Test
    @DisplayName("PRODUCING -> production_completed -> COMPLETED (persisted)")
    void full_lifecycle_producing_to_completed() {
        Project project = createProject("e2e-4", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("e2e-4")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        pipelineService.advancePipeline("e2e-4", "production_completed");

        assertEquals(ProjectStatus.COMPLETED.getCode(), project.getStatus());
    }

    @Test
    @DisplayName("Idempotent: double confirm_script from wrong state should fail")
    void double_confirm_should_fail_from_wrong_state() {
        Project project = createProject("e2e-5", ProjectStatus.SCRIPT_REVIEW);
        when(projectRepository.findByProjectId("e2e-5")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        // First confirm succeeds: SCRIPT_REVIEW -> SCRIPT_CONFIRMED -> CHARACTER_EXTRACTING
        pipelineService.advancePipeline("e2e-5", "confirm_script");
        assertEquals(ProjectStatus.CHARACTER_EXTRACTING.getCode(), project.getStatus());

        // Second confirm from CHARACTER_EXTRACTING should fail (illegal transition)
        assertThrows(BusinessException.class, () ->
            pipelineService.advancePipeline("e2e-5", "confirm_script")
        );
    }

    @Test
    @DisplayName("Invalid transition from PRODUCING should be rejected")
    void invalid_transition_from_producing() {
        Project project = createProject("e2e-6", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("e2e-6")).thenReturn(project);

        assertThrows(BusinessException.class, () ->
            pipelineService.advancePipeline("e2e-6", "confirm_script")
        );

        // Status should not change
        assertEquals(ProjectStatus.PRODUCING.getCode(), project.getStatus());
        verify(projectRepository, never()).updateById(any(Project.class));
    }

    @Test
    @DisplayName("Invalid transition: null event should be rejected")
    void invalid_transition_null_event() {
        Project project = createProject("e2e-7", ProjectStatus.SCRIPT_REVIEW);
        when(projectRepository.findByProjectId("e2e-7")).thenReturn(project);

        assertThrows(BusinessException.class, () ->
            pipelineService.advancePipeline("e2e-7", null)
        );

        verify(projectRepository, never()).updateById(any(Project.class));
    }

    @Test
    @DisplayName("Transition table: verify all confirm transitions resolve correctly")
    void transition_table_confirm_transitions() {
        assertEquals(ProjectStatus.SCRIPT_CONFIRMED,
            ProjectStatus.resolveTransition(ProjectStatus.SCRIPT_REVIEW, "confirm_script"));
        assertEquals(ProjectStatus.CHARACTER_CONFIRMED,
            ProjectStatus.resolveTransition(ProjectStatus.CHARACTER_REVIEW, "confirm_characters"));
        assertEquals(ProjectStatus.ASSET_LOCKED,
            ProjectStatus.resolveTransition(ProjectStatus.IMAGE_REVIEW, "confirm_images"));
        assertEquals(ProjectStatus.COMPLETED,
            ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"));
    }

    @Test
    @DisplayName("Transition table: verify all auto-advance transitions resolve correctly")
    void transition_table_auto_advance_transitions() {
        assertEquals(ProjectStatus.CHARACTER_EXTRACTING,
            ProjectStatus.resolveTransition(ProjectStatus.SCRIPT_CONFIRMED, "start_character_extraction"));
        assertEquals(ProjectStatus.IMAGE_GENERATING,
            ProjectStatus.resolveTransition(ProjectStatus.CHARACTER_CONFIRMED, "start_image_generation"));
        assertEquals(ProjectStatus.PANEL_GENERATING,
            ProjectStatus.resolveTransition(ProjectStatus.ASSET_LOCKED, "start_panels"));
        assertEquals(ProjectStatus.PRODUCING,
            ProjectStatus.resolveTransition(ProjectStatus.PANEL_REVIEW, "all_panels_confirmed"));
    }

    @Test
    @DisplayName("Transition table: verify failure transitions resolve correctly")
    void transition_table_failure_transitions() {
        assertEquals(ProjectStatus.OUTLINE_GENERATING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.OUTLINE_GENERATING, "script_failed"));
        assertEquals(ProjectStatus.EPISODE_GENERATING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.EPISODE_GENERATING, "script_failed"));
        assertEquals(ProjectStatus.CHARACTER_EXTRACTING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.CHARACTER_EXTRACTING, "characters_failed"));
        assertEquals(ProjectStatus.IMAGE_GENERATING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.IMAGE_GENERATING, "images_failed"));
        assertEquals(ProjectStatus.PANEL_GENERATING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.PANEL_GENERATING, "panels_failed"));
    }

    @Test
    @DisplayName("Transition table: verify retry transitions resolve correctly")
    void transition_table_retry_transitions() {
        assertEquals(ProjectStatus.DRAFT,
            ProjectStatus.resolveTransition(ProjectStatus.OUTLINE_GENERATING_FAILED, "retry"));
        assertEquals(ProjectStatus.OUTLINE_REVIEW,
            ProjectStatus.resolveTransition(ProjectStatus.EPISODE_GENERATING_FAILED, "retry"));
        assertEquals(ProjectStatus.SCRIPT_CONFIRMED,
            ProjectStatus.resolveTransition(ProjectStatus.CHARACTER_EXTRACTING_FAILED, "retry"));
        assertEquals(ProjectStatus.CHARACTER_CONFIRMED,
            ProjectStatus.resolveTransition(ProjectStatus.IMAGE_GENERATING_FAILED, "retry"));
        assertEquals(ProjectStatus.ASSET_LOCKED,
            ProjectStatus.resolveTransition(ProjectStatus.PANEL_GENERATING_FAILED, "retry"));
    }

    @Test
    @DisplayName("Invalid event from any state returns null")
    void invalid_event_returns_null() {
        assertNull(ProjectStatus.resolveTransition(ProjectStatus.DRAFT, "confirm_script"));
        assertNull(ProjectStatus.resolveTransition(ProjectStatus.COMPLETED, "confirm_script"));
        assertNull(ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "confirm_characters"));
        assertNull(ProjectStatus.resolveTransition(null, "confirm_script"));
        assertNull(ProjectStatus.resolveTransition(ProjectStatus.SCRIPT_REVIEW, null));
    }

    @Test
    @DisplayName("all_panels_confirmed gate rejects when no panels exist")
    void all_panels_confirmed_gate_rejects_empty_panels() {
        Project project = createProject("e2e-8", ProjectStatus.PANEL_REVIEW);
        when(projectRepository.findByProjectId("e2e-8")).thenReturn(project);
        // Return episodes with NO panels -> gate should reject
        com.comic.entity.Episode episode = new com.comic.entity.Episode();
        episode.setId(1L);
        episode.setProjectId("e2e-8");
        when(episodeRepository.findByProjectId("e2e-8")).thenReturn(java.util.Collections.singletonList(episode));
        when(panelRepository.findByEpisodeId(1L)).thenReturn(new java.util.ArrayList<>());

        BusinessException ex = assertThrows(BusinessException.class, () ->
            pipelineService.advancePipeline("e2e-8", "all_panels_confirmed")
        );
        assertTrue(ex.getMessage().contains("没有可生产的分镜"));

        // Status should not change
        assertEquals(ProjectStatus.PANEL_REVIEW.getCode(), project.getStatus());
        verify(projectRepository, never()).updateById(any(Project.class));
    }

    private Project createProject(String projectId, ProjectStatus status) {
        Project p = new Project();
        p.setProjectId(projectId);
        p.setUserId("test-user");
        p.setStatus(status.getCode());
        p.setDeleted(false);
        p.setProjectInfo(new HashMap<>());
        return p;
    }
}
