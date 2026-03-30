package com.comic.dto.model;

import lombok.Data;

/**
 * 角色草稿模型（从剧本中提取的角色）
 * 主角/反派包含完整字段，配角仅包含基础字段
 */
@Data
public class CharacterDraftModel {
    private String charId;
    private String name;               // 姓名
    private String role;               // 角色（主角/反派/配角）
    private String alias;              // 称谓
    private String personality;        // 性格
    private String appearance;         // 外貌描述（中文）
    private String appearancePrompt;   // 英文生图提示词（关键：供图片/视频AI使用）
    private String profession;         // 职业（含隐藏身份）
    private String background;         // 背景故事
    private String voice;              // 声音特点

    // 主角/反派扩展字段
    private String motivation;         // 核心动机
    private String weakness;           // 恐惧与弱点
    private String relationships;      // 核心关系
    private String habits;             // 语言风格/行为习惯

    private Boolean confirmed;
}
