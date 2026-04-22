package com.comic.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComicCommentaryPanelPromptBuilderTest {

    private final ComicCommentaryPanelPromptBuilder builder =
            new ComicCommentaryPanelPromptBuilder(new PanelPromptBuilder());

    @Test
    void buildGridPrompt_shouldUseVisualLabelsWhenSceneDescriptionIsTemplate() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("sceneDescription", String.join("\n",
                "(动作)ACTION_TOKEN",
                "(环境)ENV_TOKEN",
                "(状态)STATE_TOKEN",
                "(表情)EXPR_TOKEN",
                "(内声)INNER_TOKEN",
                "(音效)SFX_TOKEN"
        ));
        shot.put("visualDescription", "VISUAL_FALLBACK_TOKEN");

        String prompt = builder.buildGridPrompt("ANIME", Arrays.asList(shot), Collections.emptyList(), 1, 1);

        assertTrue(prompt.contains("ACTION_TOKEN"));
        assertTrue(prompt.contains("ENV_TOKEN"));
        assertTrue(prompt.contains("STATE_TOKEN"));
        assertTrue(prompt.contains("EXPR_TOKEN"));
        assertFalse(prompt.contains("INNER_TOKEN"));
        assertFalse(prompt.contains("SFX_TOKEN"));
        assertFalse(prompt.contains("VISUAL_FALLBACK_TOKEN"));
    }

    @Test
    void buildMultiShotPrompt_previousPanelShouldPreferSceneDescriptionOverVisualDescription() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("duration", 3);
        shot.put("sceneDescription", "CURRENT_SCENE_DESC");
        shot.put("visualDescription", "CURRENT_VISUAL_DESC");

        Map<String, Object> previousPanelLastShot = new HashMap<>();
        previousPanelLastShot.put("sceneDescription", "PREV_SCENE_DESC_TOKEN");
        previousPanelLastShot.put("visualDescription", "PREV_VISUAL_DESC_TOKEN");

        Map<String, Object> panelInfo = new HashMap<>();
        panelInfo.put("shots", Arrays.asList(shot));

        String prompt = builder.buildMultiShotPrompt("ANIME", panelInfo, null, previousPanelLastShot);

        assertTrue(prompt.contains("PREV_SCENE_DESC_TOKEN"));
        assertFalse(prompt.contains("PREV_VISUAL_DESC_TOKEN"));
    }

    @Test
    void buildMultiShotPrompt_previousPanelShouldFallbackToVisualDescriptionWhenSceneDescriptionMissing() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("duration", 3);
        shot.put("sceneDescription", "CURRENT_SCENE_DESC");

        Map<String, Object> previousPanelLastShot = new HashMap<>();
        previousPanelLastShot.put("visualDescription", "PREV_VISUAL_DESC_FALLBACK_TOKEN");

        Map<String, Object> panelInfo = new HashMap<>();
        panelInfo.put("shots", Arrays.asList(shot));

        String prompt = builder.buildMultiShotPrompt("ANIME", panelInfo, null, previousPanelLastShot);

        assertTrue(prompt.contains("PREV_VISUAL_DESC_FALLBACK_TOKEN"));
    }

    @Test
    void buildMultiShotPrompt_shouldNotAppendEmptyVisualDescriptionSegmentInPerShotScene() {
        Map<String, Object> shot = new HashMap<>();
        shot.put("duration", 4);
        shot.put("shotSize", "SHOT_SIZE_TOKEN");
        shot.put("cameraAngle", "CAMERA_ANGLE_TOKEN");
        shot.put("cameraMovement", "CAMERA_MOVE_TOKEN");
        shot.put("visualDescription", "");

        Map<String, Object> panelInfo = new HashMap<>();
        panelInfo.put("shots", Arrays.asList(shot));

        String prompt = builder.buildMultiShotPrompt("ANIME", panelInfo);
        String sceneLine = findLineWithPrefix(prompt, "Scene: ");

        assertNotNull(sceneLine);
        assertEquals("Scene: SHOT_SIZE_TOKEN，CAMERA_ANGLE_TOKEN，CAMERA_MOVE_TOKEN", sceneLine);
        assertFalse(sceneLine.endsWith("，"));
    }

    private String findLineWithPrefix(String prompt, String prefix) {
        for (String line : prompt.split("\\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }
}
