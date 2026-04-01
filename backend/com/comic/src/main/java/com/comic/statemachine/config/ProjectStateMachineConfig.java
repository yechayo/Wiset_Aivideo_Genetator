package com.comic.statemachine.config;

import com.comic.statemachine.action.*;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.guard.ProjectGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.StateContext;

import java.util.EnumSet;

/**
 * 项目状态机配置
 * 使用 StateMachineFactory 支持多项目独立状态机
 * 使用 ApplicationContext 延迟加载 Actions 以避免循环依赖
 */
@Slf4j
@Configuration
@EnableStateMachineFactory
public class ProjectStateMachineConfig extends StateMachineConfigurerAdapter<ProjectState, ProjectEventType> {

    private final ApplicationContext applicationContext;
    private final ProjectGuard projectGuard;

    public ProjectStateMachineConfig(
            ApplicationContext applicationContext,
            ProjectGuard projectGuard) {
        this.applicationContext = applicationContext;
        this.projectGuard = projectGuard;
    }

    // 延迟获取 Actions 的方法
    private ScriptGenerationAction scriptAction() {
        return applicationContext.getBean(ScriptGenerationAction.class);
    }

    private CharacterExtractionAction characterAction() {
        return applicationContext.getBean(CharacterExtractionAction.class);
    }

    private ImageGenerationAction imageAction() {
        return applicationContext.getBean(ImageGenerationAction.class);
    }

    private ProductionAction productionAction() {
        return applicationContext.getBean(ProductionAction.class);
    }

    private StoryboardAction storyboardAction() {
        return applicationContext.getBean(StoryboardAction.class);
    }

    private RollbackAction rollbackAction() {
        return applicationContext.getBean(RollbackAction.class);
    }

