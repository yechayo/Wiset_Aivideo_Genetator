package com.comic.service.script;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 流式 chunk 节流器 — 将高频小 chunk 合并为低频大 chunk 推送。
 * 独立于 ScriptService 以便单元测试。
 *
 * 规则：累计达到 MAX_CHARS 字符或距离上次推送超过 INTERVAL_MS 毫秒时触发推送。
 */
public class ChunkThrottle {

    public static final long INTERVAL_MS = 80;
    public static final int MAX_CHARS = 50;

    private final Consumer<String> publisher;
    private final long[] lastPublishTime = {0};
    private final StringBuilder pending = new StringBuilder();

    public ChunkThrottle(Consumer<String> publisher) {
        this.publisher = publisher;
    }

    /**
     * 接收一个 chunk，按节流规则决定是否立即推送。
     */
    public void accept(String chunk) {
        pending.append(chunk);
        long now = System.currentTimeMillis();
        if (now - lastPublishTime[0] >= INTERVAL_MS || pending.length() >= MAX_CHARS) {
            flush();
            lastPublishTime[0] = now;
        }
    }

    /**
     * 强制推送剩余未发送的内容。
     */
    public void flush() {
        if (pending.length() > 0) {
            publisher.accept(pending.toString());
            pending.setLength(0);
        }
    }

    // ==================== 测试辅助方法 ====================

    /**
     * 批量接收 chunk 并返回所有实际推送的内容（用于测试）。
     */
    public static List<String> collectChunks(List<String> chunks) {
        List<String> published = new ArrayList<>();
        ChunkThrottle throttle = new ChunkThrottle(published::add);
        for (String chunk : chunks) {
            throttle.accept(chunk);
        }
        throttle.flush();
        return published;
    }
}
