package com.comic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_file")
public class FileInfo {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 文件URL
     */
    private String fileUrl;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件类型：image, video, audio, document
     */
    private String fileType;

    /**
     * MIME类型
     */
    private String mimeType;

    /**
     * 存储类型：local, oss, minio
     */
    private String storageType;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
