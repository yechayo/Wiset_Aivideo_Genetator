package com.comic.dto;

import lombok.Data;

/**
 * 创建项目请求DTO
 */
@Data
public class ProjectCreateDTO {
    private String storyPrompt;       // 故事提示词/大纲
    private String genre;             // 类型
    private String targetAudience;    // 目标受众
    private Integer totalEpisodes;    // 总集数
    private Integer episodeDuration;  // 单集时长（秒）
}
