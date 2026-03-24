package com.comic.dto.request;

import lombok.Data;
import java.util.List;

/**
 * 单分镜生产请求
 */
@Data
public class ProduceRequest {
    private String backgroundUrl;
    private List<String> characterRefs;
}
