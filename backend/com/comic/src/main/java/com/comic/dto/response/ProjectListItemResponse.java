package com.comic.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Project list item DTO used by project listing API.
 */
@Data
public class ProjectListItemResponse {

    private String projectId;

    private String storyPrompt;

    private String genre;

    private String targetAudience;

    private Integer totalEpisodes;

    private Integer episodeDuration;

    private String visualStyle;

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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}