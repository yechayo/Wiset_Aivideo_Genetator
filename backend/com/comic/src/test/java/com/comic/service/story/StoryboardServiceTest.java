package com.comic.service.story;

import com.comic.ai.PromptBuilder;
import com.comic.common.BusinessException;
import com.comic.ai.text.TextGenerationService;
import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.common.ProjectStatus;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.world.CharacterService;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
    @DisplayName("generateStoryboard prefers episode.content when building the user prompt")
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
    @DisplayName("generateStoryboardWithFeedback prefers episode.content when building the revision prompt")
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
    @DisplayName("generateStoryboard auto-fills panel_id when it is missing")
    void generateStoryboard_shouldAutoFillPanelIdWhenMissing() throws Exception {
        Episode episode = new Episode();
        episode.setId(102L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(102L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(storyboardJsonWithoutPanelId());

        String result = storyboardService.generateStoryboard(102L);
        JsonNode node = objectMapper.readTree(result);

        assertEquals("ep1_p1", node.path("panels").get(0).path("panel_id").asText());
    }

    @Test
    @DisplayName("generateStoryboard normalizes numeric panel_id and character id alias")
    void generateStoryboard_shouldNormalizeNumericPanelIdAndCharacterIdAlias() throws Exception {
        Episode episode = new Episode();
        episode.setId(105L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(105L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(storyboardJsonWithNumericPanelIdAndCharacterIdAlias());

        String result = storyboardService.generateStoryboard(105L);
        JsonNode node = objectMapper.readTree(result);

        assertEquals("ep1_p1", node.path("panels").get(0).path("panel_id").asText());
        assertEquals("CHAR-1", node.path("panels").get(0).path("characters").get(0).path("char_id").asText());
    }

    @Test
    @DisplayName("generateStoryboard maps character name to known char_id when model omits it")
    void generateStoryboard_shouldMapCharacterNameToKnownCharId() throws Exception {
        Episode episode = new Episode();
        episode.setId(107L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        CharacterStateModel characterState = new CharacterStateModel();
        characterState.setCharId("SUP-1");
        characterState.setName("配角");

        when(episodeRepository.selectById(107L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.singletonList(characterState));
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(storyboardJsonWithCharacterNameOnly());

        String result = storyboardService.generateStoryboard(107L);
        JsonNode node = objectMapper.readTree(result);

        assertEquals("SUP-1", node.path("panels").get(0).path("characters").get(0).path("char_id").asText());
    }

    @Test
    @DisplayName("generateStoryboard fills missing character fields from aliases and defaults")
    void generateStoryboard_shouldFillMissingCharacterFieldsFromAliasesAndDefaults() throws Exception {
        Episode episode = new Episode();
        episode.setId(108L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        CharacterStateModel characterState = new CharacterStateModel();
        characterState.setCharId("SUP-1");
        characterState.setName("配角");

        when(episodeRepository.selectById(108L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.singletonList(characterState));
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(storyboardJsonWithMinimalCharacterFields());

        String result = storyboardService.generateStoryboard(108L);
        JsonNode character = objectMapper.readTree(result).path("panels").get(0).path("characters").get(0);

        assertEquals("SUP-1", character.path("char_id").asText());
        assertEquals("center", character.path("position").asText());
        assertEquals("standing", character.path("pose").asText());
        assertEquals("focused", character.path("expression").asText());
        assertEquals("normal", character.path("costume_state").asText());
    }

    @Test
    @DisplayName("generateStoryboard allows panels without visible characters")
    void generateStoryboard_shouldAllowPanelsWithoutVisibleCharacters() throws Exception {
        Episode episode = new Episode();
        episode.setId(109L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(109L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(storyboardJsonWithEmptyCharactersPanel());

        String result = storyboardService.generateStoryboard(109L);
        JsonNode secondPanelCharacters = objectMapper.readTree(result).path("panels").get(1).path("characters");

        assertEquals(0, secondPanelCharacters.size());
    }

    @Test
    @DisplayName("generateStoryboard fills missing character fields even when char_id already exists")
    void generateStoryboard_shouldFillCharacterFieldsWhenCharIdAlreadyExists() throws Exception {
        Episode episode = new Episode();
        episode.setId(110L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(110L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(storyboardJsonWithCharIdButMissingCharacterFields());

        String result = storyboardService.generateStoryboard(110L);
        JsonNode character = objectMapper.readTree(result).path("panels").get(0).path("characters").get(0);

        assertEquals("CHAR-1", character.path("char_id").asText());
        assertEquals("center", character.path("position").asText());
        assertEquals("standing", character.path("pose").asText());
        assertEquals("focused", character.path("expression").asText());
        assertEquals("normal", character.path("costume_state").asText());
    }

    @Test
    @DisplayName("generateStoryboard adds truncation guidance after an EOF JSON failure")
    void generateStoryboard_shouldAddTruncationGuidanceAfterEofFailure() throws Exception {
        Episode episode = new Episode();
        episode.setId(111L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setOutlineNode("OUTLINE_NODE");
        episode.setContent("EPISODE_CONTENT");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");

        when(episodeRepository.selectById(111L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(promptBuilder.addStricterConstraints("SYS_PROMPT", 2)).thenReturn("SYS_PROMPT_RETRY");
        when(textGenerationService.generate(anyString(), eq("USER_PROMPT")))
                .thenReturn(truncatedStoryboardJson(), validStoryboardJson());

        String result = storyboardService.generateStoryboard(111L);

        assertEquals(validStoryboardJson(), result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(textGenerationService, times(2)).generate(systemPromptCaptor.capture(), eq("USER_PROMPT"));

        List<String> prompts = systemPromptCaptor.getAllValues();
        assertEquals("SYS_PROMPT", prompts.get(0));
        assertTrue(prompts.get(1).contains("Previous attempt output was truncated"));
        assertTrue(prompts.get(1).contains("Keep every non-dialogue string concise"));
    }

    @Test
    @DisplayName("generateStoryboard clears a stale error before retry generation starts")
    void generateStoryboard_shouldClearStaleErrorBeforeRetryStarts() {
        Episode episode = new Episode();
        episode.setId(103L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setContent("EPISODE_CONTENT");
        episode.setErrorMsg("old failure");

        WorldConfigModel world = new WorldConfigModel();
        world.setGenre("ANIME");
        List<Episode> updates = new ArrayList<Episode>();

        when(episodeRepository.selectById(103L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenAnswer(invocation -> {
            Episode current = invocation.getArgument(0);
            updates.add(copyEpisode(current));
            return 1;
        });
        when(worldRuleService.getWorldConfig("P-1")).thenReturn(world);
        when(characterService.getCurrentStates("P-1")).thenReturn(Collections.emptyList());
        when(promptBuilder.buildStoryboardSystemPrompt(eq(world), anyList())).thenReturn("SYS_PROMPT");
        when(promptBuilder.buildEpisodeUserPrompt(eq(1), anyString(), anyString())).thenReturn("USER_PROMPT");
        when(textGenerationService.generate(eq("SYS_PROMPT"), eq("USER_PROMPT")))
                .thenReturn(validStoryboardJson());

        storyboardService.generateStoryboard(103L);

        Episode firstUpdate = updates.get(0);
        assertEquals("STORYBOARD_GENERATING", firstUpdate.getStatus());
        assertNull(firstUpdate.getErrorMsg());
    }

    @Test
    @DisplayName("retryFailedStoryboard marks project and episode as generating before async work")
    void retryFailedStoryboard_shouldMarkStatusesBeforeAsyncWork() throws Exception {
        Episode episode = new Episode();
        episode.setId(104L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setStatus("STORYBOARD_FAILED");
        episode.setErrorMsg("old failure");

        Project project = new Project();
        project.setProjectId("P-1");
        project.setStatus(ProjectStatus.STORYBOARD_GENERATING_FAILED.getCode());

        CountDownLatch releaseAsync = new CountDownLatch(1);

        when(episodeRepository.selectById(104L)).thenReturn(episode);
        when(episodeRepository.updateById(any(Episode.class))).thenReturn(1);
        when(projectRepository.updateById(any(Project.class))).thenReturn(1);
        when(projectRepository.findByProjectId("P-1")).thenAnswer(invocation -> {
            if (!"main".equals(Thread.currentThread().getName())) {
                releaseAsync.await(5, TimeUnit.SECONDS);
            }
            return project;
        });

        try {
            storyboardService.retryFailedStoryboard(104L);

            assertEquals("STORYBOARD_GENERATING", episode.getStatus());
            assertNull(episode.getErrorMsg());
            assertEquals(ProjectStatus.STORYBOARD_GENERATING.getCode(), project.getStatus());
        } finally {
            releaseAsync.countDown();
        }
    }

    @Test
    @DisplayName("retryFailedStoryboard rejects retry while episode is still generating")
    void retryFailedStoryboard_shouldRejectRetryWhenEpisodeStillGenerating() {
        Episode episode = new Episode();
        episode.setId(106L);
        episode.setProjectId("P-1");
        episode.setEpisodeNum(1);
        episode.setStatus("STORYBOARD_GENERATING");

        when(episodeRepository.selectById(106L)).thenReturn(episode);

        assertThrows(BusinessException.class, () -> storyboardService.retryFailedStoryboard(106L));
    }

    @Test
    @DisplayName("validateStoryboardJson rejects invalid shot_type")
    void validateStoryboardJson_shouldRejectInvalidShotType() {
        String invalid = validStoryboardJson().replace("\"WIDE_SHOT\"", "\"INVALID_SHOT\"");
        assertThrows(IllegalStateException.class, () -> invokeValidate(invalid));
    }

    @Test
    @DisplayName("validateStoryboardJson rejects missing background.scene_desc")
    void validateStoryboardJson_shouldRejectMissingBackgroundSceneDesc() {
        String invalid = validStoryboardJson().replace("\"scene_desc\":\"classroom\",", "");
        assertThrows(IllegalStateException.class, () -> invokeValidate(invalid));
    }

    @Test
    @DisplayName("validateStoryboardJson rejects invalid bubble_type")
    void validateStoryboardJson_shouldRejectInvalidBubbleType() {
        String invalid = validStoryboardJson().replace("\"bubble_type\":\"speech\"", "\"bubble_type\":\"invalid_type\"");
        assertThrows(IllegalStateException.class, () -> invokeValidate(invalid));
    }

    private Episode copyEpisode(Episode episode) {
        Episode copy = new Episode();
        copy.setId(episode.getId());
        copy.setProjectId(episode.getProjectId());
        copy.setEpisodeNum(episode.getEpisodeNum());
        copy.setStatus(episode.getStatus());
        copy.setErrorMsg(episode.getErrorMsg());
        copy.setRetryCount(episode.getRetryCount());
        copy.setStoryboardJson(episode.getStoryboardJson());
        return copy;
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

    private String storyboardJsonWithoutPanelId() {
        return validStoryboardJson().replace("\"panel_id\":\"ep1_p1\",", "");
    }

    private String storyboardJsonWithNumericPanelIdAndCharacterIdAlias() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":[{"
                + "\"panel_id\":1,"
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},"
                + "\"characters\":[{\"id\":\"CHAR-1\",\"position\":\"left\",\"pose\":\"stand\",\"expression\":\"neutral\",\"costume_state\":\"normal\"}],"
                + "\"dialogue\":[{\"speaker\":\"CHAR-1\",\"text\":\"hello\",\"bubble_type\":\"speech\"}],"
                + "\"sfx\":[\"wind\"],"
                + "\"pacing\":\"normal\","
                + "\"image_prompt_hint\":\"anime style\""
                + "}]"
                + "}";
    }

    private String storyboardJsonWithCharacterNameOnly() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":[{"
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},"
                + "\"characters\":[{\"name\":\"配角\",\"position\":\"left\",\"pose\":\"stand\",\"expression\":\"neutral\",\"costume_state\":\"normal\"}],"
                + "\"dialogue\":[{\"speaker\":\"配角\",\"text\":\"hello\",\"bubble_type\":\"speech\"}],"
                + "\"sfx\":[\"wind\"],"
                + "\"pacing\":\"normal\","
                + "\"image_prompt_hint\":\"anime style\""
                + "}]"
                + "}";
    }

    private String storyboardJsonWithMinimalCharacterFields() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":[{"
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},"
                + "\"characters\":[{\"name\":\"配角\",\"emotion\":\"focused\"}],"
                + "\"dialogue\":[],"
                + "\"sfx\":[\"wind\"],"
                + "\"pacing\":\"normal\","
                + "\"image_prompt_hint\":\"anime style\""
                + "}]"
                + "}";
    }

    private String storyboardJsonWithEmptyCharactersPanel() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":["
                + "{"
                + "\"panel_id\":\"ep1_p1\","
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},"
                + "\"characters\":[{\"char_id\":\"CHAR-1\",\"position\":\"left\",\"pose\":\"stand\",\"expression\":\"neutral\",\"costume_state\":\"normal\"}],"
                + "\"dialogue\":[],"
                + "\"sfx\":[\"wind\"],"
                + "\"pacing\":\"normal\","
                + "\"image_prompt_hint\":\"anime style\""
                + "},"
                + "{"
                + "\"panel_id\":\"ep1_p2\","
                + "\"shot_type\":\"CLOSE_UP\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"fragment closeup\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"tense\"},"
                + "\"characters\":[],"
                + "\"dialogue\":[],"
                + "\"sfx\":[\"beep\"],"
                + "\"pacing\":\"fast\","
                + "\"image_prompt_hint\":\"fragment\""
                + "}"
                + "]"
                + "}";
    }

    private String storyboardJsonWithCharIdButMissingCharacterFields() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":[{"
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition\","
                + "\"background\":{\"scene_desc\":\"classroom\",\"time_of_day\":\"day\",\"atmosphere\":\"calm\"},"
                + "\"characters\":[{\"char_id\":\"CHAR-1\",\"emotion\":\"focused\"}],"
                + "\"dialogue\":[],"
                + "\"sfx\":[\"wind\"],"
                + "\"pacing\":\"normal\","
                + "\"image_prompt_hint\":\"anime style\""
                + "}]"
                + "}";
    }

    private String truncatedStoryboardJson() {
        return "{"
                + "\"episode\":1,"
                + "\"title\":\"EP1\","
                + "\"panels\":[{"
                + "\"panel_id\":\"ep1_p1\","
                + "\"shot_type\":\"WIDE_SHOT\","
                + "\"camera_angle\":\"eye_level\","
                + "\"composition\":\"wide composition";
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
