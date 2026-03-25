package com.comic.dto.request;

import lombok.Data;

@Data
public class CharacterUpdateRequest {
    private String name;
    private String personality;
    private String voice;
    private String appearance;
    private String background;
}