package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "character", autoResultMap = true)
public class Character {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;
    private String status;

    @TableLogic
    private Boolean deleted = false;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> characterInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}