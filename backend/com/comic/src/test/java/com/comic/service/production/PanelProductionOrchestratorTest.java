package com.comic.service.production;

import com.comic.common.ProjectStatus;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.pipeline.PipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PanelProductionOrchestratorTest {

    private PanelProductionService panelProductionService;
    private PanelRepository panelRepository;
    private EpisodeRepository episodeRepository;
    private ProjectRepository projectRepository;
    private PipelineService pipelineService;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        panelRepository = mock(PanelRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        projectRepository = mock(ProjectRepository.class);
        pipelineService = mock(PipelineService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);

        // Constructor: panelRepository, episodeRepository, projectRepository,
        // panelPromptBuilder, imageGenerationService, videoGenerationService,
        // applicationContext, stringRedisTemplate
        panelProductionService = new PanelProductionService(
            panelRepository, episodeRepository, projectRepository,
            null, null, null, null, stringRedisTemplate
        );

        ReflectionTestUtils.setField(panelProductionService, "pipelineService", pipelineService);
    }

    @Test
    void should_pick_single_current_panel_in_project_order() {
        // Setup: 2 episodes, 2 panels each. First panel video not completed.
        Project project = createProject("proj-1", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-1")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-1");
        Episode ep2 = createEpisode(2L, "proj-1");
        when(episodeRepository.findByProjectId("proj-1")).thenReturn(Arrays.asList(ep1, ep2));

        Panel panel1 = createPanel(10L, 1L, null, null, null, null); // no video
        Panel panel2 = createPanel(11L, 1L, null, null, null, "completed"); // video done
        Panel panel3 = createPanel(20L, 2L, null, null, null, null); // no video
        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1, panel2));
        when(panelRepository.findByEpisodeId(2L)).thenReturn(Arrays.asList(panel3));

        Panel result = panelProductionService.findNextIncompletePanel("proj-1");

        assertNotNull(result);
        assertEquals(10L, result.getId()); // First non-completed panel
    }

    @Test
    void should_return_null_when_all_panels_completed() {
        Project project = createProject("proj-2", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-2")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-2");
        when(episodeRepository.findByProjectId("proj-2")).thenReturn(Arrays.asList(ep1));

        Panel panel1 = createPanel(10L, 1L, null, null, null, "completed");
        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1));

        Panel result = panelProductionService.findNextIncompletePanel("proj-2");
        assertNull(result);
    }

    @Test
    void should_pause_when_comic_pending_review() {
        Project project = createProject("proj-3", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-3")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-3");
        when(episodeRepository.findByProjectId("proj-3")).thenReturn(Arrays.asList(ep1));

        // Panel with comic pending_review
        Panel panel1 = createPanel(10L, 1L, "http://bg.png", "pending_review", null, null);
        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1));

        // Should not throw, just return (pause)
        assertDoesNotThrow(() -> panelProductionService.startOrResume("proj-3"));

        // Should NOT trigger video generation or pipeline completion
        verify(pipelineService, never()).advancePipeline(anyString(), anyString());
    }

    @Test
    void should_pause_when_step_failed() {
        Project project = createProject("proj-4", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-4")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-4");
        when(episodeRepository.findByProjectId("proj-4")).thenReturn(Arrays.asList(ep1));

        // Panel with failed background
        Panel panel1 = createPanel(10L, 1L, null, null, "failed", null);
        panel1.getPanelInfo().put("backgroundStatus", "failed");
        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1));

        assertDoesNotThrow(() -> panelProductionService.startOrResume("proj-4"));
        verify(pipelineService, never()).advancePipeline(anyString(), anyString());
    }

    @Test
    void should_not_start_next_panel_before_current_video_completed() {
        Project project = createProject("proj-5", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-5")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-5");
        when(episodeRepository.findByProjectId("proj-5")).thenReturn(Arrays.asList(ep1));

        // Panel with video generating (not completed)
        Panel panel1 = createPanel(10L, 1L, "http://bg.png", "approved", "generating", null);
        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1));

        assertDoesNotThrow(() -> panelProductionService.startOrResume("proj-5"));
        verify(pipelineService, never()).advancePipeline(anyString(), anyString());
    }

    @Test
    void no_panels_should_trigger_production_completed() {
        Project project = createProject("proj-6", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-6")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-6");
        when(episodeRepository.findByProjectId("proj-6")).thenReturn(Arrays.asList(ep1));
        when(panelRepository.findByEpisodeId(1L)).thenReturn(new ArrayList<>());

        // startOrResume with no panels should trigger production_completed
        panelProductionService.startOrResume("proj-6");
        verify(pipelineService, times(1)).advancePipeline("proj-6", "production_completed");
    }

    @Test
    void double_resume_should_not_double_trigger_completion() {
        // Setup: project with no panels (triggers completion)
        Project project = createProject("proj-dbl", ProjectStatus.PRODUCING);
        when(projectRepository.findByProjectId("proj-dbl")).thenReturn(project);

        Episode ep1 = createEpisode(1L, "proj-dbl");
        when(episodeRepository.findByProjectId("proj-dbl")).thenReturn(Arrays.asList(ep1));
        when(panelRepository.findByEpisodeId(1L)).thenReturn(new ArrayList<>());

        // First call triggers production_completed
        panelProductionService.startOrResume("proj-dbl");
        verify(pipelineService, times(1)).advancePipeline("proj-dbl", "production_completed");

        // Second call: project status is now COMPLETED (not PRODUCING), so startOrResume returns early
        project.setStatus(ProjectStatus.COMPLETED.getCode());
        panelProductionService.startOrResume("proj-dbl");
        // Should still be only 1 call (idempotent)
        verify(pipelineService, times(1)).advancePipeline("proj-dbl", "production_completed");
    }

    // Helper methods
    private Project createProject(String projectId, ProjectStatus status) {
        Project p = new Project();
        p.setProjectId(projectId);
        p.setUserId("test-user");
        p.setStatus(status.getCode());
        p.setDeleted(false);
        p.setProjectInfo(new HashMap<>());
        return p;
    }

    private Episode createEpisode(Long id, String projectId) {
        Episode ep = new Episode();
        ep.setId(id);
        ep.setProjectId(projectId);
        ep.setStatus("PANEL_CONFIRMED");
        ep.setEpisodeInfo(new HashMap<>());
        return ep;
    }

    private Panel createPanel(Long id, Long episodeId, String bgUrl, String comicStatus,
                               String videoStatus, String videoStatusOverride) {
        Panel panel = new Panel();
        panel.setId(id);
        panel.setEpisodeId(episodeId);
        Map<String, Object> info = new HashMap<>();
        if (bgUrl != null) {
            info.put("backgroundUrl", bgUrl);
            info.put("backgroundStatus", "completed");
        }
        if (comicStatus != null) info.put("comicStatus", comicStatus);
        if (videoStatus != null) info.put("videoStatus", videoStatus);
        if (videoStatusOverride != null) info.put("videoStatus", videoStatusOverride);
        panel.setPanelInfo(info);
        return panel;
    }
}
