package com.comic.service.character;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.PromptBuilder;
import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.ExpressionImage;
import com.comic.dto.ThreeViewImage;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色图片生成服务
 * 负责生成角色的九宫格表情图和三视图
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterImageGenerationService {

    private final CharacterRepository characterRepository;
    private final ImageGenerationService imageGenerationService;
    private final PromptBuilder promptBuilder;
    private final CharacterPromptManager characterPromptManager;
    private final ObjectMapper objectMapper;

    // 表情类型定义
    private static final String[] EXPRESSION_TYPES = {
        "开心", "悲伤", "愤怒", "惊讶", "恐惧", "厌恶", "轻蔑", "害羞", "平静"
    };

    // 视图类型定义
    private static final String[] VIEW_TYPES = {"正面", "侧面", "背面"};

    /**
     * 生成九宫格表情图（升级版）
     * 根据 generationMode 选择生成方式
     */
    @Transactional
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

        // 根据生成模式选择生成方式
        String mode = character.getGenerationMode();
        if (mode == null) {
            mode = "grid"; // 默认使用新模式
        }

        if ("grid".equals(mode)) {
            // 新模式：一次性生成大全图
            log.info("使用grid模式生成九宫格: charId={}", charId);
            generateExpressionGrid(charId);
        } else {
            // 旧模式：逐张生成
            log.info("使用multiple模式生成九宫格: charId={}", charId);
            generateExpressionMultiple(charId);
        }
    }

    /**
     * 新方法：生成九宫格大全图（一次性生成）
     */
    @Transactional
    private void generateExpressionGrid(String charId) {
        Character character = characterRepository.findByCharId(charId);

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
                visualStyle = CharacterPromptManager.VisualStyle.valueOf(character.getVisualStyle());
            }

            // 使用新提示词管理器构建提示词
            String prompt = characterPromptManager.buildExpressionGridPrompt(character, visualStyle);
            log.info("九宫格提示词长度: {} char", prompt.length());

            // 生成大全图（使用更大尺寸以确保清晰度）
            String imageUrl = imageGenerationService.generate(prompt, 2048, 2048, visualStyle.getCode().toLowerCase());
            log.info("九宫格大全图生成完成: {}", imageUrl);

            // 保存结果
            character.setExpressionGridUrl(imageUrl);
            character.setExpressionGridPrompt(prompt);
            character.setExpressionStatus("COMPLETED");
            character.setIsGeneratingExpression(false);
            characterRepository.updateById(character);

            log.info("九宫格大全图生成完成: charId={}", charId);

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
     * 旧方法：逐张生成九宫格表情（保持兼容）
     */
    @Transactional
    private void generateExpressionMultiple(String charId) {
        Character character = characterRepository.findByCharId(charId);

        // 检查是否已在生成中
        if (Boolean.TRUE.equals(character.getIsGeneratingExpression())) {
            throw new BusinessException("表情图正在生成中，请勿重复提交");
        }

        // 更新状态为生成中
        character.setExpressionStatus("GENERATING");
        character.setIsGeneratingExpression(true);
        character.setExpressionError(null);
        characterRepository.updateById(character);

        List<ExpressionImage> expressions = new ArrayList<>();

        try {
            log.info("开始生成九宫格表情（逐张）: charId={}, name={}", charId, character.getName());

            for (String expressionType : EXPRESSION_TYPES) {
                String prompt = promptBuilder.buildExpressionPrompt(character, expressionType);
                log.info("生成表情[{}]: charId={}", expressionType, charId);

                String imageUrl = imageGenerationService.generate(prompt, 1024, 1024, "anime");

                ExpressionImage img = new ExpressionImage();
                img.setType(expressionType);
                img.setUrl(imageUrl);
                img.setPrompt(prompt);
                expressions.add(img);

                log.info("表情[{}]生成完成: {}", expressionType, imageUrl);
            }

            // 保存结果
            character.setExpressionSheet(objectMapper.writeValueAsString(expressions));
            character.setExpressionStatus("COMPLETED");
            character.setIsGeneratingExpression(false);
            characterRepository.updateById(character);

            log.info("九宫格表情（逐张）生成完成: charId={}", charId);

        } catch (Exception e) {
            log.error("九宫格表情（逐张）生成失败: charId={}", charId, e);
            character.setExpressionStatus("FAILED");
            character.setExpressionError(e.getMessage());
            character.setIsGeneratingExpression(false);
            characterRepository.updateById(character);
            throw new BusinessException("表情生成失败: " + e.getMessage());
        }
    }

    /**
     * 生成三视图（升级版）
     * 根据 generationMode 选择生成方式
     */
    @Transactional
    public void generateThreeViewSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        // 根据生成模式选择生成方式
        String mode = character.getGenerationMode();
        if (mode == null) {
            mode = "grid"; // 默认使用新模式
        }

        if ("grid".equals(mode)) {
            // 新模式：一次性生成大全图
            log.info("使用grid模式生成三视图: charId={}", charId);
            generateThreeViewGrid(charId);
        } else {
            // 旧模式：逐张生成
            log.info("使用multiple模式生成三视图: charId={}", charId);
            generateThreeViewMultiple(charId);
        }
    }

    /**
     * 新方法：生成三视图大全图（一次性生成）
     */
    @Transactional
    private void generateThreeViewGrid(String charId) {
        Character character = characterRepository.findByCharId(charId);

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
                visualStyle = CharacterPromptManager.VisualStyle.valueOf(character.getVisualStyle());
            }

            // 使用新提示词管理器构建提示词
            String prompt = characterPromptManager.buildThreeViewGridPrompt(character, visualStyle);
            log.info("三视图提示词长度: {} char", prompt.length());

            // 生成大全图（横向布局：1024x1536）
            String imageUrl = imageGenerationService.generate(prompt, 1024, 1536, visualStyle.getCode().toLowerCase());
            log.info("三视图大全图生成完成: {}", imageUrl);

            // 保存结果
            character.setThreeViewGridUrl(imageUrl);
            character.setThreeViewGridPrompt(prompt);
            character.setThreeViewStatus("COMPLETED");
            character.setIsGeneratingThreeView(false);
            characterRepository.updateById(character);

            log.info("三视图大全图生成完成: charId={}", charId);

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
     * 旧方法：逐张生成三视图（保持兼容）
     */
    @Transactional
    private void generateThreeViewMultiple(String charId) {
        Character character = characterRepository.findByCharId(charId);

        // 检查是否已在生成中
        if (Boolean.TRUE.equals(character.getIsGeneratingThreeView())) {
            throw new BusinessException("三视图正在生成中，请勿重复提交");
        }

        // 更新状态为生成中
        character.setThreeViewStatus("GENERATING");
        character.setIsGeneratingThreeView(true);
        character.setThreeViewError(null);
        characterRepository.updateById(character);

        List<ThreeViewImage> views = new ArrayList<>();

        try {
            log.info("开始生成三视图（逐张）: charId={}, name={}", charId, character.getName());

            for (String viewType : VIEW_TYPES) {
                String prompt = promptBuilder.buildThreeViewPrompt(character, viewType);
                log.info("生成视图[{}]: charId={}", viewType, charId);

                String imageUrl = imageGenerationService.generate(prompt, 1024, 1024, "anime");

                ThreeViewImage img = new ThreeViewImage();
                img.setViewType(viewType);
                img.setUrl(imageUrl);
                img.setPrompt(prompt);
                views.add(img);

                log.info("视图[{}]生成完成: {}", viewType, imageUrl);
            }

            // 保存结果
            character.setThreeViewSheet(objectMapper.writeValueAsString(views));
            character.setThreeViewStatus("COMPLETED");
            character.setIsGeneratingThreeView(false);
            characterRepository.updateById(character);

            log.info("三视图（逐张）生成完成: charId={}", charId);

        } catch (Exception e) {
            log.error("三视图（逐张）生成失败: charId={}", charId, e);
            character.setThreeViewStatus("FAILED");
            character.setThreeViewError(e.getMessage());
            character.setIsGeneratingThreeView(false);
            characterRepository.updateById(character);
            throw new BusinessException("三视图生成失败: " + e.getMessage());
        }
    }

    /**
     * 一键生成全部（表情+三视图）
     */
    @Transactional
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
            // 主角/反派先生成表情，再生成三视图
            generateExpressionSheet(charId);
            generateThreeViewSheet(charId);
        }

        log.info("一键生成完成: charId={}", charId);
    }

    /**
     * 重试生成
     * @param charId 角色ID
     * @param type 类型: "expression" 或 "threeView"
     */
    @Transactional
    public void retryGeneration(String charId, String type) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        log.info("重试生成: charId={}, type={}", charId, type);

        if ("expression".equalsIgnoreCase(type)) {
            // 清除之前的状态
            character.setExpressionStatus(null);
            character.setExpressionSheet(null);
            character.setExpressionError(null);
            characterRepository.updateById(character);
            generateExpressionSheet(charId);
        } else if ("threeView".equalsIgnoreCase(type)) {
            // 清除之前的状态
            character.setThreeViewStatus(null);
            character.setThreeViewSheet(null);
            character.setThreeViewError(null);
            characterRepository.updateById(character);
            generateThreeViewSheet(charId);
        } else {
            throw new BusinessException("无效的生成类型: " + type);
        }
    }

    /**
     * 获取表情图列表
     */
    public List<ExpressionImage> getExpressionSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null || character.getExpressionSheet() == null) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(
                character.getExpressionSheet(),
                new TypeReference<List<ExpressionImage>>() {}
            );
        } catch (Exception e) {
            log.error("解析表情图数据失败: charId={}", charId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取三视图列表
     */
    public List<ThreeViewImage> getThreeViewSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null || character.getThreeViewSheet() == null) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(
                character.getThreeViewSheet(),
                new TypeReference<List<ThreeViewImage>>() {}
            );
        } catch (Exception e) {
            log.error("解析三视图数据失败: charId={}", charId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取表情类型列表
     */
    public static String[] getExpressionTypes() {
        return EXPRESSION_TYPES.clone();
    }

    /**
     * 获取视图类型列表
     */
    public static String[] getViewTypes() {
        return VIEW_TYPES.clone();
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

        // 验证风格参数
        try {
            CharacterPromptManager.VisualStyle.valueOf(visualStyle);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的视觉风格: " + visualStyle + "，支持的值: 3D, REAL, ANIME");
        }

        character.setVisualStyle(visualStyle);
        characterRepository.updateById(character);
        log.info("设置视觉风格: charId={}, visualStyle={}", charId, visualStyle);
    }

    /**
     * 设置角色的生成模式
     */
    @Transactional
    public void setGenerationMode(String charId, String generationMode) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        if (!"grid".equals(generationMode) && !"multiple".equals(generationMode)) {
            throw new BusinessException("无效的生成模式: " + generationMode + "，支持的值: grid, multiple");
        }

        character.setGenerationMode(generationMode);
        characterRepository.updateById(character);
        log.info("设置生成模式: charId={}, generationMode={}", charId, generationMode);
    }
}
