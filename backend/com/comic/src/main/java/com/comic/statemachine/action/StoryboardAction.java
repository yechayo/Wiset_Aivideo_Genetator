package com.comic.statemachine.action;

import com.comic.service.story.StoryboardService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 分镜相关的 Action
 * 直接调用 StoryboardService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryboardAction {

    private final StoryboardService storyboardService;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    /**
     * 开始生成分镜（异步）
     */
    public void startGeneration(String projectId) {
        log.info("Action: Start storyboard generation for project={}", projectId);
        eventPublisher.publishTaskStart(projectId, "storyboard_generation");

        CompletableFuture.runAsync(() -> {
            try {
                // 直接调用现有 Service（内部会处理所有剧集）
                storyboardService.startStoryboardGeneration(projectId);
                // 完成后发送事件
                stateMachineService.sendEvent(projectId, ProjectEventType._STORYBOARD_DONE);
                eventPublisher.publishTaskComplete(projectId, "storyboard_generation", null);
            } catch (Exception e) {
                log.error("Storyboard generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "分镜生成失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
            }
        });
    }

    /**
     * 分镜生成完成
     */
    public void onGenerationComplete(String projectId) {
        log.info("Action: Storyboard generation completed for project={}", projectId);
    }

    /**
     * 修改分镜
     */
    public void revise(String projectId, Long episodeId, String feedback) {
        log.info("Action: Revise storyboard for project={}, episodeId={}", projectId, episodeId);
        eventPublisher.publishTaskStart(projectId, "storyboard_revision");

        CompletableFuture.runAsync(() -> {
            try {
                storyboardService.reviseEpisodeStoryboard(episodeId, feedback);
                stateMachineService.sendEvent(projectId, ProjectEventType._STORYBOARD_DONE);
                eventPublisher.publishTaskComplete(projectId, "storyboard_revision", null);
            } catch (Exception e) {
                log.error("Storyboard revision failed: projectId={}, episodeId={}", projectId, episodeId, e);
                eventPublisher.publishFailure(projectId, "分镜修改失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
            }
        });
    }

    /**
     * 确认分镜
     */
    public void confirmStoryboard(String projectId, Long episodeId) {
        log.info("Action: Confirm storyboard for project={}, episodeId={}", projectId, episodeId);
        try {
            storyboardService.confirmEpisodeStoryboard(episodeId);
            eventPublisher.publishTaskComplete(projectId, "storyboard_confirmation", null);
        } catch (Exception e) {
            log.error("Storyboard confirmation failed: projectId={}, episodeId={}", projectId, episodeId, e);
            eventPublisher.publishFailure(projectId, "分镜确认失败: " + e.getMessage());
        }
    }

    /**
     * 重试失败的分镜
     */
    public void retry(String projectId, Long episodeId) {
        log.info("Action: Retry storyboard for project={}, episodeId={}", projectId, episodeId);
        eventPublisher.publishTaskStart(projectId, "storyboard_retry");

        CompletableFuture.runAsync(() -> {
            try {
                storyboardService.retryFailedStoryboard(episodeId);
                stateMachineService.sendEvent(projectId, ProjectEventType._STORYBOARD_DONE);
                eventPublisher.publishTaskComplete(projectId, "storyboard_retry", null);
            } catch (Exception e) {
                log.error("Storyboard retry failed: projectId={}, episodeId={}", projectId, episodeId, e);
                eventPublisher.publishFailure(projectId, "分镜重试失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
            }
        });
    }
}
