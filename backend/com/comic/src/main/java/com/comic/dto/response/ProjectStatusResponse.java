package com.comic.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 项目状态详情响应DTO
 */
@Data
public class ProjectStatusResponse {

    /**
     * 项目ID
     */
    private String projectId;

    /**
     * 状态码
     */
    private String statusCode;

    /**
     * 状态描述
     */
    private String statusDescription;

    /**
     * 当前前端步骤
     */
    private int currentStep;

    /**
     * 是否正在生成中
     */
    private boolean isGenerating;

    /**
     * 是否为失败状态
     */
    private boolean isFailed;

    /**
     * 是否为审核状态
     */
    private boolean isReview;

    /**
     * 已完成的步骤列表
     */
    private List<Integer> completedSteps;

    /**
     * 可用的操作列表
     */
    private List<String> availableActions;

    /**
     * 生产进度百分比（仅 PRODUCING 阶段有意义）
     */
    private Integer productionProgress;

    /**
     * 剧集生产子阶段描述（仅 PRODUCING 阶段有意义，如"正在分析场景..."）
     */
    private String productionSubStage;

    /**
     * 分镜当前集数（仅 STORYBOARD_* 阶段有意义）
     */
    private Integer storyboardCurrentEpisode;

    /**
     * 分镜总集数（仅 STORYBOARD_* 阶段有意义）
     */
    private Integer storyboardTotalEpisodes;

    /**
     * 分镜当前审核的剧集ID（仅 STORYBOARD_REVIEW 阶段有意义）
     */
    private Long storyboardReviewEpisodeId;

    /**
     * 所有集分镜是否已全部审核确认
     */
    private boolean storyboardAllConfirmed;
}
