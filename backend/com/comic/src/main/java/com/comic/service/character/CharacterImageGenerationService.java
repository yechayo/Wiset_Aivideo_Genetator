package com.comic.service.character;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.image.ImageGenerationService;
import com.comic.config.AiServiceConfiguration;
import com.comic.exception.BusinessException;
import com.comic.constant.CharacterInfoKeys;
import com.comic.constant.ProjectInfoKeys;
import com.comic.dto.response.CharacterStatusResponse;
import com.comic.entity.Character;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.redis.ProgressService;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import com.comic.statemachine.service.ProjectMilestoneStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterImageGenerationService {

    private final CharacterRepository characterRepository;
    private final ProjectRepository projectRepository;
    private final AiServiceConfiguration aiServiceConfig;
    private final ImageGenerationService imageGenerationService;
    private final CharacterPromptManager characterPromptManager;
    private final ApplicationContext applicationContext;

    @Lazy
    @Autowired
    private ProgressService progressService;

    @Lazy
    @Autowired
    private ProjectMilestoneStateMachineService milestoneStateMachineService;

    @Lazy
    @Autowired
    private StateChangeEventPublisher eventPublisher;

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

        String projectId = character.getProjectId();
        progressService.clearError(projectId);

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

            ImageGenerationService imageService = resolveImageService(projectId);

            String imageUrl;
            String threeViewGridUrl = getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_GRID_URL);
            if (threeViewGridUrl != null && !threeViewGridUrl.isEmpty()) {
                log.info("使用三视图作为参考图生成表情: {}", threeViewGridUrl);
                imageUrl = imageService.generateWithReference(
                    prompt, threeViewGridUrl, 2048, 2048);
            } else {
                imageUrl = imageService.generate(prompt, 2048, 2048, visualStyle.getCode().toLowerCase());
            }
            log.info("九宫格大全图生成完成: {}", imageUrl);

            info.put(CharacterInfoKeys.EXPRESSION_GRID_URL, imageUrl);
            info.put(CharacterInfoKeys.EXPRESSION_GRID_PROMPT, prompt);
            info.put(CharacterInfoKeys.EXPRESSION_STATUS, "COMPLETED");
            info.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, false);
            characterRepository.updateById(character);

            log.info("九宫格大全图生成完成: charId={}", charId);

            checkAndAdvanceProjectState(character);

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

        String projectId = character.getProjectId();
        progressService.clearError(projectId);

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

            ImageGenerationService imageService = resolveImageService(projectId);
            String imageUrl = imageService.generate(prompt, 1920, 1080, visualStyle.getCode().toLowerCase());
            log.info("三视图大全图生成完成: {}", imageUrl);

            info.put(CharacterInfoKeys.THREE_VIEW_GRID_URL, imageUrl);
            info.put(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT, prompt);
            info.put(CharacterInfoKeys.THREE_VIEW_STATUS, "COMPLETED");
            info.put(CharacterInfoKeys.IS_GENERATING_THREE_VIEW, false);
            characterRepository.updateById(character);

            log.info("三视图大全图生成完成: charId={}", charId);

            checkAndAdvanceProjectState(character);

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

    /**
     * 生成角色图片（前端手动触发时调用）
     */
    public void generateAll(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        doGenerateAll(charId);
    }

    /**
     * 生成角色图片（不含锁，供内部调用）
     */
    public void doGenerateAll(String charId) {
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

    public CharacterStatusResponse getGenerationStatus(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在");
        }
        CharacterStatusResponse dto = new CharacterStatusResponse();
        dto.setCharId(getCharInfoStr(character, CharacterInfoKeys.CHAR_ID));
        dto.setName(getCharInfoStr(character, CharacterInfoKeys.NAME));
        dto.setRole(getCharInfoStr(character, CharacterInfoKeys.ROLE));
        dto.setPersonality(getCharInfoStr(character, CharacterInfoKeys.PERSONALITY));
        dto.setVoice(getCharInfoStr(character, CharacterInfoKeys.VOICE));
        dto.setAppearance(getCharInfoStr(character, CharacterInfoKeys.APPEARANCE));
        dto.setBackground(getCharInfoStr(character, CharacterInfoKeys.BACKGROUND));
        dto.setConfirmed(getCharInfoBool(character, CharacterInfoKeys.CONFIRMED));
        dto.setExpressionStatus(getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_STATUS));
        dto.setThreeViewStatus(getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_STATUS));
        dto.setExpressionError(getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_ERROR));
        dto.setThreeViewError(getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_ERROR));
        dto.setIsGeneratingExpression(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_EXPRESSION));
        dto.setIsGeneratingThreeView(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_THREE_VIEW));
        dto.setVisualStyle(getCharInfoStr(character, CharacterInfoKeys.VISUAL_STYLE));
        dto.setSpecies(getCharInfoStr(character, CharacterInfoKeys.SPECIES));
        dto.setExpressionGridUrl(getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_GRID_URL));
        dto.setThreeViewGridUrl(getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_GRID_URL));
        return dto;
    }

    // ==================== 单角色驳回 ====================

    /**
     * 驳回单个角色：重置图片生成状态，允许用户修改角色描述后重新生成
     */
    @Transactional
    public void rejectSingleCharacter(String projectId, String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }

        if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IMAGES_LOCKED))) {
            throw new BusinessException("角色已锁定，无法驳回");
        }

        Map<String, Object> info = ensureCharInfo(character);
        // 重置生成状态
        info.put(CharacterInfoKeys.EXPRESSION_STATUS, null);
        info.put(CharacterInfoKeys.EXPRESSION_ERROR, null);
        info.put(CharacterInfoKeys.EXPRESSION_GRID_URL, null);
        info.put(CharacterInfoKeys.EXPRESSION_GRID_PROMPT, null);
        info.put(CharacterInfoKeys.IS_GENERATING_EXPRESSION, false);
        info.put(CharacterInfoKeys.THREE_VIEW_STATUS, null);
        info.put(CharacterInfoKeys.THREE_VIEW_ERROR, null);
        info.put(CharacterInfoKeys.THREE_VIEW_GRID_URL, null);
        info.put(CharacterInfoKeys.THREE_VIEW_GRID_PROMPT, null);
        info.put(CharacterInfoKeys.IS_GENERATING_THREE_VIEW, false);
        // 回到配置状态
        info.put(CharacterInfoKeys.CONFIRMED, false);
        info.put(CharacterInfoKeys.CHAR_STATUS, "configuring");
        character.setCharacterInfo(info);
        characterRepository.updateById(character);

        log.info("角色已驳回，回到配置状态: charId={}, name={}", charId, getCharInfoStr(character, CharacterInfoKeys.NAME));
    }

    // ==================== 单角色确认与锁定 ====================

    /**
     * 确认单个角色的配置并触发生成图片
     * 不推进项目级状态机，仅标记该角色 confirmed=true 并异步生成图片
     */
    @Transactional
    public void confirmSingleCharacter(String projectId, String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (!"episode_confirmed".equals(project.getStatus())) {
            throw new BusinessException("当前状态不能确认角色");
        }

        // 幂等：已确认且图片已完成的角色跳过
        if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.CONFIRMED))) {
            if (isCharacterImageComplete(character)) {
                log.info("角色已确认且图片已完成，跳过: charId={}", charId);
                return;
            }
            // 已确认但图片不完整（失败/被覆盖），重新触发生成
            log.info("角色已确认但图片不完整，重新触发生成: charId={}", charId);
            applicationContext.getBean(CharacterImageGenerationService.class).triggerGenerateAsync(projectId, charId);
            return;
        }

        Map<String, Object> info = ensureCharInfo(character);
        info.put(CharacterInfoKeys.CONFIRMED, true);
        info.put(CharacterInfoKeys.CHAR_STATUS, "generating");
        character.setCharacterInfo(info);
        characterRepository.updateById(character);

        log.info("角色已确认: charId={}, name={}", charId, getCharInfoStr(character, CharacterInfoKeys.NAME));

        // 异步触发图片生成（通过代理调用，确保 @Async 和事务分离生效）
        applicationContext.getBean(CharacterImageGenerationService.class).triggerGenerateAsync(projectId, charId);
    }

    /**
     * 异步生成角色图片（事务外执行）
     * 每个角色独立并发，不使用全局锁
     */
    @Async("characterImageExecutor")
    public void triggerGenerateAsync(String projectId, String charId) {
        try {
            progressService.clearError(projectId);
            doGenerateAll(charId);
        } catch (Throwable t) {
            log.error("角色确认后触发生成失败: charId={}, error={}", charId, t.getMessage(), t);
            // 回退角色状态为 review，防止前端永远显示"生成中"
            try {
                Character c = characterRepository.findByCharId(charId);
                if (c != null) {
                    Map<String, Object> info = ensureCharInfo(c);
                    info.put(CharacterInfoKeys.CHAR_STATUS, "review");
                    characterRepository.updateById(c);
                }
            } catch (Exception e2) {
                log.error("回退角色状态也失败: charId={}", charId, e2);
            }
        }
    }

    /**
     * 锁定单个角色的素材图片
     * 标记 imagesLocked=true，然后检查是否所有角色都已锁定
     * 如果全部锁定 → 推进项目状态机到 ASSET_CONFIRMED
     */
    @Transactional
    public void lockSingleCharacter(String projectId, String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在: " + charId);
        }
        if (!isCharacterImageComplete(character)) {
            throw new BusinessException("角色图片未完成，无法锁定");
        }

        Map<String, Object> info = ensureCharInfo(character);
        info.put(CharacterInfoKeys.IMAGES_LOCKED, true);
        info.put(CharacterInfoKeys.CHAR_STATUS, "locked");
        character.setCharacterInfo(info);
        characterRepository.updateById(character);

        log.info("角色图片已锁定: charId={}, name={}", charId, getCharInfoStr(character, CharacterInfoKeys.NAME));

        // 检查是否所有角色都已锁定 → 推进状态机（原子防重复）
        List<Character> allCharacters = characterRepository.findByProjectId(projectId);
        boolean allLocked = allCharacters.stream()
                .allMatch(c -> Boolean.TRUE.equals(getCharInfoBool(c, CharacterInfoKeys.IMAGES_LOCKED)));
        if (allLocked && progressService.trySetOnce(projectId, "asset_lock_sent")) {
            milestoneStateMachineService.sendEvent(projectId, ProjectMilestoneEventType.CONFIRM_ASSETS);
            log.info("所有角色已锁定，推进到素材确认: projectId={}", projectId);
        }
    }

    /**
     * 计算角色的当前阶段状态
     */
    public String computeCharStatus(Character character) {
        Map<String, Object> info = character.getCharacterInfo();
        if (info == null) return "configuring";

        // 已锁定
        if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IMAGES_LOCKED))) {
            return "locked";
        }

        // 生成中
        boolean isGenExpr = Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_EXPRESSION));
        boolean isGenThreeView = Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.IS_GENERATING_THREE_VIEW));
        String exprStatus = getCharInfoStr(character, CharacterInfoKeys.EXPRESSION_STATUS);
        String threeViewStatus = getCharInfoStr(character, CharacterInfoKeys.THREE_VIEW_STATUS);
        if (isGenExpr || isGenThreeView || "GENERATING".equals(exprStatus) || "GENERATING".equals(threeViewStatus)) {
            return "generating";
        }

        // 待审核（图片已生成但未锁定）
        if (isCharacterImageComplete(character)) {
            return "review";
        }

        // 有失败状态也视为待审核（可以重试）
        if ("FAILED".equals(exprStatus) || "FAILED".equals(threeViewStatus)) {
            return "review";
        }

        // 已确认但尚未开始生成 → 仍在排队中
        if (Boolean.TRUE.equals(getCharInfoBool(character, CharacterInfoKeys.CONFIRMED))) {
            return "generating";
        }

        return "configuring";
    }

    // ==================== 图片确认 ====================

    /**
     * 确认项目所有角色图片，进入素材锁定阶段
     */
    @Transactional
    public void confirmImages(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!"episode_confirmed".equals(project.getStatus())) {
            throw new BusinessException("当前状态不允许确认图片");
        }

        // Validate all characters have complete images
        List<Character> characters = characterRepository.findByProjectId(projectId);
        if (characters.isEmpty()) {
            throw new BusinessException("项目没有角色，无法确认图片");
        }

        List<String> incompleteChars = new ArrayList<>();
        for (Character c : characters) {
            if (!isCharacterImageComplete(c)) {
                String name = getCharInfoStr(c, CharacterInfoKeys.NAME);
                incompleteChars.add(name != null ? name : String.valueOf(c.getId()));
            }
        }

        if (!incompleteChars.isEmpty()) {
            throw new BusinessException("以下角色图片未完成: " + String.join(", ", incompleteChars));
        }

        milestoneStateMachineService.sendEvent(projectId, ProjectMilestoneEventType.CONFIRM_ASSETS);
        log.info("图片确认完成，项目进入素材锁定: projectId={}", projectId);
    }

    // ==================== 状态推进 ====================

    private void checkAndAdvanceProjectState(Character character) {
        String projectId = character.getProjectId();
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return;
        }

        List<Character> allCharacters = characterRepository.findByProjectId(projectId);
        boolean allDone = allCharacters.stream().allMatch(c -> isCharacterImageComplete(c));

        if (allDone && progressService.trySetOnce(projectId, "asset_complete_sent")) {
            log.info("所有角色图片生成完成: projectId={}", projectId);
            eventPublisher.publishTaskComplete(projectId, "asset_image", null);
        }
    }

    private boolean isCharacterImageComplete(Character character) {
        Map<String, Object> info = character.getCharacterInfo();
        if (info == null) return false;

        String threeViewStatus = info.get(CharacterInfoKeys.THREE_VIEW_STATUS) != null
                ? info.get(CharacterInfoKeys.THREE_VIEW_STATUS).toString() : null;
        if (!"COMPLETED".equals(threeViewStatus)) return false;

        // 配角只需要三视图
        String role = info.get(CharacterInfoKeys.ROLE) != null
                ? info.get(CharacterInfoKeys.ROLE).toString() : null;
        if ("配角".equals(role)) return true;

        // 主角/反派还需要九宫格
        String expressionStatus = info.get(CharacterInfoKeys.EXPRESSION_STATUS) != null
                ? info.get(CharacterInfoKeys.EXPRESSION_STATUS).toString() : null;
        return "COMPLETED".equals(expressionStatus);
    }

    // ==================== 辅助方法 ====================

    private ImageGenerationService resolveImageService(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project != null && project.getProjectInfo() != null) {
            Object provider = project.getProjectInfo().get(ProjectInfoKeys.IMAGE_PROVIDER);
            if (provider != null) return aiServiceConfig.getImageService(provider.toString());
        }
        return imageGenerationService;
    }

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