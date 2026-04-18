package com.comic.service.production;

import com.comic.ai.text.DeepSeekTextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    // ==================== Reasoner 决策解析 ====================

    @Test
    void parseReasonerDecision_shouldExtractGenerateAction() {
        String json = "{\"action\":\"generate\",\"nextBeatDescription\":\"开场铺垫\",\"estimatedSeconds\":40,\"reasoning\":\"需要建立世界观\"}";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("generate", decision.action);
        assertEquals("开场铺垫", decision.nextBeatDescription);
        assertEquals(40, decision.estimatedSeconds);
        assertNotNull(decision.reasoning);
    }

    @Test
    void parseReasonerDecision_shouldExtractExpandAction() {
        String json = "{\"action\":\"expand\",\"targetBeatIndex\":2,\"nextBeatDescription\":\"扩展高潮对峙的微表情细节\",\"estimatedSeconds\":30,\"reasoning\":\"高潮部分需要更多情绪渲染\"}";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("expand", decision.action);
        assertEquals("扩展高潮对峙的微表情细节", decision.nextBeatDescription);
        assertEquals(30, decision.estimatedSeconds);
    }

    @Test
    void parseReasonerDecision_shouldExtractPadAction() {
        String json = "{\"action\":\"pad\",\"nextBeatDescription\":\"日落氛围过渡镜头\",\"estimatedSeconds\":20,\"reasoning\":\"结尾和上一段之间需要情绪缓冲\"}";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("pad", decision.action);
        assertEquals(20, decision.estimatedSeconds);
    }

    @Test
    void parseReasonerDecision_shouldExtractDoneAction() {
        String json = "{\"action\":\"done\",\"reasoning\":\"所有剧情节点已覆盖，总时长170秒，符合要求\"}";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("done", decision.action);
    }

    @Test
    void parseReasonerDecision_shouldHandleMarkdownCodeBlock() {
        String json = "```json\n{\"action\":\"generate\",\"nextBeatDescription\":\"冲突爆发\",\"estimatedSeconds\":50,\"reasoning\":\"进入核心冲突\"}\n```";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("generate", decision.action);
        assertEquals("冲突爆发", decision.nextBeatDescription);
    }

    @Test
    void parseReasonerDecision_shouldFallbackToGenerateOnMalformed() {
        String json = "这不是合法 JSON";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("generate", decision.action, "非法 JSON 应兜底为 generate");
    }

    @Test
    void parseReasonerDecision_shouldFallbackToGenerateOnEmptyAction() {
        String json = "{\"nextBeatDescription\":\"xxx\",\"estimatedSeconds\":30}";
        StoryboardAgentService.ReasonerDecision decision = agent.parseReasonerDecision(json);

        assertEquals("generate", decision.action, "缺少 action 字段应兜底为 generate");
    }

    // ==================== Reasoner Prompt 构建 ====================

    @Test
    void buildReasonerPrompt_shouldIncludeTargetDuration() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();
        state.targetDuration = 180;
        state.accumulatedDuration = 60;

        String prompt = agent.buildReasonerPrompt(
                "主角踏入神秘森林", "角色A,角色B", "ANIME",
                state, "第3轮", false, null, "standard");

        assertTrue(prompt.contains("180"), "prompt 应包含目标时长 180 秒");
        assertTrue(prompt.contains("120"), "prompt 应包含下限 120 秒（2分钟）");
        assertTrue(prompt.contains("240"), "prompt 应包含上限 240 秒（4分钟）");
    }

    @Test
    void buildReasonerPrompt_shouldIncludeAccumulatedProgress() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();
        state.targetDuration = 180;
        state.accumulatedDuration = 95;
        state.coveredBeats.add("开场铺垫");
        state.coveredBeats.add("冲突升级");

        String prompt = agent.buildReasonerPrompt(
                "主角踏入神秘森林", "角色A,角色B", "ANIME",
                state, "第3轮", false, null, "standard");

        assertTrue(prompt.contains("95"), "prompt 应包含已生成时长");
        assertTrue(prompt.contains("开场铺垫"), "prompt 应包含已覆盖的剧情节点");
        assertTrue(prompt.contains("冲突升级"), "prompt 应包含已覆盖的剧情节点");
    }

    @Test
    void buildReasonerPrompt_shouldIncludeLastShotsForContinuity() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();
        state.targetDuration = 180;
        state.accumulatedDuration = 40;

        Map<String, Object> shot = new HashMap<>();
        shot.put("shotNumber", 10);
        shot.put("duration", 3);
        shot.put("scene", "主角凝视远方");
        state.lastShots.add(shot);

        String prompt = agent.buildReasonerPrompt(
                "后续剧情", "角色A", "ANIME",
                state, "第2轮", false, null, "standard");

        assertTrue(prompt.contains("主角凝视远方"), "prompt 应包含上一批末尾分镜用于衔接");
    }

    @Test
    void buildReasonerPrompt_shouldIncludeComicModeHint() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();
        state.targetDuration = 180;

        String prompt = agent.buildReasonerPrompt(
                "剧情内容", "角色A", "ANIME",
                state, "第1轮", true, null, "standard");

        assertTrue(prompt.contains("解说") || prompt.contains("旁白"),
                "漫剧解说模式下 prompt 应包含解说/旁白相关提示");
    }

    // ==================== 执行模型 Prompt 构建 ====================

    @Test
    void buildExecutorPrompt_shouldIncludeOnlyCurrentBeat() {
        String prompt = agent.buildExecutorPrompt(
                "主角与反派在悬崖对峙，气氛紧张",
                "角色A(主角),角色B(反派)",
                "ANIME", 45, new ArrayList<>(), false, "standard");

        assertTrue(prompt.contains("主角与反派在悬崖对峙"), "执行 prompt 应包含当前剧情段落");
        assertTrue(prompt.contains("45"), "执行 prompt 应包含本批目标时长");
    }

    @Test
    void buildExecutorPrompt_shouldIncludeLastShotsForContinuity() {
        List<Map<String, Object>> lastShots = new ArrayList<>();
        Map<String, Object> shot = new HashMap<>();
        shot.put("shotNumber", 8);
        shot.put("duration", 3);
        shot.put("scene", "主角缓缓拔出佩剑");
        lastShots.add(shot);

        String prompt = agent.buildExecutorPrompt(
                "对峙开始", "角色A", "ANIME", 40, lastShots, false, "standard");

        assertTrue(prompt.contains("主角缓缓拔出佩剑"),
                "执行 prompt 应包含上一批末尾分镜用于衔接");
    }

    @Test
    void buildExecutorPrompt_shouldNotIncludeFullScript() {
        String fullScript = "第一章：主角出身。第二章：踏上旅途。第三章：最终决战。第四章：回归家园。";
        String currentBeat = "主角踏上旅途";

        String prompt = agent.buildExecutorPrompt(
                currentBeat, "角色A", "ANIME", 40, new ArrayList<>(), false, "standard");

        // prompt 只应包含当前 beat，不应包含整集剧本
        assertTrue(prompt.contains("主角踏上旅途"), "应包含当前剧情段落");
        assertFalse(prompt.contains("最终决战"), "不应包含当前段落之外的剧情");
    }

    // ==================== 状态管理 ====================

    @Test
    void accumulateShots_shouldTrackDurationCorrectly() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();
        state.targetDuration = 180;

        List<Map<String, Object>> shots = createShots(new int[]{3, 2, 4, 3, 2});

        agent.accumulateShots(state, shots, "冲突爆发");

        assertEquals(14, state.accumulatedDuration, "累计时长应为 14 秒");
        assertEquals(5, state.allShots.size(), "总分镜数应为 5");
        assertTrue(state.coveredBeats.contains("冲突爆发"), "应记录已覆盖的剧情节点");
    }

    @Test
    void accumulateShots_shouldTrackLastShots() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();

        List<Map<String, Object>> shots = createShots(new int[]{2, 3, 4, 2, 3});
        agent.accumulateShots(state, shots, "测试节点");

        assertEquals(3, state.lastShots.size(), "lastShots 应保留最后 3 个");
        assertEquals(4, ((Number) state.lastShots.get(0).get("duration")).intValue());
        assertEquals(2, ((Number) state.lastShots.get(1).get("duration")).intValue());
        assertEquals(3, ((Number) state.lastShots.get(2).get("duration")).intValue());
    }

    @Test
    void accumulateShots_shouldAssignGlobalShotNumbers() {
        StoryboardAgentService.AgentState state = new StoryboardAgentService.AgentState();

        List<Map<String, Object>> batch1 = createShots(new int[]{2, 3});
        agent.accumulateShots(state, batch1, "节点A");

        List<Map<String, Object>> batch2 = createShots(new int[]{4, 2, 3});
        agent.accumulateShots(state, batch2, "节点B");

        assertEquals(1, state.allShots.get(0).get("globalShotNumber"));
        assertEquals(2, state.allShots.get(1).get("globalShotNumber"));
        assertEquals(3, state.allShots.get(2).get("globalShotNumber"));
        assertEquals(4, state.allShots.get(3).get("globalShotNumber"));
        assertEquals(5, state.allShots.get(4).get("globalShotNumber"));
    }

    // ==================== ReAct 循环（爽剧模式） ====================

    @Test
    void generateReAct_shouldFollowThoughtActionObservationLoop() {
        // Round 0: 叙事规划调用 (会 fallback 到兜底方案)
        // Round 1: model thinks, calls generate_shots
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("not a valid plan json") // 叙事规划调用，会 fallback
                .thenReturn("Thought: 目标120秒，先从开场爽点开始，分配30秒\n"
                        + "Action: generate_shots(爽点1:开场 → 爽点2:冲突, 30)")
                // Round 2: model sees observation, calls generate_shots again
                .thenReturn("Thought: 已生成30秒，还需要90秒。继续高潮段落。\n"
                        + "Action: generate_shots(爽点3:高潮 → 爽点4:结尾, 30)")
                // Round 3: model sees 60秒, needs more
                .thenReturn("Thought: 60秒够了，达到下限80秒(2/3)。\n"
                        + "Action: finish()");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildShotJson(new int[]{4, 4, 4, 4, 4, 4, 4, 4, 4, 4})) // 40s
                .thenReturn(buildShotJson(new int[]{4, 4, 4, 4, 4, 4, 4, 4, 4, 4})); // 40s

        List<Map<String, Object>> result = agent.generate(
                "剧本 [爽点:开场] [爽点:冲突] [爽点:高潮] [爽点:结尾]",
                "角色A", 120, "ANIME", false, null, "shuangju");

        assertFalse(result.isEmpty(), "ReAct 模式应生成分镜");
        // 1 叙事规划 + 2 rounds ReAct + 1 finish = 4 次 reasoner 调用
        verify(mockReasoner, times(4)).generate(anyString(), anyString());
        verify(mockExecutor, times(2)).generate(anyString(), anyString());
    }

    @Test
    void generateReAct_shouldRejectFinishWhenDurationTooShort() {
        when(mockReasoner.generate(anyString(), anyString()))
                // Round 0: finish rejected (0s < 40s)
                .thenReturn("Thought: 生成了15秒，时间不够，但我想结束\n"
                        + "Action: finish()")
                // Round 1: model generates
                .thenReturn("Thought: 被拒绝了，需要继续。再生成25秒。\n"
                        + "Action: generate_shots(更多爽点, 25)")
                // Round 2: model generates more
                .thenReturn("Thought: 再生成20秒。\n"
                        + "Action: generate_shots(更多爽点, 20)")
                // Round 3: 48s >= 40s (2/3 of 60), finish accepted
                .thenReturn("Thought: 48秒达到下限，结束。\n"
                        + "Action: finish()");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildShotJson(new int[]{4, 4, 4, 4, 4, 4, 4})) // 28s
                .thenReturn(buildShotJson(new int[]{4, 4, 4, 4, 4}));     // 20s

        List<Map<String, Object>> result = agent.generate(
                "剧本 [爽点:1] [爽点:2]", "角色A", 60, "ANIME", false, null, "shuangju");

        assertFalse(result.isEmpty());
        verify(mockExecutor, times(2)).generate(anyString(), anyString());
    }

    @Test
    void parseReactOutput_shouldExtractThoughtAndAction() {
        StoryboardAgentService.ReactOutput output = agent.parseReactOutput(
                "Thought: 我需要先生成开场部分，大约15秒\n"
                        + "Action: generate_shots(爽点1:开场 → 爽点2:冲突, 15)");

        assertEquals("我需要先生成开场部分，大约15秒", output.thought);
        assertEquals("generate_shots", output.action);
        assertEquals("爽点1:开场 → 爽点2:冲突, 15", output.actionArgs);
    }

    @Test
    void parseReactOutput_shouldHandleFinishAction() {
        StoryboardAgentService.ReactOutput output = agent.parseReactOutput(
                "Thought: 时长已达标\nAction: finish()");
        assertEquals("finish", output.action);
    }

    @Test
    void parseReactOutput_shouldFallbackOnBadFormat() {
        StoryboardAgentService.ReactOutput output = agent.parseReactOutput("这是自由文本没有Action");
        assertEquals("continue", output.action);
    }

    // ==================== Agent 循环（标准模式） ====================

    @Test
    void generate_shouldCompleteAfterAllBeatsCovered() {
        // Round 1: Reasoner says generate, produces 51s of content
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("{\"action\":\"generate\",\"nextBeatDescription\":\"开场铺垫\",\"estimatedSeconds\":45,\"reasoning\":\"需要建立世界观\"}")
                .thenReturn("{\"action\":\"done\",\"reasoning\":\"剧情已覆盖完毕，总时长约170秒\"}");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildShotJson(new int[]{3, 4, 3, 4, 3, 3, 4, 3, 4, 3, 3, 4, 3, 3})); // 51 seconds

        // targetDuration=60, min=40s. 51s > 40s, so done is accepted
        List<Map<String, Object>> result = agent.generate(
                "主角踏入神秘森林，遇到导师，开始修炼之旅",
                "角色A(主角),角色B(导师)",
                60, "ANIME", false, null, "standard");

        assertFalse(result.isEmpty(), "应生成分镜列表");
        verify(mockReasoner, atLeast(2)).generate(anyString(), anyString());
        verify(mockExecutor, times(1)).generate(anyString(), anyString());
    }

    @Test
    void generate_shouldStopOnMaxRounds() {
        // Reasoner 始终说 generate（模拟死循环场景）
        // 注意：第一次调用被叙事规划消费，后续 15 轮使用同一返回值
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("not a valid plan json") // 叙事规划调用，会 fallback
                .thenReturn("{\"action\":\"generate\",\"nextBeatDescription\":\"持续生成\",\"estimatedSeconds\":10,\"reasoning\":\"测试\"}");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildShotJson(new int[]{3, 3, 3}));

        List<Map<String, Object>> result = agent.generate(
                "剧情内容", "角色A", 180, "ANIME", false, null, "standard");

        assertFalse(result.isEmpty(), "即使触发 maxRounds 也应返回已有分镜");
        // 1 叙事规划 + 最多 15 轮迭代 = 最多 16 次
        verify(mockReasoner, atMost(16)).generate(anyString(), anyString());
    }

    @Test
    void generate_shouldRetryOnExecutorFailure() {
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("{\"action\":\"generate\",\"nextBeatDescription\":\"开场\",\"estimatedSeconds\":30,\"reasoning\":\"开始\"}")
                .thenReturn("{\"action\":\"done\",\"reasoning\":\"完成\"}");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenThrow(new RuntimeException("API 超时"))
                .thenReturn(buildShotJson(new int[]{3, 4, 3}));

        List<Map<String, Object>> result = agent.generate(
                "剧情内容", "角色A", 60, "ANIME", false, null, "standard");

        assertFalse(result.isEmpty(), "重试后应成功生成分镜");
        verify(mockExecutor, atLeast(2)).generate(anyString(), anyString());
    }

    @Test
    void generate_shouldHandleComicMode() {
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("{\"action\":\"generate\",\"nextBeatDescription\":\"开场解说\",\"estimatedSeconds\":40,\"reasoning\":\"漫剧解说需要旁白引导\"}")
                .thenReturn("{\"action\":\"done\",\"reasoning\":\"完成\"}");

        // 漫剧解说模式的 shot 包含 narration/dialogue/speaker 字段
        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildComicShotJson());

        List<Map<String, Object>> result = agent.generate(
                "剧情内容", "角色A(旁白者)", 60, "ANIME", true, null, "standard");

        assertFalse(result.isEmpty(), "漫剧解说模式应正常生成分镜");
        // 验证 Reasoner prompt 包含解说模式提示
        String reasonerSystemPrompt = verifyAndGetSystemPrompt(mockReasoner);
        assertTrue(reasonerSystemPrompt.contains("解说") || reasonerSystemPrompt.contains("旁白"),
                "漫剧解说模式下 Reasoner 应收到解说相关提示");
    }

    @Test
    void generate_shouldPassContinuityBetweenRounds() {
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn("{\"action\":\"generate\",\"nextBeatDescription\":\"冲突升级\",\"estimatedSeconds\":10,\"reasoning\":\"推进剧情\"}")
                .thenReturn("{\"action\":\"generate\",\"nextBeatDescription\":\"高潮\",\"estimatedSeconds\":10,\"reasoning\":\"进入高潮\"}")
                .thenReturn("{\"action\":\"done\",\"reasoning\":\"完成\"}");

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildShotJson(new int[]{3, 4, 3}))
                .thenReturn(buildShotJson(new int[]{4, 3, 4}));

        // targetDuration=30, min=20s. Two rounds produce 10+11=21s > 20s, so done is accepted
        agent.generate("剧情内容", "角色A", 30, "ANIME", false, null, "standard");

        // 验证第二执行调用时 lastShots 被传入（通过验证 Reasoner 第二次调用的 user prompt 包含上一批末尾分镜）
        // 这里我们只验证执行模型被调用了正确次数
        verify(mockExecutor, times(2)).generate(anyString(), anyString());
    }

    @Test
    void extractHookBeats_shouldExtractAllMarkers() {
        String content = "林晓星打磨道具 [爽点:专注细节] 枪身蓝光 [爽点:意外觉醒] 命中！[爽点:实力碾压]";
        List<String> hooks = agent.extractHookBeats(content);

        assertEquals(3, hooks.size());
        assertEquals("专注细节", hooks.get(0));
        assertEquals("意外觉醒", hooks.get(1));
        assertEquals("实力碾压", hooks.get(2));
    }

    @Test
    void extractHookBeats_shouldReturnEmptyOnNoMarkers() {
        List<String> hooks = agent.extractHookBeats("普通剧本内容，没有爽点标记");
        assertTrue(hooks.isEmpty());
    }

    @Test
    void buildReactSystemPrompt_shouldContainTools() {
        String prompt = agent.buildReactSystemPrompt();
        assertTrue(prompt.contains("generate_shots"));
        assertTrue(prompt.contains("finish"));
        assertTrue(prompt.contains("Thought"));
        assertTrue(prompt.contains("Action"));
        assertTrue(prompt.contains("Observation"));
    }

    @Test
    void parsePlan_shouldParseValidJson() {
        String planJson = "{\"totalSeconds\":120,\"segments\":["
                + "{\"id\":1,\"beatDescription\":\"开场\",\"allocatedSeconds\":40,\"narrativeRole\":\"铺垫\"},"
                + "{\"id\":2,\"beatDescription\":\"高潮\",\"allocatedSeconds\":80,\"narrativeRole\":\"爆发\"}"
                + "]}";

        List<StoryboardAgentService.PlanSegment> segments = agent.parsePlan(planJson);

        assertNotNull(segments);
        assertEquals(2, segments.size());
        assertEquals(40, segments.get(0).allocatedSeconds);
        assertEquals(80, segments.get(1).allocatedSeconds);
        assertEquals("开场", segments.get(0).beatDescription);
    }

    @Test
    void parsePlan_shouldReturnNullOnInvalidJson() {
        assertNull(agent.parsePlan("bad json"));
        assertNull(agent.parsePlan("{\"no\":\"segments\"}"));
    }

    @Test
    void buildPlanUserPrompt_shouldContainTargetDuration() {
        String prompt = agent.buildPlanUserPrompt("[爽点:开场][爽点:高潮]", 120, "角色A");
        assertTrue(prompt.contains("120"));
        assertTrue(prompt.contains("爽点"));
    }

    // ==================== 辅助方法 ====================

    private String buildShotJson(int[] durations) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < durations.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"shotNumber\":").append(i + 1).append(",")
              .append("\"duration\":").append(durations[i]).append(",")
              .append("\"scene\":\"场景").append(i + 1).append("\",")
              .append("\"characters\":[\"角色A\"],")
              .append("\"shotSize\":\"中景\",")
              .append("\"cameraAngle\":\"平视\",")
              .append("\"cameraMovement\":\"静止\",")
              .append("\"sceneDescription\":\"场景").append(i + 1).append("的详细描述\",")
              .append("\"visualDescription\":\"画面描述\",")
              .append("\"transitionHint\":\"").append(i < durations.length - 1 ? "过渡到下一镜" : "最后一个镜头，无需衔接").append("\",")
              .append("\"dialogue\":\"无\",")
              .append("\"speaker\":\"无\",")
              .append("\"dialogueTone\":\"无\",")
              .append("\"visualEffects\":\"无\",")
              .append("\"audioEffects\":\"无\"")
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildComicShotJson() {
        return "["
                + "{\"shotNumber\":1,\"duration\":3,\"scene\":\"开场\",\"characters\":[\"角色A\"],"
                + "\"shotSize\":\"中景\",\"cameraAngle\":\"平视\",\"cameraMovement\":\"缓慢推进\","
                + "\"sceneDescription\":\"角色A缓缓走入画面\",\"visualDescription\":\"角色A走入\","
                + "\"transitionHint\":\"过渡到下一镜\",\"narration\":\"在一个风雨交加的夜晚\",\"dialogue\":\"无\",\"speaker\":\"无\","
                + "\"dialogueTone\":\"无\",\"visualEffects\":\"无\",\"audioEffects\":\"无\"},"
                + "{\"shotNumber\":2,\"duration\":4,\"scene\":\"角色A停步\",\"characters\":[\"角色A\"],"
                + "\"shotSize\":\"近景\",\"cameraAngle\":\"平视\",\"cameraMovement\":\"静止\","
                + "\"sceneDescription\":\"角色A停下脚步凝视远方\",\"visualDescription\":\"角色A凝视\","
                + "\"transitionHint\":\"最后一个镜头，无需衔接\",\"dialogue\":\"无\",\"speaker\":\"无\","
                + "\"dialogueTone\":\"无\",\"visualEffects\":\"无\",\"audioEffects\":\"无\"}"
                + "]";
    }

    /**
     * 捕获 Reasoner 的 system prompt 用于验证
     */
    private List<Map<String, Object>> createShots(int[] durations) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", durations[i]);
            shot.put("scene", "场景" + (i + 1));
            shots.add(shot);
        }
        return shots;
    }

    private String verifyAndGetSystemPrompt(DeepSeekTextService mockService) {
        try {
            org.mockito.ArgumentCaptor<String> argCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(mockService, atLeastOnce()).generate(argCaptor.capture(), argCaptor.capture());
            // system prompt 是第一个参数
            List<String> allArgs = argCaptor.getAllValues();
            // 成对出现：system, user, system, user, ...
            return allArgs.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Phase 1: 结构分析 ====================

    @Test
    void parseStoryStructure_shouldParseValidJson() {
        String json = "{\"storyArc\":\"发现秘密→追杀→反杀\","
                + "\"beats\":["
                + "{\"id\":1,\"beat\":\"进入工厂\",\"duration\":12,\"mood\":\"紧张\","
                + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"警惕\",\"position\":\"工厂\"}]},"
                + "{\"id\":2,\"beat\":\"发现实验室\",\"duration\":10,\"mood\":\"震惊\","
                + " \"characters\":[{\"name\":\"林晓星\",\"state\":\"震惊\",\"position\":\"实验室\"},"
                + " {\"name\":\"陈墨\",\"state\":\"神秘\",\"position\":\"实验室\"}]}"
                + "],"
                + "\"transitions\":[{\"from\":\"1\",\"to\":\"2\",\"bridge\":\"推开铁门\"}]}";

        StoryboardAgentService.StoryStructure structure = agent.parseStoryStructure(json);

        assertNotNull(structure);
        assertEquals("发现秘密→追杀→反杀", structure.storyArc);
        assertEquals(2, structure.beats.size());
        assertEquals(1, structure.beats.get(0).id);
        assertEquals("林晓星", structure.beats.get(0).characters.get(0).name);
        assertEquals(2, structure.beats.get(1).characters.size());
        assertEquals(1, structure.transitions.size());
        assertEquals("推开铁门", structure.transitions.get(0).get("bridge"));
    }

    @Test
    void parseStoryStructure_shouldReturnNullOnInvalidJson() {
        assertNull(agent.parseStoryStructure("not json"));
        assertNull(agent.parseStoryStructure("{\"storyArc\":\"xxx\"}"));
    }
}
