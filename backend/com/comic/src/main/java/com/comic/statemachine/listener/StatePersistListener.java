package com.comic.statemachine.listener;

import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;

/**
 * 状态持久化监听器
 *
 * 职责：
 * - 监听状态机状态变更
 * - 自动将状态同步到数据库
 * - 确保状态机和数据库状态一致
 *
 * Phase 1 更新：修复泛型参数
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatePersistListener extends StateMachineListenerAdapter<ProjectState, ProjectEventType> {

    private final ProjectRepository projectRepository;

    /**
     * 状态转换结束时持久化到数据库
     * 注意：此方法无法直接获取 projectId，持久化逻辑已移到各 Action 中处理
     */
    @Override
    public void transitionEnded(Transition<ProjectState, ProjectEventType> transition) {
        // Transition 对象无法获取 message headers 中的 projectId
        // 状态持久化已改为在各个 Action 中直接处理
        if (transition != null && transition.getTarget() != null) {
            log.trace("Transition ended: -> {}", transition.getTarget().getId());
        }
    }

    /**
     * 持久化状态到数据库
     */
    private void persistState(String projectId, ProjectState newState) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            log.warn("Project not found when persisting state: projectId={}", projectId);
            return;
        }

        String oldStatus = project.getStatus();

        // 只在状态真正改变时更新
        if (!newState.name().equals(oldStatus)) {
            project.setStatus(newState.name());
            projectRepository.updateById(project);
            log.info("State persisted: projectId={}, {} -> {}", projectId, oldStatus, newState);
        } else {
            log.debug("State unchanged, skipping persist: projectId={}, state={}", projectId, newState);
        }
    }

    /**
     * 状态转换开始时记录日志
     */
    @Override
    public void transitionStarted(Transition<ProjectState, ProjectEventType> transition) {
        if (transition != null) {
            log.trace("Transition started: {} -> {}",
                transition.getSource().getId(),
                transition.getTarget().getId());
        }
    }

    /**
     * 状态变更时记录日志（用于调试）
     */
    @Override
    public void stateChanged(State<ProjectState, ProjectEventType> from, State<ProjectState, ProjectEventType> to) {
        if (from != null && to != null) {
            log.trace("State changed: {} -> {}", from.getId(), to.getId());
        }
    }

    /**
     * 状态机启动时记录
     */
    @Override
    public void stateMachineStarted(StateMachine<ProjectState, ProjectEventType> stateMachine) {
        log.debug("StateMachine started: id={}", stateMachine.getId());
    }

    /**
     * 状态机停止时记录
     */
    @Override
    public void stateMachineStopped(StateMachine<ProjectState, ProjectEventType> stateMachine) {
        log.debug("StateMachine stopped: id={}", stateMachine.getId());
    }

    /**
     * 状态机错误时记录
     */
    @Override
    public void stateMachineError(StateMachine<ProjectState, ProjectEventType> stateMachine, Exception exception) {
        log.error("StateMachine error: id={}, error={}", stateMachine.getId(), exception.getMessage());
    }
}
