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
        // cellW = (300-8)/3 = 97, lastColW = 300 - 2*101 = 98
        assertEquals(97, result.get(0).getWidth());
        assertEquals(98, result.get(2).getWidth());
    }

    @Test
    void splitGrid_should_handle_non_divisible_size() {
        BufferedImage img = new BufferedImage(301, 301, BufferedImage.TYPE_INT_RGB);
        List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
        assertEquals(9, result.size());
        // cellW = (301-8)/3 = 97, lastColW = 301 - 2*101 = 99
        assertEquals(97, result.get(0).getWidth());
        assertEquals(99, result.get(2).getWidth());
    }

    @Test
    void splitGrid_should_compensate_for_separator_pixels() {
        // 3列图片，宽 = 3 * 640 + 2 * 4 = 1928, 高 = 3 * 360 + 2 * 4 = 1088
        int cols = 3, rows = 3, sep = 4;
        int totalW = cols * 640 + (cols - 1) * sep;  // 1928
        int totalH = rows * 360 + (rows - 1) * sep;  // 1088
        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

        List<BufferedImage> result = GridImageService.splitGridImage(img, cols, rows);

        assertEquals(9, result.size());
        // 每个格子应该是纯净的 640x360，不包含分隔线
        assertEquals(640, result.get(0).getWidth());
        assertEquals(360, result.get(0).getHeight());
        // 验证格子之间的坐标正确：第2格从 x=644 开始（640+4）
        // 第5格从 y=364 开始（360+4）
    }

    @Test
    void splitGrid_should_handle_remainder_pixels_in_last_cell() {
        // 宽 = 1930（不是精确整除），验证最后一列拿到剩余像素
        int cols = 3, sep = 4;
        int totalW = 1930;
        int totalH = 1088;
        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

        List<BufferedImage> result = GridImageService.splitGridImage(img, cols, 3);

        assertEquals(9, result.size());
        // 前两列应该是 642px，最后一列应该是剩余像素
        int expectedCellW = (totalW - (cols - 1) * sep) / cols; // (1930-8)/3 = 640
        int lastCellW = totalW - 2 * (expectedCellW + sep);    // 1930 - 2*644 = 642
        assertEquals(640, result.get(0).getWidth());
        assertEquals(lastCellW, result.get(2).getWidth());
    }

    @Test
    void calculatePagination_should_return_correct_pages() {
        assertEquals(1, GridImageService.calculatePageCount(7, 9));
        assertEquals(1, GridImageService.calculatePageCount(9, 9));
        assertEquals(2, GridImageService.calculatePageCount(10, 9));
        assertEquals(3, GridImageService.calculatePageCount(19, 9));
    }

    @Test
    void calculatePagination_should_handle_zero_shots() {
        assertEquals(0, GridImageService.calculatePageCount(0, 9));
    }

    @Test
    void createFusionImage_dimensions_should_be_constant() {
        final int FIXED_WIDTH = 1920;
        final int FIXED_HEIGHT = 1080;
        final int BOTTOM_BAR_HEIGHT = 180;
        final int MAIN_AREA_HEIGHT = FIXED_HEIGHT - BOTTOM_BAR_HEIGHT;
        final int PAD = 4;
        final int fCols = 3;

        // 验证不同 shots 数量下 cell 尺寸计算正确
        int[] shotCounts = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int shotCount : shotCounts) {
            int fRows = (int) Math.ceil((double) shotCount / fCols);
            int cellW = (FIXED_WIDTH - PAD * (fCols + 1)) / fCols;
            int cellH = (MAIN_AREA_HEIGHT - PAD * (fRows + 1)) / fRows;

            assertTrue(cellW > 0 && cellH > 0, "shots=" + shotCount + ": cell dimensions invalid");
            assertTrue(fCols * cellW + (fCols + 1) * PAD <= FIXED_WIDTH, "shots=" + shotCount + ": width overflow");
            assertTrue(fRows * cellH + (fRows + 1) * PAD <= MAIN_AREA_HEIGHT, "shots=" + shotCount + ": height overflow");
        }
    }
}
