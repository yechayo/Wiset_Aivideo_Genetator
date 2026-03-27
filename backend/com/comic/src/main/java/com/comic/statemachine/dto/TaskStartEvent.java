package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 任务开始事件
 */
@Data
@Builder
public class TaskStartEvent {
    private String projectId;
    private String taskType;
    private long timestamp;
}
