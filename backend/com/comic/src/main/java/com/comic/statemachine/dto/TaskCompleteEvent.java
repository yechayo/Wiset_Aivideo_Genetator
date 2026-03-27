package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 任务完成事件
 */
@Data
@Builder
public class TaskCompleteEvent {
    private String projectId;
    private String taskType;
    private Object result;
    private long timestamp;
}
