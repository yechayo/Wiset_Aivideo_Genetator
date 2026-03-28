package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class EpisodeUpdateRequest {
    private String status;
    private Map<String, Object> episodeInfo;
}