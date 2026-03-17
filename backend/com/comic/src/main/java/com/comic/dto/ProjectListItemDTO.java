package com.comic.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目列表项 DTO
 * 合并项目基本信息 + 状态映射信息，供项目列表接口使用
 */
@Data
public class ProjectListItemDTO {
    /** 项目ID */
    private String projectId;

    /** 故事提示词 */
    private String storyPrompt;

    /** 类型 */
    private String genre;

    /** 目标受众 */
    private String targetAudience;

    /** 总集数 */
    private Integer totalEpisodes;

    /** 单集时长 */
    private Integer episodeDuration;

    /** 状态码 */
    private String statusCode;

    /** 状态描述（中文） */
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

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
