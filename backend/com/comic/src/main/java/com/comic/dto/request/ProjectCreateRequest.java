package com.comic.dto.request;

import lombok.Data;

/**
 * 创建项目请求DTO
 */
@Data
public class ProjectCreateRequest {
    private String storyPrompt;       // 故事提示词/大纲
    private String genre;             // 类型
    private String targetAudience;    // 目标受众
    private Integer totalEpisodes;    // 总集数
    private Integer episodeDuration;  // 单集时长（秒）
    private String visualStyle;       // 视觉风格（如 3D、ANIME、COMIC 等）
    private String imageProvider;     // "seedream" | "nanobanana"
    private String videoProvider;     // "vidu" | "grok"
    private String videoModel;        // "viduq3-pro" | "viduq3-turbo"
    private Boolean videoRefMode;    // true=参考图视频模式, false/null=首帧视频模式
    /** realtime_animation | comic_commentary，缺省由服务端写 realtime_animation */
    private String productionMode;
    private String narrationPerspective;
    private String narrationVoiceId;
    private String protagonistVoiceId;
    /** standard | shuangju */
    private String scriptStyle;
}
