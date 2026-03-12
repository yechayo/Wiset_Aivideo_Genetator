package com.comic.dto;

import lombok.Data;
import java.util.List;

@Data
public class WorldConfigDTO {
    private String seriesId;
    private String seriesName;
    private String genre;
    private String targetAudience;
    private List<String> rules;

    public String getRulesText() {
        if (rules == null || rules.isEmpty()) return "暂无特殊规则";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            sb.append(i + 1).append(". ").append(rules.get(i)).append("\n");
        }
        return sb.toString();
    }
}
