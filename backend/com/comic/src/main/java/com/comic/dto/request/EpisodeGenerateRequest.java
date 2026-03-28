package com.comic.dto.request;

import lombok.Data;

@Data
public class EpisodeGenerateRequest {
    private String chapter;
    private Integer episodeCount;
    private String modificationSuggestion;
}