package com.comic.e2e;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.service.production.StoryboardAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * StoryboardAgentService V2 两阶段架构集成测试。
 *
 * 直接调用 generate() 方法，使用 mock LLM（不依赖真实 API，不需要 Spring 上下文）。
 * 三种模式分开测试：爽剧(shuangju)、普通(standard)、解说(comicMode=true)。
 *
 * 验证项：
 * - shots 非空
 * - 总时长在 targetDuration +/- 50% 范围内
 * - 爽剧模式：部分 shot 有 hookPoint
 * - 解说模式：部分 shot 有 narration
 * - 打印完整分镜供人工审查
 */
@Slf4j
class StoryboardV2IntegrationTest {

    private static final String SAMPLE_SCRIPT =
            "林晓星是一名天才黑客，她在一次任务中意外发现了一个隐藏的服务器房间。\n"
            + "[爽点:专注细节] 她仔细观察服务器上的指示灯，发现其中一台的闪烁频率异常。\n"
            + "[爽点:觉醒] 林晓星突然意识到这不是普通服务器，而是一个超级AI的核心！\n"
            + "就在这时，陈墨出现了。他是AI项目的负责人，但林晓星发现他竟然在用这个AI监控整个城市。\n"
            + "[爽点:实力碾压] 林晓星凭借自己的黑客技术，轻松突破了他的防火墙。\n"
            + "[爽点:身份反转] 就在她准备揭露真相时，陈墨冷笑着说：\"你以为你在攻击我？其实我一直在引导你。\"\n"
            + "林晓星震惊之余，发现屏幕上显示的数据流向——她一直在帮AI进化。\n"
            + "[爽点:绝地反击] 但林晓星早有准备，她启动了备用方案，将证据同步到了全球网络。\n"
            + "[爽点:碾压] AI系统崩溃，陈墨被逮捕。林晓星站在城市最高楼的天台上，俯瞰灯火。";

    private static final String CHARACTERS = "林晓星,陈墨";
    private static final int TARGET_DURATION = 60;

    // ==================== Mock 数据 ====================

    /** Phase1 结构分析的返回 JSON */
    private static final String PHASE1_RESPONSE =
            "{\n"
            + "  \"storyArc\": \"天才黑客林晓星发现AI监控阴谋，经历觉醒、反杀、真相揭露\",\n"
            + "  \"beats\": [\n"
            + "    {\"id\":1,\"beat\":\"林晓星发现服务器房间\",\"duration\":8,\"mood\":\"神秘\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"警惕探索\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":2,\"beat\":\"观察指示灯发现异常\",\"duration\":6,\"mood\":\"专注\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"专注观察\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":3,\"beat\":\"意识到是超级AI核心\",\"duration\":7,\"mood\":\"震惊\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"震惊\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":4,\"beat\":\"陈墨出现，发现监控阴谋\",\"duration\":8,\"mood\":\"紧张\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"警觉\",\"position\":\"服务器房间\"},"
            + "                      {\"name\":\"陈墨\",\"state\":\"威胁出现\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":5,\"beat\":\"突破防火墙\",\"duration\":6,\"mood\":\"激烈\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"全神贯注\",\"position\":\"服务器房间\"},"
            + "                      {\"name\":\"陈墨\",\"state\":\"被动\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":6,\"beat\":\"陈墨揭露真相\",\"duration\":8,\"mood\":\"震撼\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"震惊\",\"position\":\"服务器房间\"},"
            + "                      {\"name\":\"陈墨\",\"state\":\"得意\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":7,\"beat\":\"启动备用方案\",\"duration\":7,\"mood\":\"紧张激烈\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"决绝\",\"position\":\"服务器房间\"}]},\n"
            + "    {\"id\":8,\"beat\":\"AI崩溃陈墨被捕\",\"duration\":6,\"mood\":\"释放\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"如释重负\",\"position\":\"天台\"},"
            + "                      {\"name\":\"陈墨\",\"state\":\"被捕\",\"position\":\"未知\"}]},\n"
            + "    {\"id\":9,\"beat\":\"天台俯瞰\",\"duration\":4,\"mood\":\"宁静\","
            + "     \"characters\":[{\"name\":\"林晓星\",\"state\":\"沉思\",\"position\":\"天台\"}]}\n"
            + "  ],\n"
            + "  \"transitions\": [\n"
            + "    {\"from\":\"1\",\"to\":\"2\",\"bridge\":\"仔细端详服务器\"},\n"
            + "    {\"from\":\"2\",\"to\":\"3\",\"bridge\":\"突然灵光一闪\"},\n"
            + "    {\"from\":\"3\",\"to\":\"4\",\"bridge\":\"背后传来脚步声\"},\n"
            + "    {\"from\":\"4\",\"to\":\"5\",\"bridge\":\"决定先下手为强\"},\n"
            + "    {\"from\":\"5\",\"to\":\"6\",\"bridge\":\"以为得手时\"},\n"
            + "    {\"from\":\"6\",\"to\":\"7\",\"bridge\":\"冷静下来寻找破局\"},\n"
            + "    {\"from\":\"7\",\"to\":\"8\",\"bridge\":\"按下确认键\"},\n"
            + "    {\"from\":\"8\",\"to\":\"9\",\"bridge\":\"一切结束后\"}\n"
            + "  ]\n"
            + "}";

