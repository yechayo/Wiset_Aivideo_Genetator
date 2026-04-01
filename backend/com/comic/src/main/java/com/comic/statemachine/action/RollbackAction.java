package com.comic.statemachine.action;

import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 回滚相关的 Action
 * 根据目标状态执行清理操作
 */
@Slf4j
@Component
public class RollbackAction {

    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    public RollbackAction(
            ProjectStateMachineService stateMachineService,
            StateChangeEventPublisher eventPublisher) {
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 回滚到目标状态
     *
     * @param projectId   项目ID
     * @param targetState 目标状态
     */
    public void rollbackTo(String projectId, ProjectState targetState) {
        log.info("Action: Rollback for project={} to targetState={}", projectId, targetState);

        if (targetState == null) {
            log.error("Rollback target state is null: projectId={}", projectId);
            eventPublisher.publishFailure(projectId, "回滚目标状态不能为空");
            return;
        }

        try {
            // 根据目标状态执行相应的清理和状态设置
            switch (targetState) {
                case OUTLINE_REVIEW:
                    // 回滚到大纲审核阶段
                    stateMachineService.persistState(projectId, ProjectState.OUTLINE_REVIEW);
                    stateMachineService.resetStateMachine(projectId, ProjectState.OUTLINE_REVIEW);
                    break;

                case SCRIPT_REVIEW:
                    // 回滚到剧本审核阶段
                    stateMachineService.persistState(projectId, ProjectState.SCRIPT_REVIEW);
                    stateMachineService.resetStateMachine(projectId, ProjectState.SCRIPT_REVIEW);
                    break;

                case EPISODE_SCRIPT_REVIEW:
                    // 回滚到分集剧本审核阶段
                    stateMachineService.persistState(projectId, ProjectState.EPISODE_SCRIPT_REVIEW);
                    stateMachineService.resetStateMachine(projectId, ProjectState.EPISODE_SCRIPT_REVIEW);
                    break;

                case CHARACTER_REVIEW:
                    // 回滚到角色审核阶段
                    stateMachineService.persistState(projectId, ProjectState.CHARACTER_REVIEW);
                    stateMachineService.resetStateMachine(projectId, ProjectState.CHARACTER_REVIEW);
                    break;

                case ASSET_LOCKED:
                    // 回滚到素材锁定阶段
                    stateMachineService.persistState(projectId, ProjectState.ASSET_LOCKED);
                    stateMachineService.resetStateMachine(projectId, ProjectState.ASSET_LOCKED);
                    break;

                case PRODUCING:
                    // 回滚到生产阶段
                    stateMachineService.persistState(projectId, ProjectState.PRODUCING);
                    stateMachineService.resetStateMachine(projectId, ProjectState.PRODUCING);
                    break;

                case STORYBOARD_REVIEW:
                    // 回滚到分镜审核阶段
                    stateMachineService.persistState(projectId, ProjectState.STORYBOARD_REVIEW);
                    stateMachineService.resetStateMachine(projectId, ProjectState.STORYBOARD_REVIEW);
                    break;

                case MERGING:
                    // 回滚到视频拼接阶段
                    stateMachineService.persistState(projectId, ProjectState.MERGING);
                    stateMachineService.resetStateMachine(projectId, ProjectState.MERGING);
                    break;

                case DRAFT:
                    // 回滚到草稿阶段（最彻底的重置）
                    stateMachineService.persistState(projectId, ProjectState.DRAFT);
                    stateMachineService.resetStateMachine(projectId, ProjectState.DRAFT);
                    break;

                default:
                    log.warn("Rollback to state {} is not explicitly handled: projectId={}", targetState, projectId);
                    stateMachineService.persistState(projectId, targetState);
                    stateMachineService.resetStateMachine(projectId, targetState);
                    break;
            }

            log.info("Rollback completed: projectId={}, targetState={}", projectId, targetState);
            eventPublisher.publishTaskComplete(projectId, "rollback", targetState.name());

        } catch (Exception e) {
            log.error("Rollback failed: projectId={}, targetState={}", projectId, targetState, e);
            eventPublisher.publishFailure(projectId, "回滚失败: " + e.getMessage());
        }
    }
}
