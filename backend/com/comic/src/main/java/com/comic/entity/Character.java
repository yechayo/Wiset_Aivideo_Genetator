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

    // 角色图片生成相关字段
    // 生成状态字段
    private String expressionStatus;    // 表情生成状态: pending/generating/completed/failed
    private String threeViewStatus;     // 三视图生成状态: pending/generating/completed/failed

    // 生成内容存储（JSON格式）
    private String expressionSheet;     // 九宫格表情图JSON数组
    private String threeViewSheet;      // 三视图JSON数组
    private String expressionPrompt;    // 表情生成提示词
    private String threeViewPrompt;     // 三视图生成提示词

    // 错误信息
    private String expressionError;     // 表情生成错误信息
    private String threeViewError;      // 三视图生成错误信息

    // 生成标志
    private Boolean isGeneratingExpression;   // 正在生成表情
    private Boolean isGeneratingThreeView;    // 正在生成三视图

    // 视觉风格和生成模式
    private String visualStyle;          // 3D/REAL/ANIME，默认3D
    private String generationMode;       // grid/multiple，默认grid

    // 大全图URL
    private String expressionGridUrl;    // 九宫格大全图URL
    private String threeViewGridUrl;     // 三视图大全图URL
    private String expressionGridPrompt; // 九宫格提示词记录
    private String threeViewGridPrompt;  // 三视图提示词记录

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
