package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("job")
public class Job {
    @TableId(type = IdType.ASSIGN_UUID)
    private String jobId;
    private String jobType;
    private String status;
    private Integer progress;
    private String progressMsg;
    private String inputParams;
    private String resultData;
    private String errorMsg;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
