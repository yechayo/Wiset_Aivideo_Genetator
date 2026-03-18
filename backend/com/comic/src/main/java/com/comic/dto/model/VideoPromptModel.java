package com.comic.dto.model;

import lombok.Data;

/**
 * 视频生成提示词模型
 */
@Data
public class VideoPromptModel {

    /**
     * 提示词文本
     */
    private String promptText;

    /**
     * 参考图URL（可选）
     */
    private String referenceImageUrl;

    /**
     * 视频时长（秒）
     */
    private Integer duration;

    /**
     * 宽高比 (16:9, 9:16, 1:1)
     */
    private String aspectRatio;

    /**
     * 分镜索引
     */
    private Integer panelIndex;

    public VideoPromptModel() {
        this.aspectRatio = "16:9";
    }

    public VideoPromptModel(String promptText, Integer duration, Integer panelIndex) {
        this.promptText = promptText;
        this.duration = duration;
        this.panelIndex = panelIndex;
        this.aspectRatio = "16:9";
    }
}
