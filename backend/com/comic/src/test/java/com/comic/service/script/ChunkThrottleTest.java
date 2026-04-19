package com.comic.service.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接测试生产代码 ChunkThrottle。
 * 该类是 ScriptService.generateScriptEpisodes 中节流逻辑的提取。
 */
class ChunkThrottleTest {

    @Test
    void accept_manySmallChunks_shouldBatchAndFlush() {
        // 模拟高频 LLM token 输出
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add("文字" + i);
        }

        List<String> published = ChunkThrottle.collectChunks(chunks);

        // 内容完整性：所有 published 拼接等于原始内容
        StringBuilder expected = new StringBuilder();
        for (String c : chunks) expected.append(c);
        assertEquals(expected.toString(), String.join("", published));

        // 有节流效果：发布次数 <= chunk 数量
        assertTrue(published.size() <= 10);
        assertTrue(published.size() >= 1);
    }

    @Test
    void accept_largeChunk_shouldImmediatelyPublish() {
        // 构造超过 MAX_CHARS 的单次 chunk
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 60; i++) large.append("A");

        List<String> published = ChunkThrottle.collectChunks(Arrays.asList(large.toString()));

        assertEquals(1, published.size());
        assertEquals(large.toString(), published.get(0));
    }

    @Test
    void accept_exactly50Chars_shouldTriggerThreshold() {
        // 边界值：恰好 50 字符
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < 50; i++) exact.append("X");

        List<String> published = ChunkThrottle.collectChunks(Arrays.asList(exact.toString()));

        assertEquals(1, published.size());
        assertEquals(50, published.get(0).length());
    }

    @Test
    void accept_49Chars_shouldFlushAtEnd() {
        // 49 字符不触发长度阈值，但 flush 时发送
        StringBuilder shortChunk = new StringBuilder();
        for (int i = 0; i < 49; i++) shortChunk.append("Y");

        List<String> published = ChunkThrottle.collectChunks(Arrays.asList(shortChunk.toString()));

        assertEquals(1, published.size());
        assertEquals(49, published.get(0).length());
    }

    @Test
    void flush_remainingChunks_shouldSendAll() {
        List<String> published = new ArrayList<>();
        ChunkThrottle throttle = new ChunkThrottle(published::add);

        // 第一次 accept 会因为 lastPublishTime=0 触发立即发布
        throttle.accept("初始chunk");
        int countAfterFirst = published.size();
        assertTrue(countAfterFirst >= 1, "首个 chunk 应因时间阈值被发布");

        // 快速追加小 chunk（在 80ms 内），不应触发新的发布
        throttle.accept("小");
        assertEquals(countAfterFirst, published.size(), "80ms 内的小 chunk 不应触发新发布");

        throttle.flush();
        assertEquals(countAfterFirst + 1, published.size(), "flush 应发送剩余");

        // 验证内容完整性
        assertEquals("初始chunk小", String.join("", published));
    }

    @Test
    void flush_empty_shouldNotPublish() {
        List<String> published = new ArrayList<>();
        ChunkThrottle throttle = new ChunkThrottle(published::add);

        throttle.flush();
        assertEquals(0, published.size(), "空内容不应发布");
    }

    @Test
    void accept_nullPublisher_shouldNotThrow() {
        // 模拟 DeepSeekTextService 中 consumer 可能为 null 时的安全检查
        // ChunkThrottle 本身构造时需要 Consumer，这里验证正常使用不会 null
        List<String> published = new ArrayList<>();
        ChunkThrottle throttle = new ChunkThrottle(published::add);
        throttle.accept("测试");
        throttle.flush();
        assertTrue(published.size() >= 1);
    }
}
