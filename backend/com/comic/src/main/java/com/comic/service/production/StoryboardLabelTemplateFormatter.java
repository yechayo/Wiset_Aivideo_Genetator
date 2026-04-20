package com.comic.service.production;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 分镜标签模板格式化器。
 * 仅做展示文本拼装，不改变原有字段语义。
 */
public final class StoryboardLabelTemplateFormatter {

    private static final String WU = "无";

    private static final String LABEL_SCENE = "（场景）";
    private static final String LABEL_CHARACTER = "（出场）";
    private static final String LABEL_BLOCKING = "（走位）";
    private static final String LABEL_ACTION = "（动作）";
    private static final String LABEL_ENVIRONMENT = "（环境）";
    private static final String LABEL_STATE = "（状态）";
    private static final String LABEL_EXPRESSION = "（表情）";
    private static final String LABEL_INNER_VOICE = "内声：";
    private static final String LABEL_AUDIO = "（音效）";

    private static final String BLOCKING_SUFFIX = "｜位置锁=无｜姿态锁=无｜朝向锁=无｜道具锁=无";

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[。；;，,\\r\\n]+");
    private static final Pattern CHARACTER_SPLIT = Pattern.compile("[,，、；;\\r\\n]+");

    private static final String[] ENVIRONMENT_HINTS = {
            "雨幕", "暴雨", "霓虹", "夜景", "玻璃幕墙", "天空", "云层", "地铁", "站台", "森林",
            "室内", "室外", "背景", "街道", "走廊", "房间"
    };
    private static final String[] STATE_HINTS = {
            "紧张", "放松", "警惕", "犹豫", "坚定", "害怕", "愤怒", "疲惫", "激动", "不安", "冷静", "慌",
            "沉默", "专注", "恍惚", "失神", "镇定"
    };
    private static final String[] EXPRESSION_HINTS = {
            "表情", "神色", "眼神", "目光", "皱眉", "微笑", "苦笑", "哭", "泪", "嘴角", "咬牙", "瞪", "叹气"
    };

    private StoryboardLabelTemplateFormatter() {
    }

    public static String formatShot(Map<String, Object> shot) {
        Map<String, Object> safeShot = shot == null ? Collections.<String, Object>emptyMap() : shot;

        String scene = fallbackToWu(asText(safeShot.get("scene")));
        List<String> characters = parseCharacters(safeShot.get("characters"));
        String characterLine = characters.isEmpty() ? WU : String.join("、", characters);

        List<String> clauses = collectClauses(safeShot);
        List<String> actionClauses = new ArrayList<>();
        List<String> environmentClauses = new ArrayList<>();
        List<String> stateClauses = new ArrayList<>();
        List<String> expressionClauses = new ArrayList<>();
        classifyClauses(clauses, actionClauses, environmentClauses, stateClauses, expressionClauses);

        String action = joinOrWu(actionClauses);
        String environment = joinOrWu(environmentClauses);
        String state = joinOrWu(stateClauses);
        String expression = joinOrWu(expressionClauses);

        String innerVoice = buildInnerVoiceLine(safeShot);
        String audio = fallbackToWu(
                firstNonBlank(
                        asText(safeShot.get("audioEffects")),
                        asText(safeShot.get("soundEffects")),
                        asText(safeShot.get("audio")),
                        asText(safeShot.get("sfx"))
                )
        );

        StringBuilder sb = new StringBuilder();
        sb.append(LABEL_SCENE).append(scene).append('\n');
        sb.append(LABEL_CHARACTER).append(characterLine).append('\n');
        appendBlockingLines(sb, characters);
        sb.append(LABEL_ACTION).append(action).append('\n');
        sb.append(LABEL_ENVIRONMENT).append(environment).append('\n');
        sb.append(LABEL_STATE).append(state).append('\n');
        sb.append(LABEL_EXPRESSION).append(expression).append('\n');
        sb.append(LABEL_INNER_VOICE).append(innerVoice).append('\n');
        sb.append(LABEL_AUDIO).append(audio);
        return sb.toString();
    }

    public static void applyToShot(Map<String, Object> shot) {
        if (shot == null) {
            return;
        }
        String sceneDescription = normalizeText(asText(shot.get("sceneDescription")));
        String visualDescription = normalizeText(asText(shot.get("visualDescription")));

        // 已是模板文本时直接复用，避免重复调用时把标签文本再次当原始子句分类。
        String formatted;
        if (isTemplateText(sceneDescription)) {
            formatted = sceneDescription;
        } else if (isTemplateText(visualDescription)) {
            formatted = visualDescription;
        } else {
            formatted = formatShot(shot);
        }

        shot.put("sceneDescription", formatted);
        shot.put("visualDescription", formatted);
    }

