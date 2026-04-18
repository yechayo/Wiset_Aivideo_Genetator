package com.comic.service.production;

import com.comic.ai.text.DeepSeekTextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StoryboardAgentServiceTest {

    private StoryboardAgentService agent;
    private DeepSeekTextService mockReasoner;
    private DeepSeekTextService mockExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockReasoner = mock(DeepSeekTextService.class);
        mockExecutor = mock(DeepSeekTextService.class);
        objectMapper = new ObjectMapper();
        agent = new StoryboardAgentService(mockReasoner, mockExecutor, objectMapper);
    }

    // ==================== Phase 1: 结构分析 ====================

    @Test
    void parseStoryStructure_shouldParseValidJson() {
        String json = "{\"storyArc\":\"发现秘密\","
                + "\"beats\":["
                + "{\"id\":1,\"beat\":\"进入工厂\",\"duration\":12,\"mood\":\"紧张\","
                + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"警惕\",\"position\":\"工厂\"}]},"
                + "{\"id\":2,\"beat\":\"发现实验室\",\"duration\":10,\"mood\":\"震惊\","
                + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"震惊\",\"position\":\"实验室\"}]}"
                + "],"
                + "\"transitions\":[{\"from\":\"1\",\"to\":\"2\",\"bridge\":\"推开铁门\"}]}";

        StoryboardAgentService.StoryStructure s = agent.parseStoryStructure(json);
        assertNotNull(s);
        assertEquals(2, s.beats.size());
        assertEquals("林晓星", s.beats.get(0).characters.get(0).name);
        assertEquals(1, s.transitions.size());
    }

    @Test
    void parseStoryStructure_shouldReturnNullOnInvalid() {
        assertNull(agent.parseStoryStructure("bad"));
        assertNull(agent.parseStoryStructure("{\"storyArc\":\"x\"}"));
    }

    // ==================== Phase 2: 骨架生成 ====================

    @Test
    void buildShotSkeletons_shouldSplitBeatsIntoShots() {
        StoryboardAgentService.StoryStructure structure = new StoryboardAgentService.StoryStructure();
        structure.beats.add(new StoryboardAgentService.StoryBeat(1, "进入工厂", 10, "紧张",
                Collections.singletonList(new StoryboardAgentService.CharacterInScene("林晓星", "警惕", "工厂"))));
        StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(Collections.emptyList(), 10);

        List<Map<String, Object>> skeletons = agent.buildShotSkeletons(structure, plan);
        assertFalse(skeletons.isEmpty());
        assertTrue(skeletons.size() >= 2);
    }

    @Test
    void buildFallbackSkeletons_shouldMatchTargetDuration() {
        StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(Collections.emptyList(), 30);
        List<Map<String, Object>> skeletons = agent.buildFallbackSkeletons(30, "内容", "A,B", plan);
        int total = skeletons.stream().mapToInt(s -> (int) s.get("duration")).sum();
        assertTrue(total >= 28 && total <= 32,
                "兜底骨架时长应接近目标时长 30s，实际: " + total);
    }

    // ==================== Hook 提取 ====================

    @Test
    void extractHookBeats_shouldExtractAllMarkers() {
        List<String> hooks = agent.extractHookBeats("内容 [爽点:专注细节] 枪身 [爽点:觉醒] 命中 [爽点:碾压]");
        assertEquals(3, hooks.size());
    }

    @Test
    void extractHookBeats_shouldReturnEmptyOnNoMarkers() {
        assertTrue(agent.extractHookBeats("普通内容").isEmpty());
    }

    // ==================== 叙事规划 ====================

    @Test
    void buildFallbackNarrativePlan_shouldSumToTarget() {
        StoryboardAgentService.NarrativePlan plan = agent.buildFallbackNarrativePlan(Arrays.asList("a","b","c"), 120);
        int total = plan.phases.stream().mapToInt(p -> p.allocatedSeconds).sum();
        assertEquals(120, total);
    }

    // ==================== V2 集成 (mock) ====================

    @Test
    void generate_shouldProduceShotsViaV2() {
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("{\"storyArc\":\"测试\",\"beats\":[{\"id\":1,\"beat\":\"开场\",\"duration\":15,\"mood\":\"紧张\","
                        + "\"characters\":[{\"name\":\"A\",\"state\":\"警惕\",\"position\":\"工厂\"}]}],"
                        + "\"transitions\":[]}")
                .thenReturn("{\"storyArcSummary\":\"测试\",\"phases\":[{\"name\":\"开场\",\"startBeat\":1,\"endBeat\":1,"
                        + "\"allocatedSeconds\":15,\"dialogueDensity\":\"sparse\",\"maxDialogueChars\":\"3秒≤15字\","
                        + "\"emotionalArc\":\"冲击\",\"pacingNote\":\"快切\"}]}");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn("[{\"shotNumber\":1,\"duration\":3,\"scene\":\"工厂\",\"characters\":[\"A\"],"
                        + "\"shotSize\":\"MEDIUM\",\"cameraAngle\":\"eye_level\",\"cameraMovement\":\"static\","
                        + "\"sceneDescription\":\"A走进工厂\",\"dialogue\":\"\",\"speaker\":\"无\","
                        + "\"dialogueTone\":\"无\",\"visualEffects\":\"无\",\"audioEffects\":\"无\","
                        + "\"transitionHint\":\"下一镜\"}]");

        List<Map<String, Object>> result = agent.generate("剧本内容", "A", 15, "cinematic", false, null, "standard");

        assertFalse(result.isEmpty());
        verify(mockReasoner, times(2)).generate(anyString(), anyString());
        verify(mockExecutor, atLeastOnce()).generate(anyString(), anyString());
    }

    @Test
    void generate_shouldFallbackWhenPhase1Fails() {
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("not json")
                .thenReturn("not json either");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn("[{\"shotNumber\":1,\"duration\":3,\"scene\":\"场景1\",\"characters\":[\"A\"],"
                        + "\"shotSize\":\"MEDIUM\",\"cameraAngle\":\"eye_level\",\"cameraMovement\":\"static\","
                        + "\"sceneDescription\":\"描述\",\"dialogue\":\"\",\"speaker\":\"无\","
                        + "\"dialogueTone\":\"无\",\"visualEffects\":\"无\",\"audioEffects\":\"无\","
                        + "\"transitionHint\":\"下一镜\"}]");

        List<Map<String, Object>> result = agent.generate("剧本", "A", 10, "cinematic", false, null, "standard");
        assertFalse(result.isEmpty(), "Phase1 失败应走兜底路径");
    }

    // ==================== 质量检查 ====================

    @Test
    void checkBatchQuality_shouldReportOverLength() {
        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> shot = new HashMap<>();
        shot.put("shotNumber", 1);
        shot.put("duration", 3);
        shot.put("dialogue", "这段台词非常非常非常长超过了十五个字的上限");
        shots.add(shot);

        String report = agent.checkBatchQuality(shots, null, -1, false);
        assertTrue(report.contains("超长") || report.contains("超过"));
    }
}
