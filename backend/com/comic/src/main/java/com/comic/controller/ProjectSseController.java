package com.comic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "项目状态实时推送")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class ProjectSseController {

    private final RedisMessageListenerContainer redisContainer;
    private final ObjectMapper objectMapper;

    /** projectId → 活跃的 SSE 连接集合 */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 订阅项目状态变更（SSE）
     * 前端: new EventSource('/api/projects/{projectId}/status/stream')
     */
    @GetMapping(value = "/{projectId}/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅项目状态实时变更")
    public SseEmitter streamProjectStatus(@PathVariable String projectId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        emitters.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        String channel = "project:status:" + projectId;

        MessageListener listener = (message, pattern) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(message.getBody(), Map.class);
                sendToEmitters(projectId, data);
            } catch (Exception e) {
                log.warn("Failed to parse Redis message for project {}", projectId, e);
            }
        };

        redisContainer.addMessageListener(listener, new ChannelTopic(channel));

        emitter.onCompletion(() -> {
            removeEmitter(projectId, emitter);
            redisContainer.removeMessageListener(listener);
            cleanupIfEmpty(projectId);
        });
        emitter.onTimeout(() -> {
            removeEmitter(projectId, emitter);
            redisContainer.removeMessageListener(listener);
            cleanupIfEmpty(projectId);
        });
        emitter.onError(e -> {
            removeEmitter(projectId, emitter);
            redisContainer.removeMessageListener(listener);
            cleanupIfEmpty(projectId);
        });

        return emitter;
    }

    private void sendToEmitters(String projectId, Map<String, Object> data) {
        CopyOnWriteArraySet<SseEmitter> set = emitters.get(projectId);
        if (set == null) return;
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("status-change").data(data));
            } catch (IOException e) {
                removeEmitter(projectId, emitter);
            }
        }
    }

    private void removeEmitter(String projectId, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> set = emitters.get(projectId);
        if (set != null) set.remove(emitter);
    }

    private void cleanupIfEmpty(String projectId) {
        CopyOnWriteArraySet<SseEmitter> set = emitters.get(projectId);
        if (set != null && set.isEmpty()) {
            emitters.remove(projectId);
        }
    }
}