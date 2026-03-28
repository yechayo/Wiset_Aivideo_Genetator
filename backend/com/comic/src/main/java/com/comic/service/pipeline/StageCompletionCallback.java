package com.comic.service.pipeline;

/**
 * 各业务 Service 完成阶段任务后，通过此接口通知 PipelineService 推进状态。
 * 所有状态变更必须经过此回调，禁止直接 project.setStatus()。
 */
public interface StageCompletionCallback {

    /**
     * 通知阶段完成，触发状态推进。
     *
     * @param projectId 项目 ID
     * @param event     事件名（对应 ProjectStatus.ALLOWED_TRANSITIONS 中的 key）
     */
    void onStageComplete(String projectId, String event);

    /**
     * 通知阶段失败。
     *
     * @param projectId 项目 ID
     * @param event     失败事件名（如 "script_failed"）
     */
    void onStageFailed(String projectId, String event);
}