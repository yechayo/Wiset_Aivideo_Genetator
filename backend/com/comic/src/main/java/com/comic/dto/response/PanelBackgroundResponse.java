package com.comic.dto.response;

import lombok.Data;

/**
 * 单分镜背景图状态响应
 */
@Data
public class PanelBackgroundResponse {
    private Integer panelIndex;
    private String backgroundUrl;
    private String status;
    private String prompt;
}
