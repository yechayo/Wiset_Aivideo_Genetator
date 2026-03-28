package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "project", autoResultMap = true)
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;
    private String userId;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Boolean deleted = false;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> projectInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}