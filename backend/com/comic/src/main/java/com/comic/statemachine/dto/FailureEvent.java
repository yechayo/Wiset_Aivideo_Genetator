package com.comic.statemachine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FailureEvent {
    private String projectId;
    private String error;
    private long timestamp;
}