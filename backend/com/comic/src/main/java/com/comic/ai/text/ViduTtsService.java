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
     * - 有台词 shot：插入 &lt;#duration#&gt; 静音标记
     */
    public String buildTtsText(List<Map<String, Object>> shots) {
        if (shots == null || shots.isEmpty()) return "";

        List<String> segments = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            boolean hasDialogue = hasDialogue(shot);
            String narration = extractNarration(shot);

            if (hasDialogue) {
                Object durationObj = shot.get("duration");
                if (durationObj != null) {
                    double duration = toDouble(durationObj);
                    segments.add("<#" + duration + "#>");
                }
            } else if (narration != null && !narration.isEmpty() && !"无".equals(narration)) {
                segments.add(narration);
            }
        }

        String result = String.join("", segments);
        // 移除首段停顿（前面没有语音）
        if (result.startsWith("<#")) {
            int endIdx = result.indexOf(">");
            if (endIdx > 0) {
                result = result.substring(endIdx + 1);
            }
        }
        return result;
    }

    /**
     * 试听音色：生成一段固定示例文本的音频，返回 OSS URL
     */
    public String preview(String voiceId) {
        return generate("大家好，欢迎收听本期故事，希望你们会喜欢。", voiceId);
    }

    /**
     * 生成 TTS 音频，上传至 OSS 持久化
     */
    public String generate(String ttsText, String voiceId) {
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