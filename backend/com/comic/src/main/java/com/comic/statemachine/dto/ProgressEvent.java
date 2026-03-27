package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 进度更新事件
 */
@Data
@Builder
public class ProgressEvent {
    private String projectId;
    private int progress;
    private String message;
    private long timestamp;
}
