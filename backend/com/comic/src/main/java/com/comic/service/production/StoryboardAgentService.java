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
 * 分镜 Agent 服务 — V2 两阶段架构
 *
 * Phase 1: LLM 结构分析（StoryStructure）
 * Phase 2: 代码拆 shot 骨架（纯代码，不调 LLM）
 * Phase 3: 全局导演规划（NarrativePlan）
 * Phase 4: 接续式分段精修（每段带前段上下文）
 *
 * 注意：Phase3 在 Phase2 之前执行，因为骨架生成需要 NarrativePlan 的阶段归属信息
 */
@Service
@Slf4j
public class StoryboardAgentService {

    private final DeepSeekTextService reasoner;
    private final DeepSeekTextService executor;
    private final ObjectMapper objectMapper;

    private static final int MAX_EXECUTOR_RETRIES = 2;
    private static final int LAST_SHOTS_COUNT = 3;
    private static final int REFINE_SEGMENT_SIZE = 15;

    private static final Pattern HOOK_PATTERN =
            Pattern.compile("[\\[【]\\s*爽点\\s*[:：]\\s*(.+?)\\s*[\\]】]");

    public StoryboardAgentService(@Qualifier("reasoner") DeepSeekTextService reasoner,
                                   @Qualifier("deepSeekTextService") DeepSeekTextService executor,
                                   ObjectMapper objectMapper) {
        this.reasoner = reasoner;
        this.executor = executor;
        this.objectMapper = objectMapper;
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

        // 预计算每个 beat 的 shot 数
        int[] beatShotCounts = new int[structure.beats.size()];
        for (int i = 0; i < structure.beats.size(); i++) {
            beatShotCounts[i] = Math.max(1, (int) Math.round((double) structure.beats.get(i).duration / 3.0));
        }

        // 追踪每个叙事阶段内的局部索引
        Map<String, Integer> phaseLocalCounter = new HashMap<>();

        for (int beatIdx = 0; beatIdx < structure.beats.size(); beatIdx++) {
            StoryBeat beat = structure.beats.get(beatIdx);
            int shotCount = beatShotCounts[beatIdx];

            int beatAllocated = 0;
            for (int i = 0; i < shotCount; i++) {
                Map<String, Object> skeleton = new HashMap<>();
                skeleton.put("shotNumber", shotNumber++);

                String phaseName = matchNarrativePhase(accumulatedSeconds, plan);
                skeleton.put("narrativePhase", phaseName);

                int phaseLocalIndex = phaseLocalCounter.getOrDefault(phaseName, 0);

                int duration;
                if (i == shotCount - 1) {
                    // 最后一个 shot: 补齐剩余时长
                    duration = Math.max(1, Math.min(5, beat.duration - beatAllocated));
                } else {
                    // 按叙事阶段节奏分配
                    int plannedDuration = allocateDuration(phaseLocalIndex, shotCount, phaseName, accumulatedSeconds);
                    // 确保不超过 beat 剩余时长（至少留 1s 给后续 shot）
                    int remaining = beat.duration - beatAllocated;
                    int minRemaining = (shotCount - i - 1);
                    duration = Math.max(1, Math.min(5, Math.min(plannedDuration, remaining - minRemaining)));
                }

                skeleton.put("duration", duration);
                skeleton.put("beatId", beat.id);
                skeleton.put("beatIndex", beatIdx);
                skeleton.put("sceneHint", beat.beat);
                skeleton.put("mood", beat.mood);

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
                beatAllocated += duration;
                phaseLocalCounter.put(phaseName, phaseLocalIndex + 1);
            }
        }

        log.info("[StoryboardAgent] Phase2 骨架生成: {} 个shot, 预估{}秒",
                skeletons.size(), accumulatedSeconds);
        return skeletons;
    }

