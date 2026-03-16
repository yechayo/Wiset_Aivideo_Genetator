package com.comic.ai.image;

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
     * 获取当前可用并发槽位
     */
    int getAvailableConcurrentSlots();
}
