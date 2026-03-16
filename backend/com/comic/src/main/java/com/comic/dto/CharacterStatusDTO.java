package com.comic.dto;

import lombok.Data;

import java.util.List;

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
    private List<ExpressionImage> expressionSheet;  // 九宫格图片列表
    private List<ThreeViewImage> threeViewSheet;    // 三视图图片列表
    private String standardImageUrl;         // 标准形象图URL
}
