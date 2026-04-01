package com.comic.statemachine.enums;

public enum ProjectEventType {

    // ===== 剧本阶段事件 =====
    GENERATE_OUTLINE,
    _OUTLINE_DONE,
    REQUEST_OUTLINE_REVISION,
    CONFIRM_OUTLINE,
    GENERATE_EPISODES,
    _EPISODE_DONE,
    GENERATE_EPISODE_SCRIPT,
    _EPISODE_SCRIPT_DONE,
    REQUEST_EPISODE_SCRIPT_REVISION,
    CONFIRM_EPISODE_SCRIPT,

    // ===== 角色阶段事件 =====
    EXTRACT_CHARACTERS,
    _CHARACTERS_DONE,
    CONFIRM_CHARACTERS,

    // ===== 素材阶段事件 =====
    GENERATE_IMAGES,
    _IMAGES_DONE,
    CONFIRM_IMAGES,

    // ===== 分镜生产阶段事件 =====
    START_PRODUCTION,
    GENERATE_TEXT,
    _TEXT_DONE,
    REGENERATE_TEXT,
    GENERATE_IMAGE,
    _IMAGE_DONE,
    REGENERATE_IMAGE,
    GENERATE_VIDEO,
    _VIDEO_DONE,
    REGENERATE_VIDEO,
    CONFIRM_PRODUCTION,

    // ===== 分镜阶段事件 =====
    START_STORYBOARD,
    _STORYBOARD_DONE,
    REVISE_STORYBOARD,
    CONFIRM_STORYBOARD,

    // ===== 视频拼接阶段事件 =====
    START_MERGE,
    _MERGE_DONE,

    // ===== 回滚事件 =====
    ROLLBACK_OUTLINE,
    ROLLBACK_EPISODE,
    ROLLBACK_CHARACTER,
    ROLLBACK_IMAGE,
    ROLLBACK_STORYBOARD,

    // ===== 失败事件 =====
    _TASK_FAILED;
}
