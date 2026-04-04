package com.comic.controller;

import com.comic.ai.text.ViduTtsService;
import com.comic.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VoiceController {

    private final ViduTtsService viduTtsService;

    @PostMapping("/preview")
    @Operation(summary = "试听音色：生成一段示例文本的 TTS 音频")
    public Result<Map<String, String>> previewVoice(@RequestBody Map<String, String> body) {
        String voiceId = body.get("voiceId");
        if (voiceId == null || voiceId.isEmpty()) {
            return Result.fail("voiceId 不能为空");
        }
        try {
            String audioUrl = viduTtsService.preview(voiceId);
            Map<String, String> data = new HashMap<>();
            data.put("audioUrl", audioUrl);
            return Result.ok(data);
        } catch (Exception e) {
            return Result.fail("试听生成失败: " + e.getMessage());
        }
    }
}
