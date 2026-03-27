package com.comic.statemachine.enums;

/**
 * 项目状态机事件类型
 * 简单事件用字符串枚举，复杂事件用 ProjectEvent 对象
 */
public enum ProjectEventType {

    // ===== 剧本阶段事件 =====
    GENERATE_OUTLINE,        // 生成大纲
    OUTLINE_GENERATED,       // 大纲生成完成
    REQUEST_OUTLINE_REVISION,// 请求修改大纲
    CONFIRM_OUTLINE,         // 确认大纲
    GENERATE_EPISODES,       // 生成剧集
    EPISODES_GENERATED,      // 剧集生成完成
    CONFIRM_SCRIPT,          // 确认剧本

    // ===== 角色阶段事件 =====
    EXTRACT_CHARACTERS,      // 提取角色
    CHARACTERS_EXTRACTED,    // 角色提取完成
    CONFIRM_CHARACTERS,      // 确认角色
    UPDATE_CHARACTER,        // 更新角色

    // ===== 图像阶段事件 =====
    GENERATE_IMAGES,         // 生成图像
    IMAGES_GENERATED,        // 图像生成完成
    CONFIRM_IMAGES,          // 确认图像

    // ===== 分镜阶段事件 =====
    START_STORYBOARD,        // 开始分镜
    STORYBOARD_GENERATED,    // 分镜生成完成
    REVISE_STORYBOARD,       // 修改分镜
    CONFIRM_STORYBOARD,      // 确认分镜

    // ===== 生产阶段事件 =====
    START_PRODUCTION,        // 开始生产
    PRODUCTION_COMPLETED,    // 生产完成

    // ===== 通用事件 =====
    RETRY,                   // 重试（从失败状态恢复）

    // ===== 内部事件（异步任务完成触发）=====
    _SCRIPT_OUTLINE_DONE,    // 大纲生成完成（内部）
    _EPISODES_DONE,          // 剧集生成完成（内部）
    _CHARACTERS_DONE,        // 角色提取完成（内部）
    _IMAGES_DONE,            // 图像生成完成（内部）
    _STORYBOARD_DONE,        // 分镜生成完成（内部）
    _PRODUCTION_DONE,        // 生产完成（内部）
    _TASK_FAILED;            // 任务失败（内部）

    /**
     * 获取事件字符串（用于简单事件场景）
     */
    public String toEventString() {
        return this.name();
    }
}
