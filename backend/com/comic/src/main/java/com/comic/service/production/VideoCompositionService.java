package com.comic.service.production;

import com.comic.dto.model.VideoSegmentModel;
import com.comic.service.oss.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频合成服务
 * 使用FFmpeg将多个视频片段合成为最终视频
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoCompositionService {

    private final OssService ossService;
    private final OkHttpClient httpClient;

    @Value("${comic.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${comic.video.temp-dir:${java.io.tmpdir}}")
    private String tempDir;

    /**
     * 合成视频
     *
     * @param videoSegments 视频片段列表
     * @param subtitleUrl 字幕URL（可选）
     * @return 最终视频URL
     */
    public String composeVideo(List<VideoSegmentModel> videoSegments, String subtitleUrl) {
        if (videoSegments == null || videoSegments.isEmpty()) {
            throw new IllegalArgumentException("视频片段列表为空");
        }

        try {
            // 直接使用 URL 进行拼接，无需先下载
            Path concatFile = createConcatFileFromUrls(videoSegments);

            // 准备输出路径
            Path outputPath = Files.createTempFile(Paths.get(tempDir), "final-video-", ".mp4");

            // 执行FFmpeg合成
            if (subtitleUrl != null && !subtitleUrl.isEmpty()) {
                // 带字幕合成
                composeWithSubtitleFromUrls(concatFile, subtitleUrl, outputPath);
            } else {
                // 不带字幕合成
                composeWithoutSubtitleFromUrls(concatFile, outputPath);
            }

            // 上传到OSS
            String finalUrl = ossService.uploadFromFile(outputPath.toString(), "videos");

            // 清理临时文件
            Files.deleteIfExists(concatFile);
            Files.deleteIfExists(outputPath);

            log.info("视频合成完成: segments={}, url={}", videoSegments.size(), finalUrl);
            return finalUrl;

        } catch (Exception e) {
            log.error("视频合成失败", e);
            throw new RuntimeException("视频合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从视频 URL 创建 concat 文件
     */
    private Path createConcatFileFromUrls(List<VideoSegmentModel> videoSegments) throws Exception {
        Path concatFile = Files.createTempFile(Paths.get(tempDir), "concat-", ".txt");

        try (FileWriter writer = new FileWriter(concatFile.toFile())) {
            for (VideoSegmentModel segment : videoSegments) {
                // 对于 URL，需要特殊处理
                String url = segment.getUrl();
                // FFmpeg concat 协议对 URL 的处理
                writer.write("file '" + url + "'\n");
            }
        }

        return concatFile;
    }

    /**
     * 合成视频（不带字幕，从 URL）
     */
    private void composeWithoutSubtitleFromUrls(Path concatFile, Path outputPath) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-f");
        command.add("concat");
        command.add("-safe");
        command.add("0");
        command.add("-protocol_whitelist");
        command.add("file,http,https,tcp,tls");
        command.add("-i");
        command.add(concatFile.toString());
        command.add("-c");
        command.add("copy");
        command.add("-y");
        command.add(outputPath.toString());

        executeFFmpeg(command);
    }

    /**
     * 合成视频（带字幕，从 URL）
     */
    private void composeWithSubtitleFromUrls(Path concatFile, String subtitleUrl, Path outputPath) throws Exception {
        // 先下载字幕文件
        Path subtitlePath = Files.createTempFile(Paths.get(tempDir), "subtitle-", ".srt");
        downloadToFile(subtitleUrl, subtitlePath);

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-f");
        command.add("concat");
        command.add("-safe");
        command.add("0");
        command.add("-protocol_whitelist");
        command.add("file,http,https,tcp,tls");
        command.add("-i");
        command.add(concatFile.toString());
        command.add("-vf");
        command.add("subtitles='" + subtitlePath.toString().replace("\\", "/").replace("'", "'\\''") + "'");
        command.add("-c:a");
        command.add("copy");
        command.add("-y");
        command.add(outputPath.toString());

        executeFFmpeg(command);

        // 清理字幕文件
        Files.deleteIfExists(subtitlePath);
    }

    /**
     * 从公网 URL 下载文件到本地路径
     */
    private void downloadToFile(String url, Path targetPath) throws Exception {
        // 添加 User-Agent，避免被 S3 拒绝
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String errorMsg;
                if (response.code() == 403) {
                    errorMsg = "视频链接访问被拒绝（403），URL=" + url;
                } else {
                    errorMsg = "下载文件失败: HTTP " + response.code() + ", url=" + url;
                }
                throw new RuntimeException(errorMsg);
            }
            Files.write(targetPath, response.body().bytes());
        }
    }

    /**
     * 执行FFmpeg命令
     */
    private void executeFFmpeg(List<String> command) throws Exception {
        log.info("执行FFmpeg命令: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 读取输出
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("FFmpeg: {}", line);
            }
        }

        // 等待完成
        boolean finished = process.waitFor(300, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg执行超时");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg执行失败，退出码: " + exitCode);
        }

        log.info("FFmpeg执行完成");
    }

    /**
     * 从视频中截取指定时间点的帧
     *
     * @param videoUrl   视频URL
     * @param timePosition 时间点（秒），-1表示最后一帧
     * @return 截取的帧图片URL（上传到OSS）
     */
    public String extractFrame(String videoUrl, float timePosition) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new IllegalArgumentException("视频URL不能为空");
        }

        try {
            // 1. 下载视频到临时文件
            Path videoPath = Files.createTempFile(Paths.get(tempDir), "frame-extract-", ".mp4");
            downloadToFile(videoUrl, videoPath);

            // 2. 准备输出路径
            Path framePath = Files.createTempFile(Paths.get(tempDir), "frame-", ".png");

            // 3. 构建FFmpeg命令
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);

            if (timePosition < 0) {
                // 截取最后一帧：先获取视频时长，再 seek 到末尾
                command.add("-sseof");
                command.add("-0.1");
            } else {
                command.add("-ss");
                command.add(String.valueOf(timePosition));
            }

            command.add("-i");
            command.add(videoPath.toString());
            command.add("-frames:v");
            command.add("1");
            command.add("-y");
            command.add(framePath.toString());

            executeFFmpeg(command);

            // 4. 上传到OSS
            String frameUrl = ossService.uploadFromFile(framePath.toString(), "frames");

            // 5. 清理临时文件
            Files.deleteIfExists(videoPath);
            Files.deleteIfExists(framePath);

            log.info("帧截取完成: videoUrl={}, timePosition={}, frameUrl={}", videoUrl, timePosition, frameUrl);
            return frameUrl;

        } catch (Exception e) {
            log.error("帧截取失败: videoUrl={}, timePosition={}", videoUrl, timePosition, e);
            throw new RuntimeException("帧截取失败: " + e.getMessage(), e);
        }
    }
}
