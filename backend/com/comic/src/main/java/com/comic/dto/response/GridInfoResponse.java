package com.comic.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 前端拆分/融合所需信息响应DTO
 */
@Data
public class GridInfoResponse {

    /**
     * 九宫格URL（第一张，兼容旧字段）
     */
    private String sceneGridUrl;

    /**
     * 角色参考图列表
     */
    private List<CharacterReferenceInfo> characterReferences;

    /**
     * 多页网格信息列表
     */
    private List<GridPageInfo> gridPages;

    /**
     * 总页数
     */
    private Integer totalPages = 0;

    /**
     * 网格列数
     */
    private Integer gridColumns = 3;

    /**
     * 网格行数
     */
    private Integer gridRows = 3;

    /**
     * 单格宽度
     */
    private Integer panelWidth = 1024;

    /**
     * 单格高度
     */
    private Integer panelHeight = 576;

    /**
     * 分隔线宽度
     */
    private Integer separatorPixels = 4;

    @Data
    public static class CharacterReferenceInfo {
        private String characterName;
        private String threeViewGridUrl;
        private String expressionGridUrl;
        private String standardImageUrl;
    }

    /**
     * 单页网格信息
     */
    @Data
    public static class GridPageInfo {
        /**
         * 网格图URL
         */
        private String sceneGridUrl;

        /**
         * 场景组索引
         */
        private Integer sceneGroupIndex;

        /**
         * 场景位置描述
         */
        private String location;

        /**
         * 出场角色列表
         */
        private List<String> characters;

        /**
         * 是否已融合提交
         */
        private boolean fused;
    }
}