    @Override
    public void configure(StateMachineStateConfigurer<ProjectState, ProjectEventType> states) throws Exception {
        states
            .withStates()
                .initial(ProjectState.DRAFT)
                .states(EnumSet.allOf(ProjectState.class))
            ;
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<ProjectState, ProjectEventType> transitions) throws Exception {
        transitions

            // ===== 剧本阶段转换 =====
            // DRAFT -> OUTLINE_GENERATING
            .withExternal()
                .source(ProjectState.DRAFT).target(ProjectState.OUTLINE_GENERATING)
                .event(ProjectEventType.GENERATE_OUTLINE)
                .guard(projectGuard.canGenerateOutline())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().startOutlineGeneration(projectId);
                })

            // OUTLINE_GENERATING -> OUTLINE_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_GENERATING).target(ProjectState.OUTLINE_REVIEW)
                .event(ProjectEventType._OUTLINE_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().onOutlineGenerated(projectId);
                })

            // OUTLINE_GENERATING -> OUTLINE_GENERATING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_GENERATING).target(ProjectState.OUTLINE_GENERATING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Outline generation failed: projectId={}", projectId);
                })

            // OUTLINE_GENERATING_FAILED -> OUTLINE_GENERATING (重试)
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_GENERATING_FAILED).target(ProjectState.OUTLINE_GENERATING)
                .event(ProjectEventType.GENERATE_OUTLINE)
                .guard(projectGuard.canGenerateOutline())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().startOutlineGeneration(projectId);
                })

            // OUTLINE_REVIEW -> OUTLINE_GENERATING (修改大纲)
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_REVIEW).target(ProjectState.OUTLINE_GENERATING)
                .event(ProjectEventType.REQUEST_OUTLINE_REVISION)
                .guard(projectGuard.canReviseOutline())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    String revisionNote = getHeader(context, "revisionNote");
                    String currentOutline = getHeader(context, "currentOutline");
                    scriptAction().reviseOutline(projectId, revisionNote, currentOutline);
                })

            // OUTLINE_REVIEW -> EPISODE_GENERATING
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_REVIEW).target(ProjectState.EPISODE_GENERATING)
                .event(ProjectEventType.GENERATE_EPISODES)
                .guard(projectGuard.canGenerateEpisodes())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    String chapter = getHeader(context, "chapter");
                    Integer episodeCount = getHeader(context, "episodeCount", Integer.class);
                    String modificationSuggestion = getHeader(context, "modificationSuggestion");
                    scriptAction().startEpisodeGeneration(projectId, chapter, episodeCount, modificationSuggestion);
                })

            // EPISODE_GENERATING -> SCRIPT_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_GENERATING).target(ProjectState.SCRIPT_REVIEW)
                .event(ProjectEventType._EPISODE_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().onEpisodesGenerated(projectId);
                })

            // EPISODE_GENERATING -> EPISODE_GENERATING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_GENERATING).target(ProjectState.EPISODE_GENERATING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Episode generation failed: projectId={}", projectId);
                })

            // EPISODE_GENERATING_FAILED -> EPISODE_GENERATING (重试)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_GENERATING_FAILED).target(ProjectState.EPISODE_GENERATING)
                .event(ProjectEventType.GENERATE_EPISODES)
                .guard(projectGuard.canGenerateEpisodes())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    String chapter = getHeader(context, "chapter");
                    Integer episodeCount = getHeader(context, "episodeCount", Integer.class);
                    String modificationSuggestion = getHeader(context, "modificationSuggestion");
                    scriptAction().startEpisodeGeneration(projectId, chapter, episodeCount, modificationSuggestion);
                })

            // SCRIPT_REVIEW -> EPISODE_SCRIPT_GENERATING
            .and()
            .withExternal()
                .source(ProjectState.SCRIPT_REVIEW).target(ProjectState.EPISODE_SCRIPT_GENERATING)
                .event(ProjectEventType.GENERATE_EPISODE_SCRIPT)
                .guard(projectGuard.canGenerateEpisodeScript())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().startEpisodeScriptGeneration(projectId);
                })

            // EPISODE_SCRIPT_GENERATING -> EPISODE_SCRIPT_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_SCRIPT_GENERATING).target(ProjectState.EPISODE_SCRIPT_REVIEW)
                .event(ProjectEventType._EPISODE_SCRIPT_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().onEpisodeScriptGenerated(projectId);
                })

            // EPISODE_SCRIPT_GENERATING -> EPISODE_SCRIPT_GENERATING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_SCRIPT_GENERATING).target(ProjectState.EPISODE_SCRIPT_GENERATING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Episode script generation failed: projectId={}", projectId);
                })

            // EPISODE_SCRIPT_GENERATING_FAILED -> EPISODE_SCRIPT_GENERATING (重试)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_SCRIPT_GENERATING_FAILED).target(ProjectState.EPISODE_SCRIPT_GENERATING)
                .event(ProjectEventType.GENERATE_EPISODE_SCRIPT)
                .guard(projectGuard.canGenerateEpisodeScript())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().startEpisodeScriptGeneration(projectId);
                })

            // EPISODE_SCRIPT_REVIEW -> EPISODE_SCRIPT_GENERATING (修改分集剧本)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_SCRIPT_REVIEW).target(ProjectState.EPISODE_SCRIPT_GENERATING)
                .event(ProjectEventType.REQUEST_EPISODE_SCRIPT_REVISION)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().startEpisodeScriptGeneration(projectId);
                })

            // EPISODE_SCRIPT_REVIEW -> CHARACTER_EXTRACTING (确认分集剧本)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_SCRIPT_REVIEW).target(ProjectState.CHARACTER_EXTRACTING)
                .event(ProjectEventType.CONFIRM_EPISODE_SCRIPT)
                .guard(projectGuard.canExtractCharacters())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().startExtraction(projectId);
                })

            // ===== 角色阶段转换 =====
            // CHARACTER_EXTRACTING -> CHARACTER_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_EXTRACTING).target(ProjectState.CHARACTER_REVIEW)
                .event(ProjectEventType._CHARACTERS_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().onExtractionComplete(projectId);
                })

            // CHARACTER_EXTRACTING -> CHARACTER_EXTRACTING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_EXTRACTING).target(ProjectState.CHARACTER_EXTRACTING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Character extraction failed: projectId={}", projectId);
                })

            // CHARACTER_EXTRACTING_FAILED -> CHARACTER_EXTRACTING (重试)
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_EXTRACTING_FAILED).target(ProjectState.CHARACTER_EXTRACTING)
                .event(ProjectEventType.EXTRACT_CHARACTERS)
                .guard(projectGuard.canExtractCharacters())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().startExtraction(projectId);
                })

            // CHARACTER_REVIEW -> IMAGE_GENERATING (确认角色)
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_REVIEW).target(ProjectState.IMAGE_GENERATING)
                .event(ProjectEventType.CONFIRM_CHARACTERS)
                .guard(projectGuard.canConfirmCharacters())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().confirmCharacters(projectId);
                })

            // ===== 素材阶段转换 =====
            // IMAGE_GENERATING -> ASSET_LOCKED (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_GENERATING).target(ProjectState.ASSET_LOCKED)
                .event(ProjectEventType._IMAGES_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    imageAction().onGenerationComplete(projectId);
                })

            // IMAGE_GENERATING -> IMAGE_GENERATING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_GENERATING).target(ProjectState.IMAGE_GENERATING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Image generation failed: projectId={}", projectId);
                })

            // IMAGE_GENERATING_FAILED -> IMAGE_GENERATING (重试)
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_GENERATING_FAILED).target(ProjectState.IMAGE_GENERATING)
                .event(ProjectEventType.GENERATE_IMAGES)
                .guard(projectGuard.canGenerateImages())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    imageAction().startGeneration(projectId);
                })

            // ===== 分镜生产阶段转换 =====
            // ASSET_LOCKED -> PRODUCING
            .and()
            .withExternal()
                .source(ProjectState.ASSET_LOCKED).target(ProjectState.PRODUCING)
                .event(ProjectEventType.START_PRODUCTION)
                .guard(projectGuard.canStartProduction())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    productionAction().startProduction(projectId);
                })

            // ASSET_LOCKED -> PRODUCING (备选: 生成文本)
            .and()
            .withExternal()
                .source(ProjectState.ASSET_LOCKED).target(ProjectState.PRODUCING)
                .event(ProjectEventType.GENERATE_TEXT)
                .guard(projectGuard.canStartProduction())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    productionAction().startProduction(projectId);
                })

            // PRODUCING -> PRODUCING (内部: 文本完成)
            .and()
            .withExternal()
                .source(ProjectState.PRODUCING).target(ProjectState.PRODUCING)
                .event(ProjectEventType._TEXT_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long panelId = getHeader(context, "panelId", Long.class);
                    log.info("Text done for project={}, panelId={}", projectId, panelId);
                })

            // PRODUCING -> PRODUCING (内部: 图像完成)
            .and()
            .withExternal()
                .source(ProjectState.PRODUCING).target(ProjectState.PRODUCING)
                .event(ProjectEventType._IMAGE_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long panelId = getHeader(context, "panelId", Long.class);
                    log.info("Image done for project={}, panelId={}", projectId, panelId);
                })

            // PRODUCING -> PRODUCING (内部: 视频完成)
            .and()
            .withExternal()
                .source(ProjectState.PRODUCING).target(ProjectState.PRODUCING)
                .event(ProjectEventType._VIDEO_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long panelId = getHeader(context, "panelId", Long.class);
                    log.info("Video done for project={}, panelId={}", projectId, panelId);
                })

            // PRODUCING -> STORYBOARD_REVIEW (确认生产)
            .and()
            .withExternal()
                .source(ProjectState.PRODUCING).target(ProjectState.STORYBOARD_REVIEW)
                .event(ProjectEventType.CONFIRM_PRODUCTION)
                .guard(projectGuard.canConfirmProduction())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    productionAction().confirmProduction(projectId);
                })

            // ===== 分镜阶段转换 =====
            // STORYBOARD_GENERATING -> STORYBOARD_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.STORYBOARD_GENERATING).target(ProjectState.STORYBOARD_REVIEW)
                .event(ProjectEventType._STORYBOARD_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    storyboardAction().onStoryboardGenerated(projectId);
                })

            // STORYBOARD_GENERATING -> STORYBOARD_GENERATING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.STORYBOARD_GENERATING).target(ProjectState.STORYBOARD_GENERATING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Storyboard generation failed: projectId={}", projectId);
                })

            // STORYBOARD_GENERATING_FAILED -> STORYBOARD_GENERATING (重试)
            .and()
            .withExternal()
                .source(ProjectState.STORYBOARD_GENERATING_FAILED).target(ProjectState.STORYBOARD_GENERATING)
                .event(ProjectEventType.START_STORYBOARD)
                .guard(projectGuard.canStartStoryboard())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    storyboardAction().startStoryboard(projectId);
                })

            // STORYBOARD_REVIEW -> STORYBOARD_GENERATING (修改分镜)
            .and()
            .withExternal()
                .source(ProjectState.STORYBOARD_REVIEW).target(ProjectState.STORYBOARD_GENERATING)
                .event(ProjectEventType.REVISE_STORYBOARD)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    String feedback = getHeader(context, "feedback");
                    storyboardAction().reviseStoryboard(projectId, feedback);
                })

            // STORYBOARD_REVIEW -> MERGING (确认分镜)
            .and()
            .withExternal()
                .source(ProjectState.STORYBOARD_REVIEW).target(ProjectState.MERGING)
                .event(ProjectEventType.CONFIRM_STORYBOARD)
                .guard(projectGuard.canConfirmStoryboard())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    storyboardAction().confirmStoryboard(projectId);
                })

            // ===== 视频拼接阶段转换 =====
            // MERGING -> COMPLETED (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.MERGING).target(ProjectState.COMPLETED)
                .event(ProjectEventType._MERGE_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Merge completed: projectId={}", projectId);
                    // Video merge completion - state persistence handled by merge service
                })

            // MERGING -> MERGING_FAILED (失败)
            .and()
            .withExternal()
                .source(ProjectState.MERGING).target(ProjectState.MERGING_FAILED)
                .event(ProjectEventType._TASK_FAILED)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Merge failed: projectId={}", projectId);
                })

            // MERGING_FAILED -> MERGING (重试)
            .and()
            .withExternal()
                .source(ProjectState.MERGING_FAILED).target(ProjectState.MERGING)
                .event(ProjectEventType.START_MERGE)
                .guard(projectGuard.canStartMerge())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    log.info("Retry merge: projectId={}", projectId);
                    // Merge retry action to be implemented
                })

            // ===== 回滚转换 =====
            // OUTLINE_REVIEW -> DRAFT
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_REVIEW).target(ProjectState.DRAFT)
                .event(ProjectEventType.ROLLBACK_OUTLINE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.DRAFT);
                })

            // SCRIPT_REVIEW -> EPISODE_GENERATING
            .and()
            .withExternal()
                .source(ProjectState.SCRIPT_REVIEW).target(ProjectState.EPISODE_GENERATING)
                .event(ProjectEventType.ROLLBACK_EPISODE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.EPISODE_GENERATING);
                })

            // EPISODE_SCRIPT_REVIEW -> SCRIPT_REVIEW
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_SCRIPT_REVIEW).target(ProjectState.SCRIPT_REVIEW)
                .event(ProjectEventType.ROLLBACK_EPISODE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.SCRIPT_REVIEW);
                })

            // CHARACTER_REVIEW -> EPISODE_SCRIPT_REVIEW
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_REVIEW).target(ProjectState.EPISODE_SCRIPT_REVIEW)
                .event(ProjectEventType.ROLLBACK_CHARACTER)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.EPISODE_SCRIPT_REVIEW);
                })

            // IMAGE_GENERATING -> CHARACTER_REVIEW
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_GENERATING).target(ProjectState.CHARACTER_REVIEW)
                .event(ProjectEventType.ROLLBACK_IMAGE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.CHARACTER_REVIEW);
                })

            // PRODUCING -> IMAGE_GENERATING
            .and()
            .withExternal()
                .source(ProjectState.PRODUCING).target(ProjectState.IMAGE_GENERATING)
                .event(ProjectEventType.ROLLBACK_STORYBOARD)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.IMAGE_GENERATING);
                })

            // STORYBOARD_REVIEW -> PRODUCING
            .and()
            .withExternal()
                .source(ProjectState.STORYBOARD_REVIEW).target(ProjectState.PRODUCING)
                .event(ProjectEventType.ROLLBACK_STORYBOARD)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.PRODUCING);
                })

            // MERGING -> STORYBOARD_REVIEW
            .and()
            .withExternal()
                .source(ProjectState.MERGING).target(ProjectState.STORYBOARD_REVIEW)
                .event(ProjectEventType.ROLLBACK_STORYBOARD)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    rollbackAction().rollbackTo(projectId, ProjectState.STORYBOARD_REVIEW);
                })

        ;
    }

    // ===== 辅助方法 =====

    private String getHeader(StateContext<ProjectState, ProjectEventType> context, String key) {
        Object value = context.getMessageHeaders().get(key);
        return value != null ? value.toString() : null;
    }

    private <T> T getHeader(StateContext<ProjectState, ProjectEventType> context, String key, Class<T> type) {
        Object value = context.getMessageHeaders().get(key);
        if (value != null && type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }
}
