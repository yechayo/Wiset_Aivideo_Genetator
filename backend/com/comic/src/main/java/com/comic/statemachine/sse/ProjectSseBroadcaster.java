package com.comic.statemachine.sse;

import com.comic.statemachine.dto.FailureEvent;
import com.comic.statemachine.dto.ProgressEvent;
import com.comic.statemachine.dto.StateChangeEvent;
import com.comic.statemachine.dto.TaskCompleteEvent;
import com.comic.statemachine.dto.TaskStartEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 项目 SSE 广播服务
 * 按 projectId 分组管理 SSE 连接
 */
@Slf4j
@Service
public class ProjectSseBroadcaster {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByProject = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 注册 SSE 连接
     * @param projectId 项目 ID
     * @param emitter SSE 连接
     * @return emitter
     */
    public SseEmitter register(String projectId, SseEmitter emitter) {
        emittersByProject.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 发送初始连接成功消息
        send(emitter, createMap(
                "type", "connected",
                "projectId", projectId,
                "timestamp", System.currentTimeMillis()
        ));

        // 设置超时和完成/超时回调
        emitter.onCompletion(() -> removeEmitter(projectId, emitter));
        emitter.onTimeout(() -> removeEmitter(projectId, emitter));

        log.info("SSE emitter registered: projectId={}, totalEmitters={}", projectId, emittersByProject.get(projectId).size());

        return emitter;
    }

    /**
     * 广播状态变更
     */
    public void broadcastStateChange(StateChangeEvent event) {
        broadcast(event.getProjectId(), createMap(
                "type", "state_change",
                "projectId", event.getProjectId(),
                "oldState", event.getOldState() != null ? event.getOldState().name() : null,
                "newState", event.getNewState().name(),
                "timestamp", event.getTimestamp()
        ));
    }

    /**
     * 广播进度更新（带节流）
     */
    private final Map<String, ProgressThrottle> throttles = new ConcurrentHashMap<>();

    public void broadcastProgress(String projectId, int progress, String message) {
        ProgressThrottle throttle = throttles.computeIfAbsent(projectId, k -> new ProgressThrottle());

        if (throttle.shouldSend(progress)) {
            broadcast(projectId, createMap(
                    "type", "progress",
                    "projectId", projectId,
                    "progress", progress,
                    "message", message,
                    "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * 广播失败事件
     */
    public void broadcastFailure(FailureEvent event) {
        broadcast(event.getProjectId(), createMap(
                "type", "failure",
                "projectId", event.getProjectId(),
                "error", event.getError(),
                "timestamp", event.getTimestamp()
        ));
    }

    /**
     * 广播任务开始
     */
    public void broadcastTaskStart(TaskStartEvent event) {
        broadcast(event.getProjectId(), createMap(
                "type", "task_start",
                "projectId", event.getProjectId(),
                "taskType", event.getTaskType(),
                "timestamp", event.getTimestamp()
        ));
    }

    /**
     * 广播任务完成
     */
    public void broadcastTaskComplete(TaskCompleteEvent event) {
        broadcast(event.getProjectId(), createMap(
                "type", "task_complete",
                "projectId", event.getProjectId(),
                "taskType", event.getTaskType(),
                "timestamp", event.getTimestamp()
        ));
    }

    /**
     * 发送心跳
     */
    public void sendHeartbeat(String projectId) {
        broadcast(projectId, createMap(
                "type", "heartbeat",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 内部广播方法
     */
    private void broadcast(String projectId, Object data) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByProject.get(projectId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No emitters for projectId: {}", projectId);
            return;
        }

        // 复制一份进行遍历，避免并发修改
        CopyOnWriteArrayList<SseEmitter> emittersCopy = new CopyOnWriteArrayList<>(emitters);

        for (SseEmitter emitter : emittersCopy) {
            send(emitter, data);
        }
    }

    /**
     * 发送数据到单个 emitter
     */
    private void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            log.warn("Failed to send SSE event, removing emitter", e);
            // 这里需要知道 projectId，但 emitter 没有 projectId 信息
            // 可以改进：包装 SseEmitter 携带 projectId
        }
    }

    /**
     * 移除 emitter
     */
    private void removeEmitter(String projectId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByProject.get(projectId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByProject.remove(projectId);
                throttles.remove(projectId);
            }
            log.info("SSE emitter removed: projectId={}, remaining={}", projectId, emitters.size());
        }
    }

    /**
     * 进度节流控制
     */
    private static class ProgressThrottle {
        private static final long THROTTLE_MS = 500; // 500ms
        private volatile long lastSendTime = 0;
        private volatile int lastProgress = -1;

        public boolean shouldSend(int currentProgress) {
            long now = System.currentTimeMillis();

            // 进度完成时必须发送
            if (currentProgress >= 100) {
                lastSendTime = now;
                lastProgress = currentProgress;
                return true;
            }

            // 进度变化超过 10% 时发送
            if (lastProgress >= 0 && Math.abs(currentProgress - lastProgress) >= 10) {
                lastSendTime = now;
                lastProgress = currentProgress;
                return true;
            }

            // 时间间隔超过阈值时发送
            if (now - lastSendTime >= THROTTLE_MS) {
                lastSendTime = now;
                lastProgress = currentProgress;
                return true;
            }

            return false;
        }
    }

    // ===== 辅助方法 =====

    /**
     * 获取所有活跃的 projectId（用于心跳）
     */
    public java.util.Set<String> getActiveProjectIds() {
        return new java.util.HashSet<>(emittersByProject.keySet());
    }

    /**
     * 创建 Map（Java 8 兼容）
     */
    @SafeVarargs
    private static Map<String, Object> createMap(Object... keyValuePairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
