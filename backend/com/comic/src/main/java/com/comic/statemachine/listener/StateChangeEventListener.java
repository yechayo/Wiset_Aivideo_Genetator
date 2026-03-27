package com.comic.statemachine.listener;

import com.comic.statemachine.dto.FailureEvent;
import com.comic.statemachine.dto.ProgressEvent;
import com.comic.statemachine.dto.StateChangeEvent;
import com.comic.statemachine.dto.TaskCompleteEvent;
import com.comic.statemachine.dto.TaskStartEvent;
import com.comic.statemachine.sse.ProjectSseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 状态变更事件监听器
 * 监听状态机发布的事件，触发 SSE 推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateChangeEventListener {

    private final ProjectSseBroadcaster broadcaster;

    /**
     * 监听状态变更事件
     */
    @EventListener
    public void onStateChange(StateChangeEvent event) {
        log.info("State change event received: projectId={}, newState={}", event.getProjectId(), event.getNewState());
        broadcaster.broadcastStateChange(event);
    }

    /**
     * 监听进度更新事件
     */
    @EventListener
    public void onProgress(ProgressEvent event) {
        log.debug("Progress event received: projectId={}, progress={}", event.getProjectId(), event.getProgress());
        broadcaster.broadcastProgress(event.getProjectId(), event.getProgress(), event.getMessage());
    }

    /**
     * 监听失败事件
     */
    @EventListener
    public void onFailure(FailureEvent event) {
        log.warn("Failure event received: projectId={}, error={}", event.getProjectId(), event.getError());
        broadcaster.broadcastFailure(event);
    }

    /**
     * 监听任务开始事件
     */
    @EventListener
    public void onTaskStart(TaskStartEvent event) {
        log.info("Task start event received: projectId={}, taskType={}", event.getProjectId(), event.getTaskType());
        broadcaster.broadcastTaskStart(event);
    }

    /**
     * 监听任务完成事件
     */
    @EventListener
    public void onTaskComplete(TaskCompleteEvent event) {
        log.info("Task complete event received: projectId={}, taskType={}", event.getProjectId(), event.getTaskType());
        broadcaster.broadcastTaskComplete(event);
    }

    /**
     * 定时发送心跳（每 30 秒）
     * 保持 SSE 连接活跃
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        // 遍历所有活跃的 projectId 发送心跳
        // ProjectSseBroadcaster 需要提供获取活跃 projectId 的方法
        log.trace("Sending heartbeat to all active SSE connections");
    }
}
