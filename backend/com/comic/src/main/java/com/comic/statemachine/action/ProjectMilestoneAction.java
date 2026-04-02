package com.comic.statemachine.action;

import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.redis.ProgressService;
import com.comic.statemachine.enums.ProjectMilestone;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import com.comic.statemachine.service.ProjectMilestoneStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 里程碑状态机 Action
 *
 * 确认操作：更新 MySQL milestone + 清理 Redis + SSE 推送
 * 回滚操作：级联清理下游数据 + 清理 Redis + 更新 MySQL milestone + SSE 推送
 */
@Slf4j
@Component
public class ProjectMilestoneAction {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final CharacterRepository characterRepository;
    private final PanelRepository panelRepository;
    private final ProgressService progressService;
    private final StateChangeEventPublisher eventPublisher;

    @Lazy
    @Autowired
    private ProjectMilestoneStateMachineService milestoneService;

    public ProjectMilestoneAction(ProjectRepository projectRepository,
                                   EpisodeRepository episodeRepository,
                                   CharacterRepository characterRepository,
                                   PanelRepository panelRepository,
                                   ProgressService progressService,
                                   StateChangeEventPublisher eventPublisher) {
        this.projectRepository = projectRepository;
        this.episodeRepository = episodeRepository;
        this.characterRepository = characterRepository;
        this.panelRepository = panelRepository;
        this.progressService = progressService;
        this.eventPublisher = eventPublisher;
    }

    // ===== 确认操作 =====

    public Action<ProjectMilestone, ProjectMilestoneEventType> confirmOutline() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            log.info("Confirm outline: projectId={}", projectId);
            progressService.clearAll(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.OUTLINE_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "outline_confirmed");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> confirmEpisode() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            log.info("Confirm episode: projectId={}", projectId);
            progressService.clearAll(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.EPISODE_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "episode_confirmed");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> confirmAssets() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            log.info("Confirm assets: projectId={}", projectId);
            progressService.clearAll(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.ASSET_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "asset_confirmed");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> confirmPanels() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            log.info("Confirm panels: projectId={}", projectId);
            progressService.clearAll(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.PANEL_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "panel_confirmed");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> assembleComplete() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            log.info("Assemble complete: projectId={}", projectId);
            progressService.clearAll(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.COMPLETED);
            eventPublisher.publishMilestoneChange(projectId, "completed");
        };
    }

    // ===== 回滚操作 =====

    public Action<ProjectMilestone, ProjectMilestoneEventType> rollbackToDraft() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            guardNotGenerating(projectId);
            log.info("Rollback to draft: projectId={}", projectId);
            progressService.clearAll(projectId);
            cascadeDeleteFromOutline(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.DRAFT);
            eventPublisher.publishMilestoneChange(projectId, "draft");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> rollbackToOutlineConfirmed() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            guardNotGenerating(projectId);
            log.info("Rollback to outline_confirmed: projectId={}", projectId);
            progressService.clearAll(projectId);
            cascadeDeleteFromEpisode(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.OUTLINE_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "outline_confirmed");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> rollbackToEpisodeConfirmed() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            guardNotGenerating(projectId);
            log.info("Rollback to episode_confirmed: projectId={}", projectId);
            progressService.clearAll(projectId);
            cascadeDeleteFromAsset(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.EPISODE_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "episode_confirmed");
        };
    }

    public Action<ProjectMilestone, ProjectMilestoneEventType> rollbackToAssetConfirmed() {
        return ctx -> {
            String projectId = getProjectId(ctx);
            guardNotGenerating(projectId);
            log.info("Rollback to asset_confirmed: projectId={}", projectId);
            progressService.clearAll(projectId);
            cascadeDeleteFromPanel(projectId);
            milestoneService.persistMilestone(projectId, ProjectMilestone.ASSET_CONFIRMED);
            eventPublisher.publishMilestoneChange(projectId, "asset_confirmed");
        };
    }

    // ===== 级联清理 =====

    /** 删除大纲及所有下游数据 */
    private void cascadeDeleteFromOutline(String projectId) {
        cascadeDeleteFromEpisode(projectId);
        // 清理大纲数据
        com.comic.entity.Project project = projectRepository.findByProjectId(projectId);
        if (project != null && project.getProjectInfo() != null) {
            project.getProjectInfo().remove("script");
            projectRepository.updateById(project);
        }
    }

    /** 删除分集剧本及所有下游数据 */
    private void cascadeDeleteFromEpisode(String projectId) {
        cascadeDeleteFromAsset(projectId);
        // 清理 episodes
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(ep.getId());
            for (Panel p : panels) {
                panelRepository.deleteById(p.getId());
            }
            episodeRepository.deleteById(ep.getId());
        }
    }

    /** 删除角色/图片数据及面板 */
    private void cascadeDeleteFromAsset(String projectId) {
        cascadeDeleteFromPanel(projectId);
        // 清理 characters
        List<Character> characters = characterRepository.findByProjectId(projectId);
        for (Character c : characters) {
            characterRepository.deleteById(c.getId());
        }
    }

    /** 删除面板数据 */
    private void cascadeDeleteFromPanel(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(ep.getId());
            for (Panel p : panels) {
                panelRepository.deleteById(p.getId());
            }
        }
    }

    // ===== 辅助 =====

    private String getProjectId(StateContext<ProjectMilestone, ProjectMilestoneEventType> ctx) {
        Object pid = ctx.getMessageHeaders().get("projectId");
        return pid != null ? pid.toString() : null;
    }

    private void guardNotGenerating(String projectId) {
        if (progressService.isGenerating(projectId)) {
            String taskType = progressService.getGeneratingTask(projectId);
            throw new com.comic.exception.BusinessException(
                "任务正在执行中（" + taskType + "），请等待完成或先清除后再回滚");
        }
    }
}