    /**
     * 兜底骨架：Phase1 失败时均匀拆分，使用 allocateDuration 按叙事阶段节奏分配时长
     */
    List<Map<String, Object>> buildFallbackSkeletons(int targetDuration, String episodeContent,
                                                       String characters, NarrativePlan plan) {
        int shotCount = Math.max(3, (int) Math.round((double) targetDuration / 3.0));
        String[] parts = splitContentEvenly(episodeContent, shotCount);

        List<Map<String, Object>> skeletons = new ArrayList<>();
        int accumulatedSeconds = 0;
        String lastPhase = "";
        int phaseLocalIndex = 0;

        for (int i = 0; i < shotCount; i++) {
            Map<String, Object> skeleton = new HashMap<>();
            skeleton.put("shotNumber", i + 1);

            String phaseName = matchNarrativePhase(accumulatedSeconds, plan);

            // 追踪 phase 内局部索引
            if (!phaseName.equals(lastPhase)) {
                phaseLocalIndex = 0;
                lastPhase = phaseName;
            }

            int duration;
            int remaining = targetDuration - accumulatedSeconds;
            int shotsLeft = shotCount - i;
            if (shotsLeft == 1) {
                // 最后一个 shot: 补齐剩余时长
                duration = Math.max(1, Math.min(5, remaining));
            } else {
                int plannedDuration = allocateDuration(phaseLocalIndex, shotCount, phaseName, accumulatedSeconds);
                int avgNeeded = remaining / shotsLeft;
                // 当平均需要时长 > 5 时，用平均值代替 plannedDuration 以避免最后 shot 无法补齐
                int baseDuration = Math.max(plannedDuration, Math.min(5, avgNeeded));
                int minRemaining = shotsLeft - 1;
                duration = Math.max(1, Math.min(5, Math.min(baseDuration, remaining - minRemaining)));
            }

            skeleton.put("duration", duration);
            skeleton.put("beatId", 1);
            skeleton.put("beatIndex", 0);
            skeleton.put("sceneHint", parts[i]);
            skeleton.put("mood", "");
            skeleton.put("narrativePhase", phaseName);

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
            phaseLocalIndex++;
        }

        log.info("[StoryboardAgent] Phase2 兜底骨架: {} 个shot", shotCount);
        return skeletons;
    }

    // ==================== 时长节奏分配 ====================

