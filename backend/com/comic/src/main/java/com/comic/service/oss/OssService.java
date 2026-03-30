package com.comic.service.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.comic.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder().build(
                ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret()
        );
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    private static final String DIR_VIDEOS = "comic/videos/";

    /**
     * 从 URL 下载视频并上传到 OSS
     * 先完整下载到内存再上传，解耦 OkHttp 和 OSS 的流生命周期
     *
     * @param videoUrl 视频临时 URL（如 Vidu 返回的 URL）
     * @param fileName 自定义文件名（不含路径），为 null 时自动生成 UUID
     * @return OSS 公网 URL
     */
    public String uploadVideoFromUrl(String videoUrl, String fileName) {
        try {
            log.info("开始从URL下载视频并上传到OSS: {}", videoUrl);

            // 1. 先将视频完整下载到内存
            byte[] videoBytes;
            String contentType;
            Request request = new Request.Builder().url(videoUrl).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RuntimeException("下载视频失败: " + response.code());
                }
                contentType = response.body().contentType() != null
                        ? response.body().contentType().toString()
                        : "video/mp4";
                videoBytes = response.body().bytes();
            }
            // OkHttp Response 已关闭，后续上传不再依赖其连接

            // 2. 生成文件名
            if (fileName == null || fileName.isEmpty()) {
                String ext = getVideoExtension(contentType, videoUrl);
                fileName = UUID.randomUUID().toString().replace("-", "") + ext;
            }

            String objectKey = DIR_VIDEOS + fileName;

            // 3. 上传到 OSS
            ByteArrayInputStream bais = new ByteArrayInputStream(videoBytes);
            String ossUrl = uploadFromInputStream(bais, objectKey, contentType, videoBytes.length);
            log.info("视频已上传到OSS: {} -> {}, 大小: {} KiB", videoUrl, ossUrl, videoBytes.length / 1024);
            return ossUrl;
        } catch (Exception e) {
            log.error("上传视频到OSS失败: {}", videoUrl, e);
            throw new RuntimeException("上传视频到OSS失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 URL 下载图片并上传到 OSS
     *
     * @param imageUrl 图片临时 URL（如 Seedream 返回的 URL）
     * @param fileName 自定义文件名（不含路径），为 null 时自动生成 UUID
     * @return OSS 公网 URL
     */
    public String uploadImageFromUrl(String imageUrl, String fileName) {
        try {
            // 1. 先将图片完整下载到内存
            byte[] imageBytes;
            String contentType;
            Request request = new Request.Builder().url(imageUrl).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RuntimeException("下载图片失败: " + response.code());
                }
                contentType = response.body().contentType() != null
                        ? response.body().contentType().toString()
                        : "image/png";
                imageBytes = response.body().bytes();
            }

            // 2. 生成文件名
            if (fileName == null || fileName.isEmpty()) {
                String ext = getImageExtension(contentType, imageUrl);
                fileName = UUID.randomUUID().toString().replace("-", "") + ext;
            }

            String objectKey = ossProperties.getDir() + fileName;

            // 3. 上传到 OSS
            ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
            String publicUrl = uploadFromInputStream(bais, objectKey, contentType, imageBytes.length);
            log.info("图片已上传到 OSS: {} -> {}, 大小: {} KiB", imageUrl, publicUrl, imageBytes.length / 1024);
            return publicUrl;
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
                ossClient.putObject(
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
     * 从 InputStream 上传到 OSS（支持前端 MultipartFile 上传）
     *
     * @param inputStream  输入流
     * @param objectKey    OSS对象Key
     * @param contentType  内容类型（如 "image/png"）
     * @param contentLength 内容长度
     * @return OSS 公网 URL
     */
    public String uploadFromInputStream(InputStream inputStream, String objectKey, String contentType, long contentLength) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setContentLength(contentLength);

            ossClient.putObject(
                    ossProperties.getBucketName(),
                    objectKey,
                    inputStream,
                    metadata
            );

            String publicUrl = getPublicUrl(objectKey);
            log.info("文件已上传到 OSS: {} -> {}", objectKey, publicUrl);
            return publicUrl;
        } catch (Exception e) {
            log.error("上传文件到 OSS 失败: {}", objectKey, e);
            throw new RuntimeException("上传文件到 OSS 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将两张图片水平拼合成一张，上传到 OSS
     * 布局：[图1 | 图2]
     *
     * @param url1 左侧图片 URL
     * @param url2 右侧图片 URL
     * @return 合并后图片的 OSS URL
     */
    public String combineImagesHorizontal(String url1, String url2) {
        try {
            log.info("开始合并图片: left={}, right={}", url1, url2);
            BufferedImage img1 = downloadImage(url1);
            BufferedImage img2 = downloadImage(url2);

            // 统一高度为两者最大值，但限制最大高度不超过1024以控制文件大小
            int targetHeight = Math.min(Math.max(img1.getHeight(), img2.getHeight()), 1024);
            // 按比例缩放
            BufferedImage scaled1 = scaleToHeight(img1, targetHeight);
            BufferedImage scaled2 = scaleToHeight(img2, targetHeight);

            int totalWidth = scaled1.getWidth() + scaled2.getWidth();
            BufferedImage combined = new BufferedImage(totalWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics g = combined.getGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, totalWidth, targetHeight);
            g.drawImage(scaled1, 0, 0, null);
            g.drawImage(scaled2, scaled1.getWidth(), 0, null);
            g.dispose();

            // 使用 JPEG 压缩，控制文件大小不超过 Seedream 的 10 MiB 限制
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageWriteParam param = ImageIO.getImageWritersByFormatName("jpeg").next().getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(combined, null, null), param);
            }
            writer.dispose();
            byte[] bytes = baos.toByteArray();
            log.info("合并图片 JPEG 压缩后大小: {} KiB ({}x{})", bytes.length / 1024, totalWidth, targetHeight);

            // 上传到 OSS
            String fileName = UUID.randomUUID().toString().replace("-", "") + ".jpg";
            String objectKey = ossProperties.getDir() + "combined/" + fileName;
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            String ossUrl = uploadFromInputStream(bais, objectKey, "image/jpeg", bytes.length);
            log.info("图片合并完成，已上传 OSS: {} ({}x{})", ossUrl, totalWidth, targetHeight);
            return ossUrl;
        } catch (Exception e) {
            log.error("合并图片失败: url1={}, url2={}", url1, url2, e);
            throw new RuntimeException("合并图片失败: " + e.getMessage(), e);
        }
    }

    private BufferedImage downloadImage(String url) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("下载图片失败: " + response.code());
            }
            byte[] bytes = response.body().bytes();
            return ImageIO.read(new ByteArrayInputStream(bytes));
        }
    }

    private BufferedImage scaleToHeight(BufferedImage src, int targetHeight) {
        if (src.getHeight() == targetHeight) return src;
        double scale = (double) targetHeight / src.getHeight();
        int targetWidth = (int) Math.round(src.getWidth() * scale);
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics g = scaled.getGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, targetWidth, targetHeight);
        g.drawImage(src.getScaledInstance(targetWidth, targetHeight, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return scaled;
    }

    /**
     * 上传 MultipartFile 到 OSS（便捷方法）
     *
     * @param file       MultipartFile
     * @param category   分类目录（如 "fusion"）
     * @return OSS 公网 URL
     */
    public String uploadMultipartFile(MultipartFile file, String category) {
        try {
            String originalName = file.getOriginalFilename();
            String ext = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf("."))
                    : ".png";
            String fileName = UUID.randomUUID().toString().replace("-", "") + ext;
            String objectKey = ossProperties.getDir() + category + "/" + fileName;

            return uploadFromInputStream(
                    file.getInputStream(),
                    objectKey,
                    file.getContentType() != null ? file.getContentType() : "image/png",
                    file.getSize()
            );
        } catch (Exception e) {
            log.error("上传MultipartFile到OSS失败", e);
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
     * 从 contentType 或 URL 中提取图片扩展名
     */
    private String getImageExtension(String contentType, String url) {
        if (contentType.contains("png")) return ".png";
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return ".jpg";
        if (contentType.contains("webp")) return ".webp";
        return extractExtensionFromUrl(url, ".png");
    }

    /**
     * 从 contentType 或 URL 中提取视频扩展名
     */
    private String getVideoExtension(String contentType, String url) {
        if (contentType.contains("mp4")) return ".mp4";
        if (contentType.contains("webm")) return ".webm";
        if (contentType.contains("mov")) return ".mov";
        return extractExtensionFromUrl(url, ".mp4");
    }

    /**
     * 共享的 URL 扩展名提取逻辑
     */
    private String extractExtensionFromUrl(String url, String defaultExt) {
        if (url.contains(".")) {
            int dotIndex = url.lastIndexOf(".");
            int queryIndex = url.indexOf("?", dotIndex);
            String ext = queryIndex > dotIndex ? url.substring(dotIndex, queryIndex) : url.substring(dotIndex);
            if (ext.length() <= 5) return ext;
        }
        return defaultExt;
    }
}
