package com.comic.service.production;

import com.comic.dto.model.VideoSegmentModel;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 字幕生成服务
 * 从分镜对白生成SRT格式字幕
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubtitleService {

    private final OssService ossService;
    private final ObjectMapper objectMapper;

    @Value("${comic.subtitle.temp-dir:${java.io.tmpdir}}")
    private String tempDir;

    /**
     * 生成分镜字幕
     *
     * @param storyboardJson 分镜JSON
     * @param videoSegments 视频片段列表（包含时长信息）
     * @return 字幕文件URL
     */
    public String generateSubtitles(String storyboardJson, List<VideoSegmentModel> videoSegments) {
        try {
            List<SubtitleEntry> entries = parseSubtitlesFromStoryboard(storyboardJson, videoSegments);
            String srtContent = formatToSRT(entries);

            // 写入临时文件
            Path tempPath = Files.createTempFile(Paths.get(tempDir), "subtitle-", ".srt");
            try (FileWriter writer = new FileWriter(tempPath.toFile())) {
                writer.write(srtContent);
            }

            // 上传到OSS
            String url = ossService.uploadFromFile(tempPath.toString(), "subtitles");

            // 删除临时文件
            Files.deleteIfExists(tempPath);

            log.info("字幕生成完成: entries={}, url={}", entries.size(), url);
            return url;

        } catch (Exception e) {
            log.error("字幕生成失败", e);
            throw new RuntimeException("字幕生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从分镜JSON解析字幕条目
     */
    private List<SubtitleEntry> parseSubtitlesFromStoryboard(String storyboardJson, List<VideoSegmentModel> videoSegments) {
        List<SubtitleEntry> entries = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(storyboardJson);
            JsonNode panelsNode = rootNode.get("panels");

            if (panelsNode == null || !panelsNode.isArray()) {
                return entries;
            }

            int subtitleIndex = 1;
            float currentTime = 0;

            for (int i = 0; i < panelsNode.size() && i < videoSegments.size(); i++) {
                JsonNode panelNode = panelsNode.get(i);
                JsonNode dialogueNode = panelNode.get("dialogue");

                if (dialogueNode != null && !dialogueNode.asText().trim().isEmpty()) {
                    String dialogue = dialogueNode.asText().trim();

                    // 计算时间范围
                    float startTime = currentTime;
                    float endTime = currentTime + videoSegments.get(i).getDuration();

                    SubtitleEntry entry = new SubtitleEntry();
                    entry.setIndex(subtitleIndex++);
                    entry.setStartTime(formatTime(startTime));
                    entry.setEndTime(formatTime(endTime));
                    entry.setText(dialogue);

                    entries.add(entry);
                }

                currentTime += videoSegments.get(i).getDuration();
            }

        } catch (Exception e) {
            log.error("解析分镜JSON失败", e);
        }

        return entries;
    }

    /**
     * 格式化为SRT格式
     */
    private String formatToSRT(List<SubtitleEntry> entries) {
        StringBuilder sb = new StringBuilder();

        for (SubtitleEntry entry : entries) {
            sb.append(entry.getIndex()).append("\n");
            sb.append(entry.getStartTime()).append(" --> ").append(entry.getEndTime()).append("\n");
            sb.append(entry.getText()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 格式化时间（秒转SRT时间格式）
     * SRT格式: 00:00:00,000
     */
    private String formatTime(float seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        int millis = (int) ((seconds % 1) * 1000);

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    /**
     * 字幕条目内部类
     */
    private static class SubtitleEntry {
        private int index;
        private String startTime;
        private String endTime;
        private String text;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }

        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }
}
