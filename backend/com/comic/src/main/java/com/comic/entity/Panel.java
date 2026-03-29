package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.comic.config.JsonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "panel", autoResultMap = true)
public class Panel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long episodeId;
    private String status;

    @TableLogic
    private Boolean deleted = false;

    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> panelInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}