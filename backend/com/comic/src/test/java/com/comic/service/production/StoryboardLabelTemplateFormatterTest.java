package com.comic.service.production;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardLabelTemplateFormatterTest {

    private static final String LABEL_SCENE = "（场景）";
    private static final String LABEL_CHARACTER = "（出场）";
    private static final String LABEL_BLOCKING = "（走位）";
    private static final String LABEL_ACTION = "（动作）";
    private static final String LABEL_ENVIRONMENT = "（环境）";
    private static final String LABEL_STATE = "（状态）";
    private static final String LABEL_EXPRESSION = "（表情）";
    private static final String LABEL_INNER_VOICE = "内声：";
    private static final String LABEL_AUDIO = "（音效）";

    private static final List<String> FIXED_LABEL_ORDER = Arrays.asList(
            LABEL_SCENE,
            LABEL_CHARACTER,
            LABEL_BLOCKING,
            LABEL_ACTION,
            LABEL_ENVIRONMENT,
            LABEL_STATE,
            LABEL_EXPRESSION,
            LABEL_INNER_VOICE,
            LABEL_AUDIO
    );

    @Test
    void formatShot_shouldKeepFixedLabelOrder_andMapInputIntoLabeledDomains() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "城市天际线夜景");
        shot.put("characters", Arrays.asList("林晓星", "陈墨"));
        // 故意不包含角色名，避免 characters 断言被 sceneDescription 同名误通过
        shot.put("sceneDescription", "快步穿过雨幕，霓虹映在玻璃幕墙上，神色紧张");

        String formatted = StoryboardLabelTemplateFormatter.formatShot(shot);

        assertContainsInOrder(formatted, FIXED_LABEL_ORDER);
        assertTrue(formatted.contains("城市天际线夜景"), "scene 值应映射到输出文本");

        String appearLine = findFirstLineStartingWith(formatted, LABEL_CHARACTER);
        assertTrue(appearLine.contains("林晓星"), "（出场）行应包含角色林晓星");

        String blockingLine = findFirstLineStartingWith(formatted, LABEL_BLOCKING + "林晓星");
        assertFalse(blockingLine.isEmpty(), "应存在（走位）林晓星 对应行");

        assertAnyClauseInLabeledLines(
                formatted,
                Arrays.asList("快步", "雨幕", "神色紧张"),
                Arrays.asList(LABEL_ACTION, LABEL_ENVIRONMENT, LABEL_STATE, LABEL_EXPRESSION)
        );
    }

    @Test
    void formatShot_shouldFillMissingFieldsWithWu_whenNoDescriptionClauses() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "城市天际线夜景");
        shot.put("characters", Arrays.asList("林晓星"));
        // 不提供可拆分子句内容，专门验证缺省填无契约
        shot.put("sceneDescription", "");

        String formatted = StoryboardLabelTemplateFormatter.formatShot(shot);

        assertContainsInOrder(formatted, FIXED_LABEL_ORDER);
        assertLineEquals(formatted, LABEL_ACTION + "无");
        assertLineEquals(formatted, LABEL_ENVIRONMENT + "无");
        assertLineEquals(formatted, LABEL_STATE + "无");
        assertLineEquals(formatted, LABEL_EXPRESSION + "无");
        assertLineEquals(formatted, LABEL_AUDIO + "无");
    }

    @Test
    void applyToShot_shouldWriteSameTaggedTextIntoSceneAndVisual() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "地铁站台");
        shot.put("characters", Arrays.asList("林晓星"));
        shot.put("sceneDescription", "抬头看向时钟");
        shot.put("dialogue", "倒计时开始了");
        shot.put("speaker", "林晓星");

        StoryboardLabelTemplateFormatter.applyToShot(shot);

        String sceneDescription = String.valueOf(shot.get("sceneDescription"));
        String visualDescription = String.valueOf(shot.get("visualDescription"));

        assertEquals(sceneDescription, visualDescription, "sceneDescription 与 visualDescription 应写入同一份标签文本");
        assertContainsInOrder(sceneDescription, FIXED_LABEL_ORDER);

        String innerVoiceLine = findFirstLineStartingWith(sceneDescription, LABEL_INNER_VOICE);
        assertTrue(innerVoiceLine.contains("林晓星") && innerVoiceLine.contains("倒计时开始了"),
                "内声行应在同一行包含说话人与台词");
    }

    @Test
    void applyToShot_shouldBeIdempotent_whenCalledTwice() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "城市街角");
        shot.put("characters", "Tony Stark, Peter Parker");
        shot.put("sceneDescription", "快步穿过街口，神色紧张");
        shot.put("dialogue", "目标出现了");
        shot.put("speaker", "Tony Stark");

        StoryboardLabelTemplateFormatter.applyToShot(shot);
        String firstPass = String.valueOf(shot.get("sceneDescription"));

        StoryboardLabelTemplateFormatter.applyToShot(shot);
        String secondPass = String.valueOf(shot.get("sceneDescription"));
        String secondVisual = String.valueOf(shot.get("visualDescription"));

        assertEquals(firstPass, secondPass, "applyToShot 重复调用应保持输出稳定");
        assertEquals(secondPass, secondVisual, "幂等场景下 sceneDescription 与 visualDescription 仍需一致");
    }

    @Test
    void formatShot_shouldSplitCharacterStringByExplicitDelimiters_notWhitespace() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "实验室");
        shot.put("characters", "Tony Stark, Peter Parker");
        shot.put("sceneDescription", "快速调整设备");

        String formatted = StoryboardLabelTemplateFormatter.formatShot(shot);

        assertLineEquals(formatted, LABEL_CHARACTER + "Tony Stark、Peter Parker");
        assertFalse(formatted.contains(LABEL_BLOCKING + "Tony" + "｜"), "不应按空格把 Tony Stark 拆分");
        assertFalse(formatted.contains(LABEL_BLOCKING + "Peter" + "｜"), "不应按空格把 Peter Parker 拆分");
    }

    @Test
    void formatShot_shouldSupportMixedCharacterList_mapAndString() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "楼顶");
        List<Object> characters = new ArrayList<>();
        Map<String, Object> mapCharacter = new LinkedHashMap<>();
        mapCharacter.put("name", "Tony Stark");
        characters.add(mapCharacter);
        characters.add("Peter Parker");
        shot.put("characters", characters);
        shot.put("sceneDescription", "转身看向远处");

        String formatted = StoryboardLabelTemplateFormatter.formatShot(shot);

        assertLineEquals(formatted, LABEL_CHARACTER + "Tony Stark、Peter Parker");
        assertFalse(findFirstLineStartingWith(formatted, LABEL_BLOCKING + "Tony Stark").isEmpty());
        assertFalse(findFirstLineStartingWith(formatted, LABEL_BLOCKING + "Peter Parker").isEmpty());
    }

    @Test
    void formatShot_shouldNotClassifyByOverGenericOneCharEnvironmentHints() {
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("scene", "教学楼走廊");
        shot.put("characters", Arrays.asList("林晓星"));
        shot.put("sceneDescription", "天真地挥手");

        String formatted = StoryboardLabelTemplateFormatter.formatShot(shot);

        assertLineEquals(formatted, LABEL_ACTION + "天真地挥手");
        assertLineEquals(formatted, LABEL_ENVIRONMENT + "无");
    }

    private static void assertAnyClauseInLabeledLines(String text, List<String> clauses, List<String> labelPrefixes) {
        List<String> candidateLines = new ArrayList<>();
        for (String label : labelPrefixes) {
            candidateLines.addAll(findLinesStartingWith(text, label));
        }

        boolean matched = false;
        for (String line : candidateLines) {
            for (String clause : clauses) {
                if (line.contains(clause)) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                break;
            }
        }

        assertTrue(matched, "至少一个 sceneDescription 子句应出现在动作/环境/状态/表情对应标签行");
    }

    private static void assertLineEquals(String text, String exactLine) {
        String regex = "(?m)^" + Pattern.quote(exactLine) + "$";
        assertTrue(Pattern.compile(regex).matcher(text).find(), "缺少独立行: " + exactLine);
    }

    private static String findFirstLineStartingWith(String text, String prefix) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return "";
    }

    private static List<String> findLinesStartingWith(String text, String prefix) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static void assertContainsInOrder(String text, List<String> tokens) {
        int cursor = -1;
        List<String> missing = new ArrayList<>();
        for (String token : tokens) {
            int idx = text.indexOf(token);
            if (idx < 0) {
                missing.add(token);
                continue;
            }
            assertTrue(idx > cursor, "标签顺序错误: " + token);
            cursor = idx;
        }
        assertTrue(missing.isEmpty(), "缺少标签: " + missing);
    }
}
