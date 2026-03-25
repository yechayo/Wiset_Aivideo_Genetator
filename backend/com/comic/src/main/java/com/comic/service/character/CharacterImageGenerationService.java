package com.comic.service.character;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterImageGenerationService {

    private final CharacterRepository characterRepository;
    private final ImageGenerationService imageGenerationService;
    private final CharacterPromptManager characterPromptManager;

    public void generateExpressionSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        if ("配角".equals(getCharInfoStr(character, CharacterInfoKeys.ROLE))) {
            log.info("配角跳过表情生成: charId={}, name={}", charId, getCharInfoStr(character, CharacterInfoKeys.NAME));
            throw new BusinessException("配角不需要生成表情图");
        }

        if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_EXPRESSION))) {
            throw new BusinessException("表情图正在生成中，请勿重复提交");
        }

        Map<String, Object> info = ensureCharInfo(character);
        info.put(CharacterInfoKeys.EXPRESSION_STATUS, "GENERATING");
        info.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, true);
        info.remove(CharacterInfoKeys.EXPRESSION_ERROR);
        characterRepository.updateById(character);

        try {
            log.info("开始生成九宫格大全图: charId={}, name={}, visualStyle={}",
                     charId, getCharInfoStr(character, CharacterInfoKeys.NAME), getCharInfoStr(character, CharacterInfoKeys.VISUAL_STYLE));

            CharacterPromptManager.VisualStyle visualStyle = CharacterPromptManager.VisualStyle.D_3D;
            String vsCode = getCharInfoStr(character, CharacterInfoKeys.VISUAL_STYLE);
            if (vsCode != null) {
                visualStyle = CharacterPromptManager.VisualStyle.fromCode(vsCode);
            }

            String prompt = characterPromptManager.buildExpressionGridPrompt(character, visualStyle);
            log.info("九宫格提示词长度: {} char", prompt.length());

            String imageUrl;
            String threeViewGridUrl = getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_GRID_URL);
            if (threeViewGridUrl != null && !threeViewGridUrl.isEmpty()) {
                log.info("使用三视图作为参考图生成表情: {}", threeViewGridUrl);
                imageUrl = imageGenerationService.generateWithReference(
                    prompt, threeViewGridUrl, 2048, 2048);
            } else {
                imageUrl = imageGenerationService.generate(prompt, 2048, 2048, visualStyle.getCode().toLowerCase());
            }
            log.info("九宫格大全图生成完成: {}", imageUrl);

            info.put(CharacterInfoKeys.EXPRESSION_GRID_URL, imageUrl);
            info.put(CharacterInfoKeys.EXPRESSION_GRID_PROMPT, prompt);
            info.put(CharacterInfoKeys.EXPRESSION_STATUS, "COMPLETED");
            info.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, false);
            characterRepository.updateById(character);

            log.info("九宫格大全图生成完成: charId={}", charId);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("九宫格大全图生成失败: charId={}", charId, e);
            info.put(CharacterInfoKeys.EXPRESSION_STATUS, "FAILED");
            info.put(CharacterInfoKeys.EXPRESSION_ERROR, e.getMessage());
            info.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, false);
            characterRepository.updateById(character);
            throw new BusinessException("九宫格大全图生成失败: " + e.getMessage());
        }
    }

    public void generateThreeViewSheet(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_THREE_VIEW))) {
            throw new BusinessException("三视图正在生成中，请勿重复提交");
        }

        Map<String, Object> info = ensureCharInfo(character);
        info.put(CharacterInfoKeys.THREE_VIEW_STATUS, "GENERATING");
        info.put(CharacterInfoKeys.IS_GENERATING_THREE_VIEW, true);
        info.remove(CharacterInfoKeys.THREE_VIEW_ERROR);
        characterRepository.updateById(character);

        try {
            log.info("开始生成三视图大全图: charId={}, name={}, visualStyle={}",
                     charId, getCharInfoStr(character, CharacterInfoKeys.NAME), getCharInfoStr(character, CharacterInfoKeys.VISUAL_STYLE));

            CharacterPromptManager.VisualStyle visualStyle = CharacterPromptManager.VisualStyle.D_3D;
            String vsCode = getCharInfoStr(character, CharacterInfoKeys.VISUAL_STYLE);
            if (vsCode != null) {
                visualStyle = CharacterPromptManager.VisualStyle.fromCode(vsCode);
            }

            String prompt = characterPromptManager.buildThreeViewGridPrompt(character, visualStyle);
            log.info("三视图提示词长度: {} char", prompt.length());

            String imageUrl = imageGenerationService.generate(prompt, 1024, 1536, visualStyle.getCode().toLowerCase());
            log.info("三视图大全图生成完成: {}", imageUrl);

            info.put(CharacterInfoKeys.THREE_VIEW_GRID_URL, imageUrl);
            info.put(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT, prompt);
            info.put(CharacterInfoKeys.THREE_VIEW_STATUS, "COMPLETED");
            info.put(CharacterInfoKeys.IS_GENERATING_THREE_VIEW, false);
            characterRepository.updateById(character);

            log.info("三视图大全图生成完成: charId={}", charId);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("三视图大全图生成失败: charId={}", charId, e);
            info.put(CharacterInfoKeys.THREE_VIEW_STATUS, "FAILED");
            info.put(CharacterInfoKeys.THREE_VIEW_ERROR, e.getMessage());
            info.put(CharacterInfoKeys.IS_GENERATING_THREE_VIEW, false);
            characterRepository.updateById(character);
            throw new BusinessException("三视图大全图生成失败: " + e.getMessage());
        }
    }

    public void generateAll(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        log.info("开始一键生成: charId={}, name={}, role={}",
                 charId, getCharInfoStr(character, CharacterInfoKeys.NAME), getCharInfoStr(character, CharacterInfoKeys.ROLE));

        if ("配角".equals(getCharInfoStr(character, CharacterInfoKeys.ROLE))) {
            log.info("配角跳过表情，直接生成三视图: charId={}", charId);
            generateThreeViewSheet(charId);
        } else {
            generateThreeViewSheet(charId);
            generateExpressionSheet(charId);
        }

        log.info("一键生成完成: charId={}", charId);
    }

    public void retryGeneration(String charId, String type) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        log.info("重试生成: charId={}, type={}", charId, type);

        Map<String, Object> info = character.getCharacterInfo();
        if (info != null) {
            if ("expression".equalsIgnoreCase(type)) {
                info.remove(CharacterInfoKeys.EXPRESSION_STATUS);
                info.remove(CharacterInfoKeys.EXPRESSION_ERROR);
                info.remove(CharacterInfoKeys.EXPRESSION_GRID_URL);
                info.remove(CharacterInfoKeys.EXPRESSION_GRID_PROMPT);
                characterRepository.updateById(character);
                generateExpressionSheet(charId);
            } else if ("threeView".equalsIgnoreCase(type)) {
                info.remove(CharacterInfoKeys.THREE_VIEW_STATUS);
                info.remove(CharacterInfoKeys.THREE_VIEW_ERROR);
                info.remove(CharacterInfoKeys.THREE_VIEW_GRID_URL);
                info.remove(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT);
                info.remove(CharacterInfoKeys.EXPRESSION_GRID_URL);
                info.remove(CharacterInfoKeys.EXPRESSION_GRID_PROMPT);
                info.remove(CharacterInfoKeys.EXPRESSION_STATUS);
                info.remove(CharacterInfoKeys.EXPRESSION_ERROR);
                characterRepository.updateById(character);
                generateThreeViewSheet(charId);
            } else {
                throw new BusinessException("无效的生成类型: " + type);
            }
        }
    }

    @Transactional
    public void setVisualStyle(String charId, String visualStyle) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        try {
            CharacterPromptManager.VisualStyle.fromCode(visualStyle);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的视觉风格: " + visualStyle);
        }

        Map<String, Object> info = ensureCharInfo(character);
        info.put(CharacterInfoKeys.VISUAL_STYLE, visualStyle);
        character.setCharacterInfo(info);
        characterRepository.updateById(character);
        log.info("设置视觉风格: charId={}, visualStyle={}", charId, visualStyle);
    }

    // ==================== 辅助方法 ====================

    private String getCharInfoStr(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Boolean getCharInfoBool(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.valueOf(v.toString());
    }

    private Map<String, Object> ensureCharInfo(Character character) {
        Map<String, Object> info = character.getCharacterInfo();
        if (info == null) {
            info = new HashMap<>();
            character.setCharacterInfo(info);
        }
        return info;
    }
}