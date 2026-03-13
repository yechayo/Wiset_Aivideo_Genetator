package com.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_task_execution")
public class TaskExecution {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 任务ID（唯一标识）
     */
    private String taskId;

    /**
     * 任务类型：script_generation, image_generation, video_generation等
     */
    private String taskType;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 节点ID
     */
    private String nodeId;

    /**
     * 任务状态：pending, running, completed, failed, cancelled
     */
    private String status;

    /**
     * 进度（0-100）
     */
    private Integer progress;

    /**
     * 结果数据（JSON格式）
     */
    private String result;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;
}
