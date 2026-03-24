package com.comic.dto.request;

import lombok.Data;
import java.util.List;

/**
 * 融合图生成请求
 */
@Data
public class FusionRequest {
    private String backgroundUrl;
    private List<String> characterRefs;
}
