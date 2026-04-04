package com.comic.ai.text;

import com.comic.config.ViduProperties;
import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Vidu TTS 旁白生成服务
 * 调用 POST https://api.vidu.cn/ent/v2/audio-tts（同步接口）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViduTtsService {

    private final ViduProperties viduProperties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OssService ossService;

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
     * 生成 TTS 音频，上传至 OSS 持久化
     */
    public String generate(String ttsText, String voiceId) {
        if (ttsText == null || ttsText.isEmpty()) {
            throw new IllegalArgumentException("TTS 文本为空，无需生成");
        }
        if (voiceId == null || voiceId.isEmpty()) {
            throw new IllegalArgumentException("voiceId 不能为空");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("text", ttsText);
        requestBody.put("voice_setting_voice_id", voiceId);
        requestBody.put("voice_setting_speed", 1.0);
        requestBody.put("voice_setting_volume", 0);
        requestBody.put("voice_setting_pitch", 0);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("序列化 TTS 请求体失败", e);
        }

        Request request = new Request.Builder()
                .url(viduProperties.getBaseUrl() + viduProperties.getTtsEndpoint())
                .addHeader("Authorization", "Token " + viduProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                log.error("Vidu TTS API 调用失败: {} - {}", response.code(), errorBody);
                throw new RuntimeException("Vidu TTS 生成失败: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("Vidu TTS 响应: {}", responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            String state = root.path("state").asText();
            String fileUrl = root.path("file_url").asText();
            int credits = root.path("credits").asInt(0);

            if (!"success".equals(state) || fileUrl.isEmpty()) {
                throw new RuntimeException("Vidu TTS 生成失败: state=" + state + ", file_url=" + fileUrl);
            }

            String ossUrl = ossService.uploadAudioFromUrl(fileUrl);
            log.info("Vidu TTS 完成: 消耗 credits={}, OSS URL={}", credits, ossUrl);
            return ossUrl;

        } catch (IOException e) {
            throw new RuntimeException("Vidu TTS 请求异常", e);
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
}
