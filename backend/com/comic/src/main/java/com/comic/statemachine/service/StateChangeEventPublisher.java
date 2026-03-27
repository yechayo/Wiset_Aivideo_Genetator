package com.comic.statemachine.service;

import com.comic.statemachine.dto.FailureEvent;
import com.comic.statemachine.dto.ProgressEvent;
import com.comic.statemachine.dto.StateChangeEvent;
import com.comic.statemachine.dto.TaskCompleteEvent;
import com.comic.statemachine.dto.TaskStartEvent;
import com.comic.statemachine.enums.ProjectState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 状态变更事件发布器
 * 作为状态机和 SSE 推送之间的桥梁
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateChangeEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 发布状态变更事件
     */
    public void publishStateChange(String projectId, ProjectState newState) {
        StateChangeEvent event = StateChangeEvent.builder()
                .projectId(projectId)
                .oldState(null) // 可选：从上下文获取旧状态
                .newState(newState)
                .timestamp(System.currentTimeMillis())
                .build();

        eventPublisher.publishEvent(event);
        log.debug("State change event published: projectId={}, newState={}", projectId, newState);
    }

    /**
     * 发布进度更新事件（节流）
     */
    public void publishProgress(String projectId, int progress, String message) {
        ProgressEvent event = ProgressEvent.builder()
                .projectId(projectId)
                .progress(progress)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();

        eventPublisher.publishEvent(event);
    }

    /**
     * 发布任务失败事件
     */
    public void publishFailure(String projectId, String error) {
        FailureEvent event = FailureEvent.builder()
                .projectId(projectId)
                .error(error)
                .timestamp(System.currentTimeMillis())
                .build();

        eventPublisher.publishEvent(event);
    }

    /**
     * 发布任务开始事件
     */
    public void publishTaskStart(String projectId, String taskType) {
        TaskStartEvent event = TaskStartEvent.builder()
                .projectId(projectId)
                .taskType(taskType)
                .timestamp(System.currentTimeMillis())
                .build();

        eventPublisher.publishEvent(event);
    }

    /**
     * 发布任务完成事件
     */
    public void publishTaskComplete(String projectId, String taskType, Object result) {
        TaskCompleteEvent event = TaskCompleteEvent.builder()
                .projectId(projectId)
                .taskType(taskType)
                .result(result)
                .timestamp(System.currentTimeMillis())
                .build();

        eventPublisher.publishEvent(event);
    }
}
