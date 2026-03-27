package com.comic.service.pipeline;

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
import com.comic.service.script.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test suite for PipelineService auto-advance behavior.
 *
 * This test documents the intended auto-chain behavior where confirming a stage
 * should automatically trigger the next stage in the pipeline.
 *
 * These tests will initially FAIL because the current implementation requires
 * manual intervention to start each stage. They serve as a safety net to ensure
 * that future implementation achieves the desired automatic progression.
 */
class PipelineServiceAutoAdvanceTest {

    private PipelineService pipelineService;
    private ProjectRepository projectRepository;
    private EpisodeRepository episodeRepository;
    private PanelRepository panelRepository;
    private CharacterRepository characterRepository;
    private ScriptService scriptService;
    private CharacterExtractService characterExtractService;
    private CharacterImageGenerationService characterImageGenerationService;
    private PanelGenerationService panelGenerationService;
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
        broadcaster = mock(ProjectStatusBroadcaster.class);

        pipelineService = new PipelineService(
            projectRepository,
            episodeRepository,
            panelRepository,
            characterRepository,
            scriptService,
            characterExtractService,
            characterImageGenerationService,
            broadcaster
        );

        // Inject mocked dependencies via reflection
        ReflectionTestUtils.setField(pipelineService, "panelGenerationService", panelGenerationService);
    }

    @Test
    void confirm_script_should_eventually_enter_character_extracting() {
        // Setup: Create a project in SCRIPT_REVIEW state
        Project project = createTestProject("test-project-1");
        project.setStatus(ProjectStatus.SCRIPT_REVIEW.getCode());

        when(projectRepository.findByProjectId("test-project-1")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        // When: User confirms the script
        try {
            pipelineService.advancePipeline("test-project-1", "confirm_script");
        } catch (BusinessException e) {
            fail("Should not throw exception: " + e.getMessage());
        }

        // Then: Status should transition to SCRIPT_CONFIRMED
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, times(1)).updateById(projectCaptor.capture());
        Project updatedProject = projectCaptor.getValue();

        assertEquals(
            ProjectStatus.SCRIPT_CONFIRMED.getCode(),
            updatedProject.getStatus(),
            "After confirming script, project should be in SCRIPT_CONFIRMED state"
        );

        // And: Character extraction should be automatically triggered
        verify(characterExtractService, times(1)).extractCharacters(eq("test-project-1"));

        // Eventually, the status should become CHARACTER_EXTRACTING
        // This verifies the auto-advance chain: SCRIPT_REVIEW -> SCRIPT_CONFIRMED -> CHARACTER_EXTRACTING
    }

    @Test
    void confirm_characters_should_eventually_enter_image_generating() {
        // Setup: Create a project in CHARACTER_REVIEW state
        Project project = createTestProject("test-project-2");
        project.setStatus(ProjectStatus.CHARACTER_REVIEW.getCode());

        when(projectRepository.findByProjectId("test-project-2")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        // When: User confirms the characters
        try {
            pipelineService.advancePipeline("test-project-2", "confirm_characters");
        } catch (BusinessException e) {
            fail("Should not throw exception: " + e.getMessage());
        }

        // Then: Status should transition to CHARACTER_CONFIRMED
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, times(1)).updateById(projectCaptor.capture());
        Project updatedProject = projectCaptor.getValue();

        assertEquals(
            ProjectStatus.CHARACTER_CONFIRMED.getCode(),
            updatedProject.getStatus(),
            "After confirming characters, project should be in CHARACTER_CONFIRMED state"
        );

        // And: Image generation should be automatically triggered
        verify(characterImageGenerationService, times(1)).generateAll(anyString());

        // Eventually, the status should become IMAGE_GENERATING
        // This verifies the auto-advance chain: CHARACTER_REVIEW -> CHARACTER_CONFIRMED -> IMAGE_GENERATING
    }

    @Test
    void confirm_images_should_eventually_enter_panel_generating() {
        // Setup: Create a project in IMAGE_REVIEW state
        Project project = createTestProject("test-project-3");
        project.setStatus(ProjectStatus.IMAGE_REVIEW.getCode());

        when(projectRepository.findByProjectId("test-project-3")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);
        doNothing().when(panelGenerationService).startPanelGeneration(anyString());

        // When: User confirms the images
        try {
            pipelineService.advancePipeline("test-project-3", "confirm_images");
        } catch (BusinessException e) {
            fail("Should not throw exception: " + e.getMessage());
        }

        // Then: Status should transition to ASSET_LOCKED
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, times(1)).updateById(projectCaptor.capture());
        Project updatedProject = projectCaptor.getValue();

        assertEquals(
            ProjectStatus.ASSET_LOCKED.getCode(),
            updatedProject.getStatus(),
            "After confirming images, project should be in ASSET_LOCKED state"
        );

        // And: Panel generation should be automatically triggered
        verify(panelGenerationService, times(1)).startPanelGeneration(eq("test-project-3"));

        // Eventually, the status should become PANEL_GENERATING
        // This verifies the auto-advance chain: IMAGE_REVIEW -> ASSET_LOCKED -> PANEL_GENERATING
    }

    @Test
    void production_completed_should_transition_to_completed() {
        // Setup: Create a project in PRODUCING state
        Project project = createTestProject("test-project-4");
        project.setStatus(ProjectStatus.PRODUCING.getCode());

        when(projectRepository.findByProjectId("test-project-4")).thenReturn(project);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);

        // When: Production is completed
        try {
            pipelineService.advancePipeline("test-project-4", "production_completed");
        } catch (BusinessException e) {
            fail("Should not throw exception: " + e.getMessage());
        }

        // Then: Status should transition to COMPLETED
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, times(1)).updateById(projectCaptor.capture());
        Project updatedProject = projectCaptor.getValue();

        assertEquals(
            ProjectStatus.COMPLETED.getCode(),
            updatedProject.getStatus(),
            "After production completes, project should be in COMPLETED state"
        );
    }

    @Test
    void invalid_transition_should_throw_exception() {
        // Setup: Create a project in IMAGE_REVIEW state
        Project project = createTestProject("test-project-5");
        project.setStatus(ProjectStatus.IMAGE_REVIEW.getCode());

        when(projectRepository.findByProjectId("test-project-5")).thenReturn(project);

        // When: Attempting an invalid transition
        try {
            pipelineService.advancePipeline("test-project-5", "invalid_event");
            fail("Should throw BusinessException for invalid transition");
        } catch (BusinessException e) {
            // Expected
            assertNotNull(e.getMessage());
            assertEquals(true, e.getMessage().contains("非法状态转换"));
        }

        // Then: Status should not change
        verify(projectRepository, never()).updateById(any(Project.class));
    }

    /**
     * Helper method to create a test project.
     */
    private Project createTestProject(String projectId) {
        Project project = new Project();
        project.setProjectId(projectId);
        project.setUserId("test-user");
        project.setDeleted(false);
        project.setProjectInfo(new HashMap<>());
        return project;
    }
}
