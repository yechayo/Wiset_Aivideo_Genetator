package com.comic.service.script;

import com.comic.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接测试生产代码 EpisodeJsonCleaner。
 * 该类是 ScriptService.parseAndSaveOneEpisode 中 JSON 解析逻辑的提取。
 */
class EpisodeJsonCleanerTest {

    private EpisodeJsonCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new EpisodeJsonCleaner(new ObjectMapper());
    }

    // ==================== cleanAndParse ====================

    @Test
    void cleanAndParse_plainJsonObject_shouldParse() {
        String json = "{\"title\":\"第1集：觉醒\",\"content\":\"场景描写\",\"characters\":\"林默\",\"keyItems\":\"怀表\"}";

        JsonNode node = cleaner.cleanAndParse(json);

        assertTrue(node.isObject());
        assertEquals("第1集：觉醒", EpisodeJsonCleaner.getField(node, "title", ""));
        assertEquals("场景描写", EpisodeJsonCleaner.getField(node, "content", ""));
    }

    @Test
    void cleanAndParse_singleElementArray_shouldTakeFirst() {
        String json = "[{\"title\":\"第2集\",\"content\":\"内容\"}]";

        JsonNode node = cleaner.cleanAndParse(json);

        assertTrue(node.isObject());
        assertEquals("第2集", EpisodeJsonCleaner.getField(node, "title", ""));
    }

    @Test
    void cleanAndParse_emptyArray_shouldThrow() {
        assertThrows(BusinessException.class, () -> cleaner.cleanAndParse("[]"));
    }

    @Test
    void cleanAndParse_markdownJsonWrapper_shouldStrip() {
        String raw = "```json\n{\"title\":\"第3集\",\"content\":\"内容\"}\n```";

        JsonNode node = cleaner.cleanAndParse(raw);

        assertEquals("第3集", EpisodeJsonCleaner.getField(node, "title", ""));
    }

    @Test
    void cleanAndParse_markdownPlainWrapper_shouldStrip() {
        String raw = "```\n{\"title\":\"第4集\",\"content\":\"内容\"}\n```";

        JsonNode node = cleaner.cleanAndParse(raw);

        assertEquals("第4集", EpisodeJsonCleaner.getField(node, "title", ""));
    }

    @Test
    void cleanAndParse_invalidJson_shouldThrow() {
        assertThrows(BusinessException.class, () -> cleaner.cleanAndParse("not json"));
    }

    @Test
    void cleanAndParse_escapedContentWithBeats_shouldParse() {
        // 爽剧剧本中有大量中文引号和爽点标记
        String json = "{\"title\":\"第5集\",\"content\":\"林默(震惊):\\\"真的有联系！\\\" [爽点:确认猜想]\\n他立刻拿出笔记本。\"}";

        JsonNode node = cleaner.cleanAndParse(json);
        String content = EpisodeJsonCleaner.getField(node, "content", "");

        assertTrue(content.contains("[爽点:确认猜想]"));
        assertTrue(content.contains("林默"));
        assertTrue(content.contains("\n"));
    }

    // ==================== getField ====================

    @Test
    void getField_existingField_shouldReturnValue() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"title\":\"测试\"}");

        assertEquals("测试", EpisodeJsonCleaner.getField(node, "title", "默认"));
    }

    @Test
    void getField_missingField_shouldReturnDefault() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"title\":\"测试\"}");

        assertEquals("", EpisodeJsonCleaner.getField(node, "content", ""));
        assertEquals("默认值", EpisodeJsonCleaner.getField(node, "characters", "默认值"));
    }

    @Test
    void getField_titleDefault_shouldUseEpisodeNum() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"content\":\"内容\"}");

        // 模拟生产代码中的默认值： "第" + episodeNum + "集"
        String defaultTitle = "第" + 3 + "集";
        assertEquals(defaultTitle, EpisodeJsonCleaner.getField(node, "title", defaultTitle));
    }

    @Test
    void getField_nullField_shouldReturnDefault() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"title\":null}");

        assertEquals("默认", EpisodeJsonCleaner.getField(node, "title", "默认"));
    }

    @Test
    void cleanAndParse_longShuangjuContent_shouldParseComplete() {
        // 模拟实际爽剧 180s 输出（~60 个爽点标记）
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            content.append("第").append(i + 1).append("个爽点场景描写。 [爽点:类型").append(i).append("]\n");
        }
        String json = "{\"title\":\"第7集：碎片之谜\",\"content\":\"" +
                content.toString().replace("\"", "\\\"").replace("\n", "\\n") +
                "\",\"characters\":\"林默,影鸦\",\"keyItems\":\"碎片,怀表\"}";

        JsonNode node = cleaner.cleanAndParse(json);
        String parsedContent = EpisodeJsonCleaner.getField(node, "content", "");

        int beatCount = 0;
        int idx = 0;
        while ((idx = parsedContent.indexOf("[爽点:", idx)) != -1) {
            beatCount++;
            idx++;
        }
        assertEquals(60, beatCount, "应包含完整 60 个爽点标记");
        assertEquals("碎片,怀表", EpisodeJsonCleaner.getField(node, "keyItems", ""));
    }
}
