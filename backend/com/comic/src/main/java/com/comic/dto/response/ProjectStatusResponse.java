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

    private Integer storyboardCurrentEpisode;

    private Integer storyboardTotalEpisodes;

    private String storyboardReviewEpisodeId;

    private boolean storyboardAllConfirmed;
}