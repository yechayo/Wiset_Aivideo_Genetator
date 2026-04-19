package com.comic.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComicCommentaryScriptPromptBuilderTest {

    private final ComicCommentaryScriptPromptBuilder builder = new ComicCommentaryScriptPromptBuilder();

    @Test
    void singleEpisodeSystemPrompt_shouldOutputOneJsonObject() {
        String prompt = builder.buildSingleEpisodeSystemPrompt();

        assertTrue(prompt.contains("JSON 对象"), "应要求输出 JSON 对象");
        assertTrue(prompt.contains("不要数组"), "应明确禁止输出数组");
        assertTrue(prompt.contains("漫剧解说"), "应标识为漫剧解说模式");
        assertTrue(prompt.contains("旁白"), "应提及旁白");
    }

    @Test
    void singleEpisodeUserPrompt_shouldIncludePositionInfo() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲", "章节", "角色", "物品", "摘要",
                2, 4, 90, null
        );

        assertTrue(prompt.contains("本章第 2 集"), "应包含当前集位置");
        assertTrue(prompt.contains("共 4 集"), "应包含总集数");
        assertTrue(prompt.contains("只输出 1 集 JSON 对象"), "应要求单集输出");
    }

    @Test
    void singleEpisodeUserPrompt_shouldIncludeDurationRequirement() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲", "章节", "角色", "物品", "摘要",
                1, 3, 120, null
        );

        assertTrue(prompt.contains("120 秒"), "应包含目标时长");
        assertTrue(prompt.contains("600-840"), "漫剧 120s 应包含对应字数范围");
    }
}
