package com.comic.statemachine.enums;

import lombok.Getter;

/**
 * 项目状态枚举（用于状态机）
 * 映射到 ProjectStatus，但更精简，用于状态机配置
 */
@Getter
public enum ProjectState {

    // ===== 剧本阶段 =====
    DRAFT(ProjectPhase.SCRIPT),
    OUTLINE_GENERATING(ProjectPhase.SCRIPT),
    OUTLINE_REVIEW(ProjectPhase.SCRIPT),
    EPISODE_GENERATING(ProjectPhase.SCRIPT),
    SCRIPT_REVIEW(ProjectPhase.SCRIPT),
    SCRIPT_CONFIRMED(ProjectPhase.SCRIPT),
    OUTLINE_GENERATING_FAILED(ProjectPhase.SCRIPT),
    EPISODE_GENERATING_FAILED(ProjectPhase.SCRIPT),

    // ===== 角色阶段 =====
    CHARACTER_EXTRACTING(ProjectPhase.CHARACTER),
    CHARACTER_REVIEW(ProjectPhase.CHARACTER),
    CHARACTER_CONFIRMED(ProjectPhase.CHARACTER),
    CHARACTER_EXTRACTING_FAILED(ProjectPhase.CHARACTER),

    // ===== 图像阶段 =====
    IMAGE_GENERATING(ProjectPhase.IMAGE),
    IMAGE_REVIEW(ProjectPhase.IMAGE),
    ASSET_LOCKED(ProjectPhase.IMAGE),
    IMAGE_GENERATING_FAILED(ProjectPhase.IMAGE),

    // ===== 分镜阶段 =====
    STORYBOARD_GENERATING(ProjectPhase.STORYBOARD),
    STORYBOARD_REVIEW(ProjectPhase.STORYBOARD),
    STORYBOARD_GENERATING_FAILED(ProjectPhase.STORYBOARD),

    // ===== 生产阶段 =====
    PRODUCING(ProjectPhase.PRODUCTION),
    COMPLETED(ProjectPhase.PRODUCTION);

    private final ProjectPhase phase;

    ProjectState(ProjectPhase phase) {
        this.phase = phase;
    }

    /**
     * 是否为失败状态
     */
    public boolean isFailed() {
        return this.name().endsWith("_FAILED");
    }

    /**
     * 是否为生成中状态
     */
    public boolean isGenerating() {
        return this.name().endsWith("_GENERATING");
    }

    /**
     * 是否为审核状态
     */
    public boolean isReview() {
        return this.name().endsWith("_REVIEW");
    }

    /**
     * 获取可重试的状态（失败状态的重试目标）
     */
    public ProjectState getRetryTarget() {
        if (!isFailed()) {
            return null;
        }
        // OUTLINE_GENERATING_FAILED -> OUTLINE_GENERATING
        String targetName = this.name().replace("_FAILED", "");
        try {
            return ProjectState.valueOf(targetName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 项目阶段分组
     */
    public enum ProjectPhase {
        SCRIPT,      // 剧本阶段
        CHARACTER,   // 角色阶段
        IMAGE,       // 图像阶段
        STORYBOARD,  // 分镜阶段
        PRODUCTION   // 生产阶段
    }
}
