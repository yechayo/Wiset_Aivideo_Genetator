package com.comic.service.production;

import com.comic.ai.text.TextGenerationService;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardEnhancementServiceTest {

    @Mock
    private TextGenerationService textGenerationService;

    @Mock
    private EpisodeRepository episodeRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StoryboardEnhancementService enhancementService;

    @BeforeEach
    void setUp() {
        enhancementService = new StoryboardEnhancementService(
                textGenerationService,
                episodeRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("RULE mode should recommend close-up/high-angle/tracking when keywords appear")
    void enhanceStoryboardJson_ruleMode_keywordRecommendation() throws Exception {
        enhancementService.setEnhancementModeForTest("RULE");

        String storyboardJson = "{\n" +
                "  \"episode\": 1,\n" +
                "  \"title\": \"t\",\n" +
                "  \"panels\": [\n" +
                "    {\n" +
                "      \"panel_id\": \"p1\",\n" +
                "      \"composition\": \"角色脸部表情特写，俯拍，跟随行走\",\n" +
                "      \"background\": {\"scene_desc\": \"街道\", \"time_of_day\": \"day\", \"atmosphere\": \"tense\"},\n" +
                "      \"dialogue\": [],\n" +
                "      \"characters\": [],\n" +
                "      \"sfx\": [],\n" +
                "      \"pacing\": \"normal\",\n" +
                "      \"image_prompt_hint\": \"hint\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        String enhanced = enhancementService.enhanceStoryboardJson(storyboardJson);
        JsonNode root = objectMapper.readTree(enhanced);
        JsonNode panel = root.path("panels").get(0);

        assertEquals("CLOSE_UP", panel.path("shot_type").asText());
        assertEquals("high_angle", panel.path("camera_angle").asText());
        assertEquals("tracking_follow", panel.path("camera_movement").asText());
        assertTrue(panel.path("enhancement_reason").asText().contains("rule"));
    }

    @Test
    @DisplayName("LLM mode should fail fast when model returns terms outside whitelist")
    void enhanceStoryboardJson_llmMode_invalidTerm_shouldFail() {
        enhancementService.setEnhancementModeForTest("LLM");
        enhancementService.setEnhancementDelayMsForTest(0L);

        String storyboardJson = "{\n" +
                "  \"episode\": 1,\n" +
                "  \"title\": \"t\",\n" +
                "  \"panels\": [\n" +
                "    {\n" +
                "      \"panel_id\": \"p1\",\n" +
                "      \"composition\": \"普通镜头\",\n" +
                "      \"background\": {\"scene_desc\": \"room\", \"time_of_day\": \"day\", \"atmosphere\": \"calm\"},\n" +
                "      \"dialogue\": [],\n" +
                "      \"characters\": [],\n" +
                "      \"sfx\": [],\n" +
                "      \"pacing\": \"normal\",\n" +
                "      \"image_prompt_hint\": \"hint\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        when(textGenerationService.generate(anyString(), anyString()))
                .thenReturn("{\"shot_type\":\"INVALID\",\"camera_angle\":\"eye_level\",\"camera_movement\":\"static\",\"reason\":\"x\"}");

        assertThrows(IllegalStateException.class, () -> enhancementService.enhanceStoryboardJson(storyboardJson));
    }

    @Test
    @DisplayName("LLM mode should apply per-panel delay between panels")
    void enhanceStoryboardJson_llmMode_shouldDelayBetweenPanels() throws Exception {
        StoryboardEnhancementService spyService = spy(enhancementService);
        spyService.setEnhancementModeForTest("LLM");
        spyService.setEnhancementDelayMsForTest(500L);
        doNothing().when(spyService).sleepBetweenPanels(anyLong());

        String storyboardJson = "{\n" +
                "  \"episode\": 1,\n" +
                "  \"title\": \"t\",\n" +
                "  \"panels\": [\n" +
                "    {\"panel_id\":\"p1\",\"composition\":\"a\",\"background\":{\"scene_desc\":\"s1\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},\"dialogue\":[],\"characters\":[],\"sfx\":[],\"pacing\":\"normal\",\"image_prompt_hint\":\"h\"},\n" +
                "    {\"panel_id\":\"p2\",\"composition\":\"b\",\"background\":{\"scene_desc\":\"s2\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},\"dialogue\":[],\"characters\":[],\"sfx\":[],\"pacing\":\"normal\",\"image_prompt_hint\":\"h\"},\n" +
                "    {\"panel_id\":\"p3\",\"composition\":\"c\",\"background\":{\"scene_desc\":\"s3\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},\"dialogue\":[],\"characters\":[],\"sfx\":[],\"pacing\":\"normal\",\"image_prompt_hint\":\"h\"}\n" +
                "  ]\n" +
                "}";

        when(textGenerationService.generate(anyString(), anyString()))
                .thenReturn("{\"shot_type\":\"MEDIUM_SHOT\",\"camera_angle\":\"eye_level\",\"camera_movement\":\"static\",\"reason\":\"ok\"}");

        spyService.enhanceStoryboardJson(storyboardJson);

        verify(spyService, times(2)).sleepBetweenPanels(500L);
    }

    @Test
    @DisplayName("enhanceEpisodeStoryboard should persist enhanced storyboard json")
    void enhanceEpisodeStoryboard_shouldUpdateEpisode() {
        enhancementService.setEnhancementModeForTest("RULE");

        Episode episode = new Episode();
        episode.setId(100L);
        episode.setStoryboardJson("{\"episode\":1,\"title\":\"t\",\"panels\":[{\"panel_id\":\"p1\",\"composition\":\"普通镜头\",\"background\":{\"scene_desc\":\"room\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},\"dialogue\":[],\"characters\":[],\"sfx\":[],\"pacing\":\"normal\",\"image_prompt_hint\":\"hint\"}]}");
        when(episodeRepository.selectById(100L)).thenReturn(episode);

        enhancementService.enhanceEpisodeStoryboard(100L);

        ArgumentCaptor<Episode> captor = ArgumentCaptor.forClass(Episode.class);
        verify(episodeRepository).updateById(captor.capture());
        Episode updated = captor.getValue();
        assertTrue(updated.getStoryboardJson().contains("camera_movement"));
    }
}
