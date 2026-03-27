package com.comic.statemachine.action;

import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 图像生成相关的 Action
 * 直接调用 CharacterImageGenerationService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenerationAction {

    private final CharacterImageGenerationService characterImageGenerationService;
    private final CharacterRepository characterRepository;
    private final ProjectStateMachineService stateMachineService;
    private final StateChangeEventPublisher eventPublisher;

    /**
     * 开始生成图像（异步）
     */
    public void startGeneration(String projectId) {
        // 获取项目的所有角色
        List<Character> characters = characterRepository.findByProjectId(projectId);
        List<String> charIds = new java.util.ArrayList<>();
        for (Character character : characters) {
            charIds.add(character.getCharId());
        }

        log.info("Action: Start image generation for project={}, count={}", projectId, charIds.size());
        eventPublisher.publishTaskStart(projectId, "image_generation");

        CompletableFuture.runAsync(() -> {
            try {
                int total = charIds.size();
                int successCount = 0;
                int failCount = 0;

                for (String charId : charIds) {
                    try {
                        // 调用现有 Service
                        characterImageGenerationService.generateAll(charId);
                        successCount++;

                        // 发布进度
                        int progress = (int) ((successCount + failCount) * 100.0 / total);
                        eventPublisher.publishProgress(projectId, progress,
                                String.format("已生成 %d/%d 个角色图像", successCount, total));

                    } catch (Exception e) {
                        log.warn("Character image generation failed: charId={}, error={}", charId, e.getMessage());
                        failCount++;
                    }
                }

                // 全部完成后发送事件
                stateMachineService.sendEvent(projectId, ProjectEventType._IMAGES_DONE);
                eventPublisher.publishTaskComplete(projectId, "image_generation",
                        String.format("成功: %d, 失败: %d", successCount, failCount));

                if (failCount > 0) {
                    log.warn("Some images failed to generate: projectId={}, success={}, fail={}",
                            projectId, successCount, failCount);
                }

            } catch (Exception e) {
                log.error("Image generation failed: projectId={}", projectId, e);
                eventPublisher.publishFailure(projectId, "图像生成失败: " + e.getMessage());
                stateMachineService.resetStateMachine(projectId, ProjectState.IMAGE_GENERATING_FAILED);
            }
        });
    }

    /**
     * 图像生成完成
     */
    public void onGenerationComplete(String projectId) {
        log.info("Action: Image generation completed for project={}", projectId);
    }

    /**
     * 确认图像
     */
    public void confirmImages(String projectId) {
        log.info("Action: Confirm images for project={}", projectId);
        // 图像确认后，素材被锁定
        // 这里可以添加额外的确认逻辑
        eventPublisher.publishTaskComplete(projectId, "image_confirmation", null);
    }
}
