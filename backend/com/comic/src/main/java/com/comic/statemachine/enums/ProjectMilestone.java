package com.comic.statemachine.enums;

import lombok.Getter;

/**
 * 项目里程碑状态（MySQL 持久化，6 个）
 *
 * 前端 step 由 ProjectService 根据 milestone + 数据存在性推导，不放在枚举里。
 */
@Getter
public enum ProjectMilestone {

    DRAFT("draft", "草稿"),
    OUTLINE_CONFIRMED("outline_confirmed", "大纲已确认"),
    EPISODE_CONFIRMED("episode_confirmed", "分集剧本已确认"),
    ASSET_CONFIRMED("asset_confirmed", "素材已确认"),
    PANEL_CONFIRMED("panel_confirmed", "面板已确认"),
    COMPLETED("completed", "已完成");

    private final String code;
    private final String description;

    ProjectMilestone(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static ProjectMilestone fromCode(String code) {
        if (code == null) return DRAFT;
        for (ProjectMilestone m : values()) {
            if (m.code.equals(code)) return m;
        }
        return DRAFT;
    }
}
