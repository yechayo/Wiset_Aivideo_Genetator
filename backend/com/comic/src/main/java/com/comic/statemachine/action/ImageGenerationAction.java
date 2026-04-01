package com.comic.statemachine.action;

import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 素材生成相关的 Action
 * 直接调用 CharacterImageGenerationService 生成角色图像
 */
@Slf4j
@Component
public class ImageGenerationAction {

    private final CharacterImageGenerationService characterImageGenerationService;
    private final CharacterRepository characterRepository;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    public ImageGenerationAction(
            @Lazy CharacterImageGenerationService characterImageGenerationService,
            CharacterRepository characterRepository,
            ProjectStateMachineService stateMachineService,
            StateChangeEventPublisher eventPublisher) {
        this.characterImageGenerationService = characterImageGenerationService;
        this.characterRepository = characterRepository;
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 开始生成图像（异步）
     */
    public void startGeneration(String projectId) {
        // 获取项目的所有角色
        List<Character> characters = characterRepository.findByProjectId(projectId);
        List<String> charIds = new ArrayList<>();
        for (Character character : characters) {
            if (character.getCharacterInfo() != null) {
                Object charId = character.getCharacterInfo().get("charId");
                if (charId != null) {
                    charIds.add(charId.toString());
                }
            }
        }

        log.info("Action: Start image generation for project={}, count={}", projectId, charIds.size());
        stateMachineService.persistState(projectId, ProjectState.IMAGE_GENERATING);
        eventPublisher.publishTaskStart(projectId, "image_generation");

        CompletableFuture.runAsync(() -> {
            try {
                int total = charIds.size();
                int successCount = 0;
                int failCount = 0;

                for (String charId : charIds) {
                    try {
                        characterImageGenerationService.generateAll(charId);
                        successCount++;

                        int progress = (int) ((successCount + failCount) * 100.0 / total);
                        eventPublisher.publishProgress(projectId, progress,
                                String.format("已生成 %d/%d 个角色图像", successCount, total));

                    } catch (Exception e) {
                        log.warn("Character image generation failed: charId={}, error={}", charId, e.getMessage());
                        failCount++;
                    }
                }

                if (failCount > 0) {
                    log.error("Some image generations failed: projectId={}, success={}, fail={}",
                            projectId, successCount, failCount);
                    eventPublisher.publishFailure(projectId,
                            String.format("图像生成失败: 成功 %d, 失败 %d", successCount, failCount));
                    stateMachineService.persistState(projectId, ProjectState.IMAGE_GENERATING_FAILED);
                    stateMachineService.resetStateMachine(projectId, ProjectState.IMAGE_GENERATING_FAILED);
                } else {
                    stateMachineService.sendEvent(projectId, ProjectEventType._IMAGES_DONE);
                    eventPublisher.publishTaskComplete(projectId, "image_generation",
                            String.format("成功: %d/%d", successCount, total));
                }

            } catch (Exception e) {
                log.error("Image generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "图像生成失败: " + e.getMessage());
                stateMachineService.persistState(projectId, ProjectState.IMAGE_GENERATING_FAILED);
                stateMachineService.resetStateMachine(projectId, ProjectState.IMAGE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 图像生成完成
     */
    public void onGenerationComplete(String projectId) {
        log.info("Action: Image generation completed for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.ASSET_LOCKED);
    }

    /**
     * 确认图像（直接进入 ASSET_LOCKED）
     */
    public void confirmImages(String projectId) {
        log.info("Action: Confirm images for project={}", projectId);
        stateMachineService.persistState(projectId, ProjectState.ASSET_LOCKED);
        eventPublisher.publishTaskComplete(projectId, "image_confirmation", null);
    }
}
