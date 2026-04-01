package com.comic.statemachine.guard;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectGuard {

    private final ProjectRepository projectRepository;

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

    /**
     * 检查是否可以生成大纲
     * 允许状态: DRAFT, OUTLINE_REVIEW, OUTLINE_GENERATING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canGenerateOutline() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.DRAFT
                    || status == ProjectState.OUTLINE_REVIEW
                    || status == ProjectState.OUTLINE_GENERATING_FAILED;
            });
        };
    }

    /**
     * 检查是否可以修改大纲
     * 允许状态: OUTLINE_REVIEW
     */
    public Guard<ProjectState, ProjectEventType> canReviseOutline() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.OUTLINE_REVIEW;
            });
        };
    }

    /**
     * 检查是否可以生成剧集
     * 允许状态: OUTLINE_REVIEW, EPISODE_GENERATING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canGenerateEpisodes() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.OUTLINE_REVIEW
                    || status == ProjectState.EPISODE_GENERATING_FAILED;
            });
        };
    }

    /**
     * 检查是否可以生成剧集脚本
     * 允许状态: EPISODE_SCRIPT_REVIEW, EPISODE_SCRIPT_GENERATING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canGenerateEpisodeScript() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.EPISODE_SCRIPT_REVIEW
                    || status == ProjectState.EPISODE_SCRIPT_GENERATING_FAILED;
            });
        };
    }

    /**
     * 检查是否可以确认剧集脚本
     * 允许状态: EPISODE_SCRIPT_REVIEW
     */
    public Guard<ProjectState, ProjectEventType> canConfirmEpisodeScript() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.EPISODE_SCRIPT_REVIEW;
            });
        };
    }

    // ===== 角色阶段守卫 =====

    /**
     * 检查是否可以提取角色
     * 允许状态: SCRIPT_REVIEW, EPISODE_SCRIPT_REVIEW, CHARACTER_EXTRACTING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canExtractCharacters() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.SCRIPT_REVIEW
                    || status == ProjectState.EPISODE_SCRIPT_REVIEW
                    || status == ProjectState.CHARACTER_EXTRACTING_FAILED;
            });
        };
    }

    /**
     * 检查是否可以确认角色
     * 允许状态: CHARACTER_REVIEW
     */
    public Guard<ProjectState, ProjectEventType> canConfirmCharacters() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.CHARACTER_REVIEW;
            });
        };
    }

    // ===== 素材阶段守卫 =====

    /**
     * 检查是否可以生成图像
     * 允许状态: CHARACTER_REVIEW, IMAGE_GENERATING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canGenerateImages() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.CHARACTER_REVIEW
                    || status == ProjectState.IMAGE_GENERATING_FAILED;
            });
        };
    }

    // ===== 分镜生产阶段守卫 =====

    /**
     * 检查是否可以开始分镜生产
     * 允许状态: ASSET_LOCKED
     */
    public Guard<ProjectState, ProjectEventType> canStartProduction() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.ASSET_LOCKED;
            });
        };
    }

    /**
     * 检查是否可以确认生产完成
     * 允许状态: PRODUCING
     */
    public Guard<ProjectState, ProjectEventType> canConfirmProduction() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.PRODUCING;
            });
        };
    }

    // ===== 分镜阶段守卫 =====

    /**
     * 检查是否可以开始分镜生成
     * 允许状态: STORYBOARD_GENERATING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canStartStoryboard() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.STORYBOARD_GENERATING_FAILED;
            });
        };
    }

    /**
     * 检查是否可以确认分镜
     * 允许状态: STORYBOARD_REVIEW
     */
    public Guard<ProjectState, ProjectEventType> canConfirmStoryboard() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.STORYBOARD_REVIEW;
            });
        };
    }

    // ===== 视频拼接阶段守卫 =====

    /**
     * 检查是否可以开始视频拼接
     * 允许状态: MERGING_FAILED
     */
    public Guard<ProjectState, ProjectEventType> canStartMerge() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.MERGING_FAILED;
            });
        };
    }

    /**
     * 检查是否可以确认视频拼接
     * 允许状态: MERGING
     */
    public Guard<ProjectState, ProjectEventType> canConfirmMerge() {
        return context -> {
            String projectId = getProjectId(context);
            return checkProject(projectId, () -> {
                ProjectState status = getCurrentState(projectId);
                return status == ProjectState.MERGING;
            });
        };
    }

    // ===== 辅助方法 =====

    private String getProjectId(StateContext<ProjectState, ProjectEventType> context) {
        Object projectId = context.getMessageHeaders().get("projectId");
        if (projectId != null) {
            return projectId.toString();
        }
        Object extended = context.getExtendedState().getVariables().get("projectId");
        if (extended != null) {
            return extended.toString();
        }
        return null;
    }

    private ProjectState getCurrentState(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return null;
        }
        try {
            return ProjectState.valueOf(project.getStatus());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
