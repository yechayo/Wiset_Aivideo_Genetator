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

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append("（").append(charId).append("）");
        sb.append("：位置=").append(location != null ? location : "未知");
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
