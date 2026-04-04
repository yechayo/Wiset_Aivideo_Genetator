package com.comic.ai.video;

import com.comic.service.oss.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 视频 + TTS 旁白音频合并服务
 * 使用 FFmpeg 将面板视频与 TTS 音频混合
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAudioMergeService {

    private final OssService ossService;

    /**
     * 合并面板视频和 TTS 旁白音频
     *
     * @param videoUrl 面板视频 OSS URL
     * @param ttsAudioUrl TTS 旁白 OSS URL
     * @return 合并后的视频 OSS URL
     */
    public String merge(String videoUrl, String ttsAudioUrl) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("video-merge-");
            String videoExt = extractExtension(videoUrl, ".mp4");
            String audioExt = extractExtension(ttsAudioUrl, ".mp3");
            File videoFile = tempDir.resolve("video" + videoExt).toFile();
            File audioFile = tempDir.resolve("tts" + audioExt).toFile();
            File outputFile = tempDir.resolve("merged" + videoExt).toFile();

            // 1. 下载视频和音频
            ossService.downloadToFile(videoUrl, videoFile.getAbsolutePath());
            ossService.downloadToFile(ttsAudioUrl, audioFile.getAbsolutePath());

            // 2. FFmpeg 合并
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", videoFile.getAbsolutePath(),
                    "-i", audioFile.getAbsolutePath(),
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-shortest",
                    outputFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取 FFmpeg 输出（Java 8 兼容）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = process.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("FFmpeg 合并失败 (exit={}): {}", exitCode, baos.toString());
                throw new RuntimeException("FFmpeg 音视频合并失败: exit code " + exitCode);
            }

            if (outputFile.length() == 0) {
                throw new RuntimeException("FFmpeg 输出文件为空");
            }

            log.info("FFmpeg 合并成功: {} + {} -> {} ({} KB)",
                    videoUrl, ttsAudioUrl, outputFile.length() / 1024);

            // 3. 上传到 OSS
            String objectKey = "merged/" + UUID.randomUUID().toString().replace("-", "") + videoExt;
            String ossUrl = ossService.uploadFromFile(outputFile.getAbsolutePath(), objectKey);
            return ossUrl;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("音视频合并失败", e);
        } finally {
            // 清理临时文件
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private String extractExtension(String url, String defaultExt) {
        if (url == null) return defaultExt;
        String path = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < path.length() - 1) {
            return path.substring(dotIdx);
        }
        return defaultExt;
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b)) // 删除前先删子文件
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
