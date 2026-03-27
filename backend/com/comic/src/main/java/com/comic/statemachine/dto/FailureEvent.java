package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 失败事件
 */
@Data
@Builder
public class FailureEvent {
    private String projectId;
    private String error;
    private long timestamp;
}
