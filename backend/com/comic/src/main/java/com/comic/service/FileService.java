package com.comic.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.comic.dto.FileUploadDTO;
import com.comic.entity.FileInfo;
import com.comic.mapper.FileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileMapper fileMapper;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @Value("${file.base-url:http://localhost:8080/files}")
    private String baseUrl;

    /**
     * 上传文件
     */
    public FileUploadDTO uploadFile(MultipartFile file) {
        try {
            // 1. 生成文件名
            String originalFilename = file.getOriginalFilename();
            String extension = FileUtil.extName(originalFilename);
            String newFileName = UUID.randomUUID().toString() + "." + extension;

            // 2. 创建日期目录
            String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String relativePath = dateDir + "/" + newFileName;
            String fullPath = uploadDir + "/" + relativePath;

            // 3. 确保目录存在
            File destFile = new File(fullPath);
            FileUtil.mkParentDirs(destFile);

            // 4. 保存文件
            file.transferTo(destFile);

            // 5. 判断文件类型
            String fileType = determineFileType(extension);
            String mimeType = file.getContentType();

            // 6. 保存到数据库
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileName(originalFilename);
            fileInfo.setFilePath(relativePath);
            fileInfo.setFileUrl(baseUrl + "/" + relativePath);
            fileInfo.setFileSize(file.getSize());
            fileInfo.setFileType(fileType);
            fileInfo.setMimeType(mimeType);
            fileInfo.setStorageType("local");
            fileInfo.setCreateTime(LocalDateTime.now());
            fileMapper.insert(fileInfo);

            // 7. 返回结果
            FileUploadDTO dto = new FileUploadDTO();
            dto.setFileId(fileInfo.getId());
            dto.setFileName(originalFilename);
            dto.setFileUrl(fileInfo.getFileUrl());
            dto.setFileSize(file.getSize());
            dto.setFileType(fileType);

            return dto;

        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * Base64上传
     */
    public FileUploadDTO uploadBase64(String base64Data, String fileName) {
        try {
            // 1. 解码Base64
            byte[] bytes = java.util.Base64.getDecoder().decode(base64Data);

            // 2. 生成文件名
            String extension = FileUtil.extName(fileName);
            String newFileName = UUID.randomUUID().toString() + "." + extension;

            // 3. 创建日期目录
            String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String relativePath = dateDir + "/" + newFileName;
            String fullPath = uploadDir + "/" + relativePath;

            // 4. 确保目录存在
            File destFile = new File(fullPath);
            FileUtil.mkParentDirs(destFile);

            // 5. 保存文件
            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                fos.write(bytes);
            }

            // 6. 判断文件类型
            String fileType = determineFileType(extension);
            String mimeType = determineMimeType(extension);

            // 7. 保存到数据库
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileName(fileName);
            fileInfo.setFilePath(relativePath);
            fileInfo.setFileUrl(baseUrl + "/" + relativePath);
            fileInfo.setFileSize((long) bytes.length);
            fileInfo.setFileType(fileType);
            fileInfo.setMimeType(mimeType);
            fileInfo.setStorageType("local");
            fileInfo.setCreateTime(LocalDateTime.now());
            fileMapper.insert(fileInfo);

            // 8. 返回结果
            FileUploadDTO dto = new FileUploadDTO();
            dto.setFileId(fileInfo.getId());
            dto.setFileName(fileName);
            dto.setFileUrl(fileInfo.getFileUrl());
            dto.setFileSize((long) bytes.length);
            dto.setFileType(fileType);

            return dto;

        } catch (Exception e) {
            log.error("Base64文件上传失败", e);
            throw new RuntimeException("Base64文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件URL
     */
    public String getFileUrl(Long fileId) {
        FileInfo fileInfo = fileMapper.selectById(fileId);
        return fileInfo != null ? fileInfo.getFileUrl() : null;
    }

    /**
     * 删除文件
     */
    public void deleteFile(Long fileId) {
        FileInfo fileInfo = fileMapper.selectById(fileId);
        if (fileInfo != null) {
            // 删除物理文件
            if ("local".equals(fileInfo.getStorageType())) {
                String fullPath = uploadDir + "/" + fileInfo.getFilePath();
                FileUtil.del(fullPath);
            }
            // 删除数据库记录
            fileMapper.deleteById(fileId);
        }
    }

    /**
     * 判断文件类型
     */
    private String determineFileType(String extension) {
        String ext = extension.toLowerCase();
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp")) {
            return "image";
        } else if (ext.matches("mp4|avi|mov|wmv|flv|mkv")) {
            return "video";
        } else if (ext.matches("mp3|wav|flac|aac")) {
            return "audio";
        } else if (ext.matches("pdf|doc|docx|xls|xlsx|ppt|pptx|txt")) {
            return "document";
        } else {
            return "other";
        }
    }

    /**
     * 根据扩展名判断MIME类型
     */
    private String determineMimeType(String extension) {
        String ext = extension.toLowerCase();
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "bmp":
                return "image/bmp";
            case "mp4":
                return "video/mp4";
            case "avi":
                return "video/x-msvideo";
            case "mov":
                return "video/quicktime";
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "pdf":
                return "application/pdf";
            case "txt":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }
}
