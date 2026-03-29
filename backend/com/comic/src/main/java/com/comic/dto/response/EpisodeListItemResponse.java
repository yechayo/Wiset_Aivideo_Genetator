package com.comic.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class EpisodeListItemResponse {
    private Long id;
    private String projectId;
    private Map<String, Object> episodeInfo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}