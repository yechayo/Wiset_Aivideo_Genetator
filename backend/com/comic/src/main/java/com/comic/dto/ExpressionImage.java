package com.comic.dto;

import lombok.Data;

/**
 * 单个表情图片
 */
@Data
public class ExpressionImage {
    private String type;      // 表情类型（开心、悲伤、愤怒等）
    private String url;       // 图片URL
    private String prompt;    // 生成提示词
}
