package com.comic.dto.response;

import lombok.Data;

/**
 * 合成最终视频结果响应
 */
@Data
public class CompositionResultResponse {
    private String finalVideoUrl;
    private Double duration;
    private Integer totalSegments;
    private String status;
}
