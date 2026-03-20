package com.comic.dto.response;

import lombok.Data;

/**
 * 单集生产状态响应DTO
 * 用于前端轮询获取生产进度
 */
@Data
public class ProductionStatusResponse {

    /**
     * 生产任务ID
     */
    private String productionId;

    /**
     * 剧集ID
     */
    private Long episodeId;

    /**
     * 生产状态: PENDING, ANALYZING, GRID_GENERATING, QUEUING, GENERATING, SUBTITLING, COMPOSING, COMPLETED, FAILED
     */
    private String status;

    /**
     * 当前阶段描述
     */
    private String currentStage;

    /**
     * 进度百分比 (0-100)
     */
    private Integer progressPercent;

    /**
     * 进度消息
     */
    private String progressMessage;

    /**
     * 总分镜数
     */
    private Integer totalPanels;

    /**
     * 已完成分镜数
     */
    private Integer completedPanels;

    /**
     * 场景九宫格图URL
     */
    private String sceneGridUrl;

    /**
     * 融合后的参考图URL
     */
    private String fusedReferenceImageUrl;

    /**
     * 最终视频URL（完成后可用）
     */
    private String finalVideoUrl;

    /**
     * 错误消息（失败时）
     */
    private String errorMessage;
}
