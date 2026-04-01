package com.comic.statemachine.action;

import com.comic.service.storyboard.StoryboardService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 分镜相关的 Action
 * 处理：分镜生成、分镜修订、分镜确认
 */
@Slf4j
@Component
public class StoryboardAction {

    private final StoryboardService storyboardService;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    public StoryboardAction(
            @Lazy StoryboardService storyboardService,
            ProjectStateMachineService stateMachineService,
            StateChangeEventPublisher eventPublisher) {
        this.storyboardService = storyboardService;
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 开始生成分镜（异步）
     */
    public void startStoryboard(String projectId) {
        log.info("Action: Start storyboard generation for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.STORYBOARD_GENERATING);
        eventPublisher.publishTaskStart(projectId, "storyboard_generation");

        CompletableFuture.runAsync(() -> {
            try {
                // 完整的分集剧本+分镜生成流程
                // generateEpisodeScriptAndStoryboard 会推进状态到 STORYBOARD_REVIEW
                storyboardService.generateEpisodeScriptAndStoryboard(projectId);
                stateMachineService.sendEvent(projectId, ProjectEventType._STORYBOARD_DONE);
                eventPublisher.publishTaskComplete(projectId, "storyboard_generation", null);
            } catch (Exception e) {
                log.error("Storyboard generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "分镜生成失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
            }
        });
    }

    /**
     * 分镜生成完成
     */
    public void onStoryboardGenerated(String projectId) {
        log.info("Action: Storyboard generated for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.STORYBOARD_REVIEW);
    }

    /**
     * 修改并重新生成分镜（异步）
     */
    public void reviseStoryboard(String projectId, String feedback) {
        log.info("Action: Revise storyboard for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.STORYBOARD_GENERATING);
        eventPublisher.publishTaskStart(projectId, "storyboard_revision");

        CompletableFuture.runAsync(() -> {
            try {
                // 重新生成整个分镜流程
                storyboardService.generateEpisodeScriptAndStoryboard(projectId);
                stateMachineService.sendEvent(projectId, ProjectEventType._STORYBOARD_DONE);
                eventPublisher.publishTaskComplete(projectId, "storyboard_revision", null);
            } catch (Exception e) {
                log.error("Storyboard revision failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "分镜修改失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.STORYBOARD_GENERATING_FAILED);
            }
        });
    }

    /**
     * 确认分镜，进入视频拼接阶段
     */
    public void confirmStoryboard(String projectId) {
        log.info("Action: Confirm storyboard for project={}", projectId);
        try {
            stateMachineService.persistState(projectId, ProjectState.MERGING);
            eventPublisher.publishTaskComplete(projectId, "storyboard_confirmation", null);
        } catch (Exception e) {
            log.error("Storyboard confirmation failed: projectId={}", projectId, e);
            eventPublisher.publishFailure(projectId, "分镜确认失败: " + e.getMessage());
        }
    }
}
