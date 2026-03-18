package com.comic.dto.model;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 视频任务组模型
 * 多个分镜可以组合成一个视频生成任务
 */
@Data
public class VideoTaskGroupModel {

    /**
     * 任务组ID
     */
    private String groupId;

    /**
     * 包含的分镜索引列表
     */
    private List<Integer> panelIndexes;

    /**
     * 总时长（秒）
     */
    private Integer totalDuration;

    /**
     * 提示词列表（每个分镜一个）
     */
    private List<VideoPromptModel> prompts;

    /**
     * 融合参考图URL（可选）
     */
    private String fusedReferenceImageUrl;

    public VideoTaskGroupModel() {
        this.groupId = "GROUP-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 获取分镜数量
     */
    public int getPanelCount() {
        return panelIndexes != null ? panelIndexes.size() : 0;
    }
}
