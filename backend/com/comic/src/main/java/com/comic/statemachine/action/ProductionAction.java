package com.comic.statemachine.action;

import com.comic.service.production.EpisodeProductionService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 生产相关的 Action
 * 直接调用 EpisodeProductionService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionAction {

    private final EpisodeProductionService episodeProductionService;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    /**
     * 开始生产（异步）
     */
    public void startProduction(String projectId) {
        log.info("Action: Start production for project={}", projectId);
        eventPublisher.publishTaskStart(projectId, "video_production");

        CompletableFuture.runAsync(() -> {
            try {
                // 直接调用现有 Service
                episodeProductionService.startProductionForProject(projectId);
                // 生产完成后发送事件
                stateMachineService.sendEvent(projectId, ProjectEventType._PRODUCTION_DONE);
                eventPublisher.publishTaskComplete(projectId, "video_production", null);
            } catch (Exception e) {
                log.error("Production failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "视频生产失败: " + e.getMessage());
                // 生产失败不转换状态，保持在 PRODUCING 状态，允许重试
            }
        });
    }

    /**
     * 生产完成
     */
    public void onProductionComplete(String projectId) {
        log.info("Action: Production completed for project={}", projectId);
    }
}
