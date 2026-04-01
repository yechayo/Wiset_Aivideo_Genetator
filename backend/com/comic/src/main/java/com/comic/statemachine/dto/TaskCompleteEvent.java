package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaskCompleteEvent {
    private String projectId;
    private String taskType;
    private Object result;
    private long timestamp;
}