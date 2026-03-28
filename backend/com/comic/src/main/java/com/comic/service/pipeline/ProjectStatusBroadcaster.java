package com.comic.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 通过 Redis Pub/Sub 广播项目状态变更。
 * 前端通过 SSE 订阅 project:status:{projectId} 频道获取实时推送。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStatusBroadcaster {

    private static final String CHANNEL_PREFIX = "project:status:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布状态变更消息。
     *
     * @param projectId 项目 ID
     * @param from      变更前状态
     * @param to        变更后状态
     */
    public void broadcast(String projectId, String from, String to) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("projectId", projectId);
            message.put("from", from);
            message.put("to", to);
            message.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + projectId, json);
            log.info("Status broadcast: projectId={}, {} -> {}", projectId, from, to);
        } catch (Exception e) {
            log.warn("Failed to broadcast status change: projectId={}, error={}", projectId, e.getMessage());
            // 广播失败不影响主流程，前端可通过轮询兜底
        }
    }
}