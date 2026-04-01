package com.comic.statemachine.enums;

import lombok.Getter;
import java.util.Arrays;
import java.util.List;

@Getter
public enum ProjectState {

    // ===== 剧本阶段 =====
    DRAFT("DRAFT", "草稿", 1),
    OUTLINE_GENERATING("OUTLINE_GENERATING", "大纲生成中", 2),
    OUTLINE_GENERATING_FAILED("OUTLINE_GENERATING_FAILED", "大纲生成失败", 2),
    OUTLINE_REVIEW("OUTLINE_REVIEW", "大纲审核", 2),
    EPISODE_GENERATING("EPISODE_GENERATING", "剧集生成中", 2),
    EPISODE_GENERATING_FAILED("EPISODE_GENERATING_FAILED", "剧集生成失败", 2),
    SCRIPT_REVIEW("SCRIPT_REVIEW", "剧本审核", 2),
    SCRIPT_CONFIRMED("SCRIPT_CONFIRMED", "剧本已确认", 3),
    EPISODE_SCRIPT_GENERATING("EPISODE_SCRIPT_GENERATING", "分集剧本生成中", 5),
    EPISODE_SCRIPT_GENERATING_FAILED("EPISODE_SCRIPT_GENERATING_FAILED", "分集剧本生成失败", 5),
    EPISODE_SCRIPT_REVIEW("EPISODE_SCRIPT_REVIEW", "分集剧本审核", 5),

    // ===== 角色阶段 =====
    CHARACTER_EXTRACTING("CHARACTER_EXTRACTING", "角色提取中", 3),
    CHARACTER_EXTRACTING_FAILED("CHARACTER_EXTRACTING_FAILED", "角色提取失败", 3),
    CHARACTER_REVIEW("CHARACTER_REVIEW", "角色审核", 3),
    CHARACTER_CONFIRMED("CHARACTER_CONFIRMED", "角色已确认", 4),

    // ===== 素材阶段 =====
    IMAGE_GENERATING("IMAGE_GENERATING", "图像生成中", 4),
    IMAGE_GENERATING_FAILED("IMAGE_GENERATING_FAILED", "图像生成失败", 4),
    IMAGE_REVIEW("IMAGE_REVIEW", "图像审核", 4),
    ASSET_LOCKED("ASSET_LOCKED", "素材已锁定", 4),

    // ===== 分镜生产阶段（PRODUCING） =====
    PRODUCING("PRODUCING", "分镜生产中", 5),

    // ===== 分镜阶段 =====
    STORYBOARD_GENERATING("STORYBOARD_GENERATING", "分镜生成中", 5),
    STORYBOARD_GENERATING_FAILED("STORYBOARD_GENERATING_FAILED", "分镜生成失败", 5),
    STORYBOARD_REVIEW("STORYBOARD_REVIEW", "分镜审核", 5),

    // ===== 视频拼接阶段 =====
    MERGING("MERGING", "视频拼接中", 6),
    MERGING_FAILED("MERGING_FAILED", "视频拼接失败", 6),

    // ===== 完成 =====
    COMPLETED("COMPLETED", "已完成", 6);

    private final String code;
    private final String description;
    private final int frontendStep;

    ProjectState(String code, String description, int frontendStep) {
        this.code = code;
        this.description = description;
        this.frontendStep = frontendStep;
    }

    public boolean isFailed() {
        return this.name().endsWith("_FAILED");
    }

    public boolean isGenerating() {
        return this.name().endsWith("_GENERATING");
    }

    public boolean isReview() {
        return this.name().endsWith("_REVIEW");
    }

    public List<Integer> getCompletedSteps() {
        int current = this.frontendStep;
        List<Integer> steps = new java.util.ArrayList<>();
        for (int i = 1; i < current; i++) {
            steps.add(i);
        }
        if (this == SCRIPT_CONFIRMED || this == CHARACTER_CONFIRMED || this == ASSET_LOCKED
                || this == COMPLETED || this == STORYBOARD_REVIEW || this == MERGING) {
            steps.add(current);
        }
        return steps;
    }

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
                return Arrays.asList("revise_outline");
            case EPISODE_SCRIPT_GENERATING:
                return Arrays.asList();
            case EPISODE_SCRIPT_GENERATING_FAILED:
                return Arrays.asList("retry");
            case EPISODE_SCRIPT_REVIEW:
                return Arrays.asList("confirm_episode_script", "revise_episode_script");
            case CHARACTER_EXTRACTING:
                return Arrays.asList();
            case CHARACTER_REVIEW:
                return Arrays.asList("confirm_characters", "update_character");
            case CHARACTER_CONFIRMED:
                return Arrays.asList();
            case IMAGE_GENERATING:
                return Arrays.asList();
            case IMAGE_GENERATING_FAILED:
                return Arrays.asList("retry");
            case IMAGE_REVIEW:
                return Arrays.asList("confirm_images");
            case ASSET_LOCKED:
                return Arrays.asList("start_production", "generate_text");
            case PRODUCING:
                return Arrays.asList();
            case STORYBOARD_GENERATING:
                return Arrays.asList();
            case STORYBOARD_GENERATING_FAILED:
                return Arrays.asList("retry");
            case STORYBOARD_REVIEW:
                return Arrays.asList("confirm_storyboard", "revise_storyboard");
            case MERGING:
                return Arrays.asList("merge_videos");
            case COMPLETED:
                return Arrays.asList("view_result");
            default:
                return Arrays.asList("retry");
        }
    }

    public static ProjectState fromCode(String code) {
        if (code == null) return DRAFT;
        // 兼容旧状态值
        if ("SCRIPT_GENERATING".equals(code)) {
            return OUTLINE_GENERATING;
        }
        if ("SCRIPT_REVISION_REQUESTED".equals(code)) {
            return OUTLINE_REVIEW;
        }
        if ("PANEL_GENERATING".equals(code)) {
            return STORYBOARD_GENERATING;
        }
        if ("PANEL_REVIEW".equals(code)) {
            return STORYBOARD_REVIEW;
        }
        if ("PANEL_GENERATING_FAILED".equals(code)) {
            return STORYBOARD_GENERATING_FAILED;
        }
        if ("VIDEO_ASSEMBLING".equals(code)) {
            return PRODUCING;
        }
        for (ProjectState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        return DRAFT;
    }

    /**
     * 解析状态转换（仅用于向后兼容旧代码）
     * 新代码应使用 Spring State Machine 处理状态转换
     * @deprecated Use Spring State Machine transitions instead
     */
    @Deprecated
    public static ProjectState resolveTransition(ProjectState from, String event) {
        // This is a stub for backward compatibility
        // The actual transition logic is now in Spring State Machine configuration
        return null;
    }
}
