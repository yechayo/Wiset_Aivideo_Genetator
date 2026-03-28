package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class PanelUpdateRequest {
    private String status;
    private Map<String, Object> panelInfo;
}