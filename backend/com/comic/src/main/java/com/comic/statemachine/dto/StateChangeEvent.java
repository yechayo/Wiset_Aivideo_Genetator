package com.comic.statemachine.dto;

import com.comic.statemachine.enums.ProjectState;
import lombok.Builder;
import lombok.Data;

/**
 * 状态变更事件
 */
@Data
@Builder
public class StateChangeEvent {
    private String projectId;
    private ProjectState oldState;
    private ProjectState newState;
    private long timestamp;
}

