package com.comic.common;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    PANEL_GENERATING("PANEL_GENERATING", "分镜生成中", 5),
    PANEL_REVIEW("PANEL_REVIEW", "分镜审核", 5),
    PANEL_GENERATING_FAILED("PANEL_GENERATING_FAILED", "分镜生成失败", 5),

    // 生产阶段（逐 Panel 视频生产，属于分镜阶段的一部分）
    PRODUCING("PRODUCING", "生产中", 5),

    // 拼接剪辑阶段
    VIDEO_ASSEMBLING("VIDEO_ASSEMBLING", "拼接剪辑中", 6),
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

    /** 合法状态转换: Map<起始状态, Map<事件, 目标状态>> */
    private static final Map<ProjectStatus, Map<String, ProjectStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<ProjectStatus, Map<String, ProjectStatus>> map = new EnumMap<>(ProjectStatus.class);

        // DRAFT → OUTLINE_GENERATING
        put(map, DRAFT, "start_script_generation", OUTLINE_GENERATING);

        // 剧本阶段内部
        put(map, OUTLINE_GENERATING, "script_generated", SCRIPT_REVIEW);
        put(map, OUTLINE_GENERATING, "script_failed", OUTLINE_GENERATING_FAILED);
        put(map, OUTLINE_REVIEW, "generate_episodes", EPISODE_GENERATING);
        put(map, OUTLINE_REVIEW, "revise_outline", OUTLINE_GENERATING);
        put(map, OUTLINE_REVIEW, "confirm_script", SCRIPT_CONFIRMED);
        put(map, EPISODE_GENERATING, "script_generated", SCRIPT_REVIEW);
        put(map, EPISODE_GENERATING, "script_failed", EPISODE_GENERATING_FAILED);
        put(map, SCRIPT_REVIEW, "generate_episodes", EPISODE_GENERATING);
        put(map, SCRIPT_REVIEW, "revise_episodes", EPISODE_GENERATING);
        put(map, SCRIPT_REVIEW, "confirm_script", SCRIPT_CONFIRMED);

        // 失败重试（包括卡在 *_GENERATING 中间状态的情况）
        put(map, OUTLINE_GENERATING_FAILED, "retry", DRAFT);
        put(map, OUTLINE_GENERATING, "retry", DRAFT);
        put(map, EPISODE_GENERATING_FAILED, "retry", OUTLINE_REVIEW);
        put(map, EPISODE_GENERATING, "retry", OUTLINE_REVIEW);
        put(map, CHARACTER_EXTRACTING_FAILED, "retry", SCRIPT_CONFIRMED);
        put(map, CHARACTER_EXTRACTING, "retry", SCRIPT_CONFIRMED);
        put(map, IMAGE_GENERATING_FAILED, "retry", CHARACTER_CONFIRMED);
        put(map, IMAGE_GENERATING, "retry", CHARACTER_CONFIRMED);
        put(map, PANEL_GENERATING_FAILED, "retry", ASSET_LOCKED);
        put(map, PANEL_GENERATING, "retry", ASSET_LOCKED);

        // 剧本 → 角色
        put(map, SCRIPT_CONFIRMED, "start_character_extraction", CHARACTER_EXTRACTING);

        // 角色阶段
        put(map, CHARACTER_EXTRACTING, "characters_extracted", CHARACTER_REVIEW);
        put(map, CHARACTER_EXTRACTING, "characters_failed", CHARACTER_EXTRACTING_FAILED);
        put(map, CHARACTER_REVIEW, "confirm_characters", CHARACTER_CONFIRMED);

        // 角色 → 图像
        put(map, CHARACTER_CONFIRMED, "start_image_generation", IMAGE_GENERATING);

        // 图像阶段
        put(map, IMAGE_GENERATING, "images_generated", IMAGE_REVIEW);
        put(map, IMAGE_GENERATING, "images_failed", IMAGE_GENERATING_FAILED);
        put(map, IMAGE_REVIEW, "confirm_images", ASSET_LOCKED);

        // 素材 → 分镜
        put(map, ASSET_LOCKED, "start_panels", PANEL_GENERATING);

        // 分镜阶段
        put(map, PANEL_GENERATING, "panels_generated", PANEL_REVIEW);
        put(map, PANEL_GENERATING, "panels_failed", PANEL_GENERATING_FAILED);
        put(map, PANEL_REVIEW, "confirm_panels", PANEL_REVIEW);
        put(map, PANEL_REVIEW, "all_panels_confirmed", PRODUCING);
        put(map, PANEL_REVIEW, "revise_panels", PANEL_GENERATING);
        put(map, PANEL_GENERATING_FAILED, "retry", ASSET_LOCKED);

        // 生产 → 拼接剪辑
        put(map, PRODUCING, "production_completed", VIDEO_ASSEMBLING);

        // 拼接剪辑 → 完成
        put(map, VIDEO_ASSEMBLING, "assembly_completed", COMPLETED);

        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private static void put(Map<ProjectStatus, Map<String, ProjectStatus>> map,
                            ProjectStatus from, String event, ProjectStatus to) {
        map.computeIfAbsent(from, k -> new HashMap<>()).put(event, to);
    }

    /**
     * 校验状态转换是否合法，返回目标状态；不合法返回 null。
     */
    public static ProjectStatus resolveTransition(ProjectStatus from, String event) {
        if (from == null || event == null) return null;
        Map<String, ProjectStatus> events = ALLOWED_TRANSITIONS.get(from);
        return events != null ? events.get(event) : null;
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
                || this == COMPLETED || this == PANEL_REVIEW || this == VIDEO_ASSEMBLING) {
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
                return Arrays.asList("revise_outline");
            case CHARACTER_EXTRACTING:
                return Arrays.asList();
            case CHARACTER_REVIEW:
                return Arrays.asList("confirm_characters", "update_character");
            case CHARACTER_CONFIRMED:
                return Arrays.asList();
            case IMAGE_GENERATING:
                return Arrays.asList();
            case IMAGE_REVIEW:
                return Arrays.asList("confirm_images");
            case ASSET_LOCKED:
                return Arrays.asList();
            case PANEL_GENERATING:
                return Arrays.asList();
            case PANEL_REVIEW:
                return Arrays.asList("confirm_panels", "revise_panels");
            case PRODUCING:
                return Arrays.asList();
            case VIDEO_ASSEMBLING:
                return Arrays.asList();
            case COMPLETED:
                return Arrays.asList("view_result");
            default:
                // 失败状态可以重试
                return Arrays.asList("retry");
        }
    }
}
