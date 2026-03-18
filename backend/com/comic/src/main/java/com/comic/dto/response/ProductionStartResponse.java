package com.comic.dto.response;

import lombok.Data;

/**
 * 生产启动响应DTO
 */
@Data
public class ProductionStartResponse {

    /**
     * 生产任务ID
     */
    private String productionId;

    /**
     * 当前状态
     */
    private String status;

    /**
     * 状态消息
     */
    private String message;

    public ProductionStartResponse(String productionId) {
        this.productionId = productionId;
        this.status = "PENDING";
        this.message = "视频生产任务已创建，等待开始...";
    }
}
