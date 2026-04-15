package com.comic.service.production;

import com.comic.ai.text.DeepSeekTextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分镜 Agent 服务
 * 使用 DeepSeek Reasoner 做规划决策 + DeepSeek-chat 分批生成分镜，
 * 确保生成足够多的分镜覆盖完整剧情，时长自然落在目标范围内。
 */
@Service
@Slf4j
public class StoryboardAgentService {

    private final DeepSeekTextService reasoner;
    private final DeepSeekTextService executor;
    private final ObjectMapper objectMapper;

    private static final int MAX_ROUNDS = 15;
    private static final int MAX_EXECUTOR_RETRIES = 2;
    private static final int LAST_SHOTS_COUNT = 3;

    public StoryboardAgentService(@Qualifier("reasoner") DeepSeekTextService reasoner,
                                   @Qualifier("textGenerationService") DeepSeekTextService executor,
                                   ObjectMapper objectMapper) {
        this.reasoner = reasoner;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    // ==================== Reasoner 决策解析 ====================

    /**
     * 解析 Reasoner 输出的 JSON 决策
     */
    public ReasonerDecision parseReasonerDecision(String json) {
        try {
            // 清理 markdown 代码块标记
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                int lastBacktick = cleaned.lastIndexOf("```");
                if (firstNewline > 0 && lastBacktick > firstNewline) {
                    cleaned = cleaned.substring(firstNewline + 1, lastBacktick).trim();
                }
            }

            JsonNode node = objectMapper.readTree(cleaned);
            String action = node.has("action") ? node.get("action").asText("generate") : "generate";
            String nextBeatDescription = node.has("nextBeatDescription") ? node.get("nextBeatDescription").asText("") : "";
            int estimatedSeconds = node.has("estimatedSeconds") ? node.get("estimatedSeconds").asInt(30) : 30;
            String reasoning = node.has("reasoning") ? node.get("reasoning").asText("") : "";
            int targetBeatIndex = node.has("targetBeatIndex") ? node.get("targetBeatIndex").asInt(-1) : -1;

            return new ReasonerDecision(action, nextBeatDescription, estimatedSeconds, reasoning, targetBeatIndex);
        } catch (Exception e) {
            log.warn("[StoryboardAgent] 解析 Reasoner 决策失败，兜底为 generate: {}", e.getMessage());
            return new ReasonerDecision("generate", "", 30, "解析失败兜底", -1);
        }
    }

    // ==================== Prompt 构建 ====================

    /**
     * 构建 Reasoner 的 system prompt
     */
    private String buildReasonerSystemPrompt(boolean comicMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个分镜规划 agent。你的任务是为短视频分镜生成做决策。\n\n");

        if (comicMode) {
            sb.append("本集为漫剧解说模式：叙事由旁白口播主导，每个分镜必须有 narration 或 dialogue。\n\n");
        }

        sb.append("你的职责：\n");
        sb.append("1. 分析剩余剧情内容和已生成进度\n");
        sb.append("2. 决定下一批应覆盖哪个剧情段落\n");
        sb.append("3. 估算该段落需要多少秒\n");
        sb.append("4. 当所有剧情覆盖完毕后，如果时长不足可以选择 expand（扩展已有节点）或 pad（填充氛围镜头）\n\n");

        sb.append("约束：\n");
        sb.append("- 每个分镜 1-4 秒\n");
        sb.append("- 优先完整覆盖所有剧情节点\n");
        sb.append("- 保持叙事连贯性，每批之间需要衔接\n\n");

        sb.append("输出纯 JSON（不要 markdown 代码块标记）：\n");
        sb.append("{\n");
        sb.append("  \"action\": \"generate|expand|pad|done\",\n");
        sb.append("  \"nextBeatDescription\": \"下一批覆盖的剧情内容摘要\",\n");
        sb.append("  \"estimatedSeconds\": 40,\n");
        sb.append("  \"targetBeatIndex\": 2,\n");
        sb.append("  \"reasoning\": \"为什么做这个决策\"\n");
        sb.append("}\n\n");

        sb.append("action 说明：\n");
        sb.append("- generate: 还有剧情未覆盖，继续生成\n");
        sb.append("- expand: 剧情覆盖完毕但时长不足，回头扩展已有节点（需指定 targetBeatIndex）\n");
        sb.append("- pad: 生成过渡/氛围镜头填充时长\n");
        sb.append("- done: 剧情已完整覆盖，结束生成\n");

        return sb.toString();
    }

