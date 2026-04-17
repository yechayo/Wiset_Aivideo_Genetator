package com.comic.ai.text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NarrationPromptBuilderTest {

    private final NarrationPromptBuilder builder = new NarrationPromptBuilder();

    @Test
    void testBuildNarrationSystemPrompt_thirdPerson() {
        String prompt = builder.buildNarrationSystemPrompt("third_person", 300);
        assertTrue(prompt.contains("第三人称"));
        assertTrue(prompt.contains("他/她"));
        assertTrue(prompt.contains("300"), "动态字数估算值未出现在 prompt 中");
        assertTrue(prompt.contains("±15%"));
    }

    @Test
    void testBuildNarrationSystemPrompt_firstPerson() {
        String prompt = builder.buildNarrationSystemPrompt("first_person", 450);
        assertTrue(prompt.contains("第一人称"));
        assertTrue(prompt.contains("「我」"));
        assertTrue(prompt.contains("450"), "动态字数估算值未出现在 prompt 中");
    }

    @Test
    void testBuildNarrationUserPrompt() {
        String prompt = builder.buildNarrationUserPrompt(
                "这是测试剧本", "角色A：主角", 60, 6);
        assertTrue(prompt.contains("60"));
        assertTrue(prompt.contains("6"));
        assertTrue(prompt.contains("预计旁白分镜数"));
        assertTrue(prompt.contains("±15%"));
    }

    @Test
    void testBuildNarrationUserPrompt_noCharacters() {
        String prompt = builder.buildNarrationUserPrompt(
                "这是测试剧本", null, 60, 6);
        assertTrue(prompt.contains("60"));
        assertTrue(prompt.contains("6"));
        assertFalse(prompt.contains("【角色信息】"));
    }

    @Test
    void testWordCountEstimate() {
        // 60s / 3 = 20 shots, 20 - 6 dialogue = 14 narration shots
        // 14 * 17 * 1.15 = 273.7 → 273
        String prompt = builder.buildNarrationUserPrompt("test", "", 60, 6);
        assertTrue(prompt.contains("273"),
                "字数估算未出现在 prompt 中，实际: " + prompt);
    }
}