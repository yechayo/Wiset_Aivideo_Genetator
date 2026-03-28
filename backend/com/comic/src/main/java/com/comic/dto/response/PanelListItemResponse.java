package com.comic.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class PanelListItemResponse {
    private Long id;
    private Long episodeId;
    private String status;
    private Map<String, Object> panelInfo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}