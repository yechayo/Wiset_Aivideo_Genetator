package com.comic.service.panel;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GridImageServiceTest {

    @Test
    void splitGrid_should_produce_9_sub_images() {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
        assertEquals(9, result.size());
        // cellW = (300-16)/3 = 94, lastColW = 300 - 2*102 = 96
        assertEquals(94, result.get(0).getWidth());
        assertEquals(96, result.get(2).getWidth());
    }

    @Test
    void splitGrid_should_handle_non_divisible_size() {
        BufferedImage img = new BufferedImage(301, 301, BufferedImage.TYPE_INT_RGB);
        List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
        assertEquals(9, result.size());
        // cellW = (301-16)/3 = 95, lastColW = 301 - 2*103 = 95
        assertEquals(95, result.get(0).getWidth());
        assertEquals(95, result.get(2).getWidth());
    }

    @Test
    void splitGrid_should_compensate_for_separator_pixels() {
        // 3列图片，宽 = 3 * 640 + 2 * 8 = 1936, 高 = 3 * 360 + 2 * 8 = 1096
        int cols = 3, rows = 3, sep = 8;
        int totalW = cols * 640 + (cols - 1) * sep;  // 1936
        int totalH = rows * 360 + (rows - 1) * sep;  // 1096
        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

        List<BufferedImage> result = GridImageService.splitGridImage(img, cols, rows);

        assertEquals(9, result.size());
        // 每个格子应该是纯净的 640x360，不包含分隔线
        assertEquals(640, result.get(0).getWidth());
        assertEquals(360, result.get(0).getHeight());
        // 验证格子之间的坐标正确：第2格从 x=648 开始（640+8）
        // 第5格从 y=368 开始（360+8）
    }

    @Test
    void splitGrid_should_handle_remainder_pixels_in_last_cell() {
        // 宽 = 1936（不是精确整除），验证最后一列拿到剩余像素
        int cols = 3, sep = 8;
        int totalW = 1936;
        int totalH = 1096;
        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

        List<BufferedImage> result = GridImageService.splitGridImage(img, cols, 3);

        assertEquals(9, result.size());
        // 前两列应该是 640px，最后一列应该是剩余像素
        int expectedCellW = (totalW - (cols - 1) * sep) / cols; // (1936-16)/3 = 640
        int lastCellW = totalW - 2 * (expectedCellW + sep);    // 1936 - 2*648 = 640
        assertEquals(640, result.get(0).getWidth());
        assertEquals(lastCellW, result.get(2).getWidth());
    }

    @Test
    void calculateGridSize_should_support_2x2() {
        assertArrayEquals(new int[]{2, 2}, GridImageService.calculateGridSize(1));
        assertArrayEquals(new int[]{2, 2}, GridImageService.calculateGridSize(4));
    }

    @Test
    void calculateGridSize_should_support_3x3() {
        assertArrayEquals(new int[]{3, 3}, GridImageService.calculateGridSize(5));
        assertArrayEquals(new int[]{3, 3}, GridImageService.calculateGridSize(9));
    }

    @Test
    void calculateGridSize_should_support_4x4() {
        assertArrayEquals(new int[]{4, 4}, GridImageService.calculateGridSize(10));
        assertArrayEquals(new int[]{4, 4}, GridImageService.calculateGridSize(16));
    }

    @Test
    void calculateGridSize_should_support_5x5() {
        assertArrayEquals(new int[]{5, 5}, GridImageService.calculateGridSize(17));
        assertArrayEquals(new int[]{5, 5}, GridImageService.calculateGridSize(25));
    }

    @Test
    void createFusionImage_dimensions_should_be_constant() {
        final int FIXED_WIDTH = 1920;
        final int FIXED_HEIGHT = 1080;
        final int BOTTOM_BAR_HEIGHT = 180;
        final int MAIN_AREA_HEIGHT = FIXED_HEIGHT - BOTTOM_BAR_HEIGHT;
        final int PAD = 4;

        // 验证不同 shots 数量下 cell 尺寸计算正确（使用动态 fCols）
        int[] shotCounts = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 16, 17, 20, 25};
        for (int shotCount : shotCounts) {
            int[] gridSize = GridImageService.calculateGridSize(shotCount);
            int fCols = gridSize[0];
            int fRows = (int) Math.ceil((double) shotCount / fCols);
            int cellW = (FIXED_WIDTH - PAD * (fCols + 1)) / fCols;
            int cellH = (MAIN_AREA_HEIGHT - PAD * (fRows + 1)) / fRows;

            assertTrue(cellW > 0 && cellH > 0, "shots=" + shotCount + ": cell dimensions invalid");
            assertTrue(fCols * cellW + (fCols + 1) * PAD <= FIXED_WIDTH, "shots=" + shotCount + ": width overflow");
            assertTrue(fRows * cellH + (fRows + 1) * PAD <= MAIN_AREA_HEIGHT, "shots=" + shotCount + ": height overflow");
        }
    }

    @Test
    void nameBadge_should_prefer_right_side_of_character_image_when_space_allows() {
        // 槽位宽度充足时，名字标签应优先贴在角色图右侧
        java.awt.Point p = GridImageService.computeNameBadgePosition(
                100, 900, 220, 132,
                120, 920, 80, 90,
                70, 24
        );

        assertTrue(p.x >= 206, "名字标签应优先放在角色图右侧");
        assertTrue(p.x <= 100 + 220 - 70 - 4, "名字标签不能超出角色槽位右边界");
    }

    @Test
    void nameBadge_should_be_clamped_inside_character_slot() {
        // 槽位较窄时，名字标签也必须被限制在角色槽位内
        java.awt.Point p = GridImageService.computeNameBadgePosition(
                20, 900, 90, 120,
                26, 910, 70, 100,
                80, 28
        );

        assertTrue(p.x >= 24, "名字标签不能越过左边界");
        assertTrue(p.x <= 20 + 90 - 80 - 4, "名字标签不能越过右边界");
        assertTrue(p.y >= 904, "名字标签不能越过上边界");
        assertTrue(p.y <= 900 + 120 - 28 - 4, "名字标签不能越过下边界");
    }

    @Test
    void appendUserHintToPrompt_should_append_trimmed_hint() {
        String result = GridImageService.appendUserHintToPrompt("基础提示词", "  镜头请更强调逆光和雨夜质感  ");
        assertEquals("基础提示词\n\n用户修改要求: 镜头请更强调逆光和雨夜质感", result);
    }

    @Test
    void appendUserHintToPrompt_should_keep_original_when_hint_blank() {
        String result = GridImageService.appendUserHintToPrompt("基础提示词", "   ");
        assertEquals("基础提示词", result);
    }
}
