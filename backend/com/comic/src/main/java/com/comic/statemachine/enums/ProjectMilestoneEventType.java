package com.comic.statemachine.enums;

/**
 * 里程碑状态机事件类型
 *
 * 只包含确认和回滚事件。生成中状态不经过状态机，由 Service 层直接处理。
 */
public enum ProjectMilestoneEventType {

    // ===== 确认操作（经过状态机） =====
    CONFIRM_OUTLINE,
    CONFIRM_EPISODE,
    CONFIRM_ASSETS,
    CONFIRM_PANELS,

    // ===== 回滚操作（经过状态机） =====
    ROLLBACK_TO_DRAFT,
    ROLLBACK_TO_OUTLINE_CONFIRMED,
    ROLLBACK_TO_EPISODE_CONFIRMED,
    ROLLBACK_TO_ASSET_CONFIRMED,

    // ===== 内部事件 =====
    _ASSEMBLE_DONE
}