    /**
     * 根据叙事阶段分配镜头时长（确定性，不使用随机数）
     */
    int allocateDuration(int phaseLocalIndex, int phaseLocalCount, String phaseName, int allocatedSeconds) {
        if (phaseName == null || phaseName.isEmpty()) return 3;

        // 收束悬念: 全部 3s, 最后一个给 5s
        if (phaseName.contains("收束") || phaseName.contains("悬念")) {
            return (phaseLocalIndex == phaseLocalCount - 1) ? 5 : 3;
        }
        // 开场钩子: 80% 用 2s, 20% 用 1s
        if (phaseName.contains("开场") || phaseName.contains("钩子")) {
            return (phaseLocalIndex % 5 == 4) ? 1 : 2;
        }
        // 铺垫发展: 交替 2s 和 3s
        if (phaseName.contains("铺垫") || phaseName.contains("发展")) {
            return (phaseLocalIndex % 2 == 0) ? 2 : 3;
        }
        // 冲突升级: 60% 用 2s, 40% 用 1s
        if (phaseName.contains("冲突") || phaseName.contains("升级")) {
            return (phaseLocalIndex % 5 < 2) ? 1 : 2;
        }
        // 高潮爆发: 每5个插一个4s升格, 其余交替1s/2s
        if (phaseName.contains("高潮") || phaseName.contains("爆发")) {
            if (phaseLocalIndex % 5 == 4) return 4;
            return (phaseLocalIndex % 2 == 0) ? 2 : 1;
        }

        // 兜底: 均匀 3s
        return 3;
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

    // ==================== Phase 4: 接续式分段精修 ====================

    /**
     * Phase 4: 分段精修骨架，填充完整字段。
     * 每段带前段最后 3 个精修 shot 作为接续上下文。
     */
    List<Map<String, Object>> refineSkeletons(List<Map<String, Object>> skeletons,
                                                StoryStructure structure, NarrativePlan plan,
                                                String characters, String visualStyle,
                                                boolean comicMode, String narrationPerspective,
                                                String scriptStyle,
                                                List<Map<String, Object>> lockedShots,
                                                String revisionNote) {
        List<Map<String, Object>> allRefined = new ArrayList<>();
        List<Map<String, Object>> lastRefined = new ArrayList<>();

        String refineSystem = buildRefineSystemPrompt(comicMode, scriptStyle, narrationPerspective);

        for (int segStart = 0; segStart < skeletons.size(); segStart += REFINE_SEGMENT_SIZE) {
            int segEnd = Math.min(segStart + REFINE_SEGMENT_SIZE, skeletons.size());
            List<Map<String, Object>> segment = new ArrayList<>(skeletons.subList(segStart, segEnd));

            String refineUser = buildRefineUserPrompt(segment, structure, plan, lastRefined,
                    characters, visualStyle, comicMode, narrationPerspective, scriptStyle, segStart,
                    lockedShots, revisionNote);

            List<Map<String, Object>> refined = refineSegment(refineSystem, refineUser, comicMode, scriptStyle);
            if (refined == null || refined.isEmpty()) {
                log.warn("[StoryboardAgent] Phase4 段 {}-{} 精修失败，使用骨架兜底", segStart, segEnd);
                refined = skeletonToFallbackShots(segment);
            }

            for (Map<String, Object> shot : refined) {
                int d = Math.max(1, Math.min(5, ((Number) shot.getOrDefault("duration", 3)).intValue()));
                shot.put("duration", d);
            }

            allRefined.addAll(refined);
            lastRefined = new ArrayList<>(refined.subList(
                    Math.max(0, refined.size() - LAST_SHOTS_COUNT), refined.size()));

            int currentPhaseIdx = estimateCurrentPhaseIndex(segStart + refined.size(), skeletons, plan);
            String quality = checkBatchQuality(refined, plan, currentPhaseIdx, comicMode);
            log.info("[StoryboardAgent] Phase4 段 {}-{}: {}镜 {}", segStart, segEnd, refined.size(), quality);
        }

        return allRefined;
    }

    private String buildRefineSystemPrompt(boolean comicMode, String scriptStyle, String narrationPerspective) {
        StringBuilder sb = new StringBuilder();

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            sb.append("你是一位爽剧分镜精修师。你的任务是将 shot 骨架精修为完整的分镜。\n");
            sb.append("节奏快，剧情事件密集推进。\n\n");
        } else if (comicMode) {
            sb.append("你是一位漫剧解说分镜精修师。你的任务是将 shot 骨架精修为完整的分镜。\n\n");
        } else {
            sb.append("你是一位专业分镜精修师。你的任务是将 shot 骨架精修为完整的分镜。\n\n");
        }

        sb.append("你会收到一组 shot 骨架（JSON 数组），每个骨架有：\n");
        sb.append("- shotNumber, duration, beatId, sceneHint, mood, characters(带name/state/position), narrativePhase\n\n");
        sb.append("你需要输出相同数量的完整 shot JSON 数组，每个 shot 包含：\n");
        sb.append("shotNumber, duration(保持不变), scene, characters(必须使用骨架中的角色全名),\n");
        sb.append("shotSize, cameraAngle, cameraMovement, sceneDescription,\n");
        sb.append("dialogue, speaker, dialogueTone, visualEffects, audioEffects, transitionHint\n");
        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            sb.append(", hookPoint\n");
        }
        if (comicMode) {
            sb.append(", narration\n");
        }
        sb.append("\n");

