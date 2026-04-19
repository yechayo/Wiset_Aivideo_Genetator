package com.comic.service.panel;

import com.comic.ai.image.ImageGenerationService;
import com.comic.service.oss.OssService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import com.comic.config.AiServiceConfiguration;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.repository.CharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 GridImageService 的分页计算和辅助方法
 */
class GridImageGenerationTest {

    @Test
    void computePageGridSizes_10shots_should_be_single_4x4_page() {
        // calculateGridSize(10) → {4,4}, 4x4=16 >= 10, 一页装下
        List<int[]> pages = GridImageService.computePageGridSizes(10);
        assertEquals(1, pages.size());
        assertArrayEquals(new int[]{4, 4}, pages.get(0));
    }

    @Test
    void computePageGridSizes_17shots_should_be_4x4_and_1x1() {
        // calculateGridSize(17) → {5,5}, 5x5=25 >= 17, 一页装下
        List<int[]> pages = GridImageService.computePageGridSizes(17);
        assertEquals(1, pages.size());
        assertArrayEquals(new int[]{5, 5}, pages.get(0));
    }

    @Test
    void computePageGridSizes_26shots_should_be_5x5_and_2x2() {
        // 5x5=25 < 26, 剩余1 → calculateGridSize(1) = {2,2}
        List<int[]> pages = GridImageService.computePageGridSizes(26);
        assertEquals(2, pages.size());
        assertArrayEquals(new int[]{5, 5}, pages.get(0));
        assertArrayEquals(new int[]{2, 2}, pages.get(1));
    }

    @Test
    void computePageGridSizes_1shot_should_return_2x2() {
        List<int[]> pages = GridImageService.computePageGridSizes(1);
        assertEquals(1, pages.size());
        assertArrayEquals(new int[]{2, 2}, pages.get(0));
    }

    @Test
    void computePageGridSizes_25shots_should_return_single_5x5() {
        List<int[]> pages = GridImageService.computePageGridSizes(25);
        assertEquals(1, pages.size());
        assertArrayEquals(new int[]{5, 5}, pages.get(0));
    }

    @Test
    void buildGridConfigs_should_match_pageGridSizes() {
        List<int[]> pageGridSizes = GridImageService.computePageGridSizes(26);
        List<Map<String, Object>> configs = GridImageService.buildGridConfigs(pageGridSizes, 26);

        assertEquals(2, configs.size());
        assertEquals(0, configs.get(0).get("page"));
        assertEquals(5, configs.get(0).get("gridCols"));
        assertEquals(5, configs.get(0).get("gridRows"));
        assertEquals(25, configs.get(0).get("shotCount"));
        assertEquals(1, configs.get(1).get("page"));
        assertEquals(2, configs.get(1).get("gridCols"));
        assertEquals(2, configs.get(1).get("gridRows"));
        assertEquals(1, configs.get(1).get("shotCount"));
    }


    @Test
    void computeShotOffset_page0_should_return_0() {
        List<int[]> pageGridSizes = GridImageService.computePageGridSizes(26);
        assertEquals(0, GridImageService.computeShotOffset(0, pageGridSizes));
    }

    @Test
    void computeShotOffset_page1_should_return_first_page_capacity() {
        List<int[]> pageGridSizes = GridImageService.computePageGridSizes(26);
        // 第0页 5x5=25, 第1页从25开始
        assertEquals(25, GridImageService.computeShotOffset(1, pageGridSizes));
    }

    @Test
    void computeShotOffset_multiPage_should_accumulate_correctly() {
        // 51 shots: 5x5(25), 5x5(25), 1x1(1)
        List<int[]> pageGridSizes = GridImageService.computePageGridSizes(51);
        assertEquals(0, GridImageService.computeShotOffset(0, pageGridSizes));
        assertEquals(25, GridImageService.computeShotOffset(1, pageGridSizes));
        assertEquals(50, GridImageService.computeShotOffset(2, pageGridSizes));
    }

    @Test
    void gridPageResult_should_hold_correct_data() {
        List<Map<String, Object>> splitShots = new ArrayList<>();
        Map<String, Object> shot = new HashMap<>();
        shot.put("splitImageUrl", "https://oss.example.com/shot_0.png");
        shot.put("shotNumber", 1);
        splitShots.add(shot);

        GridImageService.GridPageResult result = new GridImageService.GridPageResult(
            0, "https://oss.example.com/grid.png", "test prompt", splitShots);

        assertEquals(0, result.pageIndex);
        assertEquals("https://oss.example.com/grid.png", result.imageUrl);
        assertEquals("test prompt", result.prompt);
        assertEquals(1, result.splitShots.size());
        assertEquals("https://oss.example.com/shot_0.png", result.splitShots.get(0).get("splitImageUrl"));
    }
}
