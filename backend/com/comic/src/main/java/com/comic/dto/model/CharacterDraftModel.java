package com.comic.dto.model;

import lombok.Data;

/**
 * 角色草稿模型（从剧本中提取的角色）
 */
@Data
public class CharacterDraftModel {
    private String charId;            // 角色ID
    private String name;              // 角色名称
    private String role;              // 角色（主角/反派/配角）
    private String personality;       // 性格描述
    private String appearance;        // 外貌描述
    private String background;        // 背景故事
    private Boolean confirmed;        // 是否已确认
}
