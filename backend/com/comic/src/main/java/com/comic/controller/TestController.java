package com.comic.controller;

import com.comic.ai.image.NanobananaImageService;
import com.comic.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Slf4j
public class TestController {

    private final NanobananaImageService nanobananaImageService;

    private final ConcurrentHashMap<String, Long> submitTimes = new ConcurrentHashMap<>();

    /**
     * POST /api/test/generate-image
     * 提交生图任务（异步，立即返回 taskId）
     */
    @PostMapping("/generate-image")
    public Result<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String prompt = (String) body.getOrDefault("prompt", "a cute cat, anime style");
        int width = body.get("width") instanceof Number ? ((Number) body.get("width")).intValue() : 3840;
        int height = body.get("height") instanceof Number ? ((Number) body.get("height")).intValue() : 2160;
        @SuppressWarnings("unchecked")
        List<String> referenceImages = (List<String>) body.get("referenceImages");

        try {
            String taskId = nanobananaImageService.submitOnly(prompt, width, height, referenceImages);
            submitTimes.put(taskId, System.currentTimeMillis());

            log.info("测试任务已提交: taskId={}, prompt长度={}, refImages={}",
                    taskId, prompt.length(), referenceImages != null ? referenceImages.size() : 0);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("status", "submitted");
            result.put("promptLength", prompt.length());
            return Result.ok(result);
        } catch (IOException e) {
            log.error("提交测试任务失败", e);
            return Result.fail("提交失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/test/generate-image/{taskId}
     * 查询任务状态（单次非阻塞轮询）
     * 返回: submitted / queued / generating / done / failed
     * done 时额外返回 ossUrl
     */
    @GetMapping("/generate-image/{taskId}")
    public Result<Map<String, Object>> poll(@PathVariable String taskId) {
        try {
            Map<String, Object> status = nanobananaImageService.checkStatus(taskId);
            String state = (String) status.get("status");

            // 如果上游已完成，转存 OSS
            if ("done".equals(state) && status.get("imageUrl") != null) {
                String remoteUrl = (String) status.get("imageUrl");
                String ossUrl = nanobananaImageService.transferToOss(remoteUrl);
                status.put("ossUrl", ossUrl);
                Long submitTime = submitTimes.get(taskId);
                if (submitTime != null) {
                    status.put("totalElapsedMs", System.currentTimeMillis() - submitTime);
                }
                log.info("测试任务完成: taskId={}, ossUrl={}", taskId, ossUrl);
            }

            return Result.ok(status);
        } catch (Exception e) {
            log.error("查询任务状态失败: taskId={}", taskId, e);
            return Result.fail("查询失败: " + e.getMessage());
        }
    }
}
