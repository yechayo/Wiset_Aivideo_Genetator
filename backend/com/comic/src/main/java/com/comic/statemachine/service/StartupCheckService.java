package com.comic.statemachine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 服务启动时检查并修复卡死的任务状态
 *
 * 场景：服务重启时，异步任务丢失，但状态仍为 *_GENERATING
 * 解决：启动时扫描超时的生成任务，重置到上一个审核状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupCheckService {

    private final ProjectRepository projectRepository;
    private final ProjectStateMachineService stateMachineService;

    // 超时阈值：生成任务超过这个时间认为已丢失
    private static final long TIMEOUT_MINUTES = 10;

    // 所有生成中的状态
    private static final List<String> GENERATING_STATES = Arrays.asList(
            ProjectState.OUTLINE_GENERATING.name(),
            ProjectState.EPISODE_GENERATING.name(),
            ProjectState.CHARACTER_EXTRACTING.name(),
            ProjectState.IMAGE_GENERATING.name(),
            ProjectState.PANEL_GENERATING.name(),
            ProjectState.VIDEO_ASSEMBLING.name()
    );

    /**
     * 应用启动完成后执行检查
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Starting up: checking for stuck tasks...");
        int fixedCount = checkAndFixStuckTasks();
        log.info("Startup check complete: fixed {} stuck tasks", fixedCount);
    }

    /**
     * 检查并修复卡死的任务
     *
     * @return 修复的任务数量
     */
    public int checkAndFixStuckTasks() {
        int fixedCount = 0;

        // 只查询生成中的项目，避免全表扫描
        List<Project> generatingProjects = projectRepository.selectList(
                new LambdaQueryWrapper<Project>()
                        .in(Project::getStatus, GENERATING_STATES)
                        .eq(Project::getDeleted, false)
        );

        log.info("Found {} projects in generating state", generatingProjects.size());

        for (Project project : generatingProjects) {
            if (project == null || project.getDeleted()) {
                continue;
            }

            String status = project.getStatus();
            ProjectState currentState = parseProjectState(status);

            if (currentState != null && currentState.isGenerating()) {
                if (isTaskStuck(project)) {
                    fixedCount += fixStuckTask(project, currentState);
                }
            }
        }

        return fixedCount;
    }

    /**
     * 判断任务是否卡死
     */
    private boolean isTaskStuck(Project project) {
        LocalDateTime updatedAt = project.getUpdatedAt();
        if (updatedAt == null) {
            return true;
        }

        long minutesElapsed = Duration.between(updatedAt, LocalDateTime.now()).toMinutes();
        return minutesElapsed >= TIMEOUT_MINUTES;
    }

    /**
     * 修复卡死的任务
     *
     * @return 是否修复成功
     */
    private int fixStuckTask(Project project, ProjectState currentState) {
        String projectId = project.getProjectId();
        log.warn("Found stuck task: projectId={}, state={}, updatedAt={}",
                projectId, currentState, project.getUpdatedAt());

        // 根据当前状态决定回退到哪个状态
        ProjectState targetState = getRecoveryState(currentState);

        if (targetState != null) {
            try {
                // 持久化新状态
                stateMachineService.persistState(projectId, targetState);

                // 清除内存中的状态机缓存（强制重新创建）
                stateMachineService.destroyStateMachine(projectId);

                log.info("Fixed stuck task: projectId={}, {} -> {}",
                        projectId, currentState, targetState);
                return 1;
            } catch (Exception e) {
                log.error("Failed to fix stuck task: projectId={}", projectId, e);
            }
        }

        return 0;
    }

    /**
     * 获取恢复后的目标状态
     *
     * 生成中状态 -> 回退到对应的审核状态
     */
    private ProjectState getRecoveryState(ProjectState generatingState) {
        switch (generatingState) {
            case OUTLINE_GENERATING:
                return ProjectState.OUTLINE_REVIEW;
            case EPISODE_GENERATING:
                return ProjectState.SCRIPT_REVIEW;
            case CHARACTER_EXTRACTING:
                return ProjectState.CHARACTER_REVIEW;
            case IMAGE_GENERATING:
                return ProjectState.IMAGE_REVIEW;
            case PANEL_GENERATING:
                return ProjectState.PANEL_REVIEW;
            case VIDEO_ASSEMBLING:
                // 视频拼接阶段比较特殊，可能部分完成了，暂时不自动恢复
                log.warn("VIDEO_ASSEMBLING state cannot be auto-recovered, manual check required");
                return null;
            default:
                return null;
        }
    }

    /**
     * 解析状态字符串为 ProjectState
     */
    private ProjectState parseProjectState(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ProjectState.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
