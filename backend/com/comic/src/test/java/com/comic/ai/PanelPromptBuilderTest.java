package com.comic.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        assertTrue(prompt.contains("虚化边框"), "回忆镜头应要求虚化边框时间态标识");
        assertTrue(prompt.contains("回忆") || prompt.contains("闪回"), "回忆镜头应在 prompt 中显式标记");
    }

    @Test
    void buildGridPrompt_shouldIncludeImagePromptHint() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("visualDescription", "少女站在旧车站月台");
        shot.put("image_prompt_hint", "胶片颗粒感，冷色调，雨丝可见");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList());

        assertTrue(prompt.contains("胶片颗粒感"), "image_prompt_hint 应注入图像 prompt");
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

        assertTrue(prompt.contains("虚化边框"), "多镜头视频 prompt 应要求回忆镜头虚化边框");
        assertTrue(prompt.contains("镜头边缘柔和暗角"), "video_prompt_hint 应注入视频 prompt");
    }

    @Test
    void buildGridPrompt_shouldContainFillerScenesForEmptySlots() {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", "测试场景" + (i + 1));
            shots.add(shot);
        }

        String prompt = builder.buildGridPrompt("ANIME", shots, Collections.emptyList(), 3, 3);

        assertFalse(prompt.contains("纯黑色填充"), "不应包含黑格填充指令");
        assertTrue(prompt.contains("无角色"), "占位场景应包含'无角色'标记");
        assertTrue(prompt.contains("第3行第1列"), "应有第3行第1列占位");
        assertTrue(prompt.contains("第3行第2列"), "应有第3行第2列占位");
    }

    @Test
    void buildGridPrompt_fullGrid_shouldHaveNoFiller() {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("sceneDescription", "测试场景" + (i + 1));
            shots.add(shot);
        }

        String prompt = builder.buildGridPrompt("ANIME", shots, Collections.emptyList(), 3, 3);

        assertFalse(prompt.contains("纯黑色填充"));
        assertTrue(prompt.contains("第3行第3列"));
    }

    @Test
    void buildGridPrompt_shouldFlattenMultilineSceneDescriptionInGridInstruction() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("sceneDescription", "标签模板：角色A\n动作：抬手\n情绪：坚定");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList(), 1, 1);

        String line = findGridLine(prompt, "第1行第1列");
        assertNotNull(line);
        assertTrue(line.contains("标签模板：角色A 动作：抬手 情绪：坚定"), "sceneDescription 应在单格指令中单行化");
    }

    @Test
    void buildGridPrompt_shouldFlattenMultilineVisualDescriptionInGridInstruction() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("visualDescription", "镜头描述A\n镜头描述B");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList(), 1, 1);

        String line = findGridLine(prompt, "第1行第1列");
        assertNotNull(line);
        assertTrue(line.contains("镜头描述A 镜头描述B"), "visualDescription 回退分支应单行化");
    }

    @Test
    void buildGridPrompt_shouldFlattenMultilineImagePromptHintInGridInstruction() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("visualDescription", "主体站立");
        shot.put("image_prompt_hint", "细节A\n细节B");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList(), 1, 1);

        String line = findGridLine(prompt, "第1行第1列");
        assertNotNull(line);
        assertTrue(line.contains("细节A 细节B"), "image_prompt_hint 应单行化");
    }

    private String findGridLine(String prompt, String cellPrefix) {
        for (String line : prompt.split("\\n")) {
            if (line.contains(cellPrefix)) {
                return line;
            }
        }
        return null;
    }
}
