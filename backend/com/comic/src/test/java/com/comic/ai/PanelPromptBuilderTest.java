package com.comic.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void buildGridPrompt_should_contain_filler_scenes_for_empty_slots() {
        // 7个分镜 + 3x3网格 = 2个空格，应填充占位场景而非黑格
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", "测试场景" + (i + 1));
            shots.add(shot);
        }

        String prompt = builder.buildGridPrompt("ANIME", shots, Collections.emptyList(), 3, 3);

        // 不应包含"纯黑色填充"
        assertFalse(prompt.contains("纯黑色填充"), "不应包含黑格填充指令");

        // 应包含占位场景关键词
        assertTrue(prompt.contains("无角色"), "占位场景应包含'无角色'标记");

        // 应有第3行第1列和第3行第2列
        assertTrue(prompt.contains("第3行第1列"), "应有第3行第1列占位");
        assertTrue(prompt.contains("第3行第2列"), "应有第3行第2列占位");
    }

    @Test
    void buildGridPrompt_full_grid_should_have_no_filler() {
        // 恰好9个分镜填满3x3，不应有任何占位
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", "测试场景" + (i + 1));
            shots.add(shot);
        }

        String prompt = builder.buildGridPrompt("ANIME", shots, Collections.emptyList(), 3, 3);

        // 不应包含"纯黑色填充"
        assertFalse(prompt.contains("纯黑色填充"));
        // 应有完整的9个格子
        assertTrue(prompt.contains("第3行第3列"));
    }
}
