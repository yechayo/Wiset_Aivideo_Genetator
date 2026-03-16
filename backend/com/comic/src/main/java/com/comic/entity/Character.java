package com.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_character")
public class Character {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String charId;
    private String name;
    private String role;
    private String profileJson;
    private String currentStateJson;

    // 新增字段（用于角色提取和形象管理）
    private String projectId;         // 项目ID
    private String personality;       // 性格描述
    private String appearance;        // 外貌描述
    private String background;        // 背景故事
    private String standardImageUrl;  // 标准形象图URL
    private Boolean confirmed;        // 是否已确认
    private Boolean locked;           // 是否已锁定资产

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
