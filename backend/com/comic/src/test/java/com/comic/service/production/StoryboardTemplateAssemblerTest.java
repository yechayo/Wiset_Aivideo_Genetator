package com.comic.service.production;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoryboardTemplateAssemblerTest {

    private static final String LABEL_SCENE = "\uFF08\u573A\u666F\uFF09";
    private static final String LABEL_CHARACTER = "\uFF08\u51FA\u573A\uFF09";

    @Test
    void assembleVideoTemplate_shouldDeduplicateAndFilterBlanks() {
        String template = StoryboardTemplateAssembler.assembleVideoTemplate(
                Arrays.asList(null, "  ", "\u96E8\u591C\u8857\u9053", "\u96E8\u591C\u8857\u9053", "\u5929\u53F0", " \u5929\u53F0 ", "\u5730\u94C1\u7AD9"),
                Arrays.asList("Alice", "", null, "Bob", "Alice", " Bob ", "Charlie"),
                Arrays.asList("shot-1", " ", null, "shot-2")
        );

        assertEquals(String.join("\n",
                LABEL_SCENE + "\u96E8\u591C\u8857\u9053\u3001\u5929\u53F0\u3001\u5730\u94C1\u7AD9",
                LABEL_CHARACTER + "Alice\u3001Bob\u3001Charlie",
                "shot-1",
                "shot-2"
        ), template);
    }

    @Test
    void assembleVideoTemplate_shouldKeepShotDescriptionsOrder() {
        String template = StoryboardTemplateAssembler.assembleVideoTemplate(
                Collections.singletonList("scene-A"),
                Collections.singletonList("role-A"),
                Arrays.asList("shot-a", "shot-b", "shot-c")
        );

        assertEquals(String.join("\n",
                LABEL_SCENE + "scene-A",
                LABEL_CHARACTER + "role-A",
                "shot-a",
                "shot-b",
                "shot-c"
        ), template);
    }

    @Test
    void assembleVideoTemplate_shouldHandleNullInputsStably() {
        String template = StoryboardTemplateAssembler.assembleVideoTemplate(null, null, null);

        assertEquals(String.join("\n",
                LABEL_SCENE,
                LABEL_CHARACTER
        ), template);
    }

    @Test
    void assembleImageTemplate_shouldAssembleSceneAndVisualLabels() {
        String template = StoryboardTemplateAssembler.assembleImageTemplate(
                "\u96E8\u591C\u8857\u9053",
                "\uFF08\u52A8\u4F5C\uFF09\u5954\u8DD1\n\uFF08\u73AF\u5883\uFF09\u9739\u96F3"
        );

        assertEquals(String.join("\n",
                LABEL_SCENE + "\u96E8\u591C\u8857\u9053",
                "\uFF08\u52A8\u4F5C\uFF09\u5954\u8DD1",
                "\uFF08\u73AF\u5883\uFF09\u9739\u96F3"
        ), template);
    }

    @Test
    void assembleImageTemplate_shouldHandleBlankSceneAndVisualLabels() {
        String emptyTemplate = StoryboardTemplateAssembler.assembleImageTemplate(null, "  ");
        assertEquals(LABEL_SCENE, emptyTemplate);

        String onlyLabelsTemplate = StoryboardTemplateAssembler.assembleImageTemplate(" ", "line-1\nline-2");
        assertEquals(String.join("\n",
                LABEL_SCENE,
                "line-1",
                "line-2"
        ), onlyLabelsTemplate);
    }
}
