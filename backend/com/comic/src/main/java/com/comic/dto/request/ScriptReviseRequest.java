package com.comic.dto.request;

import lombok.Data;

@Data
public class ScriptReviseRequest {
    private String revisionNote;
    private String currentOutline;
}