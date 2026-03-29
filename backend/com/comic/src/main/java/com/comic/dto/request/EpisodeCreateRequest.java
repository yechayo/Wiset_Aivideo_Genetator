package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class EpisodeCreateRequest {
    private Map<String, Object> episodeInfo;
}