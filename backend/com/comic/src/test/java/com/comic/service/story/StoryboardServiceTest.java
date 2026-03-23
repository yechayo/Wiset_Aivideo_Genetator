package com.comic.service.story;

import com.comic.ai.PromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.world.CharacterService;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardServiceTest {

    @Mock
    private TextGenerationService textGenerationService;
    @Mock
    private CharacterService characterService;
    @Mock
    private WorldRuleService worldRuleService;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PromptBuilder promptBuilder;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private StoryboardService storyboardService;

    @Test
    @DisplayName("generateStoryboard - 构建用户提示词时优先使用 episode.content")
    void generateStoryboard_shouldPreferEpisodeContentForUserPrompt() throws Exception {
        Episode episode = new Episode();
        episode.setId(100L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(100L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(validStoryboardJson());

        String result = storyboardService.generateStoryboard(100L);

        assertEquals(validStoryboardJson(), result);
        verify(promptBuilder).buildEpisodeUserPrompt(eq(1), eq("EPISODE_CONTENT"), anyString());
        verify(characterService).updateStatesFromStoryboard(eq("P-1"), any(JsonNode.class));
    }

    @Test
    @DisplayName("generateStoryboardWithFeedback - 构建修订提示词时优先使用 episode.content")
    void generateStoryboardWithFeedback_shouldPreferEpisodeContentForRevisionPrompt() {
        Episode episode = new Episode();
        episode.setId(101L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(2);
        episode.setOutlineNode("OUTLINE_NODE_2");
        episode.setContent("EPISODE_CONTENT_2");
        episode.setStoryboardJson("{\"episode\":2,\"title\":\"old\",\"panels\":[]}");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(101L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildStoryboardRevisionUserPrompt(eq(2), anyString(), anyString(), anyString(), eq("feedback")))
                .thenReturn("REVISION_USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("REVISION_USER_PROMPT")))
                .thenReturn(validStoryboardJson());

        String result = storyboardService.generateStoryboardWithFeedback(101L, "feedback");

        assertEquals(validStoryboardJson(), result);
        verify(promptBuilder).buildStoryboardRevisionUserPrompt(
                eq(2),
                eq("EPISODE_CONTENT_2"),
                anyString(),
                anyString(),
                eq("feedback")
        );
    }

    @Test
    @DisplayName("validateStoryboardJson - 非法 shot_type 应抛错")
    void validateStoryboardJson_shouldRejectInvalidShotType() {
        String invalid = validStoryboardJson().replace("\"WIDE_SHOT\"", "\"INVALID_SHOT\"");
        assertThrows(IllegalStateException.class, () -> invokeValidate(invalid));
    }

    @Test
    @DisplayName("validateStoryboardJson - 缺少 background.scene_desc 应抛错")
    void validateStoryboardJson_shouldRejectMissingBackgroundSceneDesc() {
        String invalid = validStoryboardJson().replace("\"scene_desc\":\"classroom\",", "");
        assertThrows(IllegalStateException.class, () -> invokeValidate(invalid));
    }

    @Test
    @DisplayName("validateStoryboardJson - 非法 bubble_type 应抛错")
    void validateStoryboardJson_shouldRejectInvalidBubbleType() {
        String invalid = validStoryboardJson().replace("\"bubble_type\":\"speech\"", "\"bubble_type\":\"invalid_type\"");
        assertThrows(IllegalStateException.class, () -> invokeValidate(invalid));
    }

    private String validStoryboardJson() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":[{"
                + "\"panel_id\":\"ep1_p1\","
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},"
                + "\"characters\":[{\"char_id\":\"c1\",\"position\":\"left\",\"pose\":\"stand\",\"expression\":\"neutral\",\"costume_state\":\"normal\"}],"
                + "\"dialogue\":[{\"speaker\":\"c1\",\"text\":\"hello\",\"bubble_type\":\"speech\"}],"
                + "\"sfx\":[\"wind\"],"
                + "\"pacing\":\"normal\","
                + "\"image_prompt_hint\":\"anime style\""
                + "}]"
                + "}";
    }

    private void invokeValidate(String json) {
        try {
            Method method = StoryboardService.class.getDeclaredMethod("validateStoryboardJson", JsonNode.class);
            method.setAccessible(true);
            JsonNode node = objectMapper.readTree(json);
            method.invoke(storyboardService, node);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
