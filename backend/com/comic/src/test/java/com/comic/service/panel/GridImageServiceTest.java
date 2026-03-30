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
        assertEquals(100, result.get(0).getWidth());
        assertEquals(100, result.get(0).getHeight());
    }

    @Test
    void splitGrid_should_handle_non_divisible_size() {
        BufferedImage img = new BufferedImage(301, 301, BufferedImage.TYPE_INT_RGB);
        List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
        assertEquals(9, result.size());
        assertEquals(100, result.get(0).getWidth());
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
}
