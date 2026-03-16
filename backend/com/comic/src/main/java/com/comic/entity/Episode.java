package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("episode")
public class Episode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String projectId;         // 项目ID（关联到 Project）
    private Integer episodeNum;
    private String title;
    private String outlineNode;
    private String storyboardJson;

    // 两级剧本生成新增字段
    private String content;              // 剧本内容（剧本阶段）
    private String characters;           // 涉及角色列表
    private String keyItems;             // 关键物品列表
    private String continuityNote;       // 连贯性说明
    private String chapterTitle;         // 所属章节标题

    private String status;
    private String errorMsg;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
