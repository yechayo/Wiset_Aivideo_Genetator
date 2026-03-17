package com.comic.service.character;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色图片生成服务
 * 负责生成角色的九宫格表情图和三视图（均为 grid 大全图模式）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterImageGenerationService {

    private final CharacterRepository characterRepository;
    private final ImageGenerationService imageGenerationService;
    private final CharacterPromptManager characterPromptManager;

    /**
     * 生成九宫格表情大全图
     * 注意：不加 @Transactional，避免 AI 生图耗时期间锁住数据库
     */
    public void generateExpressionSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        // 配角跳过表情生成
        if ("配角".equals(character.getRole())) {
            log.info("配角跳过表情生成: charId={}, name={}", charId, character.getName());
            throw new BusinessException("配角不需要生成表情图");
        }

        // 检查是否已在生成中
        if (Boolean.TRUE.equals(character.getIsGeneratingExpression())) {
            throw new BusinessException("表情图正在生成中，请勿重复提交");
        }

        // 更新状态为生成中
        character.setExpressionStatus("GENERATING");
        character.setIsGeneratingExpression(true);
        character.setExpressionError(null);
        characterRepository.updateById(character);

        try {
            log.info("开始生成九宫格大全图: charId={}, name={}, visualStyle={}",
                     charId, character.getName(), character.getVisualStyle());

            // 获取视觉风格
            CharacterPromptManager.VisualStyle visualStyle = CharacterPromptManager.VisualStyle.D_3D;
            if (character.getVisualStyle() != null) {
                visualStyle = CharacterPromptManager.VisualStyle.fromFrontendValue(character.getVisualStyle());
            }

            // 构建提示词并生成大全图
            String prompt = characterPromptManager.buildExpressionGridPrompt(character, visualStyle);
            log.info("九宫格提示词长度: {} char", prompt.length());

            // 优先使用三视图作为参考图生成表情
            String imageUrl;
            if (character.getThreeViewGridUrl() != null && !character.getThreeViewGridUrl().isEmpty()) {
                log.info("使用三视图作为参考图生成表情: {}", character.getThreeViewGridUrl());
                imageUrl = imageGenerationService.generateWithReference(
                    prompt, character.getThreeViewGridUrl(), 2048, 2048);
            } else {
                imageUrl = imageGenerationService.generate(prompt, 2048, 2048, visualStyle.getCode().toLowerCase());
            }
            log.info("九宫格大全图生成完成: {}", imageUrl);

            // 保存结果
            character.setExpressionGridUrl(imageUrl);
            character.setExpressionGridPrompt(prompt);
            character.setExpressionStatus("COMPLETED");
            character.setIsGeneratingExpression(false);
            characterRepository.updateById(character);

            log.info("九宫格大全图生成完成: charId={}", charId);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("九宫格大全图生成失败: charId={}", charId, e);
            character.setExpressionStatus("FAILED");
            character.setExpressionError(e.getMessage());
            character.setIsGeneratingExpression(false);
            characterRepository.updateById(character);
            throw new BusinessException("九宫格大全图生成失败: " + e.getMessage());
        }
    }

    /**
     * 生成三视图大全图
     * 注意：不加 @Transactional，避免 AI 生图耗时期间锁住数据库
     */
    public void generateThreeViewSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        // 检查是否已在生成中
        if (Boolean.TRUE.equals(character.getIsGeneratingThreeView())) {
            throw new BusinessException("三视图正在生成中，请勿重复提交");
        }

        // 更新状态为生成中
        character.setThreeViewStatus("GENERATING");
        character.setIsGeneratingThreeView(true);
        character.setThreeViewError(null);
        characterRepository.updateById(character);

        try {
            log.info("开始生成三视图大全图: charId={}, name={}, visualStyle={}",
                     charId, character.getName(), character.getVisualStyle());

            // 获取视觉风格
            CharacterPromptManager.VisualStyle visualStyle = CharacterPromptManager.VisualStyle.D_3D;
            if (character.getVisualStyle() != null) {
                visualStyle = CharacterPromptManager.VisualStyle.fromFrontendValue(character.getVisualStyle());
            }

            // 构建提示词并生成大全图
            String prompt = characterPromptManager.buildThreeViewGridPrompt(character, visualStyle);
            log.info("三视图提示词长度: {} char", prompt.length());

            String imageUrl = imageGenerationService.generate(prompt, 1024, 1536, visualStyle.getCode().toLowerCase());
            log.info("三视图大全图生成完成: {}", imageUrl);

            // 保存结果
            character.setThreeViewGridUrl(imageUrl);
            character.setThreeViewGridPrompt(prompt);
            character.setThreeViewStatus("COMPLETED");
            character.setIsGeneratingThreeView(false);
            characterRepository.updateById(character);

            log.info("三视图大全图生成完成: charId={}", charId);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("三视图大全图生成失败: charId={}", charId, e);
            character.setThreeViewStatus("FAILED");
            character.setThreeViewError(e.getMessage());
            character.setIsGeneratingThreeView(false);
            characterRepository.updateById(character);
            throw new BusinessException("三视图大全图生成失败: " + e.getMessage());
        }
    }

    /**
     * 一键生成全部（表情+三视图）
     * 注意：不加 @Transactional，避免 AI 生图耗时期间锁住数据库
     */
    public void generateAll(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        log.info("开始一键生成: charId={}, name={}, role={}",
                 charId, character.getName(), character.getRole());

        // 配角只生成三视图
        if ("配角".equals(character.getRole())) {
            log.info("配角跳过表情，直接生成三视图: charId={}", charId);
            generateThreeViewSheet(charId);
        } else {
            // 先生成三视图，再生成表情（表情以三视图为参考）
            generateThreeViewSheet(charId);
            generateExpressionSheet(charId);
        }

        log.info("一键生成完成: charId={}", charId);
    }

    /**
     * 重试生成
     */
    public void retryGeneration(String charId, String type) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        log.info("重试生成: charId={}, type={}", charId, type);

        if ("expression".equalsIgnoreCase(type)) {
            character.setExpressionStatus(null);
            character.setExpressionError(null);
            character.setExpressionGridUrl(null);
            character.setExpressionGridPrompt(null);
            characterRepository.updateById(character);
            generateExpressionSheet(charId);
        } else if ("threeView".equalsIgnoreCase(type)) {
            // 重置三视图时，同时清除表情相关字段（旧表情已失去参考基础）
            character.setThreeViewStatus(null);
            character.setThreeViewError(null);
            character.setThreeViewGridUrl(null);
            character.setThreeViewGridPrompt(null);
            character.setExpressionGridUrl(null);
            character.setExpressionGridPrompt(null);
            character.setExpressionStatus(null);
            character.setExpressionError(null);
            characterRepository.updateById(character);
            generateThreeViewSheet(charId);
        } else {
            throw new BusinessException("无效的生成类型: " + type);
        }
    }

    /**
     * 设置角色的视觉风格
     */
    @Transactional
    public void setVisualStyle(String charId, String visualStyle) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        try {
            CharacterPromptManager.VisualStyle.fromFrontendValue(visualStyle);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的视觉风格: " + visualStyle);
        }

        character.setVisualStyle(visualStyle);
        characterRepository.updateById(character);
        log.info("设置视觉风格: charId={}, visualStyle={}", charId, visualStyle);
    }
}
