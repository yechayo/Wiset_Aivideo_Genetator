package com.comic.ai.video;

import java.util.Map;

/**
 * 视频生成服务接口
 * 用于分镜视频、Sora 视频生成等任务
 */
public interface VideoGenerationService {

    /**
     * 生成视频（异步任务）
     *
     * @param prompt         视频描述提示词
     * @param duration       视频时长（秒）
     * @param aspectRatio    宽高比（16:9, 9:16, 1:1）
     * @param referenceImage 参考图（可选）
     * @return 任务ID，用于查询进度
     */
    String generateAsync(String prompt, int duration, String aspectRatio, String referenceImage);

    /**
     * 查询视频生成任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态（pending/processing/completed/failed）
     */
    TaskStatus getTaskStatus(String taskId);

    /**
     * 下载生成的视频
     *
     * @param taskId 任务ID
     * @return 视频URL
     */
    String downloadVideo(String taskId);

    /**
     * 获取服务名称
     */
    String getServiceName();

    /**
     * 任务状态
     */
    class TaskStatus {
        private String taskId;
        private String status;      // pending, processing, completed, failed, cancelled
        private int progress;       // 0-100
        private String videoUrl;
        private String errorMessage;
        private String lastFrameUrl;     // 尾帧图片 URL
        private String metadata;         // 元数据（resolution, duration 等）

        public TaskStatus(String taskId, String status, int progress, String videoUrl, String errorMessage) {
            this(taskId, status, progress, videoUrl, errorMessage, null, null);
        }

        public TaskStatus(String taskId, String status, int progress, String videoUrl, String errorMessage,
                         String lastFrameUrl, String metadata) {
            this.taskId = taskId;
            this.status = status;
            this.progress = progress;
            this.videoUrl = videoUrl;
            this.errorMessage = errorMessage;
            this.lastFrameUrl = lastFrameUrl;
            this.metadata = metadata;
        }

        // Getters
        public String getTaskId() { return taskId; }
        public String getStatus() { return status; }
        public int getProgress() { return progress; }
        public String getVideoUrl() { return videoUrl; }
        public String getErrorMessage() { return errorMessage; }
        public String getLastFrameUrl() { return lastFrameUrl; }
        public String getMetadata() { return metadata; }

        public boolean isCompleted() { return "completed".equals(status); }
        public boolean isFailed() { return "failed".equals(status); }
        public boolean isPending() { return "pending".equals(status); }
        public boolean isProcessing() { return "processing".equals(status); }
        public boolean isCancelled() { return "cancelled".equals(status); }
    }
}
