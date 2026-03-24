package com.comic.dto.response;

import lombok.Data;

/**
 * 单分镜视频生成任务状态响应
 */
@Data
public class PanelVideoTaskResponse {
    private Integer panelIndex;
    private String videoUrl;
    private String status;
    private String taskId;
    private Integer duration;
    private String errorMessage;
}
