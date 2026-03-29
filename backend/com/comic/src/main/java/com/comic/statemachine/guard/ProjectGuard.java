package com.comic.statemachine.guard;

import com.comic.common.ProjectStatus;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 状态机守卫（Guard）
 * 用于检查状态转换是否允许
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectGuard {

    private final ProjectRepository projectRepository;

    /**
     * 检查项目是否可以执行某个操作
     */
    private boolean checkProject(String projectId, Supplier<Boolean> checker) {
        try {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null) {
                log.warn("Project not found: {}", projectId);
                return false;
            }
            return checker.get();
        } catch (Exception e) {
            log.error("Guard check failed for project: {}", projectId, e);
            return false;
        }
    }

    // ===== 剧本阶段守卫 =====

    public Guard<ProjectState, ProjectEventType> canGenerateOutline() {
        return context -> {
            String projectId = getProjectId(context);
            log.info("Guard check: canGenerateOutline, projectId={}", projectId);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                boolean allowed = status == ProjectStatus.DRAFT
                    || status == ProjectStatus.OUTLINE_REVIEW
                    || status == ProjectStatus.OUTLINE_GENERATING_FAILED;
                log.info("Guard check result: projectId={}, status={}, allowed={}", projectId, status, allowed);
                return allowed;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canReviseOutline() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.OUTLINE_REVIEW;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canGenerateEpisodes() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                // 允许从 OUTLINE_REVIEW 或 EPISODE_GENERATING_FAILED 重试
                return status == ProjectStatus.OUTLINE_REVIEW
                    || status == ProjectStatus.EPISODE_GENERATING_FAILED;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canConfirmScript() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.SCRIPT_REVIEW;
            });
        };
    }

    // ===== 角色阶段守卫 =====

    public Guard<ProjectState, ProjectEventType> canExtractCharacters() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.SCRIPT_CONFIRMED
                    || status == ProjectStatus.CHARACTER_EXTRACTING_FAILED;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canConfirmCharacters() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.CHARACTER_REVIEW;
            });
        };
    }

    // ===== 图像阶段守卫 =====

    public Guard<ProjectState, ProjectEventType> canGenerateImages() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.CHARACTER_CONFIRMED
                    || status == ProjectStatus.IMAGE_GENERATING_FAILED;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canConfirmImages() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.IMAGE_REVIEW;
            });
        };
    }

    // ===== 分镜阶段守卫 =====

    public Guard<ProjectState, ProjectEventType> canStartPanel() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.ASSET_LOCKED
                    || status == ProjectStatus.PANEL_CONFIRMED
                    || status == ProjectStatus.PANEL_GENERATING_FAILED;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canRevisePanel() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.PANEL_REVIEW;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canConfirmPanel() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.PANEL_REVIEW;
            });
        };
    }

    // ===== 视频剪辑阶段守卫 =====

    public Guard<ProjectState, ProjectEventType> canStartVideoAssembly() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.PANEL_CONFIRMED;
            });
        };
    }

    // ===== 辅助方法 =====

    private String getProjectId(StateContext<ProjectState, ProjectEventType> context) {
        // 从上下文中获取 projectId
        // 使用 getMessageHeaders() 获取所有 headers
        Object projectId = context.getMessageHeaders().get("projectId");
        if (projectId != null) {
            return projectId.toString();
        }

        // 尝试从 extended state 获取
        Object extended = context.getExtendedState().getVariables().get("projectId");
        if (extended != null) {
            return extended.toString();
        }

        log.warn("Could not extract projectId from StateContext, headers={}, extendedState={}",
                context.getMessageHeaders(),
                context.getExtendedState().getVariables());
        return null;
    }

    private ProjectStatus getCurrentStatus(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        return project != null ? ProjectStatus.fromCode(project.getStatus()) : null;
    }
}
