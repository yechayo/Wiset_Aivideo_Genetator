package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 视频生产任务实体
 * 跟踪单个分镜的视频生成任务
 */
@Data
@TableName("video_production_task")
public class VideoProductionTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 任务ID（唯一标识）
     */
    private String taskId;

    /**
     * 关联的剧集ID
     */
    private Long episodeId;

    /**
     * 分镜索引（从0开始）
     */
    private Integer panelIndex;

    /**
     * 任务组ID（多个分镜可分组生成）
     */
    private String taskGroup;

    /**
     * 场景描述
     */
    private String sceneDescription;

    /**
     * 视频生成提示词
     */
    private String videoPrompt;

    /**
     * 参考图URL
     */
    private String referenceImageUrl;

    /**
     * 目标时长（秒）
     */
    private Integer targetDuration;

    /**
     * 视频生成服务返回的任务ID
     */
    private String videoTaskId;

    /**
     * 生成的视频URL
     */
    private String videoUrl;

    /**
     * 任务状态: PENDING, PROCESSING, COMPLETED, FAILED
     */
    private String status;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 上一段视频的尾帧URL（用于连续视频生成）
     */
    private String lastFrameUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
