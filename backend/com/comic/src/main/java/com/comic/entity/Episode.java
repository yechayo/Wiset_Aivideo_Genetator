package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.comic.config.JsonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "episode", autoResultMap = true)
public class Episode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;

    @TableLogic
    private Boolean deleted = false;

    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> episodeInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}