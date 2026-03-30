package com.comic.dto.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CharacterStateModel {
    private String charId;
    private String name;
    private String location;
    private String emotion;
    private List<String> inventory;
    private String costumeState;
    private Map<String, String> relationships;

    // 角色特征字段（用于分镜规划和生成）
    private String appearance;           // 中文外貌描述
    private String appearancePrompt;     // 英文AI生图提示词
    private String personality;          // 性格特征

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append("（").append(charId).append("）");

        // 外貌特征
        if (appearance != null && !appearance.isEmpty()) {
            sb.append("【外貌】").append(appearance);
        }

        // 性格特征
        if (personality != null && !personality.isEmpty()) {
            sb.append("【性格】").append(personality);
        }

        // 当前状态
        sb.append("【当前状态】");
        sb.append("位置=").append(location != null ? location : "未知");
        sb.append("，情绪=").append(emotion != null ? emotion : "平静");
        if (inventory != null && !inventory.isEmpty()) {
            sb.append("，持有=");
            for (int i = 0; i < inventory.size(); i++) {
                if (i > 0) sb.append("/");
                sb.append(inventory.get(i));
            }
        }
        if (costumeState != null) {
            sb.append("，服装=").append(costumeState);
        }
        return sb.toString();
    }
}
