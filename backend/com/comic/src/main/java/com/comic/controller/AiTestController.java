package com.comic.controller;

import com.comic.ai.text.TextGenerationService;
import com.comic.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 测试接口
 * 用于测试各个 AI 提供商
 */
@RestController
@RequestMapping("/api/test/ai")
@RequiredArgsConstructor
@Tag(name = "AI测试")
public class AiTestController {

    private final TextGenerationService textGenerationService;

    /**
     * 测试 AI 调用
     * POST /api/test/ai/call
     * Body: {
     *   "systemPrompt": "你是一个专业的漫画编剧",
     *   "userPrompt": "请生成一个热血漫画的故事大纲"
     * }
     */
    @PostMapping("/call")
    @Operation(summary = "测试AI调用", description = "测试文本生成服务")
    public Result<Map<String, Object>> testAiCall(@RequestBody Map<String, String> body) {
        String systemPrompt = body.getOrDefault("systemPrompt", "你是一个专业的AI助手");
        String userPrompt = body.get("userPrompt");

        if (userPrompt == null || userPrompt.isEmpty()) {
            return Result.fail("userPrompt 不能为空");
        }

        long start = System.currentTimeMillis();
        String response = textGenerationService.generate(systemPrompt, userPrompt);
        long duration = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("service", textGenerationService.getServiceName());
        result.put("duration", duration + "ms");
        result.put("response", response);
        result.put("availableSlots", textGenerationService.getAvailableConcurrentSlots());

        return Result.ok(result);
    }

    /**
     * 获取 AI 服务信息
     * GET /api/test/ai/info
     */
    @GetMapping("/info")
    @Operation(summary = "获取AI服务信息", description = "获取当前文本生成服务的信息")
    public Result<Map<String, Object>> getAiInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("service", textGenerationService.getServiceName());
        info.put("availableSlots", textGenerationService.getAvailableConcurrentSlots());
        info.put("maxSlots", 3); // 默认最大并发
        return Result.ok(info);
    }

    /**
     * 快速测试（简单对话）
     * GET /api/test/ai/ping
     */
    @GetMapping("/ping")
    @Operation(summary = "快速测试", description = "快速测试AI服务是否可用")
    public Result<Map<String, String>> ping() {
        String response = textGenerationService.generate(
            "你是一个简洁的AI助手",
            "请用一句话介绍你自己"
        );

        Map<String, String> result = new HashMap<>();
        result.put("service", textGenerationService.getServiceName());
        result.put("response", response);
        return Result.ok(result);
    }
}
