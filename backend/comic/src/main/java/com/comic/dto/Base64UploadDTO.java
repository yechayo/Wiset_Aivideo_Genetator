package com.comic.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class Base64UploadDTO {

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotBlank(message = "Base64数据不能为空")
    private String base64Data;
}
