package com.comic.service.production;

import com.comic.service.oss.OssService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Grid split service.
 * Splits generated grid images into cell images in row-major order
 * and binds each cell to panel metadata by the same index.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GridSplitService {

    private static final String PNG_CONTENT_TYPE = "image/png";
    private static final String DEFAULT_OBJECT_KEY_PREFIX = "grid-split";
    private static final int DEFAULT_PANEL_WIDTH = 1024;
    private static final int DEFAULT_PANEL_HEIGHT = 576;
    private static final int DEFAULT_SEPARATOR_PIXELS = 4;

    private final OkHttpClient httpClient;
    private final OssService ossService;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    /**
     * Batch split with page-level fault tolerance.
     * For each page: retry once; if still failing, mark this page as skipped and continue.
     */
    public SplitBatchResult splitAndUploadPages(List<PageSplitTask> tasks) {
        SplitBatchResult batchResult = new SplitBatchResult();
        List<SplitPageResult> pageResults = new ArrayList<>();
        batchResult.setPages(pageResults);

        if (tasks == null || tasks.isEmpty()) {
            batchResult.setSuccessPages(0);
            batchResult.setSkippedPages(0);
            return batchResult;
        }

        int successPages = 0;
        int skippedPages = 0;
        for (PageSplitTask task : tasks) {
            try {
                pageResults.add(splitAndUploadSinglePage(task));
                successPages++;
            } catch (Exception firstError) {
                int pageIndex = task != null ? task.getPageIndex() : -1;
                log.warn("Grid split failed on first attempt, retrying once. pageIndex={}", pageIndex, firstError);
                try {
                    pageResults.add(splitAndUploadSinglePage(task));
                    successPages++;
                } catch (Exception secondError) {
                    log.error("Grid split failed after retry, skipping page. pageIndex={}", pageIndex, secondError);
                    pageResults.add(buildSkippedPageResult(task, secondError));
                    skippedPages++;
                }
            }
        }

        batchResult.setSuccessPages(successPages);
        batchResult.setSkippedPages(skippedPages);
        return batchResult;
    }

    /**
     * Split one page by downloading grid image from URL first.
     */
    SplitPageResult splitAndUploadSinglePage(PageSplitTask task) {
        validateTask(task);
        BufferedImage gridImage = downloadGridImage(task.getGridImageUrl());
        return splitAndUploadPage(gridImage, task);
    }

    /**
     * Split one page from already-loaded image.
     */
    public SplitPageResult splitAndUploadPage(BufferedImage gridImage, PageSplitTask task) {
        validateTask(task);
        if (gridImage == null) {
            throw new IllegalArgumentException("gridImage must not be null");
        }

        int rows = task.getRows();
        int cols = task.getCols();
        int panelWidth = resolvePanelWidth(task);
        int panelHeight = resolvePanelHeight(task);
        int separatorPixels = resolveSeparatorPixels(task);
        int pageIndex = task.getPageIndex();
        int startPanelIndex = task.getStartPanelIndex() != null ? task.getStartPanelIndex() : 0;
        String objectKeyPrefix = normalizeObjectKeyPrefix(task.getObjectKeyPrefix());
        List<JsonNode> panels = task.getPanels() != null ? task.getPanels() : Collections.emptyList();

        int totalCells = rows * cols;
        List<SplitCellResult> cells = new ArrayList<>(totalCells);

        for (int cellIndex = 0; cellIndex < totalCells; cellIndex++) {
            int row = cellIndex / cols;
            int col = cellIndex % cols;
            int sourceX = col * (panelWidth + separatorPixels);
            int sourceY = row * (panelHeight + separatorPixels);

            ensureCropBounds(gridImage, sourceX, sourceY, panelWidth, panelHeight, pageIndex, cellIndex);
            BufferedImage panelImage = gridImage.getSubimage(sourceX, sourceY, panelWidth, panelHeight);
            byte[] panelImageBytes = toPngBytes(panelImage);

            String objectKey = buildObjectKey(objectKeyPrefix, pageIndex, cellIndex);
            String uploadedUrl = ossService.uploadFromInputStream(
                    new ByteArrayInputStream(panelImageBytes),
                    objectKey,
                    PNG_CONTENT_TYPE,
                    panelImageBytes.length
            );

            JsonNode panelData = cellIndex < panels.size() ? panels.get(cellIndex) : null;

            SplitCellResult cellResult = new SplitCellResult();
            cellResult.setPageIndex(pageIndex);
            cellResult.setCellIndex(cellIndex);
            cellResult.setPanelIndex(startPanelIndex + cellIndex);
            cellResult.setPlaceholder(panelData == null || panelData.isNull());
            cellResult.setPanelData(panelData);
            cellResult.setImageUrl(uploadedUrl);
            if (panelData != null && panelData.isObject()) {
                cellResult.setPanelId(panelData.path("panel_id").asText(null));
            }
            cells.add(cellResult);
        }

        SplitPageResult pageResult = new SplitPageResult();
        pageResult.setPageIndex(pageIndex);
        pageResult.setRows(rows);
        pageResult.setCols(cols);
        pageResult.setSkipped(false);
        pageResult.setCells(cells);
        return pageResult;
    }

    private SplitPageResult buildSkippedPageResult(PageSplitTask task, Exception error) {
        SplitPageResult result = new SplitPageResult();
        result.setPageIndex(task != null ? task.getPageIndex() : -1);
        result.setRows(task != null ? task.getRows() : 0);
        result.setCols(task != null ? task.getCols() : 0);
        result.setSkipped(true);
        result.setErrorMessage(error != null ? error.getMessage() : "Unknown split error");
        result.setCells(new ArrayList<>());
        return result;
    }

    private void validateTask(PageSplitTask task) {
        if (task == null) {
            throw new IllegalArgumentException("PageSplitTask must not be null");
        }
        if (task.getRows() <= 0 || task.getCols() <= 0) {
            throw new IllegalArgumentException("rows/cols must be greater than 0");
        }
        if (task.getGridImageUrl() == null || task.getGridImageUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("gridImageUrl must not be empty");
        }
    }

    private int resolvePanelWidth(PageSplitTask task) {
        if (task.getPanelWidth() != null && task.getPanelWidth() > 0) {
            return task.getPanelWidth();
        }
        return DEFAULT_PANEL_WIDTH;
    }

    private int resolvePanelHeight(PageSplitTask task) {
        if (task.getPanelHeight() != null && task.getPanelHeight() > 0) {
            return task.getPanelHeight();
        }
        return DEFAULT_PANEL_HEIGHT;
    }

    private int resolveSeparatorPixels(PageSplitTask task) {
        if (task.getSeparatorPixels() != null && task.getSeparatorPixels() >= 0) {
            return task.getSeparatorPixels();
        }
        return DEFAULT_SEPARATOR_PIXELS;
    }

    private String normalizeObjectKeyPrefix(String prefix) {
        String raw = (prefix == null || prefix.trim().isEmpty()) ? DEFAULT_OBJECT_KEY_PREFIX : prefix.trim();
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private String buildObjectKey(String objectKeyPrefix, int pageIndex, int cellIndex) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return objectKeyPrefix + "/page-" + pageIndex + "/cell-" + cellIndex + "-" + suffix + ".png";
    }

    private BufferedImage downloadGridImage(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalArgumentException("Failed to download grid image: " + imageUrl);
            }
            BufferedImage image = ImageIO.read(response.body().byteStream());
            if (image == null) {
                throw new IllegalArgumentException("Downloaded content is not a valid image: " + imageUrl);
            }
            return image;
        } catch (IOException e) {
            throw new RuntimeException("Failed to download grid image: " + imageUrl, e);
        }
    }

    private void ensureCropBounds(
            BufferedImage image,
            int x,
            int y,
            int width,
            int height,
            int pageIndex,
            int cellIndex
    ) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > image.getWidth()
                || y + height > image.getHeight()) {
            throw new IllegalArgumentException(
                    "Grid crop out of bounds. pageIndex=" + pageIndex + ", cellIndex=" + cellIndex
            );
        }
    }

    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("Failed to encode panel image as PNG");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode panel image", e);
        }
    }

    @Data
    public static class PageSplitTask {
        private Integer pageIndex = 0;
        private String gridImageUrl;
        private Integer rows = 3;
        private Integer cols = 3;
        private Integer panelWidth = DEFAULT_PANEL_WIDTH;
        private Integer panelHeight = DEFAULT_PANEL_HEIGHT;
        private Integer separatorPixels = DEFAULT_SEPARATOR_PIXELS;
        private Integer startPanelIndex = 0;
        private List<JsonNode> panels = new ArrayList<>();
        private String objectKeyPrefix = DEFAULT_OBJECT_KEY_PREFIX;
    }

    @Data
    public static class SplitCellResult {
        private Integer pageIndex;
        private Integer cellIndex;
        private Integer panelIndex;
        private String panelId;
        private String imageUrl;
        private boolean placeholder;
        private JsonNode panelData;
    }

    @Data
    public static class SplitPageResult {
        private Integer pageIndex;
        private Integer rows;
        private Integer cols;
        private boolean skipped;
        private String errorMessage;
        private List<SplitCellResult> cells = new ArrayList<>();
    }

    @Data
    public static class SplitBatchResult {
        private Integer successPages = 0;
        private Integer skippedPages = 0;
        private List<SplitPageResult> pages = new ArrayList<>();
    }
}