        sb.append("【角色一致性 - 硬性约束】\n");
        sb.append("1. 每个 shot 的 characters 必须使用骨架中的角色名，禁止换名或用泛称（如\"主角\"\"反派\"\"路人\"）\n");
        sb.append("2. 只有骨架中列出的角色才能出现，不能凭空增减角色\n");
        sb.append("3. 角色状态必须连贯：如果上一个 shot 角色在\"奔跑\"，本 shot 不能突然\"坐着喝茶\"\n");
        sb.append("4. 角色位置变化必须合理：如果上一个 shot 在\"工厂大厅\"，本 shot 不能突然在\"地下实验室\"（除非有 transition 过渡）\n");
        sb.append("5. 骨架中的 characters 数组列出了本 beat 中在场的角色，这是唯一合法角色来源\n\n");

        sb.append("【场景一致性】\n");
        sb.append("1. 相邻 shot 的场景不能凭空跳转，必须通过 transition 过渡\n");
        sb.append("2. sceneDescription 必须与骨架中的 mood 和 position 匹配\n");
        sb.append("3. 角色动作必须符合当前场景逻辑\n\n");

        sb.append("【对话约束 - 硬性要求】\n");
        sb.append("1. 爽剧以台词驱动节奏，至少 70% 的镜头必须有 dialogue\n");
        sb.append("2. dialogue 字数必须匹配 duration：1s≤5字, 2s≤8字, 3s≤15字, 4s≤20字（字数不变）\n");
        sb.append("3. 没有 dialogue 的镜头只用于纯动作特写或场景转换，连续无台词镜头不超过 2 个\n");
        sb.append("4. 当 dialogue 为空时，speaker 填 \"无\"，dialogueTone 填 \"无\"，sceneDescription 应更详细\n\n");

        sb.append("【快切防翻车指南 - 硬性约束】\n");
        sb.append("一、动作设计：做减法\n");
        sb.append("1. 一个镜头只做一个单一变化（位置、姿势、表情，三选一）\n");
        sb.append("2. 禁止复合动作（如\"拔刀+冲刺+劈砍\"），必须拆成多个镜头\n");
        sb.append("3. 切\"结果\"不切\"过程\"：写\"男主背对镜头，刀已入鞘\"而非\"男主转身收刀\"\n\n");
        sb.append("二、镜头语言：贴脸\n");
        sb.append("1. 快切镜头（1-2s）必须用特写或极特写，禁止全景\n");
        sb.append("2. 用\"局部的动\"代替\"全局的动\"：脚踩碎地砖、刀刃划过鼻尖、手腕翻转\n");
        sb.append("3. 动作结果用静止镜头表现：\"两人背对背站立，地面裂痕\"\n\n");
        sb.append("三、运镜约束\n");
        sb.append("1. 1-2s 镜头只允许：固定镜头、极特写、主观视角(POV)\n");
        sb.append("2. 3-5s 镜头允许：缓慢推镜头、微平移\n");
        sb.append("3. 禁止复杂运镜（推+环绕+拉远）\n\n");
        sb.append("四、提示词写法\n");
        sb.append("1. 锁死起始状态：\"原本侧面朝左，猛然转头看向右\"\n");
        sb.append("2. 定格保底：\"挥拳瞬间画面定格，只有背景雨滴飞溅\"\n");
        sb.append("3. 分离前景背景：\"背景完全静止，只有前景角色在动\"\n");
        sb.append("4. 用\"升格拍摄（高帧率）\"代替\"慢动作\"\n\n");
        sb.append("五、转场\n");
        sb.append("1. transitionHint 必须写\"硬切\"\n");
        sb.append("2. 禁止渐变、模糊、溶解过渡\n\n");

