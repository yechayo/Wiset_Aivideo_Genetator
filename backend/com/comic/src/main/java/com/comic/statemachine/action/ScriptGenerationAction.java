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
        // 持久化状态到数据库
        stateMachineService.persistState(projectId, ProjectState.OUTLINE_GENERATING);
        eventPublisher.publishTaskStart(projectId, "outline_generation");

        CompletableFuture.runAsync(() -> {
            try {
                log.info("Async task: Calling scriptService.generateScriptOutline for project={}", projectId);
                // 直接调用现有 Service
                scriptService.generateScriptOutline(projectId);
                // 成功后发送内部事件
                log.info("Async task: Script generation complete, sending _SCRIPT_OUTLINE_DONE event for project={}", projectId);
                stateMachineService.sendEvent(projectId, ProjectEventType._SCRIPT_OUTLINE_DONE);
                eventPublisher.publishTaskComplete(projectId, "outline_generation", null);
            } catch (Exception e) {
                log.error("Outline generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "大纲生成失败: " + e.getMessage());
                // 持久化失败状态到数据库
                stateMachineService.persistState(projectId, ProjectState.OUTLINE_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.OUTLINE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 大纲生成完成（状态已转换）
     */
    public void onOutlineGenerated(String projectId) {
        log.info("Action: Outline generated for project={}", projectId);
        // 持久化状态到数据库
        stateMachineService.persistState(projectId, ProjectState.OUTLINE_REVIEW);
    }

    /**
     * 修改大纲
     */
    public void reviseOutline(String projectId, String revisionNote, String currentOutline) {
        log.info("Action: Revise outline for project={}", projectId);
        // 持久化状态到数据库
        stateMachineService.persistState(projectId, ProjectState.OUTLINE_GENERATING);
        eventPublisher.publishTaskStart(projectId, "outline_revision");

        CompletableFuture.runAsync(() -> {
            try {
                scriptService.reviseOutline(projectId, revisionNote, currentOutline);
                stateMachineService.sendEvent(projectId, ProjectEventType._SCRIPT_OUTLINE_DONE);
                eventPublisher.publishTaskComplete(projectId, "outline_revision", null);
            } catch (Exception e) {
                log.error("Outline revision failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "大纲修改失败: " + e.getMessage());
                // 持久化失败状态到数据库
                stateMachineService.persistState(projectId, ProjectState.OUTLINE_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.OUTLINE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 开始生成剧集（异步）
     */
    public void startEpisodeGeneration(String projectId, String chapter, Integer episodeCount, String modificationSuggestion) {
        log.info("Action: Start episode generation for project={}, chapter={}", projectId, chapter);
        // 持久化状态到数据库
        stateMachineService.persistState(projectId, ProjectState.EPISODE_GENERATING);
        eventPublisher.publishTaskStart(projectId, "episodes_generation");

        CompletableFuture.runAsync(() -> {
            try {
                // 根据参数选择生成方式
                if (chapter != null && !chapter.isEmpty()) {
                    // 生成指定章节的剧集
                    scriptService.generateScriptEpisodes(projectId, chapter,
                            episodeCount != null ? episodeCount : 1, modificationSuggestion);
                } else {
                    // 生成所有剧集
                    scriptService.generateAllEpisodes(projectId);
                }
                stateMachineService.sendEvent(projectId, ProjectEventType._EPISODES_DONE);
                eventPublisher.publishTaskComplete(projectId, "episodes_generation", null);
            } catch (Exception e) {
                log.error("Episode generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "剧集生成失败: " + e.getMessage());
                // 持久化失败状态到数据库
                stateMachineService.persistState(projectId, ProjectState.EPISODE_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.EPISODE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 剧集生成完成
     */
    public void onEpisodesGenerated(String projectId) {
        log.info("Action: Episodes generated for project={}", projectId);
        // 持久化状态到数据库
        stateMachineService.persistState(projectId, ProjectState.SCRIPT_REVIEW);
    }

    /**
     * 确认剧本
     */
    public void confirmScript(String projectId) {
        log.info("Action: Confirm script for project={}", projectId);
        try {
            scriptService.confirmScript(projectId);
            // 持久化状态到数据库
            stateMachineService.persistState(projectId, ProjectState.SCRIPT_CONFIRMED);

            // 自动触发角色提取
            log.info("Auto-triggering character extraction for project={}", projectId);
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100); // 短暂延迟确保状态转换完成
                    stateMachineService.sendEvent(projectId, ProjectEventType.EXTRACT_CHARACTERS);
                } catch (Exception e) {
                    log.error("Failed to auto-trigger character extraction: projectId={}", projectId, e);
                }
            });
        } catch (Exception e) {
            log.error("Script confirmation failed: projectId={}", projectId, e);
            eventPublisher.publishFailure(projectId, "剧本确认失败: " + e.getMessage());
        }
    }
}
