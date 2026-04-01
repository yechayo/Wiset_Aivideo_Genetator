package com.comic.statemachine.action;

import com.comic.service.character.CharacterExtractService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 角色提取相关的 Action
 */
@Slf4j
@Component
public class CharacterExtractionAction {

    private final CharacterExtractService characterExtractService;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    public CharacterExtractionAction(
            @Lazy CharacterExtractService characterExtractService,
            ProjectStateMachineService stateMachineService,
            StateChangeEventPublisher eventPublisher) {
        this.characterExtractService = characterExtractService;
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 开始提取角色（异步）
     */
    public void startExtraction(String projectId) {
        log.info("Action: Start character extraction for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.CHARACTER_EXTRACTING);
        eventPublisher.publishTaskStart(projectId, "character_extraction");

        CompletableFuture.runAsync(() -> {
            try {
                characterExtractService.extractCharacters(projectId);
                stateMachineService.sendEvent(projectId, ProjectEventType._CHARACTERS_DONE);
                eventPublisher.publishTaskComplete(projectId, "character_extraction", null);
            } catch (Exception e) {
                log.error("Character extraction failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "角色提取失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.CHARACTER_EXTRACTING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.CHARACTER_EXTRACTING_FAILED);
            }
        });
    }

    /**
     * 角色提取完成
     */
    public void onExtractionComplete(String projectId) {
        log.info("Action: Character extraction completed for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.CHARACTER_REVIEW);
    }

    /**
     * 确认角色，进入图像生成阶段
     */
    public void confirmCharacters(String projectId) {
        log.info("Action: Confirm characters for project={}", projectId);
        try {
            characterExtractService.confirmCharacters(projectId);
            stateMachineService.persistState(projectId, ProjectState.IMAGE_GENERATING);
            eventPublisher.publishTaskComplete(projectId, "character_confirmation", null);
        } catch (Exception e) {
            log.error("Character confirmation failed: projectId={}", projectId, e);
            eventPublisher.publishFailure(projectId, "角色确认失败: " + e.getMessage());
        }
    }
}