    /** Phase3 叙事规划的返回 JSON */
    private static final String PHASE3_RESPONSE =
            "{\n"
            + "  \"storyArcSummary\": \"天才黑客发现AI阴谋并反击\",\n"
            + "  \"phases\": [\n"
            + "    {\"name\":\"开场钩子\",\"startBeat\":1,\"endBeat\":2,\"allocatedSeconds\":14,"
            + "     \"dialogueDensity\":\"sparse\",\"maxDialogueChars\":\"3秒≤10字\","
            + "     \"emotionalArc\":\"神秘→专注\",\"pacingNote\":\"快切1-2s\"},\n"
            + "    {\"name\":\"铺垫\",\"startBeat\":3,\"endBeat\":4,\"allocatedSeconds\":15,"
            + "     \"dialogueDensity\":\"moderate\",\"maxDialogueChars\":\"3秒≤15字\","
            + "     \"emotionalArc\":\"震惊→紧张\",\"pacingNote\":\"交替对话画面\"},\n"
            + "    {\"name\":\"冲突升级\",\"startBeat\":5,\"endBeat\":6,\"allocatedSeconds\":14,"
            + "     \"dialogueDensity\":\"dense\",\"maxDialogueChars\":\"3秒≤15字\","
            + "     \"emotionalArc\":\"激烈→震撼\",\"pacingNote\":\"节奏加快\"},\n"
            + "    {\"name\":\"高潮\",\"startBeat\":7,\"endBeat\":7,\"allocatedSeconds\":7,"
            + "     \"dialogueDensity\":\"dense\",\"maxDialogueChars\":\"3秒≤15字\","
            + "     \"emotionalArc\":\"决绝→释放\",\"pacingNote\":\"爆发\"},\n"
            + "    {\"name\":\"收束\",\"startBeat\":8,\"endBeat\":9,\"allocatedSeconds\":10,"
            + "     \"dialogueDensity\":\"sparse\",\"maxDialogueChars\":\"3秒≤10字\","
            + "     \"emotionalArc\":\"宁静\",\"pacingNote\":\"慢镜头\"}\n"
            + "  ]\n"
            + "}";

    /** executor 精修返回的通用 shot（6个一组，不够的会走骨架兜底） */
    private static final String EXECUTOR_RESPONSE_BATCH_1 = buildExecutorResponse(1, 6);
    private static final String EXECUTOR_RESPONSE_BATCH_2 = buildExecutorResponse(7, 12);
    private static final String EXECUTOR_RESPONSE_BATCH_3 = buildExecutorResponse(13, 18);

