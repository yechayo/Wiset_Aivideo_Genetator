package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaskStartEvent {
    private String projectId;
    private String taskType;
    private long timestamp;
}