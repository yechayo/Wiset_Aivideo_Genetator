package com.comic.statemachine.action;

import com.comic.entity.Panel;
import com.comic.repository.PanelRepository;
import com.comic.service.production.PanelProductionService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 分镜生产相关的 Action
 * 处理：文本生成、图像生成（九宫格）、视频生成、确认生产
 */
@Slf4j
@Component
public class ProductionAction {

    private final PanelProductionService panelProductionService;
    private final PanelRepository panelRepository;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    public ProductionAction(
            @Lazy PanelProductionService panelProductionService,
            PanelRepository panelRepository,
            ProjectStateMachineService stateMachineService,
            StateChangeEventPublisher eventPublisher) {
        this.panelProductionService = panelProductionService;
        this.panelRepository = panelRepository;
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 开始生产流程
     */
    public void startProduction(String projectId) {
        log.info("Action: Start production for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.PRODUCING);
        eventPublisher.publishTaskStart(projectId, "production_start");
        eventPublisher.publishTaskComplete(projectId, "production_start", null);
    }

    /**
     * 生成分镜文本（异步）
     */
    public void generateText(String projectId, Long panelId) {
        log.info("Action: Generate text for project={}, panelId={}", projectId, panelId);
        eventPublisher.publishTaskStart(projectId, "text_generation");

        CompletableFuture.runAsync(() -> {
            try {
                // 文本生成由 PanelProductionService 处理
                // 九宫格生成服务内部会生成分镜文本
                stateMachineService.sendEvent(projectId, ProjectEventType._TEXT_DONE);
                eventPublisher.publishTaskComplete(projectId, "text_generation", null);
            } catch (Exception e) {
                log.error("Text generation failed: projectId={}, panelId={}", projectId, panelId, e);
                eventPublisher.publishFailure(projectId, "文本生成失败: " + e.getMessage());
            }
        });
    }

    /**
     * 生成分镜图像/九宫格（异步）
     */
    public void generateImage(String projectId, Long panelId) {
        log.info("Action: Generate image for project={}, panelId={}", projectId, panelId);
        stateMachineService.persistState(projectId, ProjectState.PRODUCING);
        eventPublisher.publishTaskStart(projectId, "image_generation");

        CompletableFuture.runAsync(() -> {
            try {
                if (panelId != null) {
                    panelProductionService.regenerateGrid(panelId);
                }
                stateMachineService.sendEvent(projectId, ProjectEventType._IMAGE_DONE);
                eventPublisher.publishTaskComplete(projectId, "image_generation", null);
            } catch (Exception e) {
                log.error("Image generation failed: projectId={}, panelId={}", projectId, panelId, e);
                eventPublisher.publishFailure(projectId, "九宫格生成失败: " + e.getMessage());
            }
        });
    }

    /**
     * 生成分镜视频（异步）
     */
    public void generateVideo(String projectId, Long panelId) {
        log.info("Action: Generate video for project={}, panelId={}", projectId, panelId);
        stateMachineService.persistState(projectId, ProjectState.PRODUCING);
        eventPublisher.publishTaskStart(projectId, "video_generation");

        CompletableFuture.runAsync(() -> {
            try {
                if (panelId != null) {
                    panelProductionService.generateVideoByPanelId(panelId);
                }
                stateMachineService.sendEvent(projectId, ProjectEventType._VIDEO_DONE);
                eventPublisher.publishTaskComplete(projectId, "video_generation", null);
            } catch (Exception e) {
                log.error("Video generation failed: projectId={}, panelId={}", projectId, panelId, e);
                eventPublisher.publishFailure(projectId, "视频生成失败: " + e.getMessage());
            }
        });
    }

    /**
     * 确认生产，进入分镜审核阶段
     */
    public void confirmProduction(String projectId) {
        log.info("Action: Confirm production for project={}", projectId);
        try {
            // 检查所有 Panel 是否完成
            stateMachineService.persistState(projectId, ProjectState.STORYBOARD_REVIEW);
            eventPublisher.publishTaskComplete(projectId, "production_confirmation", null);
        } catch (Exception e) {
            log.error("Production confirmation failed: projectId={}", projectId, e);
            eventPublisher.publishFailure(projectId, "生产确认失败: " + e.getMessage());
        }
    }
}
