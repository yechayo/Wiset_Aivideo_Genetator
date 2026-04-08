package com.comic.service.production;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PanelProductionServicePromptSelectionTest {

    @Test
    void resolveFinalVideoPrompt_should_use_custom_prompt_first() {
        Map<String, Object> info = new HashMap<>();
        info.put("enhancedVideoPrompt", "增强后的 prompt");
        info.put("customVideoPrompt", "手动改写后的最终 prompt");

        String result = PanelProductionService.resolveFinalVideoPrompt(info, "自动构建 prompt");

        assertEquals("手动改写后的最终 prompt", result);
    }

    @Test
    void resolveFinalVideoPrompt_should_use_enhanced_when_custom_blank() {
        Map<String, Object> info = new HashMap<>();
        info.put("enhancedVideoPrompt", "增强后的 prompt");
        info.put("customVideoPrompt", "   ");

        String result = PanelProductionService.resolveFinalVideoPrompt(info, "自动构建 prompt");

        assertEquals("增强后的 prompt", result);
    }

    @Test
    void resolveFinalVideoPrompt_should_fallback_to_auto_prompt() {
        Map<String, Object> info = new HashMap<>();

        String result = PanelProductionService.resolveFinalVideoPrompt(info, "自动构建 prompt");

        assertEquals("自动构建 prompt", result);
    }
}
