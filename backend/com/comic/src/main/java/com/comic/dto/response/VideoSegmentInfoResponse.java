package com.comic.dto.response;

import lombok.Data;

/**
 * 视频片段信息响应DTO
 */
@Data
public class VideoSegmentInfoResponse {

    /**
     * 分镜索引
     */
    private Integer panelIndex;

    /**
     * 视频URL
     */
    private String videoUrl;

    /**
     * 目标时长（秒）
     */
    private Integer targetDuration;

    /**
     * 视频生成提示词
     */
    private String videoPrompt;

    /**
     * 场景描述
     */
    private String sceneDescription;
}
