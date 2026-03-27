package com.comic.statemachine.action;

import com.comic.service.script.ScriptService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 剧本生成相关的 Action
 * 直接调用 ScriptService，处理异步结果
 */
@Slf4j
@Component
public class ScriptGenerationAction {

    private final ScriptService scriptService;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    public ScriptGenerationAction(
            @Lazy ScriptService scriptService,
            ProjectStateMachineService stateMachineService,
            StateChangeEventPublisher eventPublisher) {
        this.scriptService = scriptService;
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 开始生成大纲（异步）
     */
    public void startOutlineGeneration(String projectId) {
        log.info("Action: Start outline generation for project={}", projectId);
        eventPublisher.publishTaskStart(projectId, "outline_generation");

        CompletableFuture.runAsync(() -> {
            try {
                // 直接调用现有 Service
                scriptService.generateScriptOutline(projectId);
                // 成功后发送内部事件
                stateMachineService.sendEvent(projectId, ProjectEventType._SCRIPT_OUTLINE_DONE);
                eventPublisher.publishTaskComplete(projectId, "outline_generation", null);
            } catch (Exception e) {
                log.error("Outline generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "大纲生成失败: " + e.getMessage());
                // 转换到失败状态
                stateMachineService.resetStateMachine(projectId, ProjectState.OUTLINE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 大纲生成完成（状态已转换）
     */
    public void onOutlineGenerated(String projectId) {
        log.info("Action: Outline generated for project={}", projectId);
        // 状态已由状态机转换，这里可以做额外处理
    }

    /**
     * 修改大纲
     */
    public void reviseOutline(String projectId, String revisionNote, String currentOutline) {
        log.info("Action: Revise outline for project={}", projectId);
        eventPublisher.publishTaskStart(projectId, "outline_revision");

        CompletableFuture.runAsync(() -> {
            try {
                scriptService.reviseOutline(projectId, revisionNote, currentOutline);
                stateMachineService.sendEvent(projectId, ProjectEventType._SCRIPT_OUTLINE_DONE);
                eventPublisher.publishTaskComplete(projectId, "outline_revision", null);
            } catch (Exception e) {
                log.error("Outline revision failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "大纲修改失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.OUTLINE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 开始生成剧集
     */
    public void startEpisodeGeneration(String projectId) {
        log.info("Action: Start episode generation for project={}", projectId);
        eventPublisher.publishTaskStart(projectId, "episodes_generation");

        CompletableFuture.runAsync(() -> {
            try {
                // 生成所有剧集
                scriptService.generateAllEpisodes(projectId);
                stateMachineService.sendEvent(projectId, ProjectEventType._EPISODES_DONE);
                eventPublisher.publishTaskComplete(projectId, "episodes_generation", null);
            } catch (Exception e) {
                log.error("Episode generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "剧集生成失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.EPISODE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 剧集生成完成
     */
    public void onEpisodesGenerated(String projectId) {
        log.info("Action: Episodes generated for project={}", projectId);
    }

    /**
     * 确认剧本
     */
    public void confirmScript(String projectId) {
        log.info("Action: Confirm script for project={}", projectId);
        try {
            scriptService.confirmScript(projectId);
            // confirmScript 内部会调用 PipelineService.advancePipeline
            // 这里不需要额外处理
        } catch (Exception e) {
            log.error("Script confirmation failed: projectId={}", projectId, e);
            eventPublisher.publishFailure(projectId, "剧本确认失败: " + e.getMessage());
        }
    }
}
