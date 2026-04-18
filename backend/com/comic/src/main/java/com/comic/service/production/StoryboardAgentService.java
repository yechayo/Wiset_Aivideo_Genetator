package com.comic.service.production;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.constant.ProjectInfoKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分镜 Agent 服务
 *
 * 爽剧模式：ReAct (Reasoning + Acting) 循环
 *   - 单个 chat 模型负责推理和行动选择
 *   - generate_shots 作为 tool，调用同一个 chat 模型生成分镜
 *   - 每轮：Thought → Action → Observation → 下一轮 Thought
 *   - 模型看到完整历史，能根据 Observation 调整策略
 *
 * 标准模式：保留原有的迭代 Reasoner 循环
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

    private static final Pattern HOOK_PATTERN =
            Pattern.compile("[\\[【]\\s*爽点\\s*[:：]\\s*(.+?)\\s*[\\]】]");
    private static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*(\\w+)\\(([^)]*)\\)");
    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("Thought:\\s*(.+)");

    public StoryboardAgentService(@Qualifier("reasoner") DeepSeekTextService reasoner,
                                   @Qualifier("deepSeekTextService") DeepSeekTextService executor,
                                   ObjectMapper objectMapper) {
        this.reasoner = reasoner;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    // ==================== Reasoner 决策解析（标准模式用） ====================

    public ReasonerDecision parseReasonerDecision(String json) {
        try {
            String cleaned = cleanJson(json);
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

    // ==================== Hook 提取 ====================

    List<String> extractHookBeats(String episodeContent) {
        List<String> beats = new ArrayList<>();
        Matcher m = HOOK_PATTERN.matcher(episodeContent);
        while (m.find()) {
            beats.add(m.group(1).trim());
        }
        return beats;
    }

    // ==================== Phase 1: LLM 结构分析 ====================

    StoryStructure analyzeStoryStructure(String episodeContent, String characters, int targetDuration) {
        String systemPrompt = buildAnalysisSystemPrompt();
        String userPrompt = buildAnalysisUserPrompt(episodeContent, characters, targetDuration);
        try {
            String output = reasoner.generate(systemPrompt, userPrompt);
            StoryStructure structure = parseStoryStructure(output);
            if (structure != null && structure.beats != null && !structure.beats.isEmpty()) {
                padTransitions(structure);
                log.info("[StoryboardAgent] Phase1 结构分析成功: {} 个beat, 弧线='{}'",
                        structure.beats.size(), structure.storyArc);
                return structure;
            }
        } catch (Exception e) {
            log.warn("[StoryboardAgent] Phase1 结构分析失败: {}", e.getMessage());
        }
        return null;
    }

    private String buildAnalysisSystemPrompt() {
        return "你是一位专业剧本结构分析师。你的任务是分析剧本，将其拆分为叙事节点（beat），每个节点标注在场角色及其状态。\n\n"
                + "输出纯 JSON（不要 markdown 代码块标记）：\n"
                + "{\n"
                + "  \"storyArc\": \"一句话概括本集故事弧线\",\n"
                + "  \"beats\": [\n"
                + "    {\n"
                + "      \"id\": 1,\n"
                + "      \"beat\": \"林晓星进入废弃工厂\",\n"
                + "      \"duration\": 12,\n"
                + "      \"mood\": \"紧张好奇\",\n"
                + "      \"characters\": [\n"
                + "        {\"name\": \"林晓星\", \"state\": \"警惕探索\", \"position\": \"工厂大厅\"}\n"
                + "      ]\n"
                + "    }\n"
                + "  ],\n"
                + "  \"transitions\": [\n"
                + "    {\"from\": 1, \"to\": 2, \"bridge\": \"推开铁门看到微光\"}\n"
                + "  ]\n"
                + "}\n\n"
                + "【关键约束】\n"
                + "1. 每个 beat 必须包含 characters 数组，标注谁在场、什么状态、在哪个位置\n"
                + "2. 相邻 beat 间必须有 transition，描述场景如何过渡\n"
                + "3. 所有 beat 的 duration 之和 = 目标时长 ± 10%\n"
                + "4. beat 数量 10-20 个\n"
                + "5. transitions 数组长度 = beats.length - 1\n"
                + "6. 确保角色在场逻辑合理：角色不会凭空出现或消失";
    }

    private String buildAnalysisUserPrompt(String episodeContent, String characters, int targetDuration) {
        return "目标总时长：" + targetDuration + " 秒\n"
                + "角色列表：" + characters + "\n\n"
                + "剧本内容：\n" + episodeContent + "\n\n"
                + "请输出剧本结构分析 JSON。";
    }

    StoryStructure parseStoryStructure(String json) {
        try {
            String cleaned = cleanJson(json);
            JsonNode root = objectMapper.readTree(cleaned);

            StoryStructure structure = new StoryStructure();
            structure.storyArc = root.has("storyArc") ? root.get("storyArc").asText("") : "";

            JsonNode beatsNode = root.has("beats") ? root.get("beats") : null;
            if (beatsNode == null || !beatsNode.isArray() || beatsNode.isEmpty()) return null;

            for (JsonNode b : beatsNode) {
                StoryBeat beat = new StoryBeat();
                beat.id = b.has("id") ? b.get("id").asInt(0) : 0;
                beat.beat = b.has("beat") ? b.get("beat").asText("") : "";
                beat.duration = b.has("duration") ? b.get("duration").asInt(10) : 10;
                beat.mood = b.has("mood") ? b.get("mood").asText("") : "";

                beat.characters = new ArrayList<>();
                if (b.has("characters") && b.get("characters").isArray()) {
                    for (JsonNode c : b.get("characters")) {
                        String name = c.has("name") ? c.get("name").asText("") : "";
                        String state = c.has("state") ? c.get("state").asText("") : "";
                        String position = c.has("position") ? c.get("position").asText("") : "";
                        if (!name.isEmpty()) {
                            beat.characters.add(new CharacterInScene(name, state, position));
                        }
                    }
                }
                structure.beats.add(beat);
            }

            structure.transitions = new ArrayList<>();
            if (root.has("transitions") && root.get("transitions").isArray()) {
                for (JsonNode t : root.get("transitions")) {
                    Map<String, String> transition = new HashMap<>();
                    transition.put("from", t.has("from") ? t.get("from").asText("") : "");
                    transition.put("to", t.has("to") ? t.get("to").asText("") : "");
                    transition.put("bridge", t.has("bridge") ? t.get("bridge").asText("场景自然过渡") : "场景自然过渡");
                    structure.transitions.add(transition);
                }
            }

            return structure;
        } catch (Exception e) {
            log.warn("[StoryboardAgent] Phase1 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private void padTransitions(StoryStructure structure) {
        int expectedTransitions = structure.beats.size() - 1;
        while (structure.transitions.size() < expectedTransitions) {
            int fromId = structure.transitions.size() + 1;
            Map<String, String> t = new HashMap<>();
            t.put("from", String.valueOf(fromId));
            t.put("to", String.valueOf(fromId + 1));
            t.put("bridge", "场景自然过渡");
            structure.transitions.add(t);
        }
    }

    // ==================== Phase 2: 代码拆 shot 骨架 ====================

    /**
     * Phase 2: 纯代码，把 StoryStructure 的 beats 拆成 shot 骨架。
     * 不调用 LLM。
     */
    List<Map<String, Object>> buildShotSkeletons(StoryStructure structure, NarrativePlan plan) {
        List<Map<String, Object>> skeletons = new ArrayList<>();
        int shotNumber = 1;
        int accumulatedSeconds = 0;

        for (int beatIdx = 0; beatIdx < structure.beats.size(); beatIdx++) {
            StoryBeat beat = structure.beats.get(beatIdx);
            int shotCount = Math.max(1, (int) Math.round((double) beat.duration / 3.5));
            int avgDuration = Math.max(1, Math.min(4, beat.duration / shotCount));

            for (int i = 0; i < shotCount; i++) {
                Map<String, Object> skeleton = new HashMap<>();
                skeleton.put("shotNumber", shotNumber++);
                int duration = (i == shotCount - 1)
                        ? Math.max(1, Math.min(4, beat.duration - avgDuration * (shotCount - 1)))
                        : avgDuration;
                skeleton.put("duration", duration);
                skeleton.put("beatId", beat.id);
                skeleton.put("beatIndex", beatIdx);
                skeleton.put("sceneHint", beat.beat);
                skeleton.put("mood", beat.mood);
                skeleton.put("narrativePhase", matchNarrativePhase(accumulatedSeconds, plan));

                List<Map<String, String>> charList = new ArrayList<>();
                if (beat.characters != null) {
                    for (CharacterInScene c : beat.characters) {
                        Map<String, String> charMap = new HashMap<>();
                        charMap.put("name", c.name);
                        charMap.put("state", c.state);
                        charMap.put("position", c.position);
                        charList.add(charMap);
                    }
                }
                skeleton.put("characters", charList);

                skeletons.add(skeleton);
                accumulatedSeconds += duration;
            }
        }

        log.info("[StoryboardAgent] Phase2 骨架生成: {} 个shot, 预估{}秒",
                skeletons.size(), accumulatedSeconds);
        return skeletons;
    }

    /**
     * 兜底骨架：Phase1 失败时均匀拆分
     */
    List<Map<String, Object>> buildFallbackSkeletons(int targetDuration, String episodeContent,
                                                       String characters, NarrativePlan plan) {
        int shotCount = Math.max(3, (int) Math.round((double) targetDuration / 3.5));
        int avgDuration = Math.max(1, Math.min(4, targetDuration / shotCount));
        int lastDuration = targetDuration - avgDuration * (shotCount - 1);
        if (lastDuration > 4 || lastDuration < 1) {
            shotCount = targetDuration / 3;
            avgDuration = 3;
            lastDuration = targetDuration - avgDuration * (shotCount - 1);
        }

        String[] parts = splitContentEvenly(episodeContent, shotCount);

        List<Map<String, Object>> skeletons = new ArrayList<>();
        int accumulatedSeconds = 0;
        for (int i = 0; i < shotCount; i++) {
            Map<String, Object> skeleton = new HashMap<>();
            skeleton.put("shotNumber", i + 1);
            int duration = (i == shotCount - 1)
                    ? Math.max(1, Math.min(4, lastDuration))
                    : avgDuration;
            skeleton.put("duration", duration);
            skeleton.put("beatId", 1);
            skeleton.put("beatIndex", 0);
            skeleton.put("sceneHint", parts[i]);
            skeleton.put("mood", "");
            skeleton.put("narrativePhase", matchNarrativePhase(accumulatedSeconds, plan));

            List<Map<String, String>> charList = new ArrayList<>();
            if (characters != null && !characters.isEmpty()) {
                for (String name : characters.split("[,，]")) {
                    Map<String, String> charMap = new HashMap<>();
                    charMap.put("name", name.trim());
                    charMap.put("state", "");
                    charMap.put("position", "");
                    charList.add(charMap);
                }
            }
            skeleton.put("characters", charList);

            skeletons.add(skeleton);
            accumulatedSeconds += duration;
        }

        log.info("[StoryboardAgent] Phase2 兜底骨架: {} 个shot", shotCount);
        return skeletons;
    }

    private String matchNarrativePhase(int accumulatedSeconds, NarrativePlan plan) {
        if (plan == null || plan.phases == null || plan.phases.isEmpty()) return "";
        int boundary = 0;
        for (NarrativePhase phase : plan.phases) {
            boundary += phase.allocatedSeconds;
            if (accumulatedSeconds < boundary) return phase.name;
        }
        return plan.phases.get(plan.phases.size() - 1).name;
    }

    private String[] splitContentEvenly(String content, int parts) {
        if (content == null || content.isEmpty()) {
            String[] result = new String[parts];
            java.util.Arrays.fill(result, "");
            return result;
        }
        String[] result = new String[parts];
        int chunkSize = Math.max(1, content.length() / parts);
        for (int i = 0; i < parts; i++) {
            int start = Math.min(i * chunkSize, content.length());
            int end = Math.min((i + 1) * chunkSize, content.length());
            if (i == parts - 1) end = content.length();
            result[i] = content.substring(start, end);
        }
        return result;
    }

    // ==================== 叙事规划 ====================

    /**
     * 叙事规划：分析剧本结构，划分叙事阶段，为每个阶段指定对话密度和节奏。
     * 这是调用 reasoner 模型的一次性分析，在 ReAct/迭代循环开始前完成。
     * 失败时返回兜底方案。
     */
    NarrativePlan buildNarrativePlan(String episodeContent, List<String> hooks,
                                      int targetDuration, String characters,
                                      String scriptStyle, boolean comicMode) {
        String systemPrompt = buildNarrativePlanSystemPrompt(scriptStyle, comicMode);
        String userPrompt = buildNarrativePlanUserPrompt(episodeContent, hooks, targetDuration, characters);

        try {
            String planOutput = reasoner.generate(systemPrompt, userPrompt);
            NarrativePlan plan = parseNarrativePlan(planOutput);
            if (plan != null && !plan.phases.isEmpty()) {
                log.info("[StoryboardAgent] 叙事规划成功: {} 个阶段, 弧线='{}'", plan.phases.size(), plan.storyArcSummary);
                return plan;
            }
        } catch (Exception e) {
            log.warn("[StoryboardAgent] 叙事规划失败，使用兜底方案: {}", e.getMessage());
        }
        return buildFallbackNarrativePlan(hooks, targetDuration);
    }

    private String buildNarrativePlanSystemPrompt(String scriptStyle, boolean comicMode) {
        String mode = ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? "爽剧" :
                      (comicMode ? "漫剧解说" : "标准");
        return "你是一位专业的叙事分析师。你的任务是分析" + mode + "剧本，将其划分为叙事阶段。\n\n"
                + "输出纯 JSON（不要 markdown 代码块标记）：\n"
                + "{\n"
                + "  \"storyArcSummary\": \"一句话概括本集故事弧线\",\n"
                + "  \"phases\": [\n"
                + "    {\n"
                + "      \"name\": \"开场钩子\",\n"
                + "      \"startBeat\": 1,\n"
                + "      \"endBeat\": 3,\n"
                + "      \"allocatedSeconds\": 12,\n"
                + "      \"dialogueDensity\": \"sparse\",\n"
                + "      \"maxDialogueChars\": \"3秒镜头≤10字，4秒镜头≤12字\",\n"
                + "      \"emotionalArc\": \"冲击→悬念\",\n"
                + "      \"pacingNote\": \"快切为主，1-2秒镜头，视觉冲击优先，台词点到即止\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n\n"
                + "【叙事阶段划分原则】\n"
                + "1. 开场钩子（前 10% 时长）：强力视觉钩子，dialogueDensity=sparse\n"
                + "   - 多用纯画面镜头，dialogue 仅 1-2 句极短句\n"
                + "2. 铺垫/发展（20-30% 时长）：建立情境，dialogueDensity=moderate\n"
                + "   - 交替使用对话和画面叙事\n"
                + "3. 冲突升级（25-35% 时长）：矛盾激化，dialogueDensity=dense\n"
                + "   - 对话和动作交替，每 3 个镜头至少 1 个纯画面反应镜头\n"
                + "4. 高潮（15-25% 时长）：爆发，dialogueDensity=dense\n"
                + "   - 混合对话+动作，保留关键反应镜头和氛围镜头\n"
                + "5. 收束/悬念（10-15% 时长）：dialogueDensity=sparse\n"
                + "   - 回归画面叙事，留悬念\n\n"
                + "【对话密度定义】\n"
                + "- \"sparse\": ≤30% 的镜头有 dialogue\n"
                + "- \"moderate\": ≤50% 的镜头有 dialogue\n"
                + "- \"dense\": ≤70% 的镜头有 dialogue\n\n"
                + "【约束】\n"
                + "- 所有 phases 的 allocatedSeconds 之和 = 目标时长 ± 10%\n"
                + "- 每个 phase 至少覆盖 2 个爽点/剧情节点\n"
                + "- phases 数量 3-6 个";
    }

    private String buildNarrativePlanUserPrompt(String episodeContent, List<String> hooks,
                                                 int targetDuration, String characters) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标总时长：").append(targetDuration).append(" 秒\n");
        if (!hooks.isEmpty()) {
            sb.append("爽点/剧情节点（共").append(hooks.size()).append("个）：\n");
            for (int i = 0; i < hooks.size(); i++) {
                sb.append(i + 1).append(". ").append(hooks.get(i)).append("\n");
            }
        }
        sb.append("\n剧本内容：\n").append(episodeContent).append("\n\n");
        sb.append("角色：").append(characters).append("\n\n");
        sb.append("请输出叙事规划 JSON。");
        return sb.toString();
    }

    private NarrativePlan parseNarrativePlan(String json) {
        try {
            String cleaned = cleanJson(json);
            JsonNode root = objectMapper.readTree(cleaned);

            String summary = root.has("storyArcSummary") ? root.get("storyArcSummary").asText("") : "";
            JsonNode phasesNode = root.has("phases") ? root.get("phases") : null;
            if (phasesNode == null || !phasesNode.isArray() || phasesNode.isEmpty()) return null;

            List<NarrativePhase> phases = new ArrayList<>();
            for (JsonNode p : phasesNode) {
                NarrativePhase phase = new NarrativePhase();
                phase.name = p.has("name") ? p.get("name").asText("") : "未命名阶段";
                phase.startBeat = p.has("startBeat") ? p.get("startBeat").asInt(1) : 1;
                phase.endBeat = p.has("endBeat") ? p.get("endBeat").asInt(phase.startBeat) : phase.startBeat;
                phase.allocatedSeconds = p.has("allocatedSeconds") ? p.get("allocatedSeconds").asInt(20) : 20;
                phase.dialogueDensity = p.has("dialogueDensity") ? p.get("dialogueDensity").asText("moderate") : "moderate";
                phase.maxDialogueChars = p.has("maxDialogueChars") ? p.get("maxDialogueChars").asText("3秒镜头≤15字，4秒镜头≤20字") : "3秒镜头≤15字，4秒镜头≤20字";
                phase.emotionalArc = p.has("emotionalArc") ? p.get("emotionalArc").asText("") : "";
                phase.pacingNote = p.has("pacingNote") ? p.get("pacingNote").asText("") : "";
                phases.add(phase);
            }
            return new NarrativePlan(phases, summary);
        } catch (Exception e) {
            log.warn("[StoryboardAgent] 叙事规划解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 兜底叙事规划：硬编码 5 阶段分配
     */
    NarrativePlan buildFallbackNarrativePlan(List<String> hooks, int targetDuration) {
        int totalBeats = hooks.size();
        List<NarrativePhase> phases = new ArrayList<>();

        // 时长分配（最后一个阶段补齐差值，避免整数除法导致总和不足）
        int s1 = targetDuration * 10 / 100;
        int s2 = targetDuration * 25 / 100;
        int s3 = targetDuration * 30 / 100;
        int s4 = targetDuration * 25 / 100;
        int s5 = targetDuration - s1 - s2 - s3 - s4;

        if (totalBeats == 0) {
            // 无爽点时，仅按时长划分，beat 范围标记为 0
            phases.add(new NarrativePhase("开场钩子", 0, 0, s1, "sparse",
                    "3秒镜头≤10字，4秒镜头≤12字", "冲击→悬念", "快切为主，视觉冲击优先"));
            phases.add(new NarrativePhase("铺垫发展", 0, 0, s2, "moderate",
                    "3秒镜头≤15字，4秒镜头≤20字", "建立→推进", "交替对话和画面叙事"));
            phases.add(new NarrativePhase("冲突升级", 0, 0, s3, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "紧张→爆发", "节奏加快，对话和动作交替"));
            phases.add(new NarrativePhase("高潮爆发", 0, 0, s4, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "爆发→释放", "视觉和情绪并重"));
            phases.add(new NarrativePhase("收束悬念", 0, 0, s5, "sparse",
                    "3秒镜头≤10字，4秒镜头≤12字", "沉淀→悬念", "回归画面叙事，留悬念"));
        } else {
            // 有爽点时，按实际数量分配到各阶段
            int hookBeats = Math.max(1, totalBeats * 10 / 100);
            int setupBeats = Math.max(1, totalBeats * 25 / 100);
            int conflictBeats = Math.max(1, totalBeats * 30 / 100);
            int climaxBeats = Math.max(1, totalBeats * 25 / 100);
            int resolveBeats = Math.max(1, totalBeats - hookBeats - setupBeats - conflictBeats - climaxBeats);

            int idx = 0;
            phases.add(new NarrativePhase("开场钩子", idx + 1, idx + hookBeats, s1, "sparse",
                    "3秒镜头≤10字，4秒镜头≤12字", "冲击→悬念", "快切为主，视觉冲击优先"));
            idx += hookBeats;
            phases.add(new NarrativePhase("铺垫发展", idx + 1, idx + setupBeats, s2, "moderate",
                    "3秒镜头≤15字，4秒镜头≤20字", "建立→推进", "交替对话和画面叙事"));
            idx += setupBeats;
            phases.add(new NarrativePhase("冲突升级", idx + 1, idx + conflictBeats, s3, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "紧张→爆发", "节奏加快，对话和动作交替"));
            idx += conflictBeats;
            phases.add(new NarrativePhase("高潮爆发", idx + 1, idx + climaxBeats, s4, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "爆发→释放", "视觉和情绪并重"));
            idx += climaxBeats;
            phases.add(new NarrativePhase("收束悬念", idx + 1, idx + resolveBeats, s5, "sparse",
                    "3秒镜头≤10字，4秒镜头≤12字", "沉淀→悬念", "回归画面叙事，留悬念"));
        }

        return new NarrativePlan(phases, "兜底叙事规划（AI规划失败时使用）");
    }

    // ==================== ReAct System Prompt（爽剧模式） ====================

    /**
     * 爽剧 ReAct 模式的 system prompt
     * 定义可用的 tools 和输出格式
     */
    String buildReactSystemPrompt() {
        return "你是一个爽剧分镜叙事导演，使用 ReAct 模式工作。你不仅分配时长，更要像导演一样思考叙事节奏。\n\n"
                + "## 可用工具\n\n"
                + "**generate_shots(beatDescription, estimatedSeconds)**\n"
                + "- 调用分镜生成器，为指定爽点段落生成分镜\n"
                + "- beatDescription: 要覆盖的爽点描述，必须包含叙事阶段、对话密度、叙事目标\n"
                + "- estimatedSeconds: 本段目标秒数\n"
                + "- 返回: 生成的分镜列表及其总时长 + 质量报告\n\n"
                + "**finish()**\n"
                + "- 结束生成，返回所有已生成的分镜\n\n"
                + "## 输出格式（严格遵守）\n\n"
                + "每轮你必须输出两行：\n"
                + "Thought: 你的导演推理过程（分析叙事阶段、节奏、对话密度）\n"
                + "Action: tool名称(参数)\n\n"
                + "你会在下一步收到 Observation（工具执行结果），根据 Observation 继续推理。\n\n"
                + "## 叙事导演职责（最高优先级）\n\n"
                + "你不只是在\"分配时长\"，你是一位叙事导演。在每轮 Thought 中，你必须思考：\n\n"
                + "1. **叙事阶段**：当前处于哪个叙事阶段？\n"
                + "   - 开场钩子（前10%时长）：强力视觉冲击，dialogue sparse(≤30%)\n"
                + "   - 铺垫发展（20-30%）：建立情境，dialogue moderate(≤50%)\n"
                + "   - 冲突升级（25-35%）：矛盾激化，dialogue dense(≤70%)\n"
                + "   - 高潮爆发（15-25%）：视觉爆发，dialogue dense(≤70%)\n"
                + "   - 收束悬念（10-15%）：回归画面，dialogue sparse(≤30%)\n\n"
                + "2. **对话密度控制**：本段应该有多少镜头有 dialogue？\n"
                + "   - sparse: ≤30% 有 dialogue（开场/收束，视觉优先）\n"
                + "   - moderate: ≤50% 有 dialogue（铺垫，对话和画面交替）\n"
                + "   - dense: ≤70% 有 dialogue（冲突/高潮，但必须保留反应镜头）\n"
                + "   - **整体上限：不超过 60% 的镜头有 dialogue**\n\n"
                + "3. **对话长度约束**：每个镜头的 dialogue 必须匹配 duration\n"
                + "   - 1秒镜头：≤5字\n"
                + "   - 2秒镜头：≤8字\n"
                + "   - 3秒镜头：≤15字\n"
                + "   - 4秒镜头：≤20字\n\n"
                + "4. **画面呼吸**：不是每个镜头都需要 dialogue\n"
                + "   - 动作场面：至少 50% 纯画面镜头\n"
                + "   - 反应镜头：纯画面展示角色情绪反应\n"
                + "   - 环境镜头：展示场景氛围，无 dialogue\n"
                + "   - 过渡镜头：场景切换时的视觉过渡\n\n"
                + "5. **节奏变化**：不要连续相同时长的镜头\n"
                + "   - 快切段（冲突/动作）：多用 1-2 秒镜头\n"
                + "   - 情绪段（铺垫/高潮）：用 3-4 秒镜头\n"
                + "   - 相邻 3 个镜头不能全部相同时长\n\n"
                + "## generate_shots 参数格式\n\n"
                + "beatDescription 应包含完整导演指令：\n"
                + "[阶段名] 爽点描述 [对话密度:sparse/moderate/dense,≤XX%有对话] [叙事目标:xxx] [至少N个纯画面镜头]\n\n"
                + "示例：\n"
                + "Thought: 目标120秒，已生成0秒。根据叙事规划，先进入开场钩子阶段（爽点1-3），需要强力视觉冲击。对话密度 sparse（≤30%），让画面说话。分配15秒。\n"
                + "Action: generate_shots([开场钩子] 爽点1:悲壮画面 → 爽点2:背叛反转 → 爽点3:重生睁眼 [对话密度:sparse,≤30%有对话] [叙事目标:视觉冲击→悬念建立] [至少3个纯画面镜头], 15)\n\n"
                + "## 约束\n\n"
                + "- 目标时长的 2/3 是下限，4/3 是上限\n"
                + "- 每次调用 generate_shots 分配 10-25 秒\n"
                + "- 观察到累计时长 >= 目标 × 4/3 时，必须调用 finish()\n"
                + "- 观察到累计时长 >= 目标 × 2/3 时，可以调用 finish()\n"
                + "- 不要一次分配太多秒数，分多次调用更可控\n"
                + "- 保持叙事连贯性，每次生成要和上批衔接\n"
                + "- 严格参考叙事规划中的阶段分配和对话密度要求";
    }

    /**
     * 构建 ReAct 的初始 user prompt（含叙事规划）
     */
    String buildReactUserPrompt(String episodeContent, String characters, String visualStyle,
                                  int targetDuration, NarrativePlan plan) {
        List<String> hooks = extractHookBeats(episodeContent);
        int minDuration = targetDuration * 2 / 3;
        int maxDuration = targetDuration * 4 / 3;
        int maxBeats = maxDuration / 3;

        StringBuilder sb = new StringBuilder();

        // 叙事规划摘要
        if (plan != null && plan.phases != null && !plan.phases.isEmpty()) {
            sb.append("## 叙事规划（必须严格参考）\n\n");
            sb.append("整体故事弧线：").append(plan.storyArcSummary).append("\n\n");
            sb.append("叙事阶段分配：\n");
            for (int i = 0; i < plan.phases.size(); i++) {
                NarrativePhase phase = plan.phases.get(i);
                sb.append(i + 1).append(". **").append(phase.name).append("**（爽点")
                  .append(phase.startBeat).append("-").append(phase.endBeat)
                  .append("，").append(phase.allocatedSeconds).append("秒）")
                  .append(" 对话密度:").append(phase.dialogueDensity)
                  .append(" 情绪:").append(phase.emotionalArc).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 任务\n");
        sb.append("- 目标总时长：").append(targetDuration).append(" 秒（可接受范围 ").append(minDuration).append("~").append(maxDuration).append(" 秒）\n");
        sb.append("- 剧本共 ").append(hooks.size()).append(" 个爽点，目标覆盖 ").append(maxBeats).append(" 个\n");
        sb.append("- 每次调用 generate_shots 分配 10-25 秒\n\n");

        sb.append("## 剧本中的爽点\n");
        for (int i = 0; i < hooks.size(); i++) {
            sb.append(i + 1).append(". ").append(hooks.get(i)).append("\n");
        }
        sb.append("\n");

        sb.append("## 剧本内容\n").append(episodeContent).append("\n\n");
        sb.append("## 角色名单（characters 字段必须使用以下全名，禁止使用「主角」「反派」等泛称）\n");
        sb.append(characters).append("\n\n");
        sb.append("## 视觉风格\n").append(visualStyle).append("\n\n");
        sb.append("请按照叙事规划的阶段分配开始工作。");

        return sb.toString();
    }

    // ==================== ReAct Loop（爽剧模式核心） ====================

    /**
     * 解析模型输出中的 Thought 和 Action
     */
    ReactOutput parseReactOutput(String modelOutput) {
        String thought = "";
        String action = "";
        String actionArgs = "";

        Matcher thoughtM = THOUGHT_PATTERN.matcher(modelOutput);
        if (thoughtM.find()) {
            thought = thoughtM.group(1).trim();
        }

        Matcher actionM = ACTION_PATTERN.matcher(modelOutput);
        if (actionM.find()) {
            action = actionM.group(1).trim();
            actionArgs = actionM.group(2).trim();
        }

        // 如果没有匹配到格式，兜底为 continue（让模型继续思考）
        if (action.isEmpty()) {
            action = "continue";
        }

        return new ReactOutput(thought, action, actionArgs);
    }

    /**
     * 爽剧模式：ReAct 循环（含叙事规划）
     */
    private List<Map<String, Object>> generateReAct(String episodeContent, String characters,
                                                     int targetDuration, String visualStyle,
                                                     String scriptStyle) {
        log.info("[StoryboardAgent] ReAct 模式启动: target={}s", targetDuration);

        AgentState state = new AgentState();
        state.targetDuration = targetDuration;

        // === 叙事规划 ===
        List<String> hooks = extractHookBeats(episodeContent);
        NarrativePlan plan = buildNarrativePlan(episodeContent, hooks, targetDuration, characters, scriptStyle, false);
        state.narrativePlan = plan;

        String systemPrompt = buildReactSystemPrompt();
        String executorSystem = buildExecutorSystemPrompt(false, scriptStyle);

        // 对话历史：累积所有 Thought/Action/Observation
        StringBuilder conversation = new StringBuilder();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            String roundLabel = "轮次" + (round + 1);

            // 硬性上限守卫
            int maxDuration = targetDuration * 4 / 3;
            if (state.accumulatedDuration >= maxDuration) {
                log.info("[StoryboardAgent] 时长达上限（{}s >= {}s），强制结束",
                        state.accumulatedDuration, maxDuration);
                break;
            }

            // ---- Step 1: 模型推理 (Thought + Action) ----
            String userMsg;
            if (round == 0) {
                userMsg = buildReactUserPrompt(episodeContent, characters, visualStyle, targetDuration, plan);
            } else {
                // 后续轮次：包含叙事阶段信息
                String phaseInfo = getCurrentPhaseInfo(state, plan);
                userMsg = "## 当前进度\n"
                        + "- 已生成时长：" + state.accumulatedDuration + " 秒\n"
                        + "- 已覆盖段落：" + state.coveredBeats.size() + " 个\n"
                        + "- 目标：" + targetDuration + " 秒（范围 " + (targetDuration * 2 / 3) + "~" + maxDuration + " 秒）\n"
                        + phaseInfo + "\n"
                        + "## 之前的历史\n" + conversation + "\n\n"
                        + "请继续工作。";
            }

            String modelOutput;
            try {
                modelOutput = reasoner.generate(systemPrompt, userMsg);
            } catch (Exception e) {
                log.error("[StoryboardAgent] {} 模型调用失败: {}", roundLabel, e.getMessage());
                break;
            }

            ReactOutput react = parseReactOutput(modelOutput);
            log.info("[StoryboardAgent] {} Thought: {}", roundLabel, react.thought);
            log.info("[StoryboardAgent] {} Action: {}({})", roundLabel, react.action, react.actionArgs);

            // ---- Step 2: 执行 Action ----
            if ("finish".equals(react.action)) {
                int minDuration = targetDuration * 2 / 3;
                if (state.accumulatedDuration < minDuration && round < MAX_ROUNDS - 1) {
                    log.info("[StoryboardAgent] {} finish 被拒绝：时长不足（{}s < {}s）", roundLabel,
                            state.accumulatedDuration, minDuration);
                    conversation.append("Thought: ").append(react.thought).append("\n")
                            .append("Action: finish()\n")
                            .append("Observation: 拒绝执行 finish，时长不足。当前 ").append(state.accumulatedDuration)
                            .append(" 秒 < 最低 ").append(minDuration).append(" 秒。请继续 generate_shots。\n\n");
                    continue;
                }
                log.info("[StoryboardAgent] {} 结束生成", roundLabel);
                break;
            }

            if ("generate_shots".equals(react.action)) {
                // 解析参数
                String[] args = react.actionArgs.split(",\\s*", 2);
                String beatDescription = args.length > 0 ? args[0].trim() : "";
                int estimatedSeconds = args.length > 1 ? parseIntSafe(args[1].trim(), 15) : 15;

                // 调用 Executor 生成分镜（传入叙事规划）
                List<Map<String, Object>> batchShots = callExecutor(
                        executorSystem, beatDescription, characters, visualStyle,
                        estimatedSeconds, state.lastShots, scriptStyle,
                        plan, state.currentPhaseIndex);

                if (batchShots == null || batchShots.isEmpty()) {
                    conversation.append("Thought: ").append(react.thought).append("\n")
                            .append("Action: generate_shots(").append(react.actionArgs).append(")\n")
                            .append("Observation: 分镜生成失败，请调整参数重试。\n\n");
                    continue;
                }

                accumulateShots(state, batchShots, beatDescription);
                updateCurrentPhase(state, plan);
                int batchSeconds = batchShots.stream()
                        .mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();

                // 质量检查
                String qualityReport = checkBatchQuality(batchShots, plan, state.currentPhaseIndex, false);

                String observation = "Observation: 成功生成 " + batchShots.size() + " 个分镜，"
                        + "本段 " + batchSeconds + " 秒，累计 " + state.accumulatedDuration + " 秒，"
                        + "已覆盖 " + state.coveredBeats.size() + " 个段落。\n"
                        + qualityReport;

                log.info("[StoryboardAgent] {} {}", roundLabel, observation);

                conversation.append("Thought: ").append(react.thought).append("\n")
                        .append("Action: generate_shots(").append(react.actionArgs).append(")\n")
                        .append(observation).append("\n\n");
            } else {
                // continue 或未知 action，让模型重试
                conversation.append("(模型输出未包含有效 Action，请重新输出 Thought + Action)\n\n");
            }
        }

        log.info("[StoryboardAgent] ReAct 完成: 总分镜={}, 总时长={}秒, 轮次={}",
                state.allShots.size(), state.accumulatedDuration, state.coveredBeats.size());

        return state.allShots;
    }

    /**
     * 调用 Executor 生成分镜（含重试，带叙事规划）
     */
    private List<Map<String, Object>> callExecutor(String executorSystem, String beatDescription,
                                                    String characters, String visualStyle,
                                                    int estimatedSeconds,
                                                    List<Map<String, Object>> lastShots,
                                                    String scriptStyle,
                                                    NarrativePlan plan, int currentPhaseIndex) {
        String executorUser = buildExecutorPrompt(
                beatDescription, characters, visualStyle,
                estimatedSeconds, lastShots, false, scriptStyle,
                plan, currentPhaseIndex);

        for (int retry = 0; retry <= MAX_EXECUTOR_RETRIES; retry++) {
            try {
                String executorOutput = executor.generate(executorSystem, executorUser);
                List<Map<String, Object>> shots = parseShotArray(executorOutput);
                if (shots != null && !shots.isEmpty()) return shots;
            } catch (Exception e) {
                log.warn("[StoryboardAgent] Executor 失败, 重试 {}/{}: {}",
                        retry + 1, MAX_EXECUTOR_RETRIES, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 调用 Executor 生成分镜（含重试，无叙事规划，向后兼容）
     */
    private List<Map<String, Object>> callExecutor(String executorSystem, String beatDescription,
                                                    String characters, String visualStyle,
                                                    int estimatedSeconds,
                                                    List<Map<String, Object>> lastShots,
                                                    String scriptStyle) {
        return callExecutor(executorSystem, beatDescription, characters, visualStyle,
                estimatedSeconds, lastShots, scriptStyle, null, -1);
    }

    // ==================== Plan 模式（兜底，不再主用） ====================

    String buildPlanSystemPrompt() {
        return "你是一个爽剧分镜规划 agent。根据剧本和目标时长，输出完整时间分配计划。\n\n"
                + "输出纯 JSON：{\"totalSeconds\":120,\"segments\":[{\"id\":1,\"beatDescription\":\"...\",\"allocatedSeconds\":30,\"narrativeRole\":\"...\"}]}\n\n"
                + "规则：\n"
                + "- 所有段落的 allocatedSeconds 之和 = 目标时长±10%\n"
                + "- 4-8 个段落，每段 10-25 秒\n"
                + "- 每段包含连续的爽点，保持叙事连贯性";
    }

    String buildPlanUserPrompt(String episodeContent, int targetDuration, String characters) {
        List<String> hooks = extractHookBeats(episodeContent);
        StringBuilder sb = new StringBuilder();
        sb.append("目标时长：").append(targetDuration).append(" 秒\n");
        sb.append("爽点（共").append(hooks.size()).append("个）：\n");
        for (int i = 0; i < hooks.size(); i++) sb.append(i + 1).append(". ").append(hooks.get(i)).append("\n");
        sb.append("\n剧本：").append(episodeContent).append("\n角色：").append(characters).append("\n\n请输出计划 JSON。");
        return sb.toString();
    }

    List<PlanSegment> parsePlan(String json) {
        try {
            String cleaned = cleanJson(json);
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode segs = root.has("segments") ? root.get("segments") : null;
            if (segs == null || !segs.isArray() || segs.isEmpty()) return null;

            List<PlanSegment> result = new ArrayList<>();
            for (JsonNode seg : segs) {
                result.add(new PlanSegment(
                        seg.has("id") ? seg.get("id").asInt(result.size() + 1) : result.size() + 1,
                        seg.has("beatDescription") ? seg.get("beatDescription").asText("") : "",
                        seg.has("allocatedSeconds") ? seg.get("allocatedSeconds").asInt(15) : 15,
                        seg.has("narrativeRole") ? seg.get("narrativeRole").asText("") : ""
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("[StoryboardAgent] Plan 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private List<PlanSegment> buildFallbackPlan(String episodeContent, int targetDuration) {
        List<String> hooks = extractHookBeats(episodeContent);
        int segCount = Math.max(4, Math.min(8, hooks.size() / 5));
        int secPerSeg = targetDuration / segCount;
        int beatsPerSeg = hooks.size() / segCount;
        List<PlanSegment> segs = new ArrayList<>();
        for (int i = 0; i < segCount; i++) {
            int start = i * beatsPerSeg;
            int end = (i == segCount - 1) ? hooks.size() : (i + 1) * beatsPerSeg;
            StringBuilder desc = new StringBuilder();
            for (int j = start; j < end; j++) {
                if (j > start) desc.append(" → ");
                desc.append("爽点").append(j + 1).append(":").append(hooks.get(j));
            }
            segs.add(new PlanSegment(i + 1, desc.toString(), secPerSeg, ""));
        }
        return segs;
    }

    // ==================== Prompt（标准模式） ====================

    String buildReasonerSystemPrompt(boolean comicMode, String scriptStyle) {
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            return buildReactSystemPrompt();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个分镜叙事导演 agent。你不只是规划剧情覆盖，更要像导演一样思考叙事节奏。\n\n");
        if (comicMode) sb.append("本集为漫剧解说模式：叙事由旁白口播主导，每个分镜必须有 narration 或 dialogue。\n\n");
        sb.append("## 叙事导演职责\n\n");
        sb.append("1. **叙事阶段分析**：分析剧情结构，识别当前叙事阶段\n");
        sb.append("   - 开场建立：引入情境/人物，dialogue moderate(≤50%)\n");
        sb.append("   - 发展铺陈：推进关系/揭示信息，dialogue moderate(≤50%)\n");
        sb.append("   - 冲突升级：矛盾激化，节奏加快，dialogue dense(≤70%)\n");
        sb.append("   - 高潮爆发：核心对决/揭露，视觉和情绪并重，dialogue dense(≤70%)\n");
        sb.append("   - 收束结尾：情绪沉淀/悬念，dialogue sparse(≤30%)\n\n");
        sb.append("2. **对话密度控制**：在 nextBeatDescription 中标注对话密度要求\n");
        sb.append("   - sparse: ≤30% 有 dialogue\n");
        sb.append("   - moderate: ≤50% 有 dialogue\n");
        sb.append("   - dense: ≤70% 有 dialogue\n");
        sb.append("   - 每个镜头 dialogue 字数约束：1s≤5字, 2s≤8字, 3s≤15字, 4s≤20字\n\n");
        sb.append("3. **节奏变化**：铺垫段稍慢（3-4秒镜头），冲突段加快（1-2秒镜头），高潮段爆发\n\n");
        sb.append("4. **画面呼吸**：不是每个镜头都需要 dialogue，保留反应镜头、环境镜头\n\n");
        if (comicMode) {
            sb.append("## 解说模式特殊职责\n");
            sb.append("- 每个分镜都需要 narration（旁白口播稿），narration 是叙事主体\n");
            sb.append("- dialogue 是点缀，仅在关键角色互动时使用\n");
            sb.append("- narration 长度同样受 duration 约束\n\n");
        }
        sb.append("## 原有职责\n\n");
        sb.append("1. 分析剩余剧情内容和已生成进度\n");
        sb.append("2. 决定下一批应覆盖哪个剧情段落\n");
        sb.append("3. 估算该段落需要多少秒\n");
        sb.append("4. 当所有剧情覆盖完毕后，如果时长不足可以选择 expand（扩展已有节点）或 pad（填充氛围镜头）\n\n");
        sb.append("约束：\n");
        sb.append("- 每个分镜 1-4 秒\n");
        sb.append("- 优先完整覆盖所有剧情节点\n");
        sb.append("- 保持叙事连贯性，每批之间需要衔接\n\n");
        sb.append("输出纯 JSON（不要 markdown 代码块标记，不要在值中额外嵌套引号）：\n");
        sb.append("{\n");
        sb.append("  \"action\": \"generate|expand|pad|done\",\n");
        sb.append("  \"nextBeatDescription\": \"下一批覆盖的剧情内容摘要（含叙事阶段和对话密度标注）\",\n");
        sb.append("  \"estimatedSeconds\": 40,\n");
        sb.append("  \"targetBeatIndex\": 2,\n");
        sb.append("  \"reasoning\": \"叙事阶段分析和导演决策理由\"\n");
        sb.append("}\n\n");
        sb.append("action 说明：\n");
        sb.append("- generate: 还有剧情未覆盖，继续生成\n");
        sb.append("- expand: 剧情覆盖完毕但时长不足，回头扩展已有节点（需指定 targetBeatIndex）\n");
        sb.append("- pad: 生成过渡/氛围镜头填充时长\n");
        sb.append("- done: 剧情已完整覆盖，结束生成\n");
        return sb.toString();
    }

    public String buildReasonerPrompt(String episodeContent, String characters, String visualStyle,
                                       AgentState state, String roundLabel, boolean comicMode,
                                       String narrationPerspective, String scriptStyle) {
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            return buildReactUserPrompt(episodeContent, characters, visualStyle, state.targetDuration, state.narrativePlan);
        }

        return buildReasonerPrompt(episodeContent, characters, visualStyle, state, roundLabel,
                comicMode, narrationPerspective, scriptStyle, state.narrativePlan);
    }

    /** 带 NarrativePlan 参数的版本 */
    public String buildReasonerPrompt(String episodeContent, String characters, String visualStyle,
                                       AgentState state, String roundLabel, boolean comicMode,
                                       String narrationPerspective, String scriptStyle,
                                       NarrativePlan plan) {
        int minDuration = state.targetDuration * 2 / 3;
        int maxDuration = state.targetDuration * 4 / 3;

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态\n");
        sb.append("- 目标总时长：").append(state.targetDuration).append(" 秒（范围 ").append(minDuration).append("~").append(maxDuration).append(" 秒）\n");
        sb.append("- 已生成时长：").append(state.accumulatedDuration).append(" 秒\n");
        sb.append("- 轮次：").append(roundLabel).append("（最多 ").append(MAX_ROUNDS).append(" 轮）\n\n");

        // 叙事规划信息
        if (plan != null && plan.phases != null && !plan.phases.isEmpty()) {
            sb.append("## 叙事规划\n\n");
            sb.append("整体弧线：").append(plan.storyArcSummary).append("\n");
            sb.append("当前阶段：").append(getCurrentPhaseInfo(state, plan)).append("\n\n");
        }

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

        sb.append("## 角色\n").append(characters).append("（characters 字段必须使用上述全名）\n");

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

    // ==================== Executor Prompt ====================

    String buildExecutorSystemPrompt(boolean comicMode, String scriptStyle) {
        StringBuilder base = new StringBuilder();
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base.append("你是一位爽剧分镜师。节奏极快，三秒一个爽点。\n\n");
        } else {
            base.append(comicMode ? "你是一位漫剧解说分镜师。\n\n" : "你是一位专业分镜师。\n\n");
        }

        base.append("输出纯 JSON 数组。每个分镜：\n");
        base.append("shotNumber, duration(1-4秒), scene, characters, shotSize, cameraAngle, cameraMovement,\n");
        base.append("sceneDescription, dialogue, speaker, dialogueTone, visualEffects, audioEffects, transitionHint");
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base.append(", hookPoint");
        }
        if (comicMode) {
            base.append(", narration");
        }
        base.append("\n\n");

        base.append("【characters 字段 - 极其重要】\n");
        base.append("characters 字段必须使用用户消息中给出的角色全名，禁止使用「主角」「反派」「配角」「路人」等泛称。\n");
        base.append("例如用户消息中角色为「林晓星」，则 characters 必须写 [\"林晓星\"]，不能写 [\"主角\"]。\n");
        base.append("如果某个分镜中没有已命名的角色，写空数组 []。\n\n");

        // ========== 通用对话约束 ==========
        base.append("【对话约束 - 硬性要求，违反即为失败】\n\n");
        base.append("1. 不是每个镜头都需要 dialogue\n");
        base.append("   - dialogue 填 \"\" 或 \"无\" 的镜头是完全正常的，甚至是必要的\n");
        base.append("   - 纯画面镜头类型：反应镜头（角色表情变化）、环境镜头（场景氛围）、动作镜头（视觉冲击）、过渡镜头（场景切换）\n");
        base.append("   - 目标：约 40-60% 的镜头有 dialogue，其余为纯画面叙事\n\n");
        base.append("2. dialogue 字数必须匹配 duration（正常语速约 4-5 字/秒）\n");
        base.append("   - duration=1秒：dialogue ≤ 5 字\n");
        base.append("   - duration=2秒：dialogue ≤ 8 字\n");
        base.append("   - duration=3秒：dialogue ≤ 15 字\n");
        base.append("   - duration=4秒：dialogue ≤ 20 字\n");
        base.append("   - 超出此范围的 dialogue 视为生成失败\n\n");
        base.append("3. 对话分布原则\n");
        base.append("   - 连续 3 个镜头不能都有 dialogue（至少穿插 1 个纯画面镜头）\n");
        base.append("   - 动作/打斗场面：dialogue 应少，用画面讲故事\n");
        base.append("   - 情感高潮：可以有一句有力的 dialogue，但周围应有反应镜头\n\n");
        base.append("4. 当 dialogue 为空时\n");
        base.append("   - speaker 填 \"无\"\n");
        base.append("   - dialogueTone 填 \"无\"\n");
        base.append("   - sceneDescription 应更详细，用画面替代文字叙事\n\n");

        // ========== 爽剧模式追加 ==========
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            base.append("【爽剧模式】\n");
            base.append("- hookPoint 字段必须填写，标注本镜头的爽点类型（如\"实力碾压\"\"身份反转\"\"视觉冲击\"等）\n");
            base.append("- 内心独白也算 dialogue，但字数同样受 duration 约束\n\n");
        }

        // ========== 解说模式追加 ==========
        if (comicMode) {
            base.append("【解说模式特殊约束】\n");
            base.append("- 每个镜头必须有 narration 字段（旁白口播稿）\n");
            base.append("- narration 字数同样受 duration 约束：3秒≤15字，4秒≤20字\n");
            base.append("- narration 和 dialogue 不能同时存在（旁白时角色不说话，角色说话时无旁白）\n");
            base.append("- 当有 dialogue 时，narration 填 \"无\"\n");
            base.append("- narration 优先级高于 dialogue：关键剧情节点用 narration 推进，角色对话是点缀\n\n");
        }

        base.append("AI视频原则：单主体、慢动作。");
        return base.toString();
    }

    public String buildExecutorPrompt(String nextBeatDescription, String characters, String visualStyle,
                                       int estimatedSeconds, List<Map<String, Object>> lastShots,
                                       boolean comicMode, String scriptStyle,
                                       NarrativePlan plan, int currentPhaseIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("覆盖段落：").append(nextBeatDescription).append("\n");
        sb.append("目标时长：").append(estimatedSeconds).append(" 秒\n\n");
        if (!lastShots.isEmpty()) {
            sb.append("上批末尾分镜（用于衔接）：\n");
            for (Map<String, Object> s : lastShots) {
                sb.append("- 第").append(s.get("shotNumber")).append("镜[").append(s.get("duration")).append("秒] ")
                  .append(s.getOrDefault("scene", s.getOrDefault("sceneDescription", ""))).append("\n");
            }
            sb.append("\n");
        }

        // 叙事上下文
        if (plan != null && plan.phases != null && currentPhaseIndex >= 0 && currentPhaseIndex < plan.phases.size()) {
            NarrativePhase phase = plan.phases.get(currentPhaseIndex);
            sb.append("## 叙事上下文\n\n");
            sb.append("当前叙事阶段：").append(phase.name).append("\n");
            sb.append("阶段情绪走向：").append(phase.emotionalArc).append("\n");
            sb.append("对话密度要求：").append(phase.dialogueDensity).append("（")
              .append(getDialogueDensityPercent(phase.dialogueDensity)).append("的镜头可以有 dialogue）\n");
            sb.append("对话长度上限：").append(phase.maxDialogueChars).append("\n");
            sb.append("节拍指导：").append(phase.pacingNote).append("\n\n");

            // 前序阶段摘要
            if (currentPhaseIndex > 0) {
                sb.append("前序阶段：");
                for (int i = 0; i < currentPhaseIndex; i++) {
                    if (i > 0) sb.append(" → ");
                    sb.append(plan.phases.get(i).name).append("(").append(plan.phases.get(i).emotionalArc).append(")");
                }
                sb.append("\n");
            }
            // 后续阶段预告
            if (currentPhaseIndex < plan.phases.size() - 1) {
                sb.append("后续阶段：");
                for (int i = currentPhaseIndex + 1; i < plan.phases.size(); i++) {
                    if (i > currentPhaseIndex + 1) sb.append(" → ");
                    sb.append(plan.phases.get(i).name).append("(").append(plan.phases.get(i).emotionalArc).append(")");
                }
                sb.append("\n\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append("角色（characters 字段必须使用以下全名，禁止写「主角」「反派」等泛称）：\n");
        sb.append(characters).append("\n\n");
        sb.append("风格：").append(visualStyle).append("\n");
        sb.append("请严格按照对话密度和长度约束生成分镜 JSON 数组。");
        return sb.toString();
    }

    /** 保留向后兼容的无 plan 参数版本 */
    public String buildExecutorPrompt(String nextBeatDescription, String characters, String visualStyle,
                                       int estimatedSeconds, List<Map<String, Object>> lastShots,
                                       boolean comicMode, String scriptStyle) {
        return buildExecutorPrompt(nextBeatDescription, characters, visualStyle,
                estimatedSeconds, lastShots, comicMode, scriptStyle, null, -1);
    }

    private String getDialogueDensityPercent(String density) {
        if (density == null) return "≤50%";
        switch (density) {
            case "sparse": return "≤30%";
            case "moderate": return "≤50%";
            case "dense": return "≤70%";
            default: return "≤50%";
        }
    }

    // ==================== 状态管理 ====================

    public void accumulateShots(AgentState state, List<Map<String, Object>> shots, String beatDescription) {
        if (shots == null || shots.isEmpty()) return;
        for (Map<String, Object> shot : shots) {
            int d = Math.max(1, Math.min(4, ((Number) shot.get("duration")).intValue()));
            shot.put("duration", d);
        }
        int t = state.accumulatedDuration;
        for (int i = 0; i < shots.size(); i++) {
            Map<String, Object> shot = shots.get(i);
            shot.put("globalShotNumber", state.allShots.size() + i + 1);
            shot.put("startTime", t);
            t += ((Number) shot.get("duration")).intValue();
            shot.put("endTime", t);
        }
        state.allShots.addAll(shots);
        state.accumulatedDuration = t;
        if (beatDescription != null && !beatDescription.isEmpty()) state.coveredBeats.add(beatDescription);
        state.lastShots.clear();
        int start = Math.max(0, shots.size() - LAST_SHOTS_COUNT);
        for (int i = start; i < shots.size(); i++) state.lastShots.add(shots.get(i));
    }

    /**
     * 更新当前叙事阶段索引：根据累计时长判断是否应进入下一阶段
     */
    private void updateCurrentPhase(AgentState state, NarrativePlan plan) {
        if (plan == null || plan.phases == null || plan.phases.isEmpty()) return;
        int accumulated = 0;
        for (int i = 0; i < plan.phases.size(); i++) {
            accumulated += plan.phases.get(i).allocatedSeconds;
            if (state.accumulatedDuration < accumulated) {
                state.currentPhaseIndex = i;
                return;
            }
        }
        state.currentPhaseIndex = plan.phases.size() - 1;
    }

    /**
     * 获取当前叙事阶段信息字符串
     */
    private String getCurrentPhaseInfo(AgentState state, NarrativePlan plan) {
        if (plan == null || plan.phases == null || plan.phases.isEmpty()) return "";
        int idx = Math.min(state.currentPhaseIndex, plan.phases.size() - 1);
        NarrativePhase phase = plan.phases.get(idx);
        return phase.name + "（对话密度:" + phase.dialogueDensity + "，情绪:" + phase.emotionalArc + "）";
    }

    // ==================== 质量检查 ====================

    /**
     * 检查本批次分镜的对话质量和密度
     * 返回质量报告字符串
     */
    String checkBatchQuality(List<Map<String, Object>> shots, NarrativePlan plan, int phaseIndex, boolean comicMode) {
        int dialogueCount = 0;
        int overLengthCount = 0;
        int narrationMissingCount = 0;
        List<String> issues = new ArrayList<>();

        for (Map<String, Object> shot : shots) {
            String dialogue = shot.get("dialogue") != null ? shot.get("dialogue").toString() : "";
            int duration = shot.get("duration") != null ? ((Number) shot.get("duration")).intValue() : 3;

            boolean hasDialogue = dialogue != null && !dialogue.isEmpty() && !"无".equals(dialogue) && !"\"无\"".equals(dialogue);
            if (hasDialogue) {
                dialogueCount++;
                int maxChars = getMaxDialogueChars(duration);
                if (dialogue.length() > maxChars) {
                    overLengthCount++;
                    issues.add("第" + shot.get("shotNumber") + "镜 dialogue " + dialogue.length()
                            + "字超过" + duration + "秒上限" + maxChars + "字");
                }
            }

            // 解说模式：检查 narration
            if (comicMode) {
                String narration = shot.get("narration") != null ? shot.get("narration").toString() : "";
                if (narration.isEmpty() || "无".equals(narration)) {
                    // 如果也没有 dialogue，则缺少 narration
                    if (!hasDialogue) {
                        narrationMissingCount++;
                    }
                } else {
                    int maxChars = getMaxDialogueChars(duration);
                    if (narration.length() > maxChars) {
                        issues.add("第" + shot.get("shotNumber") + "镜 narration " + narration.length()
                                + "字超过" + duration + "秒上限" + maxChars + "字");
                    }
                }
            }
        }

        double dialogueRatio = shots.size() > 0 ? (double) dialogueCount / shots.size() : 0;
        double maxRatio = 0.6; // 整体上限
        if (plan != null && phaseIndex >= 0 && phaseIndex < plan.phases.size()) {
            maxRatio = getDialogueRatio(plan.phases.get(phaseIndex).dialogueDensity);
        }

        StringBuilder report = new StringBuilder();
        report.append("【质量报告】");
        report.append("对话密度:").append(String.format("%.0f%%", dialogueRatio * 100));
        report.append("(上限").append(String.format("%.0f%%", maxRatio * 100)).append(")");

        if (dialogueRatio > maxRatio) {
            issues.add("对话密度" + String.format("%.0f%%", dialogueRatio * 100)
                    + "超过上限" + String.format("%.0f%%", maxRatio * 100));
        }
        if (overLengthCount > 0) {
            issues.add(overLengthCount + "个镜头dialogue超长");
        }
        if (comicMode && narrationMissingCount > 0) {
            issues.add(narrationMissingCount + "个镜头缺少narration且无dialogue");
        }

        if (!issues.isEmpty()) {
            report.append(" 问题:").append(String.join("; ", issues));
        } else {
            report.append(" 通过");
        }

        return report.toString();
    }

    private int getMaxDialogueChars(int duration) {
        switch (duration) {
            case 1: return 5;
            case 2: return 8;
            case 3: return 15;
            case 4: return 20;
            default: return 20;
        }
    }

    private double getDialogueRatio(String density) {
        if (density == null) return 0.5;
        switch (density) {
            case "sparse": return 0.3;
            case "moderate": return 0.5;
            case "dense": return 0.7;
            default: return 0.5;
        }
    }

    // ==================== 主入口 ====================

    public List<Map<String, Object>> generate(String episodeContent, String characters,
                                               int targetDuration, String visualStyle,
                                               boolean comicMode, String narrationPerspective,
                                               String scriptStyle) {
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            return generateReAct(episodeContent, characters, targetDuration, visualStyle, scriptStyle);
        }
        return generateIterative(episodeContent, characters, targetDuration, visualStyle, comicMode, narrationPerspective, scriptStyle);
    }

    // ==================== 标准模式迭代 ====================

    private List<Map<String, Object>> generateIterative(String episodeContent, String characters,
                                                         int targetDuration, String visualStyle,
                                                         boolean comicMode, String narrationPerspective,
                                                         String scriptStyle) {
        AgentState state = new AgentState();
        state.targetDuration = targetDuration;

        // === 叙事规划 ===
        List<String> hooks = extractHookBeats(episodeContent);
        NarrativePlan plan = buildNarrativePlan(episodeContent, hooks, targetDuration, characters, scriptStyle, comicMode);
        state.narrativePlan = plan;

        String systemPrompt = buildReasonerSystemPrompt(comicMode, scriptStyle);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            String roundLabel = "第" + (round + 1) + "轮";

            // 硬性上限守卫（与 ReAct 模式一致）
            int maxDuration = targetDuration * 4 / 3;
            if (state.accumulatedDuration >= maxDuration) {
                log.info("[StoryboardAgent] {} 时长达上限（{}s >= {}s），强制结束", roundLabel, state.accumulatedDuration, maxDuration);
                break;
            }

            String userPrompt = buildReasonerPrompt(episodeContent, characters, visualStyle, state, roundLabel, comicMode, narrationPerspective, scriptStyle, plan);
            String reasonerOutput;
            try { reasonerOutput = reasoner.generate(systemPrompt, userPrompt); }
            catch (Exception e) { log.warn("[StoryboardAgent] Reasoner 失败: {}", e.getMessage()); break; }

            ReasonerDecision decision = parseReasonerDecision(reasonerOutput);
            log.info("[StoryboardAgent] {} action={}, beat='{}', est={}s", roundLabel, decision.action, decision.nextBeatDescription, decision.estimatedSeconds);

            if ("done".equals(decision.action)) {
                if (state.accumulatedDuration < targetDuration * 2 / 3 && round < MAX_ROUNDS - 1) {
                    log.info("[StoryboardAgent] {} done 但时长不足，继续", roundLabel);
                    decision = new ReasonerDecision("generate", "继续", 20, "强制继续", 0);
                } else { break; }
            }

            String executorSystem = buildExecutorSystemPrompt(comicMode, scriptStyle);
            String executorUser = buildExecutorPrompt(decision.nextBeatDescription, characters, visualStyle, decision.estimatedSeconds, state.lastShots, comicMode, scriptStyle, plan, state.currentPhaseIndex);
            List<Map<String, Object>> batchShots = null;
            for (int retry = 0; retry <= MAX_EXECUTOR_RETRIES; retry++) {
                try {
                    batchShots = parseShotArray(executor.generate(executorSystem, executorUser));
                    if (batchShots != null && !batchShots.isEmpty()) break;
                } catch (Exception e) { log.warn("[StoryboardAgent] Executor 重试 {}/{}: {}", retry + 1, MAX_EXECUTOR_RETRIES, e.getMessage()); }
            }
            if (batchShots == null || batchShots.isEmpty()) continue;
            accumulateShots(state, batchShots, decision.nextBeatDescription);
            updateCurrentPhase(state, plan);

            // 质量检查
            String qualityReport = checkBatchQuality(batchShots, plan, state.currentPhaseIndex, comicMode);
            log.info("[StoryboardAgent] {} 完成: {}镜, 累计{}s {}", roundLabel, batchShots.size(), state.accumulatedDuration, qualityReport);
        }
        log.info("[StoryboardAgent] 标准模式完成: {}镜, {}s", state.allShots.size(), state.accumulatedDuration);
        return state.allShots;
    }

    // ==================== JSON 工具 ====================

    /** 宽松 ObjectMapper：允许 LLM 常见的格式瑕疵 */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper();
    static {
        com.fasterxml.jackson.core.JsonFactory f = LENIENT_MAPPER.getFactory();
        LENIENT_MAPPER.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        LENIENT_MAPPER.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        LENIENT_MAPPER.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
        LENIENT_MAPPER.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        LENIENT_MAPPER.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseShotArray(String json) {
        // 第1层：宽松 ObjectMapper + cleanJson
        try {
            String cleaned = cleanJson(json);
            JsonNode root = LENIENT_MAPPER.readTree(cleaned);
            return extractShotsFromNode(root);
        } catch (Exception e) {
            log.warn("[StoryboardAgent] 宽松解析失败: {}", e.getMessage());
        }

        // 第2层：截断恢复（处理真正的输出截断）
        List<Map<String, Object>> recovered = truncationRecovery(json);
        if (recovered != null) {
            log.info("[StoryboardAgent] 截断恢复成功: {} 个分镜", recovered.size());
            return recovered;
        }

        // 第3层：逐对象正则提取（最终兜底）
        return regexExtractShots(json);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractShotsFromNode(JsonNode root) {
        if (root.isArray()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode n : root) {
                try { result.add(LENIENT_MAPPER.convertValue(n, Map.class)); }
                catch (Exception ignored) { /* 跳过坏元素 */ }
            }
            return result.isEmpty() ? null : result;
        }
        if (root.has("shots")) return extractShotsFromNode(root.get("shots"));
        if (root.has("panels")) {
            List<Map<String, Object>> flat = new ArrayList<>();
            for (JsonNode p : root.get("panels"))
                if (p.has("shots"))
                    for (JsonNode s : p.get("shots")) {
                        try { flat.add(LENIENT_MAPPER.convertValue(s, Map.class)); }
                        catch (Exception ignored) {}
                    }
            return flat.isEmpty() ? null : flat;
        }
        return null;
    }

    /**
     * 截断恢复：找到最后一个完整的 }，截断并补全数组
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> truncationRecovery(String raw) {
        try {
            String cleaned = cleanJson(raw);
            int arrStart = cleaned.indexOf('[');
            if (arrStart < 0) return null;
            int lastBrace = cleaned.lastIndexOf('}');
            if (lastBrace <= arrStart) return null;

            String truncated = cleaned.substring(arrStart, lastBrace + 1) + "]";
            JsonNode root = LENIENT_MAPPER.readTree(truncated);
            return extractShotsFromNode(root);
        } catch (Exception e) {
            log.warn("[StoryboardAgent] 截断恢复失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 最终兜底：用正则逐个提取 { ... } 对象，单独解析
     * 即使 JSON 整体结构损坏，也能抢救出完整的单个对象
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> regexExtractShots(String raw) {
        try {
            // 匹配 "shotNumber" 或 "shot_size" 等字段开头的对象
            java.util.regex.Pattern objPattern = java.util.regex.Pattern.compile(
                    "\\{[^{}]*\"shot[Nn]umber\"\\s*:\\s*\\d+[^{}]*\\}");
            java.util.regex.Matcher m = objPattern.matcher(raw);
            List<Map<String, Object>> result = new ArrayList<>();
            ObjectMapper lenient = LENIENT_MAPPER;
            while (m.find()) {
                try {
                    String objStr = m.group();
                    // 修复尾逗号
                    objStr = objStr.replaceAll(",\\s*}", "}");
                    result.add(lenient.readValue(objStr, Map.class));
                } catch (Exception ignored) {}
            }
            if (!result.isEmpty()) {
                log.info("[StoryboardAgent] 正则提取成功: {} 个分镜", result.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("[StoryboardAgent] 正则提取失败: {}", e.getMessage());
        }
        return null;
    }

    private String cleanJson(String raw) {
        String c = raw.trim();
        // 去掉 markdown 代码块
        if (c.startsWith("```")) { int a = c.indexOf('\n'), b = c.lastIndexOf("```"); if (a > 0 && b > a) c = c.substring(a + 1, b).trim(); }
        // 中文引号 → 空或普通引号
        c = c.replace("\u201c", "").replace("\u201d", "").replace("\u2018", "").replace("\u2019", "");
        // 修复字符串值中的意外引号断裂: "value"xxx" → "value xxx"
        c = c.replaceAll(":\\s*\"\\s+\"([^\"]*?)\"\"", ": \"$1\"");
        // 修复数组值被多余引号包裹: "key": "[...]"  → "key": [...]
        c = c.replaceAll("\"(\\w+)\"\\s*:\\s*\"(\\[[^\\]]*\\])\"", "\"$1\": $2");
        // 尾逗号
        c = c.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
        // 去掉 control characters (除 \n \r \t 外)
        c = c.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return c;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    // ==================== 数据类 ====================

    public static class ReasonerDecision {
        public final String action, nextBeatDescription, reasoning;
        public final int estimatedSeconds, targetBeatIndex;
        public ReasonerDecision(String action, String nextBeatDescription, int estimatedSeconds, String reasoning, int targetBeatIndex) {
            this.action = action; this.nextBeatDescription = nextBeatDescription; this.estimatedSeconds = estimatedSeconds; this.reasoning = reasoning; this.targetBeatIndex = targetBeatIndex;
        }
    }

    public static class AgentState {
        public int targetDuration, accumulatedDuration;
        public final List<String> coveredBeats = new ArrayList<>();
        public final List<Map<String, Object>> allShots = new ArrayList<>();
        public final List<Map<String, Object>> lastShots = new ArrayList<>();
        /** 叙事规划：阶段追踪 */
        public int currentPhaseIndex = 0;
        public NarrativePlan narrativePlan = null;
    }

    public static class PlanSegment {
        public final int id;
        public final String beatDescription, narrativeRole;
        public final int allocatedSeconds;
        public PlanSegment(int id, String beatDescription, int allocatedSeconds, String narrativeRole) {
            this.id = id; this.beatDescription = beatDescription; this.allocatedSeconds = allocatedSeconds; this.narrativeRole = narrativeRole;
        }
    }

    public static class ReactOutput {
        public final String thought, action, actionArgs;
        public ReactOutput(String thought, String action, String actionArgs) {
            this.thought = thought; this.action = action; this.actionArgs = actionArgs;
        }
    }

    // ==================== 叙事规划数据类 ====================

    public static class NarrativePhase {
        public String name;            // "开场钩子"/"铺垫"/"冲突升级"/"高潮"/"收束"
        public int startBeat;
        public int endBeat;
        public int allocatedSeconds;
        public String dialogueDensity; // "sparse"(≤30%)/"moderate"(≤50%)/"dense"(≤70%)
        public String maxDialogueChars;// "3秒镜头≤15字，4秒镜头≤20字"
        public String emotionalArc;    // 情绪走向
        public String pacingNote;      // 节拍指导

        public NarrativePhase() {}
        public NarrativePhase(String name, int startBeat, int endBeat, int allocatedSeconds,
                              String dialogueDensity, String maxDialogueChars,
                              String emotionalArc, String pacingNote) {
            this.name = name; this.startBeat = startBeat; this.endBeat = endBeat;
            this.allocatedSeconds = allocatedSeconds; this.dialogueDensity = dialogueDensity;
            this.maxDialogueChars = maxDialogueChars; this.emotionalArc = emotionalArc;
            this.pacingNote = pacingNote;
        }
    }

    public static class NarrativePlan {
        public List<NarrativePhase> phases;
        public String storyArcSummary;

        public NarrativePlan() { this.phases = new ArrayList<>(); }
        public NarrativePlan(List<NarrativePhase> phases, String storyArcSummary) {
            this.phases = phases; this.storyArcSummary = storyArcSummary;
        }
    }

    // ==================== 两阶段架构数据类 ====================

    public static class CharacterInScene {
        public String name;
        public String state;
        public String position;

        public CharacterInScene() {}
        public CharacterInScene(String name, String state, String position) {
            this.name = name; this.state = state; this.position = position;
        }
    }

    public static class StoryBeat {
        public int id;
        public String beat;
        public int duration;
        public String mood;
        public List<CharacterInScene> characters;

        public StoryBeat() {}
        public StoryBeat(int id, String beat, int duration, String mood, List<CharacterInScene> characters) {
            this.id = id; this.beat = beat; this.duration = duration;
            this.mood = mood; this.characters = characters;
        }
    }

    public static class StoryStructure {
        public String storyArc;
        public List<StoryBeat> beats;
        public List<Map<String, String>> transitions;

        public StoryStructure() { this.beats = new ArrayList<>(); this.transitions = new ArrayList<>(); }
    }
}
