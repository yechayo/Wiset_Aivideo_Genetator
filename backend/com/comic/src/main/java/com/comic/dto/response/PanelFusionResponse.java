package com.comic.dto.response;

import lombok.Data;
import java.util.List;

/**
 * 单分镜融合图状态响应
 */
@Data
public class PanelFusionResponse {
    private Integer panelIndex;
    private String fusionUrl;
    private String status;
    private String referenceBackground;
    private List<String> characterRefs;
}
