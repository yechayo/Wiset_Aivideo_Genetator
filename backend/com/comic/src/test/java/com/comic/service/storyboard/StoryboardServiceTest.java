package com.comic.service.storyboard;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StoryboardServiceTest {

    @Test
    void greedy_grouping_should_not_exceed_16s_per_group() {
        List<Map<String, Object>> shots = createShots(new int[]{3, 2, 4, 3, 2, 1, 4});
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        assertEquals(2, groups.size());
        for (List<Map<String, Object>> group : groups) {
            int total = group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();
            assertTrue(total <= 16, "每组时长不应超过16秒, 实际: " + total);
        }
    }

    @Test
    void greedy_grouping_should_handle_single_group() {
        List<Map<String, Object>> shots = createShots(new int[]{3, 2, 4});
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        assertEquals(1, groups.size());
        assertEquals(3, groups.get(0).size());
    }

    @Test
    void greedy_grouping_should_handle_empty_shots() {
        assertEquals(0, StoryboardService.greedyGroup(new ArrayList<>(), 16).size());
    }

    @Test
    void greedy_grouping_should_clamp_oversized_shot() {
        List<Map<String, Object>> shots = createShots(new int[]{20});
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        assertEquals(1, groups.size());
    }

    @Test
    void greedy_grouping_should_produce_many_groups() {
        int[] durations = new int[24];
        Arrays.fill(durations, 3);
        List<Map<String, Object>> shots = createShots(durations);
        List<List<Map<String, Object>>> groups = StoryboardService.greedyGroup(shots, 16);
        for (List<Map<String, Object>> group : groups) {
            int total = group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();
            assertTrue(total <= 16);
        }
    }

    private List<Map<String, Object>> createShots(int[] durations) {
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            Map<String, Object> shot = new HashMap<>();
            shot.put("shotNumber", i + 1);
            shot.put("duration", durations[i]);
            shots.add(shot);
        }
        return shots;
    }
}
