package com.comic.dto.response;

import lombok.Data;

/**
 * 角色生成状态响应DTO
 */
@Data
public class CharacterStatusResponse {
    private String charId;
    private String name;
    private String role;                      // 角色（主角/反派/配角）
    private String personality;               // 性格描述
    private String voice;                     // 声音描述
    private String appearance;                // 外貌描述
    private String background;                // 背景故事
    private Boolean confirmed;                // 是否已确认
    private String expressionStatus;         // 表情生成状态: pending/generating/completed/failed
    private String threeViewStatus;          // 三视图生成状态: pending/generating/completed/failed
    private String expressionError;          // 表情生成错误信息
    private String threeViewError;           // 三视图生成错误信息
    private Boolean isGeneratingExpression;  // 正在生成表情
    private Boolean isGeneratingThreeView;   // 正在生成三视图
    private String visualStyle;              // 视觉风格: 3D/REAL/ANIME
    private String expressionGridUrl;        // 九宫格大全图URL
    private String threeViewGridUrl;         // 三视图大全图URL
}
