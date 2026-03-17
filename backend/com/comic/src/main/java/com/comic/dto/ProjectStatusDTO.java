package com.comic.dto;

import lombok.Data;

import java.util.List;

/**
 * 项目状态详情 DTO
 * 返回给前端的完整状态信息，包含步骤映射和可用操作
 */
@Data
public class ProjectStatusDTO {
    /** 项目ID */
    private String projectId;

    /** 状态码，如 "OUTLINE_REVIEW" */
    private String statusCode;

    /** 状态描述，如 "大纲审核" */
    private String statusDescription;

    /** 当前前端步骤 (1-5) */
    private int currentStep;

    /** 是否正在生成中 */
    private boolean isGenerating;

    /** 是否为失败状态 */
    private boolean isFailed;

    /** 是否为审核状态 */
    private boolean isReview;

    /** 已完成的步骤列表 */
    private List<Integer> completedSteps;

    /** 当前可执行的操作列表 */
    private List<String> availableActions;
}
