package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 创作项目实体
 * 作为整个流水线的根节点，所有数据都挂在 projectId 下
 */
@Data
@TableName("project")
public class Project {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;          // 项目唯一标识（UUID）
    private String userId;             // 用户ID
    private String storyPrompt;        // 故事提示词/大纲
    private String genre;              // 类型（热血玄幻、都市异能等）
    private String targetAudience;     // 目标受众
    private Integer totalEpisodes;     // 总集数
    private Integer episodeDuration;   // 单集时长（秒）

    // 项目状态机
    // DRAFT → SCRIPT_GENERATING → SCRIPT_REVIEW → SCRIPT_CONFIRMED
    // → CHARACTER_EXTRACTING → CHARACTER_REVIEW → CHARACTER_CONFIRMED
    // → IMAGE_GENERATING → IMAGE_REVIEW → ASSET_LOCKED → PRODUCING → COMPLETED
    private String status;

    private String seriesId;           // 生成的seriesId（关联到Episode）
    private String scriptRevisionNote; // 剧本修改意见

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
