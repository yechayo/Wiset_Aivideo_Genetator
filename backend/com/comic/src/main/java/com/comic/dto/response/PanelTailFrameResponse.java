package com.comic.dto.response;

import lombok.Data;

/**
 * 单分镜尾帧图响应
 */
@Data
public class PanelTailFrameResponse {
    private Integer panelIndex;
    private String tailFrameUrl;
    private String sourceVideoUrl;
    private String status;
}
