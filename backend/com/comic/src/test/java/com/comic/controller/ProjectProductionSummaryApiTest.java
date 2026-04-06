package com.comic.controller;

import com.comic.dto.response.ProjectProductionSummaryResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.service.project.ProjectService;
import com.comic.service.redis.ProgressService;
import com.comic.service.script.ScriptService;
import com.comic.statemachine.enums.ProjectMilestone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectService#getProductionSummary(String)} 与旧 Pipeline 逻辑对齐后的单元测试。
 */
class ProjectProductionSummaryApiTest {

    private ProjectService projectService;
    private ProjectRepository projectRepository;
    private EpisodeRepository episodeRepository;
    private PanelRepository panelRepository;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        panelRepository = mock(PanelRepository.class);

        projectService = new ProjectService(
                projectRepository,
                episodeRepository,
                panelRepository,
                mock(CharacterRepository.class),
                mock(ScriptService.class),
                mock(CharacterExtractService.class),
                mock(CharacterImageGenerationService.class),
                mock(ProgressService.class)
        );
    }

    @Test
    void get_production_summary_should_return_current_panel_and_progress() {
        Project project = new Project();
        project.setProjectId("proj-1");
        project.setStatus(ProjectMilestone.ASSET_CONFIRMED.getCode());
        when(projectRepository.findByProjectId("proj-1")).thenReturn(project);

        Episode ep1 = new Episode();
        ep1.setId(1L);
        ep1.setProjectId("proj-1");
        when(episodeRepository.findByProjectId("proj-1")).thenReturn(Arrays.asList(ep1));

        Panel panel1 = new Panel();
        panel1.setId(10L);
        panel1.setEpisodeId(1L);
        Map<String, Object> info1 = new HashMap<>();
        info1.put("videoStatus", "completed");
        panel1.setPanelInfo(info1);

        Panel panel2 = new Panel();
        panel2.setId(11L);
        panel2.setEpisodeId(1L);
        Map<String, Object> info2 = new HashMap<>();
        info2.put("gridStatus", "generated");
        info2.put("videoStatus", "pending");
        panel2.setPanelInfo(info2);

        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1, panel2));

        ProjectProductionSummaryResponse summary = projectService.getProductionSummary("proj-1");

        assertEquals(11L, summary.getCurrentPanelId());
        assertEquals(1L, summary.getCurrentEpisodeId());
        assertEquals(2, summary.getCurrentPanelIndex());
        assertEquals(2, summary.getTotalPanelCount());
        assertEquals(1, summary.getCompletedPanelCount());
        assertEquals("pending_review", summary.getProductionSubStage());
        assertEquals("awaiting_grid_approval", summary.getBlockedReason());
    }

    @Test
    void get_production_summary_all_completed_should_return_no_current_panel() {
        Project project = new Project();
        project.setProjectId("proj-2");
        project.setStatus(ProjectMilestone.ASSET_CONFIRMED.getCode());
        when(projectRepository.findByProjectId("proj-2")).thenReturn(project);

        Episode ep1 = new Episode();
        ep1.setId(1L);
        ep1.setProjectId("proj-2");
        when(episodeRepository.findByProjectId("proj-2")).thenReturn(Arrays.asList(ep1));

        Panel panel1 = new Panel();
        panel1.setId(10L);
        panel1.setEpisodeId(1L);
        Map<String, Object> info1 = new HashMap<>();
        info1.put("videoStatus", "completed");
        panel1.setPanelInfo(info1);
        when(panelRepository.findByEpisodeId(1L)).thenReturn(Arrays.asList(panel1));

        ProjectProductionSummaryResponse summary = projectService.getProductionSummary("proj-2");

        assertNull(summary.getCurrentPanelId());
        assertEquals(1, summary.getTotalPanelCount());
        assertEquals(1, summary.getCompletedPanelCount());
    }
}
