package com.comic.dto.model;

import lombok.Data;

/**
 * 视频片段模型
 * 表示一个视频片段及其元数据
 */
@Data
public class VideoSegmentModel {
    /**
     * 视频URL
     */
    private String url;

    /**
     * 视频时长（秒）
     */
    private float duration;

    /**
     * 对应的分镜索引
     */
    private int panelIndex;

    public VideoSegmentModel() {
    }

    public VideoSegmentModel(String url, float duration, int panelIndex) {
        this.url = url;
        this.duration = duration;
        this.panelIndex = panelIndex;
    }
}
