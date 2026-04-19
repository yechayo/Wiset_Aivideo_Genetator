package com.comic.ai.video;

import com.comic.config.KlingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KlingVideoService 真实 API 单测
 * 直接调用可灵 API，验证单镜头 std 模式视频生成 + 轮询
 *
 * 运行: mvn test -Dtest=KlingVideoServiceTest
 */
@Tag("manual")
class KlingVideoServiceTest {

    private static final String TEST_IMAGE_URL =
        "https://cdn.pixabay.com/photo/2024/02/28/07/42/european-shorthair-8601492_640.jpg";

    @Test
    void generateSingleShot_std_5s() throws Exception {
        KlingProperties props = new KlingProperties();
        props.setAccessKey("ARQneJtTMHMNAyhLNA4m94ytfgQggDJL");
        props.setSecretKey("bNdCMGJCFDTkQDAmGPB4et8yRPPMPNQT");
        props.setBaseUrl("https://api-beijing.klingai.com");
        props.setModelName("kling-v3");
        props.setMode("std");

        OkHttpClient httpClient = new OkHttpClient.Builder().build();
        ObjectMapper objectMapper = new ObjectMapper();
        KlingVideoService service = new KlingVideoService(props, httpClient, objectMapper);

        // 1. 提交任务
        System.out.println("=== 1. 提交单镜头视频 (kling-v3 std, 5s) ===");
        String taskId = service.generateAsync(
            "A cute cat looking at the camera, gentle movement, cinematic lighting",
            5, "16:9", TEST_IMAGE_URL, false, "kling-v3-std"
        );
        assertNotNull(taskId, "taskId 不应为 null");
        System.out.println("taskId = " + taskId);

        // 2. 轮询直到完成
        System.out.println("\n=== 2. 轮询任务状态 ===");
        VideoGenerationService.TaskStatus status;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);
            status = service.getTaskStatus(taskId);
            System.out.printf("  [%d] status=%s progress=%d url=%s credits=%s%n",
                i + 1, status.getStatus(), status.getProgress(),
                status.getVideoUrl() != null
                    ? status.getVideoUrl().substring(0, Math.min(60, status.getVideoUrl().length())) + "..."
                    : "null",
                status.getCredits());

            if (status.isCompleted()) {
                System.out.println("\n=== 3. 视频生成成功! ===");
                System.out.println("videoUrl = " + status.getVideoUrl());
                System.out.println("credits = " + status.getCredits());
                assertNotNull(status.getVideoUrl(), "视频 URL 不应为 null");
                return;
            }
            if (status.isFailed()) {
                fail("视频生成失败: " + status.getErrorMessage());
            }
        }
        fail("超时，任务未完成");
    }
}
