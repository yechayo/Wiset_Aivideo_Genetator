package com.comic.statemachine.guard;

import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectMilestone;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * 里程碑状态机 Guard
 *
 * 检查数据存在性，确认操作是否允许执行。
 * 防重复提交在 Controller 层通过 progressService.isGenerating() 检查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectMilestoneGuard {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final CharacterRepository characterRepository;
    private final PanelRepository panelRepository;

    /** 确认大纲：大纲数据必须存在 */
    public Guard<ProjectMilestone, ProjectMilestoneEventType> canConfirmOutline() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            boolean exists = outlineExists(projectId);
            if (!exists) log.warn("Guard reject: outline not found, projectId={}", projectId);
            return exists;
        };
    }

    /** 确认分集剧本：episodes 数据必须存在 */
    public Guard<ProjectMilestone, ProjectMilestoneEventType> canConfirmEpisodes() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            boolean exists = episodesExist(projectId);
            if (!exists) log.warn("Guard reject: episodes not found, projectId={}", projectId);
            return exists;
        };
    }

    /** 确认素材：所有角色图片必须已完成 */
    public Guard<ProjectMilestone, ProjectMilestoneEventType> canConfirmAssets() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            boolean charsExist = charactersExist(projectId);
            boolean imagesDone = allCharacterImagesDone(projectId);
            boolean ok = charsExist && imagesDone;
            if (!ok) log.warn("Guard reject: assets not ready, projectId={}, chars={}, images={}", projectId, charsExist, imagesDone);
            return ok;
        };
    }

    /** 确认面板：所有面板 text+image+video 必须已完成 */
    public Guard<ProjectMilestone, ProjectMilestoneEventType> canConfirmPanels() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            boolean ok = allPanelsCompleted(projectId);
            if (!ok) log.warn("Guard reject: panels not all completed, projectId={}", projectId);
            return ok;
        };
    }

    // ===== 数据存在性检查 =====

    private boolean outlineExists(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null || project.getProjectInfo() == null) return false;
        Object script = project.getProjectInfo().get("script");
        if (script instanceof java.util.Map) {
            Object outline = ((java.util.Map<?, ?>) script).get("outline");
            return outline != null && !outline.toString().trim().isEmpty();
        }
        return false;
    }

    private boolean episodesExist(String projectId) {
        java.util.List<?> episodes = episodeRepository.findByProjectId(projectId);
        return episodes != null && !episodes.isEmpty();
    }

    private boolean charactersExist(String projectId) {
        java.util.List<?> characters = characterRepository.findByProjectId(projectId);
        return characters != null && !characters.isEmpty();
    }

    private boolean allCharacterImagesDone(String projectId) {
        java.util.List<com.comic.entity.Character> characters = characterRepository.findByProjectId(projectId);
        for (com.comic.entity.Character c : characters) {
            java.util.Map<String, Object> info = c.getCharacterInfo();
            if (info == null) return false;
            // 优先检查 imagesLocked 标记（单角色锁定模式下使用）
            Object locked = info.get("imagesLocked");
            if (locked != null && Boolean.TRUE.equals(locked)) continue;
            String threeView = info.get("threeViewStatus") != null ? info.get("threeViewStatus").toString() : null;
            if (!"COMPLETED".equals(threeView)) return false;
            String role = info.get("role") != null ? info.get("role").toString() : null;
            if (!"配角".equals(role)) {
                String expression = info.get("expressionStatus") != null ? info.get("expressionStatus").toString() : null;
                if (!"COMPLETED".equals(expression)) return false;
            }
        }
        return true;
    }

    private boolean allPanelsCompleted(String projectId) {
        java.util.List<com.comic.entity.Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (com.comic.entity.Episode ep : episodes) {
            java.util.List<com.comic.entity.Panel> panels = panelRepository.findByEpisodeId(ep.getId());
            for (com.comic.entity.Panel panel : panels) {
                java.util.Map<String, Object> info = panel.getPanelInfo();
                if (info == null) return false;
                String videoStatus = info.get("videoStatus") != null ? info.get("videoStatus").toString() : null;
                if (!"completed".equals(videoStatus)) return false;
            }
        }
        return true;
    }

    // ===== 辅助 =====

    private String getProjectId(StateContext<ProjectMilestone, ProjectMilestoneEventType> ctx) {
        Object pid = ctx.getMessageHeaders().get("projectId");
        return pid != null ? pid.toString() : null;
    }
}
