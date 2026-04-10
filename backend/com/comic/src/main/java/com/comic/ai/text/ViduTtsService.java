package com.comic.ai.text;

import com.comic.config.MiniMaxTtsProperties;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * MiniMax TTS 旁白生成服务
 * 调用 POST https://api.minimaxi.com/v1/t2a_v2（同步接口）
 * 响应返回 hex 编码音频，解码后上传至 OSS 持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViduTtsService {

    private final MiniMaxTtsProperties minimaxProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OssService ossService;

    private static final String TTS_ENDPOINT = "/v1/t2a_v2";

    /**
     * TTS 文本拼接算法
     * 按 shot 顺序拼接：
     * - 无台词 shot：输出 narration 文本
     * - 有台词 shot：累积时长到待插入的静音段
     *
     * MiniMax TTS API 限制：
     * - &lt;#x#&gt; 停顿标记不可连续使用
     * - x 范围 [0.01, 99.99]，小于等于 0 时不输出
     *
     * 健壮性处理：
     * - 多个连续有台词 shot → 合并为一个静音段（总时长）
     * - 静音段前后已无朗读文本 → 跳过（如首尾）
     * - duration <= 0 → 忽略
     * - narration 为空/无 → 不输出
     */
    public String buildTtsText(List<Map<String, Object>> shots) {
        if (shots == null || shots.isEmpty()) return "";

        List<String> segments = new ArrayList<>();
        // 待插入的静音总时长（处理连续多个有台词 shot 的场景）
        double pendingSilenceSeconds = 0;

        for (Map<String, Object> shot : shots) {
            boolean hasDialogue = hasDialogue(shot);
            String narration = extractNarration(shot);

            if (hasDialogue) {
                // 有台词 → 累加时长，静音段由后续旁白或结束时统一吐出
                Object durationObj = shot.get("duration");
                if (durationObj != null) {
                    double duration = toDouble(durationObj);
                    if (duration > 0) {
                        pendingSilenceSeconds += duration;
                    }
                }
            } else if (narration != null && !narration.isEmpty() && !"无".equals(narration)) {
                // 无台词 + 有旁白 → 先吐出之前累积的静音段（>0 才输出）
                if (pendingSilenceSeconds > 0) {
                    // 检查前一个 segment 是否已是静音段，避免连续（理论上不会有，但防御性检查）
                    if (segments.isEmpty() || !segments.get(segments.size() - 1).startsWith("<#")) {
                        segments.add("<#" + formatDuration(pendingSilenceSeconds) + "#>");
                    } else {
                        // 前一个也是静音段，合并（追加时长）
                        String last = segments.remove(segments.size() - 1);
                        double prev = parseLastDuration(last);
                        segments.add("<#" + formatDuration(prev + pendingSilenceSeconds) + "#>");
                    }
                    pendingSilenceSeconds = 0;
                }
                segments.add(narration);
            }
            // 既无台词也无旁白 → 跳过（不产生任何输出）
        }

        // 循环结束后：吐出末尾累积的静音段
        if (pendingSilenceSeconds > 0) {
            // 末尾静音段前面已有朗读文本才输出，否则首段停顿需去掉
            if (!segments.isEmpty()) {
                segments.add("<#" + formatDuration(pendingSilenceSeconds) + "#>");
            }
        }

        // 注意：不删除首位静音
        // TTS 与视频从头到尾严格对齐，第一个 shot 可能是纯台词（无旁白），
        // 此时需要在 TTS 开头插入对应时长的静音，才能与视频时间轴完全对齐。
        String result = String.join("", segments);
        return result;
    }

    /** 保留两位小数，避免浮点精度问题 */
    private String formatDuration(double seconds) {
        return String.format(Locale.US, "%.2f", seconds);
    }

    /** 从 "&lt;#x#&gt;" 字符串中解析 x 值 */
    private double parseLastDuration(String silenceTag) {
        try {
            int start = silenceTag.indexOf("<#") + 2;
            int end = silenceTag.indexOf("#>", start);
            if (start > 1 && end > start) {
                return Double.parseDouble(silenceTag.substring(start, end));
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 试听音色：生成一段固定示例文本的音频，返回 OSS URL
     */
    public String preview(String voiceId) {
        return generate("大家好，欢迎收听本期故事，希望你们会喜欢。", voiceId, null);
    }

    /**
     * 生成 TTS 音频，上传至 OSS 持久化
     */
    public String generate(String ttsText, String voiceId, String emotion) {
        if (ttsText == null || ttsText.isEmpty()) {
            throw new IllegalArgumentException("TTS 文本为空，无需生成");
        }
        if (voiceId == null || voiceId.isEmpty()) {
            throw new IllegalArgumentException("voiceId 不能为空");
        }

        // 构造请求体
        Map<String, Object> voiceSetting = new HashMap<>();
        voiceSetting.put("voice_id", voiceId);
        voiceSetting.put("speed", 1.0);
        if (emotion != null && !emotion.isEmpty()) {
            voiceSetting.put("emotion", emotion);
        }

        Map<String, Object> audioSetting = new HashMap<>();
        audioSetting.put("sample_rate", 32000);
        audioSetting.put("bitrate", 128000);
        audioSetting.put("format", "mp3");
        audioSetting.put("channel", 1);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", minimaxProperties.getModel());
        requestBody.put("text", ttsText);
        requestBody.put("stream", false);
        requestBody.put("voice_setting", voiceSetting);
        requestBody.put("audio_setting", audioSetting);
        requestBody.put("output_format", "hex");

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("序列化 TTS 请求体失败", e);
        }

        Request request = new Request.Builder()
                .url(minimaxProperties.getBaseUrl() + TTS_ENDPOINT)
                .addHeader("Authorization", "Bearer " + minimaxProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                log.error("MiniMax TTS API 调用失败: {} - {}", response.code(), errorBody);
                throw new RuntimeException("MiniMax TTS 生成失败: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("MiniMax TTS 响应: {}", responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode baseResp = root.path("base_resp");
            int statusCode = baseResp.path("status_code").asInt(-1);
            String statusMsg = baseResp.path("status_msg").asText();

            if (statusCode != 0) {
                throw new RuntimeException("MiniMax TTS 生成失败: status_code=" + statusCode + ", status_msg=" + statusMsg);
            }

            JsonNode data = root.path("data");
            String hexAudio = data.path("audio").asText("");
            if (hexAudio.isEmpty()) {
                throw new RuntimeException("MiniMax TTS 返回音频为空");
            }

            // 解码 hex → byte[]
            byte[] audioBytes = hexToBytes(hexAudio);

            // 上传到 OSS（与 uploadAudioFromUrl 保持一致路径）
            String objectKey = "tts/" + UUID.randomUUID().toString().replace("-", "") + ".mp3";
            String ossUrl = ossService.uploadFromInputStream(
                    new ByteArrayInputStream(audioBytes), objectKey, "audio/mpeg", audioBytes.length);
            log.info("MiniMax TTS 完成: 音频大小={}KB, OSS URL={}", audioBytes.length / 1024, ossUrl);
            return ossUrl;

        } catch (IOException e) {
            throw new RuntimeException("MiniMax TTS 请求异常", e);
        }
    }

    private boolean hasDialogue(Map<String, Object> shot) {
        Object dialogue = shot.get("dialogue");
        if (dialogue == null) return false;
        String d = dialogue.toString().trim();
        return !d.isEmpty() && !"无".equals(d);
    }

    /**
     * 提取 narration 文本
     * 优先读 narration 字段，fallback 到 speaker="旁白" + dialogue（历史数据兼容）
     */
    private String extractNarration(Map<String, Object> shot) {
        if (shot == null) return null;
        Object n = shot.get("narration");
        if (n != null) {
            String s = n.toString().trim();
            if (!s.isEmpty() && !"无".equals(s)) return s;
        }
        String speaker = shot.get("speaker") != null ? shot.get("speaker").toString() : "";
        String dialogue = shot.get("dialogue") != null ? shot.get("dialogue").toString() : "";
        if (speaker.contains("旁白") && !dialogue.isEmpty() && !"无".equals(dialogue.trim())) {
            return dialogue.trim();
        }
        return null;
    }

    private double toDouble(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try { return Double.parseDouble(obj.toString()); }
        catch (Exception e) { return 0; }
    }

    /**
     * Hex 字符串 → byte[]
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}