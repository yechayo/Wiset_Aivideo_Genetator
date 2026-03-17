package com.comic.dto;

import lombok.Data;

/**
 * 角色生成状态传输对象
 */
@Data
public class CharacterStatusDTO {
    private String charId;
    private String name;
    private String role;                      // 角色（主角/反派/配角）
    private String expressionStatus;         // 表情生成状态: pending/generating/completed/failed
    private String threeViewStatus;          // 三视图生成状态: pending/generating/completed/failed
    private String expressionError;          // 表情生成错误信息
    private String threeViewError;           // 三视图生成错误信息
    private Boolean isGeneratingExpression;  // 正在生成表情
    private Boolean isGeneratingThreeView;   // 正在生成三视图
    private String standardImageUrl;         // 标准形象图URL
    private String visualStyle;              // 视觉风格: 3D/REAL/ANIME
    private String expressionGridUrl;        // 九宫格大全图URL
    private String threeViewGridUrl;         // 三视图大全图URL
}
