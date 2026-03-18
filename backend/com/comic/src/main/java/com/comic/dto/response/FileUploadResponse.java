package com.comic.dto.response;

import lombok.Data;

/**
 * 文件上传响应
 */
@Data
public class FileUploadResponse {

    /**
     * 文件ID
     */
    private Long fileId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件URL
     */
    private String fileUrl;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件类型
     */
    private String fileType;
}
