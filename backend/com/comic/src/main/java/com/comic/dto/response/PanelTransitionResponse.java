package com.comic.dto.response;

import lombok.Data;

/**
 * 单分镜过渡融合图状态响应
 */
@Data
public class PanelTransitionResponse {
    private Integer panelIndex;
    private String transitionUrl;
    private String status;
    private String sourceFusionUrl;
    private String sourceTailFrameUrl;
}
