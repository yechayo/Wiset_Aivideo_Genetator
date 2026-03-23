package com.comic.controller;

import com.comic.common.Result;
import com.comic.service.oss.OssService;
import com.comic.service.production.EpisodeProductionService;
import com.comic.service.production.GridSplitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeControllerTest {

    private static final Long EPISODE_ID = 100L;

    @Mock
    private EpisodeProductionService productionService;

    @Mock
    private OssService ossService;

    @InjectMocks
    private EpisodeController episodeController;

    @Test
    @DisplayName("splitGridPage - body为空时默认按pageIndex=0调用后端切图")
    void splitGridPage_shouldUseDefaultPageIndexWhenBodyIsNull() {
        GridSplitService.SplitPageResult splitResult = new GridSplitService.SplitPageResult();
        splitResult.setPageIndex(0);
        splitResult.setRows(3);
        splitResult.setCols(3);
        splitResult.setSkipped(false);
        when(productionService.splitGridPageForFusion(EPISODE_ID, 0)).thenReturn(splitResult);

        Result<GridSplitService.SplitPageResult> result = episodeController.splitGridPage(EPISODE_ID, null);

        assertEquals(200, result.getCode());
        assertEquals("success", result.getMessage());
        assertNotNull(result.getData());
        assertEquals(0, result.getData().getPageIndex());
        verify(productionService).splitGridPageForFusion(EPISODE_ID, 0);
    }

    @Test
    @DisplayName("splitGridPage - pageIndex为字符串数字时应正确解析")
    void splitGridPage_shouldParseNumericStringPageIndex() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageIndex", "2");

        GridSplitService.SplitPageResult splitResult = new GridSplitService.SplitPageResult();
        splitResult.setPageIndex(2);
        splitResult.setRows(2);
        splitResult.setCols(3);
        splitResult.setSkipped(false);
        when(productionService.splitGridPageForFusion(EPISODE_ID, 2)).thenReturn(splitResult);

        Result<GridSplitService.SplitPageResult> result = episodeController.splitGridPage(EPISODE_ID, body);

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().getPageIndex());
        verify(productionService).splitGridPageForFusion(EPISODE_ID, 2);
    }

    @Test
    @DisplayName("splitGridPage - pageIndex格式错误时抛出异常")
    void splitGridPage_shouldThrowWhenPageIndexFormatInvalid() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageIndex", "abc");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.splitGridPage(EPISODE_ID, body)
        );

        assertEquals("pageIndex 格式不正确", ex.getMessage());
        verify(productionService, never()).splitGridPageForFusion(EPISODE_ID, 0);
    }

    @Test
    @DisplayName("splitGridPage - pageIndex小于0时抛出异常")
    void splitGridPage_shouldThrowWhenPageIndexIsNegative() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageIndex", -1);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.splitGridPage(EPISODE_ID, body)
        );

        assertEquals("pageIndex 不能小于0", ex.getMessage());
        verify(productionService, never()).splitGridPageForFusion(EPISODE_ID, -1);
    }
}

