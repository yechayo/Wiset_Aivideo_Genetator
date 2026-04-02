package com.comic.statemachine.config;

import com.comic.statemachine.action.ProjectMilestoneAction;
import com.comic.statemachine.enums.ProjectMilestone;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import com.comic.statemachine.guard.ProjectMilestoneGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

import static com.comic.statemachine.enums.ProjectMilestone.*;
import static com.comic.statemachine.enums.ProjectMilestoneEventType.*;

/**
 * 里程碑状态机配置（6 个状态）
 *
 * 只管理 milestone 确认/回滚。生成中状态由 Service + Redis 管理，不经过状态机。
 * 使用独立的 Factory 名称 "projectMilestoneFactory"，与旧配置并存。
 */
@Slf4j
@Configuration
@EnableStateMachineFactory(name = "projectMilestoneFactory")
public class ProjectMilestoneStateMachineConfig
        extends StateMachineConfigurerAdapter<ProjectMilestone, ProjectMilestoneEventType> {

    private final ApplicationContext applicationContext;
    private final ProjectMilestoneGuard guard;

    public ProjectMilestoneStateMachineConfig(ApplicationContext applicationContext,
                                               ProjectMilestoneGuard guard) {
        this.applicationContext = applicationContext;
        this.guard = guard;
    }

    private ProjectMilestoneAction action() {
        return applicationContext.getBean(ProjectMilestoneAction.class);
    }

    @Override
    public void configure(StateMachineStateConfigurer<ProjectMilestone, ProjectMilestoneEventType> states) throws Exception {
        states
            .withStates()
                .initial(DRAFT)
                .states(EnumSet.allOf(ProjectMilestone.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<ProjectMilestone, ProjectMilestoneEventType> transitions) throws Exception {
        transitions
            // ===== 确认操作 =====
            .withExternal()
                .source(DRAFT).target(OUTLINE_CONFIRMED)
                .event(CONFIRM_OUTLINE)
                .guard(guard.canConfirmOutline())
                .action(action().confirmOutline())
            .and()
            .withExternal()
                .source(OUTLINE_CONFIRMED).target(EPISODE_CONFIRMED)
                .event(CONFIRM_EPISODE)
                .guard(guard.canConfirmEpisodes())
                .action(action().confirmEpisode())
            .and()
            .withExternal()
                .source(EPISODE_CONFIRMED).target(ASSET_CONFIRMED)
                .event(CONFIRM_ASSETS)
                .guard(guard.canConfirmAssets())
                .action(action().confirmAssets())
            .and()
            .withExternal()
                .source(ASSET_CONFIRMED).target(PANEL_CONFIRMED)
                .event(CONFIRM_PANELS)
                .guard(guard.canConfirmPanels())
                .action(action().confirmPanels())

            // ===== 拼接完成 =====
            .and()
            .withExternal()
                .source(PANEL_CONFIRMED).target(COMPLETED)
                .event(_ASSEMBLE_DONE)
                .action(action().assembleComplete())

            // ===== 回滚操作 =====
            .and()
            .withExternal()
                .source(OUTLINE_CONFIRMED).target(DRAFT)
                .event(ROLLBACK_TO_DRAFT)
                .action(action().rollbackToDraft())
            .and()
            .withExternal()
                .source(EPISODE_CONFIRMED).target(OUTLINE_CONFIRMED)
                .event(ROLLBACK_TO_OUTLINE_CONFIRMED)
                .action(action().rollbackToOutlineConfirmed())
            .and()
            .withExternal()
                .source(ASSET_CONFIRMED).target(EPISODE_CONFIRMED)
                .event(ROLLBACK_TO_EPISODE_CONFIRMED)
                .action(action().rollbackToEpisodeConfirmed())
            .and()
            .withExternal()
                .source(PANEL_CONFIRMED).target(ASSET_CONFIRMED)
                .event(ROLLBACK_TO_ASSET_CONFIRMED)
                .action(action().rollbackToAssetConfirmed())
        ;
    }
}
