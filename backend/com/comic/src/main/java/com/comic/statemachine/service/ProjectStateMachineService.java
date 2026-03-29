package com.comic.statemachine.service;

import com.comic.common.ProjectStatus;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.state.State;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目状态机服务
 * 管理状态机实例的创建、获取、恢复和事件发送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStateMachineService {

    private final StateMachineFactory<ProjectState, ProjectEventType> stateMachineFactory;
    private final ProjectRepository projectRepository;
    private final StateChangeEventPublisher eventPublisher;

    // 缓存状态机实例（按 projectId）
    private final Map<String, StateMachine<ProjectState, ProjectEventType>> stateMachines = new ConcurrentHashMap<>();

    /**
     * 获取或创建状态机实例
     */
    public StateMachine<ProjectState, ProjectEventType> getStateMachine(String projectId) {
        return stateMachines.computeIfAbsent(projectId, this::createStateMachine);
    }

    /**
     * 创建新的状态机实例
     */
    private StateMachine<ProjectState, ProjectEventType> createStateMachine(String projectId) {
        log.info("Creating state machine for project: {}", projectId);

        StateMachine<ProjectState, ProjectEventType> stateMachine = stateMachineFactory.getStateMachine(projectId);

        // 从 DB 恢复状态
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null) {
            String currentStatus = project.getStatus();
            if (currentStatus != null) {
                ProjectState currentState = mapToStateMachineState(ProjectStatus.fromCode(currentStatus));
                if (currentState != null && currentState != ProjectState.DRAFT) {
                    // 恢复状态（使用新的 API）
                    stateMachine.getStateMachineAccessor()
                            .doWithAllRegions(access -> access.resetStateMachine(
                                    new DefaultStateMachineContext<>(currentState, null, null, null, null, null)
                            ));
                    log.info("State machine restored to: {}", currentState);
                }
            }
        }

        // 启动状态机（阻塞等待完成）
        stateMachine.startReactively().block();

        return stateMachine;
    }

    /**
     * 发送事件到状态机
     */
    public boolean sendEvent(String projectId, ProjectEventType event) {
        return sendEvent(projectId, event, null);
    }

    /**
     * 发送带负载的事件到状态机
     */
    public boolean sendEvent(String projectId, ProjectEventType event, Map<String, Object> headers) {
        try {
            StateMachine<ProjectState, ProjectEventType> stateMachine = getStateMachine(projectId);

            // 确保 projectId 总是在 headers 中
            Map<String, Object> finalHeaders = new HashMap<>();
            if (headers != null) {
                finalHeaders.putAll(headers);
            }
            finalHeaders.put("projectId", projectId);

            log.info("Sending event: projectId={}, event={}, headers={}", projectId, event, finalHeaders);

            // 使用 MessageBuilder 构建消息
            Message<ProjectEventType> message = MessageBuilder.withPayload(event)
                    .copyHeaders(finalHeaders)
                    .build();
            boolean accepted = stateMachine.sendEvent(message);

            State<ProjectState, ProjectEventType> currentState = stateMachine.getState();
            if (!accepted) {
                log.error("Event NOT ACCEPTED: projectId={}, event={}, currentState={}, guard=false - event not valid for current state. Hint: CONFIRM_IMAGES requires IMAGE_REVIEW state, but current state is {}",
                        projectId, event, currentState != null ? currentState.getId() : null, currentState != null ? currentState.getId() : null);
            } else {
                log.info("Event accepted: projectId={}, event={}, currentState={}",
                        projectId, event, currentState != null ? currentState.getId() : null);
            }

            return accepted;
        } catch (Exception e) {
            log.error("Failed to send event: projectId={}, event={}", projectId, event, e);
            return false;
        }
    }

    /**
     * 获取当前状态
     */
    public ProjectState getCurrentState(String projectId) {
        StateMachine<ProjectState, ProjectEventType> stateMachine = getStateMachine(projectId);
        State<ProjectState, ProjectEventType> state = stateMachine.getState();
        return state != null ? state.getId() : null;
    }

    /**
     * 重置状态机（用于测试或异常恢复）
     */
    public void resetStateMachine(String projectId, ProjectState newState) {
        StateMachine<ProjectState, ProjectEventType> stateMachine = getStateMachine(projectId);

        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> access.resetStateMachine(
                        new DefaultStateMachineContext<>(newState, null, null, null, null, null)
                ));

        log.info("State machine reset: projectId={}, newState={}", projectId, newState);
    }

    /**
     * 销毁状态机实例（项目完成或取消时）
     */
    public void destroyStateMachine(String projectId) {
        StateMachine<ProjectState, ProjectEventType> stateMachine = stateMachines.remove(projectId);
        if (stateMachine != null) {
            stateMachine.stopReactively().block();
            log.info("State machine destroyed: projectId={}", projectId);
        }
    }

    /**
     * 映射 ProjectStatus 到 ProjectState
     */
    private ProjectState mapToStateMachineState(ProjectStatus status) {
        if (status == null) {
            return ProjectState.DRAFT;
        }

        try {
            return ProjectState.valueOf(status.getCode());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown ProjectStatus: {}", status.getCode());
            return ProjectState.DRAFT;
        }
    }

    /**
     * 映射 ProjectState 到 ProjectStatus
     */
    public ProjectStatus mapToProjectStatus(ProjectState state) {
        if (state == null) {
            return ProjectStatus.DRAFT;
        }

        return ProjectStatus.fromCode(state.name());
    }

    /**
     * 持久化状态到数据库（供 Action 调用）
     */
    public void persistState(String projectId, ProjectState newState) {
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
}
