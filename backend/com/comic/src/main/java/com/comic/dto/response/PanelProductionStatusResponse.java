package com.comic.dto.response;

import lombok.Data;
import java.util.List;

/**
 * 单分镜完整生产状态响应
 */
@Data
public class PanelProductionStatusResponse {
    private Integer panelIndex;
    private String overallStatus;
    private String backgroundUrl;
    private String backgroundStatus;
    private String fusionUrl;
    private String fusionStatus;
    private String transitionUrl;
    private String transitionStatus;
    private String videoUrl;
    private String videoStatus;
    private Integer videoDuration;
    private String tailFrameUrl;
    private String currentStage;
}
