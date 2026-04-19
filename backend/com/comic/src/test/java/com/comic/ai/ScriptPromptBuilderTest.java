package com.comic.ai;

import com.comic.constant.ProjectInfoKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPromptBuilderTest {

    private final ScriptPromptBuilder builder = new ScriptPromptBuilder();

    // ==================== buildSingleEpisodeSystemPrompt ====================

    @Test
    void singleEpisodeSystemPrompt_standardStyle_shouldOutputOneJsonObject() {
        String prompt = builder.buildSingleEpisodeSystemPrompt("standard");

        assertTrue(prompt.contains("JSON 对象"), "应要求输出 JSON 对象而非数组");
        assertTrue(prompt.contains("不要数组"), "应明确禁止输出数组");
        assertFalse(prompt.contains("JSON 数组长度必须恰好"), "不应包含多集数组约束");
        assertTrue(prompt.contains("title"), "应包含 title 字段");
        assertTrue(prompt.contains("content"), "应包含 content 字段");
    }

    @Test
    void singleEpisodeSystemPrompt_shuangjuStyle_shouldIncludeShuangjuRules() {
        String prompt = builder.buildSingleEpisodeSystemPrompt(ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU);

        assertTrue(prompt.contains("爽剧模式"), "爽剧模式应包含爽剧标识");
        assertTrue(prompt.contains("[爽点:描述]"), "应包含爽点标记格式");
        assertTrue(prompt.contains("720-960"), "应包含字数要求");
        assertTrue(prompt.contains("短句"), "爽剧模式应包含短句约束");
    }

    @Test
    void singleEpisodeSystemPrompt_standardStyle_shouldIncludeDurationRules() {
        String prompt = builder.buildSingleEpisodeSystemPrompt("standard");

        assertTrue(prompt.contains("5-7"), "标准模式应包含每秒字数要求");
        assertTrue(prompt.contains("叙事弧线"), "应包含叙事节奏原则");
    }

    // ==================== buildSingleEpisodeUserPrompt ====================

    @Test
    void singleEpisodeUserPrompt_shouldIncludePositionInfo() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲内容", "### 第一章（第1-3集）", "角色A", "物品B",
                "前序摘要", 2, 3, 120, null, "standard"
        );

        assertTrue(prompt.contains("本章第 2 集"), "应包含当前集位置");
        assertTrue(prompt.contains("共 3 集"), "应包含总集数");
        assertTrue(prompt.contains("只输出 1 集 JSON 对象"), "应要求只输出 1 集");
        assertTrue(prompt.contains("不要输出数组"), "应禁止输出数组");
    }

    @Test
    void singleEpisodeUserPrompt_shouldIncludeDurationRequirement() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲", "章节", "角色", "物品", "摘要",
                1, 3, 180, null, "standard"
        );

        assertTrue(prompt.contains("180 秒"), "应包含目标时长");
        assertTrue(prompt.contains("900-1260"), "标准 180s 应包含对应字数范围");
    }

    @Test
    void singleEpisodeUserPrompt_shuangjuStyle_shouldIncludeBeatRequirement() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲", "章节", "角色", "物品", "摘要",
                1, 3, 120, null, ProjectInfoKeys.SCRIPT_STYLE_SHUANGJU
        );

        assertTrue(prompt.contains("爽点数量硬性要求"), "爽剧模式应包含爽点数量要求");
        assertTrue(prompt.contains("[爽点:XX]"), "应包含爽点格式");
        assertTrue(prompt.contains("1440-1920"), "爽剧 120s 应包含对应字数范围");
    }

    @Test
    void singleEpisodeUserPrompt_shouldIncludeModificationSuggestion() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲", "章节", "角色", "物品", "摘要",
                1, 3, 60, "增加更多冲突", "standard"
        );

        assertTrue(prompt.contains("修改建议"), "应包含修改建议标题");
        assertTrue(prompt.contains("增加更多冲突"), "应包含具体修改建议内容");
    }

    @Test
    void singleEpisodeUserPrompt_noModificationSuggestion_shouldNotInclude() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲", "章节", "角色", "物品", "摘要",
                1, 3, 60, null, "standard"
        );

        assertFalse(prompt.contains("修改建议"), "无修改建议时不应包含该标题");
    }

    @Test
    void singleEpisodeUserPrompt_shouldIncludeContextFields() {
        String prompt = builder.buildSingleEpisodeUserPrompt(
                "大纲文本", "第一章", "角色描述", "物品描述", "前序剧情",
                1, 2, 60, null, "standard"
        );

        assertTrue(prompt.contains("大纲文本"), "应包含完整大纲");
        assertTrue(prompt.contains("第一章"), "应包含目标章节");
        assertTrue(prompt.contains("角色描述"), "应包含全局角色");
        assertTrue(prompt.contains("物品描述"), "应包含全局物品");
        assertTrue(prompt.contains("前序剧情"), "应包含前序摘要");
    }
}
