package com.comic.dto.request;

import lombok.Data;

/**
 * 状态推进/回退请求
 */
@Data
public class AdvanceRequest {
    private String direction;  // "forward" or "backward"
    private String event;      // forward 时必填
}