    public static void applyToShots(List<Map<String, Object>> shots) {
        if (shots == null) {
            return;
        }
        for (Map<String, Object> shot : shots) {
            applyToShot(shot);
        }
    }

    private static void appendBlockingLines(StringBuilder sb, List<String> characters) {
        if (characters.isEmpty()) {
            sb.append(LABEL_BLOCKING).append(WU).append(BLOCKING_SUFFIX).append('\n');
            return;
        }
        for (String character : characters) {
            sb.append(LABEL_BLOCKING).append(character).append(BLOCKING_SUFFIX).append('\n');
        }
    }

    private static List<String> collectClauses(Map<String, Object> shot) {
        Set<String> clauseSet = new LinkedHashSet<>();
        splitIntoClauses(firstNonBlank(asText(shot.get("sceneDescription"))), clauseSet);
        splitIntoClauses(firstNonBlank(asText(shot.get("visualDescription"))), clauseSet);
        return new ArrayList<>(clauseSet);
    }

    private static void splitIntoClauses(String text, Set<String> out) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String[] parts = CLAUSE_SPLIT.split(text);
        for (String part : parts) {
            String clause = normalizeText(part);
            if (!clause.isEmpty()) {
                out.add(clause);
            }
        }
    }

    private static void classifyClauses(List<String> clauses,
                                        List<String> actionClauses,
                                        List<String> environmentClauses,
                                        List<String> stateClauses,
                                        List<String> expressionClauses) {
        for (String clause : clauses) {
            if (containsAny(clause, EXPRESSION_HINTS)) {
                expressionClauses.add(clause);
                continue;
            }
            if (containsAny(clause, STATE_HINTS)) {
                stateClauses.add(clause);
                continue;
            }
            if (containsAny(clause, ENVIRONMENT_HINTS)) {
                environmentClauses.add(clause);
                continue;
            }
            actionClauses.add(clause);
        }
    }

    private static String buildInnerVoiceLine(Map<String, Object> shot) {
        String dialogue = normalizeText(asText(shot.get("dialogue")));
        String speaker = normalizeText(asText(shot.get("speaker")));
        if (dialogue.isEmpty()) {
            return WU;
        }
        if (speaker.isEmpty()) {
            return "「" + dialogue + "」";
        }
        return "「" + speaker + "：" + dialogue + "」";
    }

    private static List<String> parseCharacters(Object rawCharacters) {
        List<String> names = new ArrayList<>();
        if (rawCharacters == null) {
            return names;
        }

        if (rawCharacters instanceof List<?>) {
            List<?> list = (List<?>) rawCharacters;
            for (Object item : list) {
                if (item instanceof Map<?, ?>) {
                    Object name = ((Map<?, ?>) item).get("name");
                    addName(names, asText(name));
                } else {
                    addName(names, asText(item));
                }
            }
            return deduplicate(names);
        }

        String text = normalizeText(asText(rawCharacters));
        if (text.isEmpty()) {
            return names;
        }
        String[] parts = CHARACTER_SPLIT.split(text);
        for (String part : parts) {
            addName(names, part);
        }
        return deduplicate(names);
    }

    private static void addName(List<String> names, String name) {
        String normalized = normalizeText(name);
        if (!normalized.isEmpty()) {
            names.add(normalized);
        }
    }

    private static List<String> deduplicate(List<String> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }

    private static String joinOrWu(List<String> clauses) {
        return clauses.isEmpty() ? WU : String.join("；", clauses);
    }

    private static boolean containsAny(String text, String[] hints) {
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String fallbackToWu(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty() ? WU : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeText(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isTemplateText(String text) {
        String normalized = normalizeText(text);
        if (normalized.isEmpty() || !normalized.startsWith(LABEL_SCENE)) {
            return false;
        }
        return normalized.contains(LABEL_CHARACTER)
                && normalized.contains(LABEL_BLOCKING)
                && normalized.contains(LABEL_ACTION)
                && normalized.contains(LABEL_ENVIRONMENT)
                && normalized.contains(LABEL_STATE)
                && normalized.contains(LABEL_EXPRESSION)
                && normalized.contains(LABEL_INNER_VOICE)
                && normalized.contains(LABEL_AUDIO);
    }
}
