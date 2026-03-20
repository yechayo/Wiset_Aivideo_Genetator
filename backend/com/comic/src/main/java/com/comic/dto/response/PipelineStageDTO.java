package com.comic.dto.response;

import lombok.Data;

/**
 * 管线阶段DTO
 * 用于前端展示生产管线每个阶段的状态
 */
@Data
public class PipelineStageDTO {

    /**
     * 阶段标识: storyboard, analyzing, grid_generating, grid_fusion, prompt_building, video_generating, subtitle, composition, completed
     */
    private String key;

    /**
     * 阶段中文名
     */
    private String name;

    /**
     * 显示状态: pending, active, waiting_user, completed, failed
     */
    private String displayStatus;

    /**
     * 进度百分比 0-100
     */
    private int progress;

    /**
     * 阶段描述或错误信息
     */
    private String message;
}