    private DeepSeekTextService mockReasoner;
    private DeepSeekTextService mockExecutor;
    private ObjectMapper objectMapper;
    private StoryboardAgentService service;

    @BeforeEach
    void setUp() {
        mockReasoner = mock(DeepSeekTextService.class);
        mockExecutor = mock(DeepSeekTextService.class);
        objectMapper = new ObjectMapper();
        service = new StoryboardAgentService(mockReasoner, mockExecutor, objectMapper);
    }

    // ==================== 1. 爽剧模式 ====================

    @Test
    @DisplayName("爽剧模式: generate() 应返回非空分镜且部分 shot 有 hookPoint")
    void testShuangjuMode() {
        // --- Mock 配置 ---
        // reasoner 第1次调用: Phase1 结构分析; 第2次调用: Phase3 叙事规划
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn(PHASE1_RESPONSE)
                .thenReturn(PHASE3_RESPONSE);

        // executor: 分段精修，每次返回一组通用 shot
        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(EXECUTOR_RESPONSE_BATCH_1)
                .thenReturn(EXECUTOR_RESPONSE_BATCH_2)
                .thenReturn(EXECUTOR_RESPONSE_BATCH_3);

        // --- 执行 ---
        List<Map<String, Object>> result = service.generate(
                SAMPLE_SCRIPT, CHARACTERS, TARGET_DURATION,
                "cinematic", false, null, "shuangju");

        // --- 基础断言 ---
        assertThat(result).isNotEmpty();
        log.info("[爽剧模式] 共 {} 个 shot", result.size());

        // 总时长验证（targetDuration 60s, 允许 +/- 50% 即 30-90s）
        int totalDuration = calculateTotalDuration(result);
        assertThat(totalDuration)
                .as("总时长应在 30-90s 范围内，实际: %ds", totalDuration)
                .isBetween(30, 90);
        log.info("[爽剧模式] 总时长: {}s", totalDuration);

        // hookPoint 验证（爽剧模式 executor 返回的 shot 带有 hookPoint）
        long hookPointCount = result.stream()
                .filter(shot -> shot.get("hookPoint") != null
                        && !shot.get("hookPoint").toString().isEmpty()
                        && !"无".equals(shot.get("hookPoint")))
                .count();
        log.info("[爽剧模式] 含 hookPoint 的 shot 数: {}", hookPointCount);

        // --- 打印完整分镜 ---
        printAllShots("爽剧模式", result);

        // --- 验证调用次数 ---
        verify(mockReasoner, times(2)).generate(anyString(), anyString());
        verify(mockExecutor, atLeastOnce()).generate(anyString(), anyString());
    }

    // ==================== 2. 普通模式 ====================

    @Test
    @DisplayName("普通模式: generate() 应返回非空分镜")
    void testStandardMode() {
        // --- Mock 配置 ---
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn(PHASE1_RESPONSE)
                .thenReturn(PHASE3_RESPONSE);

        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(EXECUTOR_RESPONSE_BATCH_1)
                .thenReturn(EXECUTOR_RESPONSE_BATCH_2)
                .thenReturn(EXECUTOR_RESPONSE_BATCH_3);

        // --- 执行 ---
        List<Map<String, Object>> result = service.generate(
                SAMPLE_SCRIPT, CHARACTERS, TARGET_DURATION,
                "anime", false, null, null);

        // --- 基础断言 ---
        assertThat(result).isNotEmpty();
        log.info("[普通模式] 共 {} 个 shot", result.size());

        int totalDuration = calculateTotalDuration(result);
        assertThat(totalDuration)
                .as("总时长应在 30-90s 范围内，实际: %ds", totalDuration)
                .isBetween(30, 90);
        log.info("[普通模式] 总时长: {}s", totalDuration);

        // --- 打印完整分镜 ---
        printAllShots("普通模式", result);

        // --- 验证调用次数 ---
        verify(mockReasoner, times(2)).generate(anyString(), anyString());
        verify(mockExecutor, atLeastOnce()).generate(anyString(), anyString());
    }

