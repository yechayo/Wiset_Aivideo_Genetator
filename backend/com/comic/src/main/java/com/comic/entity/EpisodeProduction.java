package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 单集生产状态实体
 * 跟踪单集视频生产的整体状态和进度
 */
@Data
@TableName("episode_production")
public class EpisodeProduction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 生产任务ID（唯一标识）
     */
    private String productionId;

    /**
     * 关联的剧集ID
     */
    private Long episodeId;

    /**
     * 生产状态: PENDING, ANALYZING, GRID_GENERATING, QUEUING, GENERATING, SUBTITLING, COMPOSING, COMPLETED, FAILED
     */
    private String status;

    /**
     * 当前阶段: SCENE_ANALYSIS, GRID_GENERATION, VIDEO_GENERATION, SUBTITLE_GENERATION, VIDEO_COMPOSITION
     */
    private String currentStage;

    /**
     * 进度百分比 (0-100)
     */
    private Integer progressPercent;

    /**
     * 进度消息
     */
    private String progressMessage;

    /**
     * 场景分析结果（JSON格式）
     */
    private String sceneAnalysisJson;

    /**
     * 生成的场景九宫格图URL
     */
    private String sceneGridUrl;

    /**
     * 前端融合后的参考图OSS URL
     */
    private String fusedReferenceUrl;

    /**
     * 所有场景组的网格图URL列表（JSON数组格式）
     */
    private String sceneGridUrls;

    /**
     * 所有场景组的融合图URL列表（JSON数组格式）
     */
    private String fusedGridUrls;

    /**
     * 总分镜数
     */
    private Integer totalPanels;

    /**
     * 已完成的分镜数
     */
    private Integer completedPanels;

    /**
     * 总视频组数
     */
    private Integer totalVideoGroups;

    /**
     * 已完成的视频组数
     */
    private Integer completedVideoGroups;

    /**
     * 最终合成视频URL
     */
    private String finalVideoUrl;

    /**
     * 字幕文件URL
     */
    private String subtitleUrl;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 错误消息
     */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
}
