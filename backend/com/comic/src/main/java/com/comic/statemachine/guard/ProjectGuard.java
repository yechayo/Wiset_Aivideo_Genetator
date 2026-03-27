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
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.DRAFT || status == ProjectStatus.OUTLINE_REVIEW;
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
                return status == ProjectStatus.OUTLINE_REVIEW;
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
                return status == ProjectStatus.SCRIPT_CONFIRMED;
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
                return status == ProjectStatus.CHARACTER_CONFIRMED;
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

    public Guard<ProjectState, ProjectEventType> canStartStoryboard() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.ASSET_LOCKED;
            });
        };
    }

    public Guard<ProjectState, ProjectEventType> canReviseStoryboard() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.STORYBOARD_REVIEW;
            });
        };
    }

    // ===== 生产阶段守卫 =====

    public Guard<ProjectState, ProjectEventType> canStartProduction() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectStatus status = getCurrentStatus(projectId);
                return status == ProjectStatus.STORYBOARD_REVIEW;
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

        return null;
    }

    private ProjectStatus getCurrentStatus(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        return project != null ? ProjectStatus.fromCode(project.getStatus()) : null;
    }
}
