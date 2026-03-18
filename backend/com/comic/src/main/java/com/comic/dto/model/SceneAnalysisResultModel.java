package com.comic.dto.model;

import lombok.Data;

import java.util.List;

/**
 * 场景分析结果模型
 */
@Data
public class SceneAnalysisResultModel {

    /**
     * 场景分组列表
     */
    private List<SceneGroupModel> sceneGroups;

    /**
     * 分镜总数
     */
    private Integer totalPanelCount;

    /**
     * 场景总数
     */
    private Integer sceneCount;

    public int getSceneCount() {
        return sceneGroups != null ? sceneGroups.size() : 0;
    }
}
