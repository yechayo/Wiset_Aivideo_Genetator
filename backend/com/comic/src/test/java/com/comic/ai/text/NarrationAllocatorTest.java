package com.comic.ai.text;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NarrationAllocatorTest {

    private final NarrationAllocator allocator = new NarrationAllocator();

    private List<Map<String, Object>> makeShots(Object... pairs) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i / 2 + 1);
            shot.put("duration", pairs[i + 1]);
            shot.put("dialogue", "无");
            shot.put("speaker", "无");
            shot.put("narration", "");
            shots.add(shot);
        }
        return shots;
    }

    @Test
    void testAllocate_basic() {
        String narration = "阳光洒在古老的城墙上。他从未想过会有这一天。";
        List<Map<String, Object>> shots = makeShots(1, 3, 2, 3, 3, 3);

        allocator.allocate(narration, shots, "third_person");

        String nar1 = str(shots.get(0).get("narration"));
        assertFalse("无".equals(nar1), "第一个 shot 应被分配旁白，实际: " + nar1);
        assertTrue(nar1.contains("阳光"), "第一个 shot 应包含第一句内容，实际: " + nar1);
        String nar2 = str(shots.get(1).get("narration"));
        assertFalse("无".equals(nar2), "第二个 shot 应被分配旁白，实际: " + nar2);
    }

    @Test
    void testMutualExclusion() {
        List<Map<String, Object>> shots = makeShots(1, 3, 2, 3);
        shots.get(0).put("dialogue", "我真的不想这样做。");
        shots.get(0).put("speaker", "林远");
        shots.get(0).put("narration", "他握紧了拳头");

        String narration = "阳光洒在古老的城墙上。";
        allocator.allocate(narration, shots, "third_person");

        assertEquals("无", shots.get(0).get("narration"));
        assertFalse("无".equals(shots.get(1).get("narration")));
    }

    @Test
    void testSanitizeDialogue_removesNarrationKeyword() {
        List<Map<String, Object>> shots = new ArrayList<>();
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("shotNumber", 1);
        shot.put("dialogue", "旁白：他是如何走到这一步的");
        shot.put("speaker", "旁白");
        shots.add(shot);

        allocator.sanitizeDialogue(shots);

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

        allocator.forceDialogueRatio(shots, 3);

        long kept = shots.stream()
                .filter(s -> !"无".equals(s.get("dialogue")))
                .count();
        assertEquals(3, kept);
    }

    @Test
    void testEmptyNarration() {
        List<Map<String, Object>> shots = makeShots(1, 3, 2, 3);
        allocator.allocate("", shots, "third_person");
        assertEquals("无", shots.get(0).get("narration"));
        assertEquals("无", shots.get(1).get("narration"));
    }

    @Test
    void testWordCountConstraint() {
        String narration = "阳光洒在古老的城墙上。他从未想过会有这一天。风吹动她的发丝。";
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", 3);
            shot.put("dialogue", "无");
            shot.put("speaker", "无");
            shot.put("narration", "");
            shots.add(shot);
        }

        allocator.allocate(narration, shots, "third_person");

        for (Map<String, Object> shot : shots) {
            String nar = str(shot.get("narration"));
            if (!"无".equals(nar)) {
                int len = nar.length();
                assertTrue(len >= 5 && len <= 16,
                        "Shot " + shot.get("shotNumber") + " narration 长度 " + len + " 超出范围 [5,16]");
            }
        }
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }
}