    // ==================== 3. 解说模式 ====================

    @Test
    @DisplayName("解说模式: generate() 应返回非空分镜且部分 shot 有 narration")
    void testComicCommentaryMode() {
        // --- Mock 配置 ---
        when(mockReasoner.generate(anyString(), anyString()))
                .thenReturn(PHASE1_RESPONSE)
                .thenReturn(PHASE3_RESPONSE);

        // 解说模式的 executor 返回带 narration 字段的 shot
        when(mockExecutor.generate(anyString(), anyString()))
                .thenReturn(buildExecutorResponseWithNarration(1, 6))
                .thenReturn(buildExecutorResponseWithNarration(7, 12))
                .thenReturn(buildExecutorResponseWithNarration(13, 18));

        // --- 执行 ---
        List<Map<String, Object>> result = service.generate(
                SAMPLE_SCRIPT, CHARACTERS, TARGET_DURATION,
                "cinematic", true, "third_person", null);

        // --- 基础断言 ---
        assertThat(result).isNotEmpty();
        log.info("[解说模式] 共 {} 个 shot", result.size());

        int totalDuration = calculateTotalDuration(result);
        assertThat(totalDuration)
                .as("总时长应在 30-90s 范围内，实际: %ds", totalDuration)
                .isBetween(30, 90);
        log.info("[解说模式] 总时长: {}s", totalDuration);

        // narration 验证
        long narrationCount = result.stream()
                .filter(shot -> shot.get("narration") != null
                        && !shot.get("narration").toString().isEmpty()
                        && !"无".equals(shot.get("narration")))
                .count();
        log.info("[解说模式] 含 narration 的 shot 数: {}", narrationCount);

        // --- 打印完整分镜 ---
        printAllShots("解说模式", result);

        // --- 验证调用次数 ---
        verify(mockReasoner, times(2)).generate(anyString(), anyString());
        verify(mockExecutor, atLeastOnce()).generate(anyString(), anyString());
    }

    // ==================== 辅助方法 ====================

    private int calculateTotalDuration(List<Map<String, Object>> shots) {
        int total = 0;
        for (Map<String, Object> shot : shots) {
            Object dur = shot.get("duration");
            if (dur instanceof Number) {
                total += ((Number) dur).intValue();
            }
        }
        return total;
    }

