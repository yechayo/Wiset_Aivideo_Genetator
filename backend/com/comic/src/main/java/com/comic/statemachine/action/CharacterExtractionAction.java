package com.comic.statemachine.action;

import com.comic.dto.model.CharacterDraftModel;
import com.comic.service.character.CharacterExtractService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 角色提取相关的 Action
 * 直接调用 CharacterExtractService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterExtractionAction {

    private final CharacterExtractService characterExtractService;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    /**
     * 开始提取角色（异步）
     */
    public void startExtraction(String projectId) {
        log.info("Action: Start character extraction for project={}", projectId);
        eventPublisher.publishTaskStart(projectId, "character_extraction");

        CompletableFuture.runAsync(() -> {
            try {
                // 直接调用现有 Service（会自动更新状态到 CHARACTER_REVIEW）
                characterExtractService.extractCharacters(projectId);
                // 发送内部事件
                stateMachineService.sendEvent(projectId, ProjectEventType._CHARACTERS_DONE);
                eventPublisher.publishTaskComplete(projectId, "character_extraction", null);
            } catch (Exception e) {
                log.error("Character extraction failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "角色提取失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.CHARACTER_EXTRACTING_FAILED);
            }
        });
    }

    /**
     * 角色提取完成
     */
    public void onExtractionComplete(String projectId) {
        log.info("Action: Character extraction completed for project={}", projectId);
    }

    /**
     * 确认角色
     */
    public void confirmCharacters(String projectId) {
        log.info("Action: Confirm characters for project={}", projectId);
        try {
            characterExtractService.confirmCharacters(projectId);
            eventPublisher.publishTaskComplete(projectId, "character_confirmation", null);
        } catch (Exception e) {
            log.error("Character confirmation failed: projectId={}", projectId, e);
            eventPublisher.publishFailure(projectId, "角色确认失败: " + e.getMessage());
        }
    }

    /**
     * 更新角色
     */
    public void updateCharacter(String projectId, String charId, CharacterDraftModel dto) {
        log.info("Action: Update character for project={}, charId={}", projectId, charId);
        try {
            characterExtractService.updateCharacter(charId, dto);
            eventPublisher.publishTaskComplete(projectId, "character_update", null);
        } catch (Exception e) {
            log.error("Character update failed: projectId={}, charId={}", projectId, charId, e);
            eventPublisher.publishFailure(projectId, "角色更新失败: " + e.getMessage());
        }
    }
}