        if (ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle)) {
            sb.append("【爽剧模式】\n");
            sb.append("- hookPoint 字段必须填写，标注本镜头的爽点类型\n");
            sb.append("- 节奏极快，多用 1-2 秒快切镜头\n\n");
        }
        if (comicMode) {
            sb.append("【解说模式】\n");
            sb.append("- 每个镜头必须有 narration 字段（旁白口播稿）\n");
            sb.append("- narration 字数受 duration 约束：3s≤15字, 4s≤20字\n");
            sb.append("- narration 和 dialogue 不能同时存在\n");
            sb.append("- 当有 dialogue 时，narration 填 \"无\"\n");
            sb.append("- narration 优先级高于 dialogue：关键剧情节点用 narration 推进\n");
            if (narrationPerspective != null) {
                if ("third_person".equals(narrationPerspective)) {
                    sb.append("- 旁白必须使用第三人称叙述\n");
                } else if ("first_person".equals(narrationPerspective)) {
                    sb.append("- 旁白必须使用第一人称「我」叙述\n");
                }
            }
            sb.append("\n");
        }

        sb.append("输出纯 JSON 数组，不要 markdown 代码块。保持骨架的 shotNumber 不变，duration 可在骨架基础上 ±1s 微调（范围 1-5s）。");
        return sb.toString();
    }

    private String buildRefineUserPrompt(List<Map<String, Object>> segment,
                                          StoryStructure structure, NarrativePlan plan,
                                          List<Map<String, Object>> lastRefined,
                                          String characters, String visualStyle,
                                          boolean comicMode, String narrationPerspective,
                                          String scriptStyle, int globalOffset,
                                          List<Map<String, Object>> lockedShots,
                                          String revisionNote) {
        StringBuilder sb = new StringBuilder();

        if (structure != null && structure.storyArc != null && !structure.storyArc.isEmpty()) {
            sb.append("## 全局故事弧线\n").append(structure.storyArc).append("\n\n");
        }

        if (plan != null && plan.phases != null && !plan.phases.isEmpty()) {
            sb.append("## 叙事规划\n");
            for (int i = 0; i < plan.phases.size(); i++) {
                NarrativePhase phase = plan.phases.get(i);
                sb.append(i + 1).append(". ").append(phase.name)
                  .append(" (").append(phase.allocatedSeconds).append("秒, 密度:").append(phase.dialogueDensity)
                  .append(", 情绪:").append(phase.emotionalArc).append(")\n");
            }
            sb.append("\n");
        }

        if (structure != null && structure.transitions != null && !structure.transitions.isEmpty()) {
            int firstBeatId = segment.isEmpty() ? 0 : (int) segment.get(0).getOrDefault("beatId", 0);
            int lastBeatId = segment.isEmpty() ? 0 : (int) segment.get(segment.size() - 1).getOrDefault("beatId", 0);
            List<String> relevantTransitions = new ArrayList<>();
            for (Map<String, String> t : structure.transitions) {
                int from = parseIntSafe(t.getOrDefault("from", "0"), 0);
                int to = parseIntSafe(t.getOrDefault("to", "0"), 0);
                if (from >= firstBeatId && to <= lastBeatId + 1) {
                    relevantTransitions.add(t.get("bridge"));
                }
            }
            if (!relevantTransitions.isEmpty()) {
                sb.append("## 场景过渡\n");
                for (String bridge : relevantTransitions) {
                    sb.append("- ").append(bridge).append("\n");
                }
                sb.append("\n");
            }
        }

        // 注入锁定分镜作为上下文约束
        if (lockedShots != null && !lockedShots.isEmpty()) {
            sb.append("## 锁定分镜（必须原样保留，不可修改）\n");
            try {
                sb.append(objectMapper.writeValueAsString(lockedShots)).append("\n\n");
            } catch (Exception e) {
                for (Map<String, Object> shot : lockedShots) {
                    sb.append("- [LOCKED] 第").append(shot.get("shotNumber")).append("镜: ")
                      .append(shot.getOrDefault("sceneDescription", "")).append("\n");
                }
                sb.append("\n");
            }
        }

        if (revisionNote != null && !revisionNote.isEmpty()) {
            sb.append("## 用户反馈（请据此改进）\n").append(revisionNote).append("\n\n");
        }

        if (!lastRefined.isEmpty()) {
            sb.append("## 前段接续（最后").append(lastRefined.size()).append("个精修shot，严格参照保持连贯）\n");
            try {
                sb.append(objectMapper.writeValueAsString(lastRefined)).append("\n\n");
            } catch (Exception e) {
                for (Map<String, Object> shot : lastRefined) {
                    sb.append("- 第").append(shot.get("shotNumber")).append("镜[")
                      .append(shot.get("duration")).append("秒] ")
                      .append(shot.getOrDefault("sceneDescription", shot.getOrDefault("scene", ""))).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("## 当前段 shot 骨架（请精修为完整 shot）\n");
        try {
            sb.append(objectMapper.writeValueAsString(segment)).append("\n\n");
        } catch (Exception e) {
            sb.append("[骨架序列化失败]\n\n");
        }

        sb.append("## 角色列表\n").append(characters).append("\n");
        sb.append("（characters 字段必须使用骨架中给出的角色全名，禁止泛称）\n\n");
        sb.append("风格：").append(visualStyle).append("\n");

        if (comicMode && narrationPerspective != null) {
            if ("third_person".equals(narrationPerspective)) {
                sb.append("\n旁白人称：第三人称\n");
            } else if ("first_person".equals(narrationPerspective)) {
                sb.append("\n旁白人称：第一人称「我」\n");
            }
        }

        sb.append("\n请输出精修后的完整 shot JSON 数组。保持 shotNumber 不变，duration 可在骨架基础上 ±1s 微调（范围 1-5s）。");
        return sb.toString();
    }

    private List<Map<String, Object>> refineSegment(String systemPrompt, String userPrompt,
                                                      boolean comicMode, String scriptStyle) {
        for (int retry = 0; retry <= MAX_EXECUTOR_RETRIES; retry++) {
            try {
                String output = executor.generate(systemPrompt, userPrompt);
                List<Map<String, Object>> shots = parseShotArray(output);
                if (shots != null && !shots.isEmpty()) {
                    return shots;
                }
            } catch (Exception e) {
                log.warn("[StoryboardAgent] Phase4 精修重试 {}/{}: {}", retry + 1, MAX_EXECUTOR_RETRIES, e.getMessage());
            }
        }
        return null;
    }

    private List<Map<String, Object>> skeletonToFallbackShots(List<Map<String, Object>> skeletons) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> sk : skeletons) {
            Map<String, Object> shot = new HashMap<>(sk);
            shot.putIfAbsent("scene", sk.getOrDefault("sceneHint", ""));
            shot.putIfAbsent("sceneDescription", sk.getOrDefault("sceneHint", ""));
            shot.putIfAbsent("shotSize", "MEDIUM");
            shot.putIfAbsent("cameraAngle", "eye_level");
            shot.putIfAbsent("cameraMovement", "static");
            shot.putIfAbsent("dialogue", "");
            shot.putIfAbsent("speaker", "无");
            shot.putIfAbsent("dialogueTone", "无");
            shot.putIfAbsent("visualEffects", "无");
            shot.putIfAbsent("audioEffects", "无");
            shot.putIfAbsent("transitionHint", "过渡到下一镜");
            result.add(shot);
        }
        return result;
    }

    private int estimateCurrentPhaseIndex(int globalShotIndex, List<Map<String, Object>> skeletons,
                                           NarrativePlan plan) {
        if (plan == null || plan.phases == null || skeletons.isEmpty()) return 0;
        int accDuration = 0;
        for (int i = 0; i < Math.min(globalShotIndex, skeletons.size()); i++) {
            accDuration += ((Number) skeletons.get(i).getOrDefault("duration", 3)).intValue();
        }
        int boundary = 0;
        for (int i = 0; i < plan.phases.size(); i++) {
            boundary += plan.phases.get(i).allocatedSeconds;
            if (accDuration < boundary) return i;
        }
        return plan.phases.size() - 1;
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
                + "1. 开场钩子（前 10% 时长）：强力视觉钩子，dialogueDensity=moderate\n"
                + "   - 用短台词引入冲突，快速建立角色关系\n"
                + "2. 铺垫/发展（20-30% 时长）：建立情境，dialogueDensity=dense\n"
                + "   - 台词驱动叙事推进，大量对话推进剧情\n"
                + "3. 冲突升级（25-35% 时长）：矛盾激化，dialogueDensity=dense\n"
                + "   - 对话和动作交替，台词节奏紧凑\n"
                + "4. 高潮（15-25% 时长）：爆发，dialogueDensity=dense\n"
                + "   - 混合对话+动作，保留关键反应镜头和氛围镜头\n"
                + "5. 收束/悬念（10-15% 时长）：dialogueDensity=moderate\n"
                + "   - 台词收束，留悬念结尾\n\n"
                + "【对话密度定义】\n"
                + "- \"sparse\": ≤50% 的镜头有 dialogue\n"
                + "- \"moderate\": ≤70% 的镜头有 dialogue\n"
                + "- \"dense\": ≤85% 的镜头有 dialogue\n\n"
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
            phases.add(new NarrativePhase("开场钩子", 0, 0, s1, "moderate",
                    "3秒镜头≤10字，4秒镜头≤12字", "冲击→悬念", "快切为主，台词引入冲突"));
            phases.add(new NarrativePhase("铺垫发展", 0, 0, s2, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "建立→推进", "台词驱动叙事推进"));
            phases.add(new NarrativePhase("冲突升级", 0, 0, s3, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "紧张→爆发", "节奏加快，对话和动作交替"));
            phases.add(new NarrativePhase("高潮爆发", 0, 0, s4, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "爆发→释放", "视觉和情绪并重"));
            phases.add(new NarrativePhase("收束悬念", 0, 0, s5, "moderate",
                    "3秒镜头≤10字，4秒镜头≤12字", "沉淀→悬念", "台词收束，留悬念"));
        } else {
            // 有爽点时，按实际数量分配到各阶段
            int hookBeats = Math.max(1, totalBeats * 10 / 100);
            int setupBeats = Math.max(1, totalBeats * 25 / 100);
            int conflictBeats = Math.max(1, totalBeats * 30 / 100);
            int climaxBeats = Math.max(1, totalBeats * 25 / 100);
            int resolveBeats = Math.max(1, totalBeats - hookBeats - setupBeats - conflictBeats - climaxBeats);

            int idx = 0;
            phases.add(new NarrativePhase("开场钩子", idx + 1, idx + hookBeats, s1, "moderate",
                    "3秒镜头≤10字，4秒镜头≤12字", "冲击→悬念", "快切为主，台词引入冲突"));
            idx += hookBeats;
            phases.add(new NarrativePhase("铺垫发展", idx + 1, idx + setupBeats, s2, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "建立→推进", "台词驱动叙事推进"));
            idx += setupBeats;
            phases.add(new NarrativePhase("冲突升级", idx + 1, idx + conflictBeats, s3, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "紧张→爆发", "节奏加快，对话和动作交替"));
            idx += conflictBeats;
            phases.add(new NarrativePhase("高潮爆发", idx + 1, idx + climaxBeats, s4, "dense",
                    "3秒镜头≤15字，4秒镜头≤20字", "爆发→释放", "视觉和情绪并重"));
            idx += climaxBeats;
            phases.add(new NarrativePhase("收束悬念", idx + 1, idx + resolveBeats, s5, "moderate",
                    "3秒镜头≤10字，4秒镜头≤12字", "沉淀→悬念", "台词收束，留悬念"));
        }

        return new NarrativePlan(phases, "兜底叙事规划（AI规划失败时使用）");
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
        double maxRatio = 0.85; // 整体上限
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
        if (density == null) return 0.7;
        switch (density) {
            case "sparse": return 0.5;
            case "moderate": return 0.7;
            case "dense": return 0.85;
            default: return 0.7;
        }
    }

    // ==================== 主入口 ====================

    public List<Map<String, Object>> generate(String episodeContent, String characters,
                                               int targetDuration, String visualStyle,
                                               boolean comicMode, String narrationPerspective,
                                               String scriptStyle) {
        return generateV2(episodeContent, characters, targetDuration, visualStyle,
                comicMode, narrationPerspective, scriptStyle, null, null);
    }

    public List<Map<String, Object>> generate(String episodeContent, String characters,
                                               int targetDuration, String visualStyle,
                                               boolean comicMode, String narrationPerspective,
                                               String scriptStyle,
                                               List<Map<String, Object>> lockedShots,
                                               String revisionNote) {
        return generateV2(episodeContent, characters, targetDuration, visualStyle,
                comicMode, narrationPerspective, scriptStyle, lockedShots, revisionNote);
    }

    /**
     * 两阶段架构：Phase1 结构分析 → Phase3 规划 → Phase2 骨架(需要phase信息) → Phase4 精修
     * 注意：Phase3 在 Phase2 之前执行，因为骨架生成需要 NarrativePlan 的阶段归属信息
     */
    private List<Map<String, Object>> generateV2(String episodeContent, String characters,
                                                   int targetDuration, String visualStyle,
                                                   boolean comicMode, String narrationPerspective,
                                                   String scriptStyle,
                                                   List<Map<String, Object>> lockedShots,
                                                   String revisionNote) {
        log.info("[StoryboardAgent] V2 启动: target={}s, mode={}, lockedShots={}, hasRevision={}",
                targetDuration,
                ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU.equals(scriptStyle) ? "爽剧" : (comicMode ? "解说" : "标准"),
                lockedShots != null ? lockedShots.size() : 0,
                revisionNote != null && !revisionNote.isEmpty());

        // === Phase 1: LLM 结构分析 ===
        StoryStructure structure = analyzeStoryStructure(episodeContent, characters, targetDuration);

        // === Phase 3: 全局导演规划 ===
        // Phase3 在 Phase2 之前执行，因为骨架生成需要知道每个 shot 属于哪个叙事阶段
        List<String> hooks = extractHookBeats(episodeContent);
        String existingArc = (structure != null && structure.storyArc != null) ? structure.storyArc : "";
        NarrativePlan plan = buildNarrativePlan(episodeContent, hooks, targetDuration, characters, scriptStyle, comicMode);
        if (!existingArc.isEmpty()) {
            plan.storyArcSummary = existingArc;
        }

        // === Phase 2: 代码拆 shot 骨架 ===
        List<Map<String, Object>> skeletons;
        if (structure != null && structure.beats != null && !structure.beats.isEmpty()) {
            skeletons = buildShotSkeletons(structure, plan);
        } else {
            skeletons = buildFallbackSkeletons(targetDuration, episodeContent, characters, plan);
        }

        log.info("[StoryboardAgent] Phase2 完成: {} 个shot骨架", skeletons.size());

        // === Phase 4: 接续式分段精修 ===
        List<Map<String, Object>> refinedShots = refineSkeletons(skeletons, structure, plan,
                characters, visualStyle, comicMode, narrationPerspective, scriptStyle,
                lockedShots, revisionNote);

        // 赋予全局编号和时间戳
        int t = 0;
        for (int i = 0; i < refinedShots.size(); i++) {
            Map<String, Object> shot = refinedShots.get(i);
            shot.put("globalShotNumber", i + 1);
            shot.put("startTime", t);
            int d = ((Number) shot.getOrDefault("duration", 3)).intValue();
            t += d;
            shot.put("endTime", t);
        }

        log.info("[StoryboardAgent] V2 完成: {}镜, {}s", refinedShots.size(), t);
        return refinedShots;
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

    // ==================== 叙事规划数据类 ====================

    public static class NarrativePhase {
        public String name;            // "开场钩子"/"铺垫"/"冲突升级"/"高潮"/"收束"
        public int startBeat;
        public int endBeat;
        public int allocatedSeconds;
        public String dialogueDensity; // "sparse"(≤50%)/"moderate"(≤70%)/"dense"(≤85%)
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
