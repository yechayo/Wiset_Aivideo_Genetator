package com.comic.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Project status detail DTO for frontend polling.
 */
@Data
public class ProjectStatusResponse {

    private String projectId;

    private String statusCode;

    private String statusDescription;

    private int currentStep;

    @JsonProperty("isGenerating")
    private boolean isGenerating;

    @JsonProperty("isFailed")
    private boolean isFailed;

    @JsonProperty("isReview")
    private boolean isReview;

    private List<Integer> completedSteps;

    private List<String> availableActions;

    private Integer productionProgress;

    private String productionSubStage;

    private Integer panelCurrentEpisode;

    private Integer panelTotalEpisodes;

    private String panelReviewEpisodeId;

    private boolean panelAllConfirmed;

    /** 合并阶段：最终视频 URL */
    private String finalVideoUrl;

    /** 合并阶段状态：idle / merging / completed / failed */
    private String mergeStatus;

    /** Redis 存储的失败原因（生成中出错时存在） */
    private String errorMessage;
}