package com.comic.dto.model;

import lombok.Data;

import java.util.List;

/**
 * 场景分组模型
 * 表示同一场景的一组分镜
 */
@Data
public class SceneGroupModel {

    /**
     * 起始分镜索引
     */
    private Integer startPanelIndex;

    /**
     * 结束分镜索引
     */
    private Integer endPanelIndex;

    /**
     * 场景ID
     */
    private String sceneId;

    /**
     * 场景位置
     */
    private String location;

    /**
     * 时间（如：白天、夜晚、黄昏）
     */
    private String timeOfDay;

    /**
     * 出场角色列表
     */
    private List<String> characters;

    /**
     * 光线描述
     */
    private String lighting;

    /**
     * 氛围描述
     */
    private String mood;

    /**
     * 场景描述
     */
    private String description;

    /**
     * 分镜数量
     */
    public int getPanelCount() {
        if (startPanelIndex == null || endPanelIndex == null) {
            return 0;
        }
        return endPanelIndex - startPanelIndex + 1;
    }
}
