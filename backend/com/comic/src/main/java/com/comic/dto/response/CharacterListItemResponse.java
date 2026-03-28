package com.comic.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色列表查询返回的精简DTO
 */
@Data
public class CharacterListItemResponse {
    private String charId;
    private String name;
    private String role;
    private String personality;
    private String voice;
    private String appearance;
    private String background;
    private String visualStyle;
    private String expressionStatus;
    private String threeViewStatus;
    private Boolean confirmed;
    private String species;           // 物种类型
    private LocalDateTime createdAt;
}