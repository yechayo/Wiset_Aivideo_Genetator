package com.comic.service.script;

import com.comic.ai.PromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class ScriptServiceEpisodeCountTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private TextGenerationService textGenerationService;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private WorldRuleService worldRuleService;

    private ScriptService scriptService;

    @BeforeEach
    void setUp() {
        scriptService = new ScriptService(
                projectRepository,
                episodeRepository,
                textGenerationService,
                promptBuilder,
                worldRuleService,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("单集模式优先级最高：无论传入多少都强制返回1")
    void resolveEpisodeCount_singleModeAlwaysOne() {
        Project project = new Project();
        project.setTotalEpisodes(1);
        project.setEpisodesPerChapter(4);

        int count = scriptService.resolveEpisodeCount(project, "单集剧本", 5, false);

        assertEquals(1, count);
    }

    @Test
    @DisplayName("多集模式：显式传入episodeCount时优先使用")
    void resolveEpisodeCount_useRequestedCountWhenProvided() {
        Project project = new Project();
        project.setTotalEpisodes(12);
        project.setEpisodesPerChapter(4);

        int count = scriptService.resolveEpisodeCount(project, "第1章：开端（第1-3集）", 2, false);

        assertEquals(2, count);
    }

    @Test
    @DisplayName("多集模式：未传episodeCount时按章节区间自动计算")
    void resolveEpisodeCount_parseFromChapterRange() {
        Project project = new Project();
        project.setTotalEpisodes(12);
        project.setEpisodesPerChapter(4);

        int count = scriptService.resolveEpisodeCount(project, "第1章：开端（第1-3集）", null, false);

        assertEquals(3, count);
    }

    @Test
    @DisplayName("章节区间无法解析时回退episodesPerChapter")
    void resolveEpisodeCount_fallbackToProjectEpisodesPerChapter() {
        Project project = new Project();
        project.setTotalEpisodes(12);
        project.setEpisodesPerChapter(3);

        int count = scriptService.resolveEpisodeCount(project, "第1章：开端", null, false);

        assertEquals(3, count);
    }

    @Test
    @DisplayName("章节区间无法解析且episodesPerChapter为空时回退默认4")
    void resolveEpisodeCount_fallbackToDefaultFour() {
        Project project = new Project();
        project.setTotalEpisodes(12);
        project.setEpisodesPerChapter(null);

        int count = scriptService.resolveEpisodeCount(project, "第1章：开端", null, false);

        assertEquals(4, count);
    }
}
