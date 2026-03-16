package com.comic.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskStatusVO {
    private String taskId;
    private String taskType;
    private Long projectId;
    private String nodeId;
    private String status;
    private Integer progress;
    private String result;
    private String error;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
