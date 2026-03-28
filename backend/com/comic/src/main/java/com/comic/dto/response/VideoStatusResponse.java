package com.comic.dto.response;

import lombok.Data;

@Data
public class VideoStatusResponse {
    private Long panelId;
    private String status;
    private String videoUrl;
    private String taskId;
    private String errorMessage;
    private Integer duration;
}