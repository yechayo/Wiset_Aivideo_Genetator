package com.comic.common;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目状态枚举
 * 统一管理所有项目状态，替代各服务中的散落状态常量
 */
@Getter
public enum ProjectStatus {

    // 初始状态
    DRAFT("DRAFT", "草稿", 1),

    // 剧本阶段
    OUTLINE_GENERATING("OUTLINE_GENERATING", "大纲生成中", 2),
    OUTLINE_REVIEW("OUTLINE_REVIEW", "大纲审核", 2),
    EPISODE_GENERATING("EPISODE_GENERATING", "剧集生成中", 2),
    SCRIPT_REVIEW("SCRIPT_REVIEW", "剧本审核", 2),
    SCRIPT_CONFIRMED("SCRIPT_CONFIRMED", "剧本已确认", 3),
    OUTLINE_GENERATING_FAILED("OUTLINE_GENERATING_FAILED", "大纲生成失败", 2),
    EPISODE_GENERATING_FAILED("EPISODE_GENERATING_FAILED", "剧集生成失败", 2),

    // 角色阶段
    CHARACTER_EXTRACTING("CHARACTER_EXTRACTING", "角色提取中", 3),
    CHARACTER_REVIEW("CHARACTER_REVIEW", "角色审核", 3),
    CHARACTER_CONFIRMED("CHARACTER_CONFIRMED", "角色已确认", 4),
    CHARACTER_EXTRACTING_FAILED("CHARACTER_EXTRACTING_FAILED", "角色提取失败", 3),

    // 图像阶段
    IMAGE_GENERATING("IMAGE_GENERATING", "图像生成中", 4),
    IMAGE_REVIEW("IMAGE_REVIEW", "图像审核", 4),
    ASSET_LOCKED("ASSET_LOCKED", "素材已锁定", 4),
    IMAGE_GENERATING_FAILED("IMAGE_GENERATING_FAILED", "图像生成失败", 4),

    // 分镜阶段（逐集生成+逐集审核）
    STORYBOARD_GENERATING("STORYBOARD_GENERATING", "分镜生成中", 5),
    STORYBOARD_REVIEW("STORYBOARD_REVIEW", "分镜审核", 5),
    STORYBOARD_GENERATING_FAILED("STORYBOARD_GENERATING_FAILED", "分镜生成失败", 5),

    // 生产阶段
    PRODUCING("PRODUCING", "生产中", 6),
    COMPLETED("COMPLETED", "已完成", 6);

    /** 状态码 */
    private final String code;

    /** 状态描述 */
    private final String description;

    /** 对应前端步骤 (1-6) */
    private final int frontendStep;

    ProjectStatus(String code, String description, int frontendStep) {
        this.code = code;
        this.description = description;
        this.frontendStep = frontendStep;
    }

    /**
     * 根据状态码获取枚举，支持旧状态值兼容
     */
    public static ProjectStatus fromCode(String code) {
        if (code == null) {
            return DRAFT;
        }
        // 兼容旧状态值
        if ("SCRIPT_GENERATING".equals(code)) {
            return OUTLINE_GENERATING;
        }
        if ("SCRIPT_REVISION_REQUESTED".equals(code)) {
            return OUTLINE_REVIEW;
        }
        for (ProjectStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        // 未知状态码返回 DRAFT
        return DRAFT;
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
     * 获取已完成的前端步骤列表
     * 例如当前状态为 SCRIPT_REVIEW (step=2)，则 step 1 已完成
     */
    public List<Integer> getCompletedSteps() {
        int current = this.frontendStep;
        List<Integer> steps = new java.util.ArrayList<>();
        for (int i = 1; i < current; i++) {
            steps.add(i);
        }
        // 当前步骤处于确认态时，当前步骤也算完成
        if (this == SCRIPT_CONFIRMED || this == CHARACTER_CONFIRMED || this == ASSET_LOCKED
                || this == COMPLETED || this == STORYBOARD_REVIEW) {
            steps.add(current);
        }
        return steps;
    }

    /**
     * 获取当前状态下可执行的操作列表
     */
    public List<String> getAvailableActions() {
        switch (this) {
            case DRAFT:
                return Arrays.asList("generate_outline");
            case OUTLINE_GENERATING:
                return Arrays.asList();
            case OUTLINE_REVIEW:
                return Arrays.asList("generate_episodes", "revise_outline", "confirm_script");
            case EPISODE_GENERATING:
                return Arrays.asList();
            case SCRIPT_REVIEW:
                return Arrays.asList("generate_episodes", "revise_episodes", "confirm_script");
            case SCRIPT_CONFIRMED:
                return Arrays.asList("extract_characters", "revise_outline");
            case CHARACTER_EXTRACTING:
                return Arrays.asList();
            case CHARACTER_REVIEW:
                return Arrays.asList("confirm_characters", "update_character");
            case CHARACTER_CONFIRMED:
                return Arrays.asList("generate_images");
            case IMAGE_GENERATING:
                return Arrays.asList();
            case IMAGE_REVIEW:
                return Arrays.asList("confirm_images");
            case ASSET_LOCKED:
                return Arrays.asList("start_storyboard");
            case STORYBOARD_GENERATING:
                return Arrays.asList();
            case STORYBOARD_REVIEW:
                return Arrays.asList("confirm_storyboard", "revise_storyboard", "start_production");
            case PRODUCING:
                return Arrays.asList();
            case COMPLETED:
                return Arrays.asList("view_result");
            default:
                // 失败状态可以重试
                return Arrays.asList("retry");
        }
    }
}
