package com.comic.dto.response;

import lombok.Data;

/**
 * 项目级生产摘要（PRODUCING 阶段专用）
 */
@Data
public class ProjectProductionSummaryResponse {

    private Long currentEpisodeId;
    private Long currentPanelId;
    private Integer currentPanelIndex;
    private Integer totalPanelCount;
    private Integer completedPanelCount;
    private String productionSubStage;
    private String blockedReason;
}
