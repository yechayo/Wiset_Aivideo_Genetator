package com.comic.service.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.comic.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 阿里云 OSS 上传服务
 * 用于将 Seedream 生成的临时图片转存到 OSS，获得永久公网 URL
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OssService {

    private final OssProperties ossProperties;
    private final OkHttpClient httpClient;

    private OSS ossClient;

    private OSS getOssClient() {
        if (ossClient == null) {
            ossClient = new OSSClientBuilder().build(
                    ossProperties.getEndpoint(),
                    ossProperties.getAccessKeyId(),
                    ossProperties.getAccessKeySecret()
            );
        }
        return ossClient;
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 从 URL 下载图片并上传到 OSS
     *
     * @param imageUrl 图片临时 URL（如 Seedream 返回的 URL）
     * @param fileName 自定义文件名（不含路径），为 null 时自动生成 UUID
     * @return OSS 公网 URL
     */
    public String uploadFromUrl(String imageUrl, String fileName) {
        try {
            // 下载图片
            Request request = new Request.Builder().url(imageUrl).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RuntimeException("下载图片失败: " + response.code());
                }

                String contentType = response.body().contentType() != null
                        ? response.body().contentType().toString()
                        : "image/png";
                InputStream inputStream = response.body().byteStream();

                // 生成文件名
                if (fileName == null || fileName.isEmpty()) {
                    String ext = getExtension(contentType, imageUrl);
                    fileName = UUID.randomUUID().toString().replace("-", "") + ext;
                }

                String objectKey = ossProperties.getDir() + fileName;

                // 上传到 OSS
                getOssClient().putObject(
                        ossProperties.getBucketName(),
                        objectKey,
                        inputStream
                );

                String publicUrl = getPublicUrl(objectKey);
                log.info("图片已上传到 OSS: {} -> {}", imageUrl, publicUrl);
                return publicUrl;
            }
        } catch (Exception e) {
            log.error("上传图片到 OSS 失败: {}", imageUrl, e);
            throw new RuntimeException("上传图片到 OSS 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从本地文件上传到 OSS
     *
     * @param filePath 本地文件路径
     * @param category 分类目录（如 "videos", "subtitles"）
     * @return OSS 公网 URL
     */
    public String uploadFromFile(String filePath, String category) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new FileNotFoundException("文件不存在: " + filePath);
            }

            String fileName = file.getName();
            String objectKey = ossProperties.getDir() + category + "/" + fileName;

            try (InputStream inputStream = new FileInputStream(file)) {
                getOssClient().putObject(
                        ossProperties.getBucketName(),
                        objectKey,
                        inputStream
                );
            }

            String publicUrl = getPublicUrl(objectKey);
            log.info("文件已上传到 OSS: {} -> {}", filePath, publicUrl);
            return publicUrl;
        } catch (Exception e) {
            log.error("上传文件到 OSS 失败: {}", filePath, e);
            throw new RuntimeException("上传文件到 OSS 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取公网访问 URL
     */
    private String getPublicUrl(String objectKey) {
        return "https://" + ossProperties.getBucketName() + "." + ossProperties.getEndpoint() + "/" + objectKey;
    }

    /**
     * 从 contentType 或 URL 中提取文件扩展名
     */
    private String getExtension(String contentType, String url) {
        if (contentType.contains("png")) return ".png";
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return ".jpg";
        if (contentType.contains("webp")) return ".webp";
        // 从 URL 提取
        if (url.contains(".")) {
            int dotIndex = url.lastIndexOf(".");
            int queryIndex = url.indexOf("?", dotIndex);
            String ext = queryIndex > dotIndex ? url.substring(dotIndex, queryIndex) : url.substring(dotIndex);
            if (ext.length() <= 5) return ext;
        }
        return ".png";
    }
}
