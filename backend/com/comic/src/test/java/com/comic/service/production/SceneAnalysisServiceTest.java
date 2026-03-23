package com.comic.service.production;

import com.comic.dto.model.SceneAnalysisResultModel;
import com.comic.dto.model.SceneGroupModel;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneAnalysisServiceTest {

    @Mock
    private EpisodeRepository episodeRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SceneAnalysisService sceneAnalysisService;

    @Test
    @DisplayName("analyzeScenes - 按 background.scene_desc 连续分组并提取角色 char_id")
    void analyzeScenes_shouldGroupByBackgroundSceneDesc() {
        Episode episode = new Episode();
        episode.setId(1L);
        episode.setStoryboardJson(storyboardJsonForGrouping());
        when(episodeRepository.selectById(1L)).thenReturn(episode);

        SceneAnalysisResultModel result = sceneAnalysisService.analyzeScenes(1L);

        assertEquals(3, result.getTotalPanelCount());
        assertEquals(2, result.getSceneCount());

        List<SceneGroupModel> groups = result.getSceneGroups();
        assertEquals(2, groups.size());

        SceneGroupModel g1 = groups.get(0);
        assertEquals("SCENE-0", g1.getSceneId());
        assertEquals("教室", g1.getLocation());
        assertEquals(0, g1.getStartPanelIndex());
        assertEquals(1, g1.getEndPanelIndex());
        assertTrue(g1.getCharacters().contains("char_a"));
        assertTrue(g1.getCharacters().contains("char_b"));

        SceneGroupModel g2 = groups.get(1);
        assertEquals("SCENE-1", g2.getSceneId());
        assertEquals("操场", g2.getLocation());
        assertEquals(2, g2.getStartPanelIndex());
        assertEquals(2, g2.getEndPanelIndex());
        assertEquals(1, g2.getCharacters().size());
        assertEquals("char_c", g2.getCharacters().get(0));
    }

    @Test
    @DisplayName("analyzeScenes - 缺少 background.scene_desc 时抛异常")
    void analyzeScenes_shouldFailWhenSceneDescMissing() {
        Episode episode = new Episode();
        episode.setId(2L);
        episode.setStoryboardJson("{\"episode\":1,\"title\":\"t\",\"panels\":[{\"background\":{\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},\"characters\":[]}]}");
        when(episodeRepository.selectById(2L)).thenReturn(episode);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> sceneAnalysisService.analyzeScenes(2L));
        assertTrue(ex.getMessage().contains("scene_desc"));
    }

    private String storyboardJsonForGrouping() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"第1集\","
                + "\"panels\":["
                + "{"
                + "\"panel_id\":\"ep1_p1\","
                + "\"composition\":\"教室开场\","
                + "\"background\":{\"scene_desc\":\"教室\",\"time_of_day\":\"白天\",\"atmosphere\":\"平静\"},"
                + "\"characters\":[{\"char_id\":\"char_a\"}]"
                + "},"
                + "{"
                + "\"panel_id\":\"ep1_p2\","
                + "\"composition\":\"教室对话\","
                + "\"background\":{\"scene_desc\":\"教室\",\"time_of_day\":\"白天\",\"atmosphere\":\"平静\"},"
                + "\"characters\":[{\"char_id\":\"char_b\"}]"
                + "},"
                + "{"
                + "\"panel_id\":\"ep1_p3\","
                + "\"composition\":\"操场奔跑\","
                + "\"background\":{\"scene_desc\":\"操场\",\"time_of_day\":\"白天\",\"atmosphere\":\"热闹\"},"
                + "\"characters\":[{\"char_id\":\"char_c\"}]"
                + "}"
                + "]"
                + "}";
    }
}

