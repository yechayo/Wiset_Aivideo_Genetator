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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @DisplayName("submitFusionPage - body为空时抛出参数异常而不是NPE")
    void submitFusionPage_shouldThrowWhenBodyIsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.submitFusionPage(EPISODE_ID, null)
        );

        assertEquals("请求体不能为空", ex.getMessage());
        verify(productionService, never()).submitFusionPage(EPISODE_ID, 0, java.util.Collections.emptyList());
    }

    @Test
    @DisplayName("submitFusionPage - 默认pageIndex=0并返回totalFused")
    @SuppressWarnings("unchecked")
    void submitFusionPage_shouldUseDefaultPageIndex() {
        Map<String, Object> body = new HashMap<>();
        body.put("panelFusedUrls", new java.util.ArrayList<>(java.util.Arrays.asList(
                "https://mock/fused-0.png",
                "https://mock/fused-1.png"
        )));
        when(productionService.submitFusionPage(EPISODE_ID, 0, (java.util.List<String>) body.get("panelFusedUrls")))
                .thenReturn(2);

        Result<Map<String, Object>> result = episodeController.submitFusionPage(EPISODE_ID, body);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(2, result.getData().get("totalFused"));
        assertEquals(0, result.getData().get("pageIndex"));
        verify(productionService).submitFusionPage(EPISODE_ID, 0, (java.util.List<String>) body.get("panelFusedUrls"));
    }

    @Test
    @DisplayName("submitFusionPage - 字符串pageIndex可正确解析")
    @SuppressWarnings("unchecked")
    void submitFusionPage_shouldParseStringPageIndex() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageIndex", "3");
        body.put("panelFusedUrls", new java.util.ArrayList<>(java.util.Arrays.asList(
                "https://mock/fused-0.png"
        )));
        when(productionService.submitFusionPage(EPISODE_ID, 3, (java.util.List<String>) body.get("panelFusedUrls")))
                .thenReturn(7);

        Result<Map<String, Object>> result = episodeController.submitFusionPage(EPISODE_ID, body);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(7, result.getData().get("totalFused"));
        assertEquals(3, result.getData().get("pageIndex"));
        verify(productionService).submitFusionPage(EPISODE_ID, 3, (java.util.List<String>) body.get("panelFusedUrls"));
    }

    @Test
    @DisplayName("submitFusionPage - pageIndex格式错误时抛出异常")
    void submitFusionPage_shouldThrowWhenPageIndexFormatInvalid() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageIndex", "bad-index");
        body.put("panelFusedUrls", new java.util.ArrayList<>(java.util.Arrays.asList("https://mock/fused-0.png")));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.submitFusionPage(EPISODE_ID, body)
        );

        assertEquals("pageIndex 格式不正确", ex.getMessage());
        verify(productionService, never()).submitFusionPage(eq(EPISODE_ID), anyInt(), anyList());
    }

    @Test
    @DisplayName("submitFusionPage - pageIndex小于0时抛出异常")
    void submitFusionPage_shouldThrowWhenPageIndexNegative() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageIndex", -1);
        body.put("panelFusedUrls", new java.util.ArrayList<>(java.util.Arrays.asList("https://mock/fused-0.png")));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.submitFusionPage(EPISODE_ID, body)
        );

        assertEquals("pageIndex 不能小于0", ex.getMessage());
        verify(productionService, never()).submitFusionPage(eq(EPISODE_ID), anyInt(), anyList());
    }

    @Test
    @DisplayName("submitFusionPage - panelFusedUrls缺失或为空时抛出异常")
    void submitFusionPage_shouldThrowWhenPanelFusedUrlsMissingOrEmpty() {
        Map<String, Object> missingBody = new HashMap<>();
        missingBody.put("pageIndex", 0);
        IllegalArgumentException ex1 = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.submitFusionPage(EPISODE_ID, missingBody)
        );
        assertEquals("panelFusedUrls 不能为空", ex1.getMessage());

        Map<String, Object> emptyBody = new HashMap<>();
        emptyBody.put("pageIndex", 0);
        emptyBody.put("panelFusedUrls", new java.util.ArrayList<String>());
        IllegalArgumentException ex2 = assertThrows(
                IllegalArgumentException.class,
                () -> episodeController.submitFusionPage(EPISODE_ID, emptyBody)
        );
        assertEquals("panelFusedUrls 不能为空", ex2.getMessage());

        verify(productionService, never()).submitFusionPage(eq(EPISODE_ID), anyInt(), anyList());
    }
}
