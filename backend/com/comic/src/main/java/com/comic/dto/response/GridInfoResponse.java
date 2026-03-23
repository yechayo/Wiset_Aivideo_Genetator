package com.comic.dto.response;

import lombok.Data;

import java.util.List;

/**
 * Response DTO for grid split/fusion UI.
 */
@Data
public class GridInfoResponse {

    /**
     * First grid page URL for backward compatibility.
     */
    private String sceneGridUrl;

    /**
     * Character reference images.
     */
    private List<CharacterReferenceInfo> characterReferences;

    /**
     * Multi-page grid information.
     */
    private List<GridPageInfo> gridPages;

    /**
     * Total grid pages.
     */
    private Integer totalPages = 0;

    /**
     * Global/default grid columns.
     */
    private Integer gridColumns = 3;

    /**
     * Global/default grid rows.
     */
    private Integer gridRows = 3;

    /**
     * Single cell width.
     */
    private Integer panelWidth = 1024;

    /**
     * Single cell height.
     */
    private Integer panelHeight = 576;

    /**
     * Separator line width in pixels.
     */
    private Integer separatorPixels = 4;

    @Data
    public static class CharacterReferenceInfo {
        private String characterName;
        private String threeViewGridUrl;
        private String expressionGridUrl;
    }

    @Data
    public static class GridPageInfo {
        /**
         * Grid page URL.
         */
        private String sceneGridUrl;

        /**
         * Current page columns.
         */
        private Integer gridColumns = 3;

        /**
         * Current page rows.
         */
        private Integer gridRows = 3;

        /**
         * Scene-group index that this page belongs to.
         */
        private Integer sceneGroupIndex;

        /**
         * Scene location.
         */
        private String location;

        /**
         * Related characters.
         */
        private List<String> characters;

        /**
         * Whether this page is fused.
         */
        private boolean fused;

        /**
         * Fused panel URLs in row-major order.
         */
        private List<String> fusedPanels;
    }
}
