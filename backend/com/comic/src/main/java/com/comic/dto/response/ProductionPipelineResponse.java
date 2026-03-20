package com.comic.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 生产管线全链路状态响应
 * 用于前端 Step5 页面展示管线可视化
 */
@Data
public class ProductionPipelineResponse {

    /**
     * 剧集ID
     */
    private String episodeId;

    /**
     * 剧集标题
     */
    private String episodeTitle;

    /**
     * 剧集状态: DRAFT, GENERATING, DONE, FAILED
     */
    private String episodeStatus;

    /**
     * 生产状态: NOT_STARTED, IN_PROGRESS, COMPLETED, FAILED
     */
    private String productionStatus;

    /**
     * 管线阶段列表
     */
    private List<PipelineStageDTO> stages;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 最终视频URL
     */
    private String finalVideoUrl;

    /**
     * 已生成的场景网格图URL列表（支持边生成边展示）
     */
    private List<String> sceneGridUrls;
}