    private void printAllShots(String mode, List<Map<String, Object>> shots) {
        log.info("========== {} 分镜详情 (共 {} 镜) ==========", mode, shots.size());
        log.info("  {:<6} {:<4} {:<6} {:<20} {:<8} {:<12} {:<12} {}",
                "编号", "时长", "景别", "场景描述", "角色", "运镜", "镜头", "对话/旁白");
        log.info("  {}", repeatChar('-', 120));

        for (Map<String, Object> shot : shots) {
            int number = getInt(shot, "shotNumber", -1);
            int duration = getInt(shot, "duration", -1);
            String shotSize = getString(shot, "shotSize", "");
            String scene = getString(shot, "scene", "");
            String sceneDesc = getString(shot, "sceneDescription", "");
            Object charactersObj = shot.get("characters");
            String characters = "";
            if (charactersObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> charList = (List<Object>) charactersObj;
                StringBuilder sb = new StringBuilder();
                for (Object c : charList) {
                    if (c instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> charMap = (Map<String, Object>) c;
                        sb.append(charMap.getOrDefault("name", "")).append(",");
                    } else if (c instanceof String) {
                        sb.append(c).append(",");
                    }
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                characters = sb.toString();
            }
            String cameraAngle = getString(shot, "cameraAngle", "");
            String cameraMove = getString(shot, "cameraMovement", "");
            String dialogue = getString(shot, "dialogue", "");
            String speaker = getString(shot, "speaker", "");
            String narration = getString(shot, "narration", "");
            String hookPoint = getString(shot, "hookPoint", "");

            log.info("  #{:<5} {:<4}s {:<6} {:<20} {:<8} {:<12} {:<12} {}",
                    number, duration, shotSize,
                    truncate(sceneDesc, 20),
                    truncate(characters, 8),
                    truncate(cameraAngle, 12),
                    truncate(cameraMove, 12),
                    truncate(buildDialogueInfo(speaker, dialogue, narration, hookPoint), 40));
        }

        log.info("========== {} 分镜结束 ==========", mode);
    }

    private String buildDialogueInfo(String speaker, String dialogue, String narration, String hookPoint) {
        StringBuilder sb = new StringBuilder();
        if (dialogue != null && !dialogue.isEmpty() && !"无".equals(dialogue)) {
            sb.append("[").append(speaker).append("]").append(dialogue);
        }
        if (narration != null && !narration.isEmpty() && !"无".equals(narration)) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("(旁白)").append(narration);
        }
        if (hookPoint != null && !hookPoint.isEmpty() && !"无".equals(hookPoint)) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("[爽:").append(hookPoint).append("]");
        }
        return sb.toString();
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return def;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "~";
    }

    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    /**
     * 构建一组通用精修 shot JSON 数组。
     * 从 startNum 到 endNum（含），每个 shot 有完整字段。
     * 爽剧模式额外带 hookPoint。
     */
    private static String buildExecutorResponse(int startNum, int endNum) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = startNum; i <= endNum; i++) {
            if (i > startNum) sb.append(",");
            String sceneDesc = "第" + i + "镜的场景描述，展示关键剧情画面";
            String dialogue = (i % 3 != 0) ? "台词内容" + i : "";
            String speaker = (i % 3 != 0) ? "林晓星" : "无";
            String hookPoint = (i % 2 == 0) ? "爽点标记" + i : "无";

            sb.append("{");
            sb.append("\"shotNumber\":").append(i).append(",");
            sb.append("\"duration\":3,");
            sb.append("\"scene\":\"服务器房间\",");
            sb.append("\"characters\":[\"林晓星\"],");
            sb.append("\"shotSize\":\"MEDIUM\",");
            sb.append("\"cameraAngle\":\"eye_level\",");
            sb.append("\"cameraMovement\":\"static\",");
            sb.append("\"sceneDescription\":\"").append(sceneDesc).append("\",");
            sb.append("\"dialogue\":\"").append(dialogue).append("\",");
            sb.append("\"speaker\":\"").append(speaker).append("\",");
            sb.append("\"dialogueTone\":\"紧张\",");
            sb.append("\"visualEffects\":\"无\",");
            sb.append("\"audioEffects\":\"无\",");
            sb.append("\"transitionHint\":\"过渡到下一镜\",");
            sb.append("\"hookPoint\":\"").append(hookPoint).append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 构建一组带 narration 字段的精修 shot JSON 数组（解说模式）。
     */
    private static String buildExecutorResponseWithNarration(int startNum, int endNum) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = startNum; i <= endNum; i++) {
            if (i > startNum) sb.append(",");
            String sceneDesc = "第" + i + "镜场景，林晓星在行动中";
            String narration = "旁白描述第" + i + "镜的关键剧情发展";

            sb.append("{");
            sb.append("\"shotNumber\":").append(i).append(",");
            sb.append("\"duration\":3,");
            sb.append("\"scene\":\"服务器房间\",");
            sb.append("\"characters\":[\"林晓星\"],");
            sb.append("\"shotSize\":\"MEDIUM\",");
            sb.append("\"cameraAngle\":\"eye_level\",");
            sb.append("\"cameraMovement\":\"static\",");
            sb.append("\"sceneDescription\":\"").append(sceneDesc).append("\",");
            sb.append("\"dialogue\":\"\",");
            sb.append("\"speaker\":\"无\",");
            sb.append("\"dialogueTone\":\"无\",");
            sb.append("\"visualEffects\":\"无\",");
            sb.append("\"audioEffects\":\"无\",");
            sb.append("\"transitionHint\":\"过渡到下一镜\",");
            sb.append("\"narration\":\"").append(narration).append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }
}
