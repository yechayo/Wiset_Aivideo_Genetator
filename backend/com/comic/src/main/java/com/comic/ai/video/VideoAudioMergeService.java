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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 视频 + TTS 旁白音频合并服务
 * 使用 FFmpeg 将面板视频与 TTS 音频混合
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAudioMergeService {

    private static final double TTS_VOLUME = 1.0;   // TTS 旁白音量
    private static final double BG_VOLUME = 1.0;    // 视频原声音量（与旁白同等）

    /** atempo 兜底容差：音频时长在此范围内认为匹配，不做调速 */
    private static final double ATEMPO_TOLERANCE_LOW = 0.88;
    private static final double ATEMPO_TOLERANCE_HIGH = 1.12;

    private final OssService ossService;

    /**
     * 合并面板视频和 TTS 旁白音频
     * <p>
     * 音频混合策略：TTS 旁白与视频原声同等音量（各 1.0）。
     * 使用 amix 滤镜混合两路音频，当视频无音频轨时降级为纯 TTS 叠加。
     * <p>
     * 时长兜底：检测 TTS 音频与视频时长差异，偏差超 ±12% 时自动用 atempo 调速对齐。
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

            // 2. 获取视频/音频时长 & 检测视频音频轨
            double videoDuration = getDuration(videoFile);
            double audioDuration = getDuration(audioFile);
            boolean hasVideoAudio = probeHasAudioStream(videoFile);
            String durStr = String.format("%.2f", videoDuration);

            // 3. 计算 atempo 兜底：音频时长偏差超 ±12% 则调速
            double tempo = 1.0;
            if (audioDuration > 0 && videoDuration > 0) {
                double ratio = audioDuration / videoDuration;
                if (ratio < ATEMPO_TOLERANCE_LOW || ratio > ATEMPO_TOLERANCE_HIGH) {
                    tempo = Math.max(0.5, Math.min(2.0, ratio));
                    log.info("音频时长兜底: 音频={}s, 视频={}s, 比值={}, atempo={}",
                            String.format("%.2f", audioDuration), durStr, String.format("%.3f", ratio), tempo);
                } else {
                    log.info("音频时长正常: 音频={}s, 视频={}s, 无需调速",
                            String.format("%.2f", audioDuration), durStr);
                }
            }

            log.info("视频时长: {}s, 音频时长: {}s, 含音频轨: {}, atempo: {}",
                    durStr, String.format("%.2f", audioDuration), hasVideoAudio, tempo);

            List<String> command;
            if (hasVideoAudio) {
                // 视频 + TTS 双路混合：TTS 加 atempo(兜底) + apad 防止短于视频，duration=first 以视频时长为准
                String ttsFilter = tempo != 1.0
                        ? "[1:a]atempo=" + tempo + ",volume=" + TTS_VOLUME + ",aresample=44100,apad[fg]"
                        : "[1:a]volume=" + TTS_VOLUME + ",aresample=44100,apad[fg]";
                command = new ArrayList<>();
                command.add("ffmpeg");
                command.add("-y");
                command.add("-i");
                command.add(videoFile.getAbsolutePath());
                command.add("-i");
                command.add(audioFile.getAbsolutePath());
                command.add("-filter_complex");
                command.add("[0:a]volume=" + BG_VOLUME + ",aresample=44100[bg];"
                        + ttsFilter + ";"
                        + "[bg][fg]amix=inputs=2:duration=first:dropout_transition=3[a]");
                command.add("-map");
                command.add("0:v:0");
                command.add("-map");
                command.add("[a]");
                command.add("-c:v");
                command.add("copy");
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
                command.add(outputFile.getAbsolutePath());
            } else {
                // 视频无音频轨：TTS 加 atempo(兜底) + apad 补静音 + atrim 对齐视频时长
                String ttsFilter = tempo != 1.0
                        ? "[1:a]atempo=" + tempo + ",apad,atrim=0:" + durStr + "[a]"
                        : "[1:a]apad,atrim=0:" + durStr + "[a]";
                command = new ArrayList<>();
                command.add("ffmpeg");
                command.add("-y");
                command.add("-i");
                command.add(videoFile.getAbsolutePath());
                command.add("-i");
                command.add(audioFile.getAbsolutePath());
                command.add("-filter_complex");
                command.add(ttsFilter);
                command.add("-map");
                command.add("0:v:0");
                command.add("-map");
                command.add("[a]");
                command.add("-c:v");
                command.add("copy");
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
                command.add(outputFile.getAbsolutePath());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

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

            log.info("FFmpeg 合并成功 ({}): {} + {} -> {} KB",
                    hasVideoAudio ? "amix双路混合" : "纯TTS叠加",
                    videoUrl, ttsAudioUrl, outputFile.length() / 1024);

            // 3. 上传到 OSS
            String objectKey = "merged/" + UUID.randomUUID().toString().replace("-", "") + videoExt;
            String ossUrl = ossService.uploadFromFile(outputFile.getAbsolutePath(), objectKey);
            return ossUrl;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("音视频合并失败", e);
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * 使用 ffprobe 获取媒体文件时长（秒）
     */
    private double getDuration(File mediaFile) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("ffprobe");
            cmd.add("-v");
            cmd.add("error");
            cmd.add("-show_entries");
            cmd.add("format=duration");
            cmd.add("-of");
            cmd.add("default=noprint_wrappers=1:nokey=1");
            cmd.add(mediaFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = process.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("ffprobe exit " + exitCode + ": " + baos.toString());
            }

            return Double.parseDouble(baos.toString().trim());
        } catch (Exception e) {
            throw new RuntimeException("ffprobe 获取时长失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 ffprobe 检测视频是否包含音频轨
     */
    private boolean probeHasAudioStream(File videoFile) {
        try {
            List<String> probeCmd = new ArrayList<>();
            probeCmd.add("ffprobe");
            probeCmd.add("-v");
            probeCmd.add("error");
            probeCmd.add("-select_streams");
            probeCmd.add("a");
            probeCmd.add("-show_entries");
            probeCmd.add("stream=codec_type");
            probeCmd.add("-of");
            probeCmd.add("default=noprint_wrappers=1:nokey=1");
            probeCmd.add(videoFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(probeCmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = process.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("ffprobe 检测音频轨失败: {}", baos.toString());
                return false;
            }

            // ffprobe 输出 codec_type=... 表示有音频轨
            return baos.toString().trim().length() > 0;
        } catch (Exception e) {
            log.warn("ffprobe 检测音频轨异常: {}", e.getMessage());
            return false;
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
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
