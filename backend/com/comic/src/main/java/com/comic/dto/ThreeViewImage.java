package com.comic.dto;

import lombok.Data;

/**
 * 单个三视图图片
 */
@Data
public class ThreeViewImage {
    private String viewType;  // 视图类型（正面、侧面、背面）
    private String url;       // 图片URL
    private String prompt;    // 生成提示词
}
