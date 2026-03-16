package com.comic.dto;

import lombok.Data;

@Data
public class FileUploadDTO {
    private Long fileId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileType;
}
