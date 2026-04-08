package com.comic.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelPromptBuilderTest {

    private final PanelPromptBuilder builder = new PanelPromptBuilder();

    @Test
    void buildGridPrompt_shouldMarkFlashbackWithBlurBorder() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("visualDescription", "主角回忆起童年在雨中的场景");
        shot.put("shotSize", "中景");
        shot.put("cameraAngle", "平视");
        shot.put("cameraMovement", "静止");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList());

        assertTrue(prompt.contains("虚化边框"), "回忆镜头未要求虚化边框时间态标识");
        assertTrue(prompt.contains("回忆") || prompt.contains("闪回"), "回忆镜头未在 prompt 中显式标记");
    }

    @Test
    void buildGridPrompt_shouldIncludeImagePromptHint() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("visualDescription", "少女站在旧车站月台");
        shot.put("image_prompt_hint", "胶片颗粒感，冷色调，雨丝可见");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList());

        assertTrue(prompt.contains("胶片颗粒感"), "image_prompt_hint 未注入图片 prompt");
    }

    @Test
    void buildMultiShotPrompt_shouldIncludeFlashbackRuleAndVideoHint() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("duration", 5);
        shot.put("shotSize", "近景");
        shot.put("cameraAngle", "仰角");
        shot.put("cameraMovement", "缓慢推进");
        shot.put("visualDescription", "闪回到两人初见的咖啡馆");
        shot.put("video_prompt_hint", "镜头边缘柔和暗角，节奏放慢");

        Map<String, Object> panelInfo = new HashMap<>();
        panelInfo.put("shots", Arrays.asList(shot));

        String prompt = builder.buildMultiShotPrompt("ANIME", panelInfo);

        assertTrue(prompt.contains("虚化边框"), "多镜头视频 prompt 未要求回忆镜头虚化边框");
        assertTrue(prompt.contains("镜头边缘柔和暗角"), "video_prompt_hint 未注入视频 prompt");
    }
}
