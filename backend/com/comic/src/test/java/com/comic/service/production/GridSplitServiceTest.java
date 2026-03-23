package com.comic.service.production;

import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GridSplitServiceTest {

    @Mock
    private OssService ossService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GridSplitService gridSplitService;

    @BeforeEach
    void setUp() {
        gridSplitService = new GridSplitService(new OkHttpClient(), ossService, objectMapper);
    }

    @Test
    @DisplayName("splitAndUploadPage should split by row-major order and bind panel metadata for 3x3")
    void splitAndUploadPage_threeByThree_rowMajorBinding() throws Exception {
        int rows = 3;
        int cols = 3;
        int panelWidth = 12;
        int panelHeight = 8;
        int separator = 2;
        int startPanelIndex = 100;

        BufferedImage gridImage = createGridImage(rows, cols, panelWidth, panelHeight, separator);
        List<JsonNode> panels = createPanels(9);
        GridSplitService.PageSplitTask task = createTask(
                0, rows, cols, panelWidth, panelHeight, separator, startPanelIndex, panels
        );

        List<byte[]> uploadedBytes = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger(0);
        when(ossService.uploadFromInputStream(any(InputStream.class), anyString(), eq("image/png"), anyLong()))
                .thenAnswer(invocation -> {
                    InputStream in = invocation.getArgument(0);
                    uploadedBytes.add(readAllBytes(in));
                    return "https://oss.test/cell-" + counter.getAndIncrement() + ".png";
                });

        GridSplitService.SplitPageResult result = gridSplitService.splitAndUploadPage(gridImage, task);

        assertFalse(result.isSkipped());
        assertEquals(9, result.getCells().size());
        assertEquals(9, uploadedBytes.size());

        for (int i = 0; i < 9; i++) {
            GridSplitService.SplitCellResult cell = result.getCells().get(i);
            assertEquals(i, cell.getCellIndex());
            assertEquals(startPanelIndex + i, cell.getPanelIndex());
            assertFalse(cell.isPlaceholder());
            assertNotNull(cell.getPanelData());
            assertEquals("p" + i, cell.getPanelData().path("panel_id").asText());
            assertEquals("https://oss.test/cell-" + i + ".png", cell.getImageUrl());

            BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploadedBytes.get(i)));
            assertNotNull(uploaded);
            assertEquals(panelWidth, uploaded.getWidth());
            assertEquals(panelHeight, uploaded.getHeight());
            int centerRgb = uploaded.getRGB(panelWidth / 2, panelHeight / 2);
            assertEquals(expectedCellColor(i).getRGB(), centerRgb);
        }
    }

    @Test
    @DisplayName("splitAndUploadPage should support 2x3 and mark missing panel metadata as placeholders")
    void splitAndUploadPage_twoByThree_placeholderBinding() throws Exception {
        int rows = 2;
        int cols = 3;
        int panelWidth = 10;
        int panelHeight = 6;
        int separator = 1;
        int startPanelIndex = 10;

        BufferedImage gridImage = createGridImage(rows, cols, panelWidth, panelHeight, separator);
        List<JsonNode> panels = createPanels(4);
        GridSplitService.PageSplitTask task = createTask(
                1, rows, cols, panelWidth, panelHeight, separator, startPanelIndex, panels
        );

        AtomicInteger counter = new AtomicInteger(0);
        when(ossService.uploadFromInputStream(any(InputStream.class), anyString(), eq("image/png"), anyLong()))
                .thenAnswer(invocation -> "https://oss.test/two-by-three-" + counter.getAndIncrement() + ".png");

        GridSplitService.SplitPageResult result = gridSplitService.splitAndUploadPage(gridImage, task);

        assertFalse(result.isSkipped());
        assertEquals(6, result.getCells().size());

        for (int i = 0; i < 6; i++) {
            GridSplitService.SplitCellResult cell = result.getCells().get(i);
            assertEquals(i, cell.getCellIndex());
            assertEquals(startPanelIndex + i, cell.getPanelIndex());
            if (i < 4) {
                assertFalse(cell.isPlaceholder());
                assertNotNull(cell.getPanelData());
                assertEquals("p" + i, cell.getPanelData().path("panel_id").asText());
            } else {
                assertTrue(cell.isPlaceholder());
                assertNull(cell.getPanelData());
            }
        }
    }

    @Test
    @DisplayName("splitAndUploadPages should retry once then skip failed page and continue next page")
    void splitAndUploadPages_retryOnce_thenSkipAndContinue() {
        GridSplitService spyService = spy(gridSplitService);

        GridSplitService.PageSplitTask failPage = createTask(0, 3, 3, 12, 8, 2, 0, createPanels(9));
        GridSplitService.PageSplitTask successPage = createTask(1, 2, 3, 10, 6, 1, 9, createPanels(6));

        doThrow(new RuntimeException("first failure"))
                .doThrow(new RuntimeException("second failure"))
                .when(spyService).splitAndUploadSinglePage(failPage);

        GridSplitService.SplitPageResult successResult = new GridSplitService.SplitPageResult();
        successResult.setPageIndex(1);
        successResult.setRows(2);
        successResult.setCols(3);
        successResult.setSkipped(false);
        successResult.setCells(new ArrayList<>());
        doReturn(successResult).when(spyService).splitAndUploadSinglePage(successPage);

        GridSplitService.SplitBatchResult batchResult = spyService.splitAndUploadPages(
                Arrays.asList(failPage, successPage)
        );

        assertEquals(2, batchResult.getPages().size());
        assertEquals(1, batchResult.getSuccessPages());
        assertEquals(1, batchResult.getSkippedPages());

        GridSplitService.SplitPageResult first = batchResult.getPages().get(0);
        assertTrue(first.isSkipped());
        assertEquals(0, first.getPageIndex());
        assertNotNull(first.getErrorMessage());

        GridSplitService.SplitPageResult second = batchResult.getPages().get(1);
        assertFalse(second.isSkipped());
        assertEquals(1, second.getPageIndex());

        verify(spyService, times(2)).splitAndUploadSinglePage(failPage);
        verify(spyService, times(1)).splitAndUploadSinglePage(successPage);
    }

    private GridSplitService.PageSplitTask createTask(
            int pageIndex,
            int rows,
            int cols,
            int panelWidth,
            int panelHeight,
            int separatorPixels,
            int startPanelIndex,
            List<JsonNode> panels
    ) {
        GridSplitService.PageSplitTask task = new GridSplitService.PageSplitTask();
        task.setPageIndex(pageIndex);
        task.setRows(rows);
        task.setCols(cols);
        task.setPanelWidth(panelWidth);
        task.setPanelHeight(panelHeight);
        task.setSeparatorPixels(separatorPixels);
        task.setStartPanelIndex(startPanelIndex);
        task.setPanels(panels);
        task.setGridImageUrl("https://unused.test/grid.png");
        task.setObjectKeyPrefix("grid-split/test");
        return task;
    }

    private List<JsonNode> createPanels(int count) {
        List<JsonNode> panels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode panel = objectMapper.createObjectNode();
            panel.put("panel_id", "p" + i);
            panel.put("composition", "composition-" + i);
            panels.add(panel);
        }
        return panels;
    }

    private BufferedImage createGridImage(int rows, int cols, int panelWidth, int panelHeight, int separatorPixels) {
        int width = cols * panelWidth + (cols - 1) * separatorPixels;
        int height = rows * panelHeight + (rows - 1) * separatorPixels;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = row * cols + col;
                int x = col * (panelWidth + separatorPixels);
                int y = row * (panelHeight + separatorPixels);
                g.setColor(expectedCellColor(index));
                g.fillRect(x, y, panelWidth, panelHeight);
            }
        }
        g.dispose();
        return image;
    }

    private Color expectedCellColor(int index) {
        return new Color(
                (index * 40) % 256,
                (index * 70) % 256,
                (index * 110) % 256
        );
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