    /**
     * 构建 Reasoner 的 user prompt（包含当前状态）
     */
    public String buildReasonerPrompt(String episodeContent, String characters, String visualStyle,
                                       AgentState state, String roundLabel, boolean comicMode,
                                       String narrationPerspective) {
        int minDuration = state.targetDuration * 2 / 3;
        int maxDuration = state.targetDuration * 4 / 3;

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态\n");
        sb.append("- 目标总时长：").append(state.targetDuration).append(" 秒（范围 ").append(minDuration).append("~").append(maxDuration).append(" 秒）\n");
        sb.append("- 已生成时长：").append(state.accumulatedDuration).append(" 秒\n");
        sb.append("- 轮次：").append(roundLabel).append("（最多 ").append(MAX_ROUNDS).append(" 轮）\n\n");

        sb.append("## 剧本内容\n").append(episodeContent).append("\n\n");

        sb.append("## 已覆盖剧情节点\n");
        if (state.coveredBeats.isEmpty()) {
            sb.append("（无，刚开始）\n");
        } else {
            for (int i = 0; i < state.coveredBeats.size(); i++) {
                sb.append(i + 1).append(". ").append(state.coveredBeats.get(i)).append("\n");
            }
        }
        sb.append("\n");

        if (!state.lastShots.isEmpty()) {
            sb.append("## 上一批末尾分镜（用于衔接）\n");
            for (Map<String, Object> shot : state.lastShots) {
                sb.append("- 第").append(shot.get("shotNumber")).append("镜 [").append(shot.get("duration")).append("秒] ")
                  .append(shot.getOrDefault("scene", shot.getOrDefault("sceneDescription", ""))).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 角色\n").append(characters).append("\n");
        sb.append("## 视觉风格\n").append(visualStyle).append("\n");

        if (comicMode) {
            sb.append("## 模式\n本集为漫剧解说模式，分镜需包含 narration/旁白口播稿。\n");
        }

        if (comicMode && narrationPerspective != null) {
            if ("third_person".equals(narrationPerspective)) {
                sb.append("## 人称要求\n旁白必须使用第三人称叙述。\n");
            } else if ("first_person".equals(narrationPerspective)) {
                sb.append("## 人称要求\n旁白必须使用第一人称「我」叙述。\n");
            }
        }

        sb.append("\n请输出你的决策 JSON。");

        return sb.toString();
    }

    /**
     * 构建执行模型（DeepSeek-chat）的 system prompt
     */
    private String buildExecutorSystemPrompt(boolean comicMode) {
        StringBuilder sb = new StringBuilder();
        if (comicMode) {
            sb.append("你是一位专做「漫剧解说」短视频的分镜师。叙事由**旁白口播**主导：每一镜都必须写出观众能直接念出来的解说词。\n");
        } else {
            sb.append("你是一位专业的影视分镜师。\n");
        }

        sb.append("关键约束：\n");
        sb.append("- 每个分镜时长：1-4秒\n");
        sb.append("- 节奏要有快慢变化：一闪而过的画面用 1-2 秒，需要消化的内容用 3-4 秒，相邻镜头避免连续 3 个以上相同时长\n\n");

        sb.append("输出纯 JSON 数组，不要包含 markdown 代码块标记。\n\n");

        sb.append("每个分镜包含以下字段：\n");
        sb.append("- shotNumber: 镜头编号（从1开始）\n");
        sb.append("- duration: 时长（秒，1-4）\n");
        sb.append("- scene: 场景描述\n");
        sb.append("- characters: 出场角色数组\n");
        sb.append("- shotSize: 景别\n");
        sb.append("- cameraAngle: 角度\n");
        sb.append("- cameraMovement: 运镜\n");
        sb.append("- sceneDescription: 详细画面描述（含运镜细节、角色状态、光影氛围）\n");
        sb.append("- visualDescription: 纯画面描述\n");
        sb.append("- transitionHint: 镜头衔接提示\n");

        if (comicMode) {
            sb.append("- narration: 旁白口播稿（中文口语，duration=1时2~6字、duration=2时5~9字、duration=3时9~13字、duration=4时12~16字）\n");
            sb.append("- dialogue: 角色台词（有 narration 的分镜填「无」）\n");
            sb.append("- speaker: 说话人（无台词填「无」）\n");
            sb.append("- dialogueTone: 对白语气\n");
        } else {
            sb.append("- dialogue: 对白\n");
            sb.append("- speaker: 说话人\n");
            sb.append("- dialogueTone: 对白语气\n");
        }
        sb.append("- visualEffects: 视觉特效\n");
        sb.append("- audioEffects: 音效\n");

        sb.append("\n**AI视频生成原则：**\n");
        sb.append("1.【单主体原则】每个分镜最多1个角色动作，禁止双人互动。\n");
        sb.append("2.【慢动作原则】运镜缓慢，角色动作微小。\n");

        return sb.toString();
    }

    /**
     * 构建执行模型的 user prompt（只包含当前剧情段落）
     */
    public String buildExecutorPrompt(String nextBeatDescription, String characters, String visualStyle,
                                       int estimatedSeconds, List<Map<String, Object>> lastShots,
                                       boolean comicMode) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 本批任务\n");
        sb.append("- 覆盖剧情段落：").append(nextBeatDescription).append("\n");
        sb.append("- 本批目标时长：").append(estimatedSeconds).append(" 秒（±10秒可接受）\n\n");

        if (!lastShots.isEmpty()) {
            sb.append("## 上一批末尾分镜（衔接用）\n");
            for (Map<String, Object> shot : lastShots) {
                sb.append("- 第").append(shot.get("shotNumber")).append("镜 [").append(shot.get("duration")).append("秒] ")
                  .append(shot.getOrDefault("scene", shot.getOrDefault("sceneDescription", ""))).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 角色\n").append(characters).append("\n");
        sb.append("## 视觉风格\n").append(visualStyle).append("\n\n");

        sb.append("请生成本批分镜 JSON 数组。");

        return sb.toString();
    }

    // ==================== 状态管理 ====================

    /**
     * 将一批生成的 shots 累加到全局状态
     */
    public void accumulateShots(AgentState state, List<Map<String, Object>> shots, String beatDescription) {
        if (shots == null || shots.isEmpty()) return;

        // 标准化每个 shot 的 duration
        for (Map<String, Object> shot : shots) {
            int duration = ((Number) shot.get("duration")).intValue();
            duration = Math.max(1, Math.min(4, duration));
            shot.put("duration", duration);
        }

        // 分配全局 shot 编号和起止时间
        int currentTime = state.accumulatedDuration;
        for (int i = 0; i < shots.size(); i++) {
            Map<String, Object> shot = shots.get(i);
            int globalNum = state.allShots.size() + i + 1;
            shot.put("globalShotNumber", globalNum);
            shot.put("startTime", currentTime);
            currentTime += ((Number) shot.get("duration")).intValue();
            shot.put("endTime", currentTime);
        }

        state.allShots.addAll(shots);
        state.accumulatedDuration = currentTime;

        // 更新 coveredBeats
        if (beatDescription != null && !beatDescription.isEmpty()) {
            state.coveredBeats.add(beatDescription);
        }

        // 更新 lastShots（保留最后 N 个）
        state.lastShots.clear();
        int start = Math.max(0, shots.size() - LAST_SHOTS_COUNT);
        for (int i = start; i < shots.size(); i++) {
            state.lastShots.add(shots.get(i));
        }
    }

    // ==================== 主循环 ====================

    /**
     * Agent 主入口：分批生成分镜，直到剧情覆盖完毕
     *
     * @param episodeContent     剧本正文
     * @param characters         角色描述
     * @param targetDuration     目标总时长（秒）
     * @param visualStyle        视觉风格
     * @param comicMode          是否漫剧解说模式
     * @param narrationPerspective 人称视角（first_person/third_person），漫剧模式专用
     * @return 完整的分镜列表
     */
    public List<Map<String, Object>> generate(String episodeContent, String characters,
                                               int targetDuration, String visualStyle,
                                               boolean comicMode, String narrationPerspective) {
        AgentState state = new AgentState();
        state.targetDuration = targetDuration;

        String systemPrompt = buildReasonerSystemPrompt(comicMode);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            String roundLabel = "第" + (round + 1) + "轮";

            // ① Reasoner 思考
            String userPrompt = buildReasonerPrompt(
                    episodeContent, characters, visualStyle, state, roundLabel, comicMode, narrationPerspective);

            String reasonerOutput;
            try {
                reasonerOutput = reasoner.generate(systemPrompt, userPrompt);
            } catch (Exception e) {
                log.warn("[StoryboardAgent] Reasoner 调用失败，轮次={}: {}", roundLabel, e.getMessage());
                break;
            }

            ReasonerDecision decision = parseReasonerDecision(reasonerOutput);
            log.info("[StoryboardAgent] 轮次={}, action={}, beat='{}', estimated={}s, reason={}",
                    roundLabel, decision.action, decision.nextBeatDescription, decision.estimatedSeconds, decision.reasoning);

            // ④ 判断是否结束
            if ("done".equals(decision.action)) {
                log.info("[StoryboardAgent] Reasoner 判定完成: {}", decision.reasoning);
                break;
            }

            // ② 执行：生成本批分镜
            String executorSystem = buildExecutorSystemPrompt(comicMode);
            String executorUser = buildExecutorPrompt(
                    decision.nextBeatDescription, characters, visualStyle,
                    decision.estimatedSeconds, state.lastShots, comicMode);

            List<Map<String, Object>> batchShots = null;
            for (int retry = 0; retry <= MAX_EXECUTOR_RETRIES; retry++) {
                try {
                    String executorOutput = executor.generate(executorSystem, executorUser);
                    batchShots = parseShotArray(executorOutput);
                    if (batchShots != null && !batchShots.isEmpty()) break;
                } catch (Exception e) {
                    log.warn("[StoryboardAgent] 执行模型调用失败，重试 {}/{}: {}",
                            retry + 1, MAX_EXECUTOR_RETRIES, e.getMessage());
                }
            }

            if (batchShots == null || batchShots.isEmpty()) {
                log.warn("[StoryboardAgent] 执行模型重试耗尽，跳过本批");
                continue;
            }

            // ③ 累加状态
            accumulateShots(state, batchShots, decision.nextBeatDescription);
            log.info("[StoryboardAgent] 轮次{}完成: 本批{}个分镜, 累计{}秒, 已覆盖{}个节点",
                    roundLabel, batchShots.size(), state.accumulatedDuration, state.coveredBeats.size());
        }

        log.info("[StoryboardAgent] 生成完毕: 总分镜={}, 总时长={}秒, 剧情节点={}",
                state.allShots.size(), state.accumulatedDuration, state.coveredBeats.size());

        return state.allShots;
    }

    // ==================== JSON 解析 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseShotArray(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                int lastBacktick = cleaned.lastIndexOf("```");
                if (firstNewline > 0 && lastBacktick > firstNewline) {
                    cleaned = cleaned.substring(firstNewline + 1, lastBacktick).trim();
                }
            }

            JsonNode arrayNode = objectMapper.readTree(cleaned);
            if (!arrayNode.isArray()) {
                // 尝试提取 shots 或 panels 数组
                if (arrayNode.has("shots")) arrayNode = arrayNode.get("shots");
                else if (arrayNode.has("panels")) {
                    // Panel-aware 格式：展平所有 panels 的 shots
                    List<Map<String, Object>> flatList = new ArrayList<>();
                    for (JsonNode panel : arrayNode.get("panels")) {
                        if (panel.has("shots")) {
                            for (JsonNode shot : panel.get("shots")) {
                                flatList.add(objectMapper.convertValue(shot, Map.class));
                            }
                        }
                    }
                    return flatList;
                } else {
                    return null;
                }
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode shotNode : arrayNode) {
                Map<String, Object> shot = objectMapper.convertValue(shotNode, Map.class);
                result.add(shot);
            }
            return result;
        } catch (Exception e) {
            log.error("[StoryboardAgent] 解析分镜 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 数据类 ====================

    /**
     * Reasoner 决策结果
     */
    public static class ReasonerDecision {
        public final String action;
        public final String nextBeatDescription;
        public final int estimatedSeconds;
        public final String reasoning;
        public final int targetBeatIndex;

        public ReasonerDecision(String action, String nextBeatDescription,
                                int estimatedSeconds, String reasoning, int targetBeatIndex) {
            this.action = action;
            this.nextBeatDescription = nextBeatDescription;
            this.estimatedSeconds = estimatedSeconds;
            this.reasoning = reasoning;
            this.targetBeatIndex = targetBeatIndex;
        }
    }

    /**
     * Agent 循环状态
     */
    public static class AgentState {
        public int targetDuration;
        public int accumulatedDuration;
        public final List<String> coveredBeats = new ArrayList<>();
        public final List<Map<String, Object>> allShots = new ArrayList<>();
        public final List<Map<String, Object>> lastShots = new ArrayList<>();
    }
}
