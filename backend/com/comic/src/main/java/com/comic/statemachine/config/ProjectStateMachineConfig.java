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

    private PanelAction panelAction() {
        return applicationContext.getBean(PanelAction.class);
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

            // OUTLINE_GENERATING_FAILED -> OUTLINE_GENERATING (重试大纲生成)
            .and()
            .withExternal()
                .source(ProjectState.OUTLINE_GENERATING_FAILED).target(ProjectState.OUTLINE_GENERATING)
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
                .event(ProjectEventType._SCRIPT_OUTLINE_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().onOutlineGenerated(projectId);
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

            // OUTLINE_REVIEW -> EPISODE_GENERATING (生成剧集)
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

            // EPISODE_GENERATING_FAILED -> EPISODE_GENERATING (重试生成剧集)
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

            // EPISODE_GENERATING -> SCRIPT_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.EPISODE_GENERATING).target(ProjectState.SCRIPT_REVIEW)
                .event(ProjectEventType._EPISODES_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().onEpisodesGenerated(projectId);
                })

            // SCRIPT_REVIEW -> SCRIPT_CONFIRMED (确认剧本)
            .and()
            .withExternal()
                .source(ProjectState.SCRIPT_REVIEW).target(ProjectState.SCRIPT_CONFIRMED)
                .event(ProjectEventType.CONFIRM_SCRIPT)
                .guard(projectGuard.canConfirmScript())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    scriptAction().confirmScript(projectId);
                })

            // ===== 角色阶段转换 =====
            // SCRIPT_CONFIRMED -> CHARACTER_EXTRACTING
            .and()
            .withExternal()
                .source(ProjectState.SCRIPT_CONFIRMED).target(ProjectState.CHARACTER_EXTRACTING)
                .event(ProjectEventType.EXTRACT_CHARACTERS)
                .guard(projectGuard.canExtractCharacters())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().startExtraction(projectId);
                })

            // CHARACTER_EXTRACTING_FAILED -> CHARACTER_EXTRACTING (重试角色提取)
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_EXTRACTING_FAILED).target(ProjectState.CHARACTER_EXTRACTING)
                .event(ProjectEventType.EXTRACT_CHARACTERS)
                .guard(projectGuard.canExtractCharacters())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().startExtraction(projectId);
                })

            // CHARACTER_EXTRACTING -> CHARACTER_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_EXTRACTING).target(ProjectState.CHARACTER_REVIEW)
                .event(ProjectEventType._CHARACTERS_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().onExtractionComplete(projectId);
                })

            // CHARACTER_REVIEW -> CHARACTER_CONFIRMED
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_REVIEW).target(ProjectState.CHARACTER_CONFIRMED)
                .event(ProjectEventType.CONFIRM_CHARACTERS)
                .guard(projectGuard.canConfirmCharacters())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    characterAction().confirmCharacters(projectId);
                })

            // ===== 图像阶段转换 =====
            // CHARACTER_CONFIRMED -> IMAGE_GENERATING
            .and()
            .withExternal()
                .source(ProjectState.CHARACTER_CONFIRMED).target(ProjectState.IMAGE_GENERATING)
                .event(ProjectEventType.GENERATE_IMAGES)
                .guard(projectGuard.canGenerateImages())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    imageAction().startGeneration(projectId);
                })

            // IMAGE_GENERATING_FAILED -> IMAGE_GENERATING (重试图像生成)
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_GENERATING_FAILED).target(ProjectState.IMAGE_GENERATING)
                .event(ProjectEventType.GENERATE_IMAGES)
                .guard(projectGuard.canGenerateImages())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    imageAction().startGeneration(projectId);
                })

            // IMAGE_GENERATING -> IMAGE_REVIEW (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_GENERATING).target(ProjectState.IMAGE_REVIEW)
                .event(ProjectEventType._IMAGES_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    imageAction().onGenerationComplete(projectId);
                })

            // IMAGE_REVIEW -> ASSET_LOCKED
            .and()
            .withExternal()
                .source(ProjectState.IMAGE_REVIEW).target(ProjectState.ASSET_LOCKED)
                .event(ProjectEventType.CONFIRM_IMAGES)
                .guard(projectGuard.canConfirmImages())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    imageAction().confirmImages(projectId);
                })

            // ===== 分镜阶段转换（包含完整生产流程） =====
            // ASSET_LOCKED -> PANEL_GENERATING
            // PANEL_GENERATING 包含：分镜文本 → 背景图 → 融合图 → 视频
            .and()
            .withExternal()
                .source(ProjectState.ASSET_LOCKED).target(ProjectState.PANEL_GENERATING)
                .event(ProjectEventType.START_PANEL)
                .guard(projectGuard.canStartPanel())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long episodeId = getHeader(context, "episodeId", Long.class);
                    panelAction().startFullProduction(projectId, episodeId);
                })

            // PANEL_GENERATING_FAILED -> PANEL_GENERATING (重试分镜生成)
            .and()
            .withExternal()
                .source(ProjectState.PANEL_GENERATING_FAILED).target(ProjectState.PANEL_GENERATING)
                .event(ProjectEventType.START_PANEL)
                .guard(projectGuard.canStartPanel())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long episodeId = getHeader(context, "episodeId", Long.class);
                    panelAction().startFullProduction(projectId, episodeId);
                })

            // PANEL_GENERATING -> PANEL_REVIEW (内部事件，单集分镜生产完成)
            .and()
            .withExternal()
                .source(ProjectState.PANEL_GENERATING).target(ProjectState.PANEL_REVIEW)
                .event(ProjectEventType._PANEL_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    panelAction().onEpisodeProductionComplete(projectId);
                })

            // PANEL_REVIEW -> PANEL_GENERATING (修改分镜)
            .and()
            .withExternal()
                .source(ProjectState.PANEL_REVIEW).target(ProjectState.PANEL_GENERATING)
                .event(ProjectEventType.REVISE_PANEL)
                .guard(projectGuard.canRevisePanel())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long episodeId = getHeader(context, "episodeId", Long.class);
                    String feedback = getHeader(context, "feedback");
                    panelAction().reviseAndReproduce(projectId, episodeId, feedback);
                })

            // PANEL_REVIEW -> PANEL_CONFIRMED (确认当前分镜，继续下一集)
            .and()
            .withExternal()
                .source(ProjectState.PANEL_REVIEW).target(ProjectState.PANEL_CONFIRMED)
                .event(ProjectEventType.CONFIRM_PANEL)
                .guard(projectGuard.canConfirmPanel())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long episodeId = getHeader(context, "episodeId", Long.class);
                    panelAction().confirmAndContinue(projectId, episodeId);
                })

            // PANEL_CONFIRMED -> PANEL_GENERATING (继续生成下一集)
            .and()
            .withExternal()
                .source(ProjectState.PANEL_CONFIRMED).target(ProjectState.PANEL_GENERATING)
                .event(ProjectEventType.START_PANEL)
                .guard(projectGuard.canStartPanel())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    Long episodeId = getHeader(context, "episodeId", Long.class);
                    panelAction().startFullProduction(projectId, episodeId);
                })

            // ===== 视频剪辑阶段转换 =====
            // PANEL_CONFIRMED -> VIDEO_ASSEMBLING (所有分镜确认后)
            .and()
            .withExternal()
                .source(ProjectState.PANEL_CONFIRMED).target(ProjectState.VIDEO_ASSEMBLING)
                .event(ProjectEventType.START_VIDEO_ASSEMBLY)
                .guard(projectGuard.canStartVideoAssembly())
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    panelAction().startVideoAssembly(projectId);
                })

            // VIDEO_ASSEMBLING -> COMPLETED (内部事件)
            .and()
            .withExternal()
                .source(ProjectState.VIDEO_ASSEMBLING).target(ProjectState.COMPLETED)
                .event(ProjectEventType._VIDEO_ASSEMBLY_DONE)
                .action(context -> {
                    String projectId = getHeader(context, "projectId");
                    panelAction().onVideoAssemblyComplete(projectId);
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
