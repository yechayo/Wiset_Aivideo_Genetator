package com.comic.e2e;

import com.comic.ai.text.NarrationAllocator;
import com.comic.ai.text.NarrationPromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 旁白生成流程端到端测试
 * 使用 @TestConfiguration 隔离旁白相关 bean，不依赖 OSS 等基础设施
 */
@SpringJUnitConfig(NarrationE2eTest.NarrationTestConfig.class)
class NarrationE2eTest {

    @TestConfiguration
    static class NarrationTestConfig {
        @Bean
        public NarrationAllocator narrationAllocator() {
            return new NarrationAllocator();
        }

        @Bean
        public NarrationPromptBuilder narrationPromptBuilder() {
            return new NarrationPromptBuilder();
        }
    }

    @Autowired
    private NarrationAllocator narrationAllocator;

    @Autowired
    private NarrationPromptBuilder narrationPromptBuilder;

    @Test
    void testBeansAreWired() {
        assertNotNull(narrationAllocator);
        assertNotNull(narrationPromptBuilder);
    }

    @Test
    void testFullAllocationFlow() {
        String narration = "阳光洒在古老的城墙上。他从未想过会有这一天。风吹动她的发丝。" +
                "她静静地看着远方。";

        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", 3);
            shot.put("dialogue", "无");
            shot.put("speaker", "无");
            shots.add(shot);
        }

        narrationAllocator.allocate(narration, shots, "third_person");

        long filled = shots.stream()
                .filter(s -> !"无".equals(s.get("narration")))
                .count();
        assertTrue(filled > 0, "至少有一个 shot 被分配了旁白");

        for (Map<String, Object> shot : shots) {
            String nar = str(shot.get("narration"));
            if (!"无".equals(nar)) {
                int len = nar.length();
                // duration=3 时实现约束为 [14, 24] (softMax=20 * 1.2)
                assertTrue(len >= 5 && len <= 24,
                        "Shot " + shot.get("shotNumber") + " narration 长度 " + len + " 超出范围");
            }
        }
    }

    @Test
    void testMutualExclusionEnforced() {
        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("shotNumber", 1);
        s1.put("duration", 3);
        s1.put("dialogue", "我不能放弃！");
        s1.put("speaker", "林远");
        s1.put("narration", "有旁白");
        shots.add(s1);

        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("shotNumber", 2);
        s2.put("duration", 3);
        s2.put("dialogue", "无");
        s2.put("speaker", "无");
        shots.add(s2);

        narrationAllocator.allocate("旁白稿内容。", shots, "third_person");

        assertEquals("无", shots.get(0).get("narration"), "dialogue shot 的 narration 必须为「无」");
        assertFalse("无".equals(shots.get(1).get("narration")), "旁白 shot 应有旁白");
    }

    @Test
    void testSanitizeDialogue() {
        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("shotNumber", 1);
        shot.put("dialogue", "旁白：他是如何走到这一步的");
        shot.put("speaker", "旁白");
        shots.add(shot);

        narrationAllocator.sanitizeDialogue(shots);

        assertEquals("无", shots.get(0).get("dialogue"));
        assertEquals("无", shots.get(0).get("speaker"));
    }

    @Test
    void testForceDialogueRatio() {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("dialogue", "台词" + (i + 1));
            shot.put("dialogueTone", i == 2 ? "愤怒而急促" : "平静地说");
            shots.add(shot);
        }

        narrationAllocator.forceDialogueRatio(shots, 3);

        long kept = shots.stream()
                .filter(s -> !"无".equals(s.get("dialogue")))
                .count();
        assertEquals(3, kept, "应保留恰好 3 个对白");
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }
}