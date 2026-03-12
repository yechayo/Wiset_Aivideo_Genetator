package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("episode")
public class Episode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String seriesId;
    private Integer episodeNum;
    private String title;
    private String outlineNode;
    private String storyboardJson;
    private String status;
    private String errorMsg;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
