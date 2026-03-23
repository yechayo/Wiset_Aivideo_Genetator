package com.comic.dto.response;

import lombok.Data;

/**
 * 单个格子的状态（原子化模式用）
 */
@Data
public class PanelStateResponse {
    /**
     * 分镜索引
     */
    private Integer panelIndex;

    /**
     * 融合状态: "pending" | "completed"
     */
    private String fusionStatus;

    /**
     * 融合图URL
     */
    private String fusionUrl;

    /**
     * 提示词
     */
    private String promptText;

    /**
     * 视频状态: "pending" | "generating" | "completed" | "failed"
     */
    private String videoStatus;

    /**
     * 视频URL
     */
    private String videoUrl;

    /**
     * 视频任务ID
     */
    private String videoTaskId;

    /**
     * 分镜描述信息
     */
    private String sceneDescription;

    /**
     * 镜头类型
     */
    private String shotType;

    /**
     * 对话
     */
    private String dialogue;

    /**
     * 格子标识（如 "ep1_p1"）
     */
    private String panelId;
}
