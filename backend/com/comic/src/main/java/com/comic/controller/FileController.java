package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.Base64UploadRequest;
import com.comic.dto.response.FileUploadResponse;
import com.comic.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Tag(name = "文件管理")
@SecurityRequirement(name = "bearerAuth")
public class FileController {

    private final FileService fileService;

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    @Operation(summary = "上传文件")
    public Result<FileUploadResponse> uploadFile(
            @Parameter(description = "文件")
            @RequestParam("file") MultipartFile file) {
        return Result.ok(fileService.uploadFile(file));
    }

    /**
     * Base64上传
     */
    @PostMapping("/upload/base64")
    @Operation(summary = "Base64上传")
    public Result<FileUploadResponse> uploadBase64(@Valid @RequestBody Base64UploadRequest dto) {
        return Result.ok(fileService.uploadBase64(dto.getBase64Data(), dto.getFileName()));
    }

    /**
     * 获取文件URL
     */
    @GetMapping("/{fileId}/url")
    @Operation(summary = "获取文件URL")
    public Result<String> getFileUrl(@PathVariable Long fileId) {
        return Result.ok(fileService.getFileUrl(fileId));
    }

    /**
     * 删除文件
     */
    @DeleteMapping("/{fileId}")
    @Operation(summary = "删除文件")
    public Result<Void> deleteFile(@PathVariable Long fileId) {
        fileService.deleteFile(fileId);
        return Result.ok();
    }
}
