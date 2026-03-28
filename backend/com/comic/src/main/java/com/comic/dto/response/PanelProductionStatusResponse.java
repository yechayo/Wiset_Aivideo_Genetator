package com.comic.dto.response;

import lombok.Data;

@Data
public class PanelProductionStatusResponse {
    private Long panelId;
    private String overallStatus;
    private String currentStage;

    private String backgroundStatus;
    private String backgroundUrl;

    private String comicStatus;
    private String comicUrl;

    private String videoStatus;
    private String videoUrl;
    private Integer videoDuration;
    private String videoTaskId;
    private Boolean offPeak;

    private String errorMessage;
}