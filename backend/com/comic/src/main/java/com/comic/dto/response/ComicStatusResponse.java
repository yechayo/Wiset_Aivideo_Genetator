package com.comic.dto.response;

import lombok.Data;

@Data
public class ComicStatusResponse {
    private Long panelId;
    private String status;
    private String comicUrl;
    private String backgroundUrl;
    private String errorMessage;
}