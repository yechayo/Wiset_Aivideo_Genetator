package com.comic.statemachine.service;

import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectMilestone;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 里程碑状态机服务
 *
 * 管理 per-project 的里程碑状态机实例。
 * 使用 projectMilestoneFactory（6 状态），与旧的 27 状态状态机并存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMilestoneStateMachineService {

    private final StateMachineFactory<ProjectMilestone, ProjectMilestoneEventType> projectMilestoneFactory;
    private final ProjectRepository projectRepository;
    private final StateChangeEventPublisher eventPublisher;

    private final Map<String, StateMachine<ProjectMilestone, ProjectMilestoneEventType>> stateMachines = new ConcurrentHashMap<>();

    /** 获取或创建状态机实例 */
    public StateMachine<ProjectMilestone, ProjectMilestoneEventType> getStateMachine(String projectId) {
        return stateMachines.computeIfAbsent(projectId, this::createStateMachine);
    }

    private StateMachine<ProjectMilestone, ProjectMilestoneEventType> createStateMachine(String projectId) {
        log.info("Creating milestone state machine: projectId={}", projectId);
        StateMachine<ProjectMilestone, ProjectMilestoneEventType> sm = projectMilestoneFactory.getStateMachine(projectId);

        // 从 DB 恢复状态
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null) {
            String status = project.getStatus();
            ProjectMilestone milestone = ProjectMilestone.fromCode(status);
            if (milestone != ProjectMilestone.DRAFT) {
                sm.getStateMachineAccessor()
                    .doWithAllRegions(access -> access.resetStateMachine(
                        new DefaultStateMachineContext<>(milestone, null, null, null, null, null)
                    ));
                log.info("Milestone state machine restored: projectId={}, milestone={}", projectId, milestone);
            }
        }

        sm.startReactively().block();
        return sm;
    }

    /** 发送事件 */
    public boolean sendEvent(String projectId, ProjectMilestoneEventType event) {
        return sendEvent(projectId, event, null);
    }

    /** 发送带负载的事件 */
    public boolean sendEvent(String projectId, ProjectMilestoneEventType event, Map<String, Object> headers) {
        try {
            StateMachine<ProjectMilestone, ProjectMilestoneEventType> sm = getStateMachine(projectId);

            Map<String, Object> finalHeaders = new HashMap<>();
            if (headers != null) finalHeaders.putAll(headers);
            finalHeaders.put("projectId", projectId);

            log.info("Sending milestone event: projectId={}, event={}", projectId, event);

            Message<ProjectMilestoneEventType> message = MessageBuilder.withPayload(event)
                .copyHeaders(finalHeaders)
                .build();
            boolean accepted = sm.sendEvent(message);

            if (!accepted) {
                log.error("Milestone event NOT ACCEPTED: projectId={}, event={}, currentState={}",
                    projectId, event, sm.getState() != null ? sm.getState().getId() : null);
            }
            return accepted;
        } catch (Exception e) {
            log.error("Failed to send milestone event: projectId={}, event={}", projectId, event, e);
            return false;
        }
    }

    /** 获取当前里程碑 */
    public ProjectMilestone getCurrentMilestone(String projectId) {
        StateMachine<ProjectMilestone, ProjectMilestoneEventType> sm = getStateMachine(projectId);
        return sm.getState() != null ? sm.getState().getId() : ProjectMilestone.DRAFT;
    }

    /** 持久化里程碑到数据库 */
    public void persistMilestone(String projectId, ProjectMilestone newMilestone) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            log.warn("Project not found when persisting milestone: projectId={}", projectId);
            return;
        }

        String oldStatus = project.getStatus();
        String newCode = newMilestone.getCode();

        if (!newCode.equals(oldStatus)) {
            project.setStatus(newCode);
            projectRepository.updateById(project);
            log.info("Milestone persisted: projectId={}, {} -> {}", projectId, oldStatus, newCode);
            eventPublisher.publishMilestoneChange(projectId, newCode);
        }
    }

    /** 销毁状态机实例 */
    public void destroyStateMachine(String projectId) {
        StateMachine<ProjectMilestone, ProjectMilestoneEventType> sm = stateMachines.remove(projectId);
        if (sm != null) {
            sm.stopReactively().block();
            log.info("Milestone state machine destroyed: projectId={}", projectId);
        }
    }
}
