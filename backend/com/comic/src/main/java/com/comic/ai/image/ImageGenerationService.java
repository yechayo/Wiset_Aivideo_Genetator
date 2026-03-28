package com.comic.ai.image;

import java.util.List;

/**
 * 图片生成服务接口
 * 用于角色立绘、分镜图、背景图等图片生成任务
 */
public interface ImageGenerationService {

    /**
     * 生成图片
     *
     * @param prompt     图片描述提示词
     * @param width      图片宽度
     * @param height     图片高度
     * @param style      图片风格（anime、realistic、comic 等）
     * @return 图片URL或Base64
     */
    String generate(String prompt, int width, int height, String style);

    /**
     * 生成图片（使用参考图）
     *
     * @param prompt           图片描述
     * @param referenceImage   参考图URL或Base64
     * @param width            宽度
     * @param height           高度
     * @return 生成的图片URL或Base64
     */
    String generateWithReference(String prompt, String referenceImage, int width, int height);

    /**
     * 获取服务名称
     */
    String getServiceName();

    /**
     * 多参考图生成（Seedream 多图输入）
     * prompt 中用"图1"、"图2"...指代参考图列表中的图片
     *
     * @param prompt          提示词（包含"图1"/"图2"等引用）
     * @param referenceImages 参考图 URL 列表（按顺序对应图1、图2...）
     * @param width           宽度
     * @param height          高度
     * @return 生成的图片 URL
     */
    String generateWithMultipleReferences(String prompt, List<String> referenceImages, int width, int height);

    /**
     * 获取当前可用并发槽位
     */
    int getAvailableConcurrentSlots();
}
