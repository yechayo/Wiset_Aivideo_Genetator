package com.comic.dto.request;

import lombok.Data;

@Data
public class FileUploadRequest {
    private Long fileId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileType;
}
