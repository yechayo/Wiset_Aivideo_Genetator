package com.comic.service.character;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.ai.text.TextGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.model.CharacterDraftModel;
import com.comic.dto.request.CharacterUpdateRequest;
import com.comic.dto.response.CharacterListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.entity.Character;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterExtractService {

    private final ProjectRepository projectRepository;
    private final CharacterRepository characterRepository;
    private final TextGenerationService textGenerationService;
    private final ObjectMapper objectMapper;

    /**
     * 注意：此方法由状态机 Action 在异步线程中调用，使用独立事务确保数据落库
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public List<CharacterDraftModel> extractCharacters(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!ProjectStatus.SCRIPT_CONFIRMED.getCode().equals(project.getStatus()) &&
            !ProjectStatus.CHARACTER_REVIEW.getCode().equals(project.getStatus()) &&
            !ProjectStatus.CHARACTER_EXTRACTING.getCode().equals(project.getStatus())) {
            throw new BusinessException("请先确认剧本后再提取角色");
        }

        // 状态已由 triggerNextStage 设置，无需重复设置

        try {
            String outline = getScriptOutlineText(project);
            if (outline == null || outline.trim().isEmpty()) {
                throw new BusinessException("项目大纲为空，无法提取角色");
            }

            String storyPrompt = getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT);

            String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);
            if (visualStyle == null) visualStyle = "3D";

            String systemPrompt = buildCharacterExtractionSystemPrompt(visualStyle);
            String userPrompt = buildCharacterExtractionUserPrompt(storyPrompt, outline);

            String result = textGenerationService.generate(systemPrompt, userPrompt);

            List<CharacterDraftModel> characters = parseCharacters(result, projectId);

            saveCharacters(projectId, characters);

            log.info("角色提取完成: projectId={}, 角色数={}", projectId, characters.size());
            return characters;

        } catch (Exception e) {
            log.error("角色提取失败: projectId={}", projectId, e);
            throw new BusinessException("角色提取失败: " + e.getMessage());
        }
    }

    @Transactional
    public void confirmCharacters(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (!ProjectStatus.CHARACTER_REVIEW.getCode().equals(project.getStatus())) {
            throw new BusinessException("当前状态不能确认角色");
        }
        List<Character> characters = characterRepository.findByProjectId(projectId);
        for (Character character : characters) {
            Map<String, Object> info = character.getCharacterInfo();
            if (info != null) {
                info.put(CharacterInfoKeys.CONFIRMED, true);
                character.setCharacterInfo(info);
                characterRepository.updateById(character);
            }
        }
        log.info("角色已确认: projectId={}", projectId);
    }

    @Transactional
    public void updateCharacter(String charId, CharacterUpdateRequest dto) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在");
        }
        Project project = projectRepository.findByProjectId(character.getProjectId());
        if (project == null || !ProjectStatus.CHARACTER_REVIEW.getCode().equals(project.getStatus())) {
            throw new BusinessException("当前状态不能编辑角色");
        }
        Map<String, Object> info = character.getCharacterInfo();
        if (info == null) info = new HashMap<>();
        if (dto.getName() != null) {
            info.put(CharacterInfoKeys.NAME, dto.getName());
        }
        if (dto.getPersonality() != null) {
            info.put(CharacterInfoKeys.PERSONALITY, dto.getPersonality());
        }
        if (dto.getVoice() != null) {
            info.put(CharacterInfoKeys.VOICE, dto.getVoice());
        }
        if (dto.getAppearance() != null) {
            info.put(CharacterInfoKeys.APPEARANCE, dto.getAppearance());
        }
        if (dto.getBackground() != null) {
            info.put(CharacterInfoKeys.BACKGROUND, dto.getBackground());
        }
        character.setCharacterInfo(info);
        characterRepository.updateById(character);
        log.info("角色已更新: charId={}", charId);
    }

    public PaginatedResponse<CharacterListItemResponse> getProjectCharactersPage(
            String projectId, String role, String name, int page, int size) {
        IPage<Character> charPage = characterRepository.findPageByProjectId(
                projectId, role, name, new Page<>(page, size));
        List<CharacterListItemResponse> items = new ArrayList<>();
        for (Character character : charPage.getRecords()) {
            items.add(toListItemResponse(character));
        }
        return PaginatedResponse.of(items, charPage.getTotal(), (int) charPage.getCurrent(), (int) charPage.getSize());
    }

    @Transactional
    public void deleteCharacter(String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在");
        }
        Project project = projectRepository.findByProjectId(character.getProjectId());
        if (project == null || !ProjectStatus.CHARACTER_REVIEW.getCode().equals(project.getStatus())) {
            throw new BusinessException("当前状态不能删除角色");
        }
        characterRepository.deleteById(character.getId());
        log.info("角色已删除: charId={}", charId);
    }

    // ================= 私有方法 =================

    private List<CharacterDraftModel> parseCharacters(String jsonResult, String projectId) {
        try {
            String cleanJson = extractJsonFromResponse(jsonResult);
            log.info("清理后的JSON: {}", cleanJson);

            List<Map<String, Object>> dataList = objectMapper.readValue(
                cleanJson,
                new TypeReference<List<Map<String, Object>>>() {}
            );

            List<CharacterDraftModel> result = new ArrayList<>();
            for (Map<String, Object> data : dataList) {
                CharacterDraftModel dto = new CharacterDraftModel();
                dto.setCharId(generateCharId(projectId));
                dto.setName(getStringValue(data, "name"));
                dto.setRole(getStringValue(data, "role"));
                dto.setAlias(getStringValue(data, "alias"));
                dto.setPersonality(getStringValue(data, "personality"));
                dto.setAppearance(getStringValue(data, "appearance"));
                dto.setAppearancePrompt(getStringValue(data, "appearancePrompt"));
                dto.setProfession(getStringValue(data, "profession"));
                dto.setBackground(getStringValue(data, "background"));
                dto.setVoice(getStringValue(data, "voice"));
                // 主角/反派扩展字段
                dto.setMotivation(getStringValue(data, "motivation"));
                dto.setWeakness(getStringValue(data, "weakness"));
                dto.setRelationships(getStringValue(data, "relationships"));
                dto.setHabits(getStringValue(data, "habits"));
                dto.setConfirmed(false);
                result.add(dto);
            }

            return result;
        } catch (Exception e) {
            log.error("解析角色数据失败, AI返回原始内容: {}", jsonResult, e);
            throw new BusinessException("解析角色数据失败: " + e.getMessage());
        }
    }

    private String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        String cleaned = response.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        int startIndex = cleaned.indexOf('[');
        int endIndex = cleaned.lastIndexOf(']');

        if (startIndex >= 0 && endIndex > startIndex) {
            cleaned = cleaned.substring(startIndex, endIndex + 1);
        }

        return cleaned.trim();
    }

    private String getStringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    private void saveCharacters(String projectId, List<CharacterDraftModel> characters) {
        Project project = projectRepository.findByProjectId(projectId);
        String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);
        if (visualStyle == null) visualStyle = "3D";

        List<Character> oldCharacters = characterRepository.findByProjectId(projectId);
        for (Character old : oldCharacters) {
            characterRepository.deleteById(old.getId());
        }

        for (CharacterDraftModel dto : characters) {
            Character character = new Character();
            character.setProjectId(projectId);

            Map<String, Object> info = new HashMap<>();
            info.put(CharacterInfoKeys.CHAR_ID, dto.getCharId());
            info.put(CharacterInfoKeys.NAME, dto.getName());
            info.put(CharacterInfoKeys.ROLE, dto.getRole());
            info.put(CharacterInfoKeys.ALIAS, dto.getAlias());
            info.put(CharacterInfoKeys.PERSONALITY, dto.getPersonality());
            info.put(CharacterInfoKeys.APPEARANCE, dto.getAppearance());
            info.put(CharacterInfoKeys.APPEARANCE_PROMPT, dto.getAppearancePrompt());
            info.put(CharacterInfoKeys.PROFESSION, dto.getProfession());
            info.put(CharacterInfoKeys.VOICE, dto.getVoice());
            info.put(CharacterInfoKeys.BACKGROUND, dto.getBackground());

            // 主角/反派扩展字段（配角可能为空）
            boolean isMain = "主角".equals(dto.getRole()) || "反派".equals(dto.getRole());
            if (isMain) {
                putIfNotEmpty(info, CharacterInfoKeys.MOTIVATION, dto.getMotivation());
                putIfNotEmpty(info, CharacterInfoKeys.WEAKNESS, dto.getWeakness());
                putIfNotEmpty(info, CharacterInfoKeys.RELATIONSHIPS, dto.getRelationships());
                putIfNotEmpty(info, CharacterInfoKeys.HABITS, dto.getHabits());
            }

            info.put(CharacterInfoKeys.CONFIRMED, false);
            info.put(CharacterInfoKeys.VISUAL_STYLE, visualStyle);
            character.setCharacterInfo(info);

            characterRepository.insert(character);
        }
    }

    private void putIfNotEmpty(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    private CharacterListItemResponse toListItemResponse(Character character) {
        CharacterListItemResponse resp = new CharacterListItemResponse();
        Map<String, Object> info = character.getCharacterInfo();
        if (info == null) return resp;
        resp.setCharId(getInfoStr(info, CharacterInfoKeys.CHAR_ID));
        resp.setName(getInfoStr(info, CharacterInfoKeys.NAME));
        resp.setRole(getInfoStr(info, CharacterInfoKeys.ROLE));
        resp.setPersonality(getInfoStr(info, CharacterInfoKeys.PERSONALITY));
        resp.setVoice(getInfoStr(info, CharacterInfoKeys.VOICE));
        resp.setAppearance(getInfoStr(info, CharacterInfoKeys.APPEARANCE));
        resp.setBackground(getInfoStr(info, CharacterInfoKeys.BACKGROUND));
        resp.setVisualStyle(getInfoStr(info, CharacterInfoKeys.VISUAL_STYLE));
        resp.setExpressionStatus(getInfoStr(info, CharacterInfoKeys.EXPRESSION_STATUS));
        resp.setThreeViewStatus(getInfoStr(info, CharacterInfoKeys.THREE_VIEW_STATUS));
        resp.setConfirmed(getInfoBool(info, CharacterInfoKeys.CONFIRMED));
        resp.setCreatedAt(character.getCreatedAt());
        return resp;
    }

    private String generateCharId(String projectId) {
        return "CHAR-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String getProjectInfoStr(Project project, String key) {
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private String getInfoStr(Map<String, Object> info, String key) {
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Boolean getInfoBool(Map<String, Object> info, String key) {
        Object v = info != null ? info.get(key) : null;
        return v != null ? Boolean.valueOf(v.toString()) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getScriptMap(Project project) {
        Map<String, Object> info = project.getProjectInfo();
        if (info == null) return null;
        Object script = info.get(ProjectInfoKeys.SCRIPT);
        return script instanceof Map ? (Map<String, Object>) script : null;
    }

    // ================= 角色 Prompt 构建 =================

    /**
     * 构建角色提取系统提示词
     * 参考文档设计：主角11字段（含英文appearancePrompt）、配角5字段
     */
    private String buildCharacterExtractionSystemPrompt(String visualStyle) {
        String styleDesc;
        switch (visualStyle != null ? visualStyle.toUpperCase() : "3D") {
            case "REAL": styleDesc = "真人写实风格（真人电影质感，自然光影）"; break;
            case "ANIME": styleDesc = "动漫风格（精细2D动漫插画）"; break;
            case "MANGA": styleDesc = "日本漫画风格（彩色漫画插画）"; break;
            case "INK": styleDesc = "中国水墨风格（水墨写意）"; break;
            case "CYBERPUNK": styleDesc = "赛博朋克风格（霓虹灯光，未来感）"; break;
            default: styleDesc = "3D CG风格（高精度3D建模，半写实）"; break;
        }

        return "你是一位资深的角色设计师和小说家。\n"
            + "你的任务是根据提供的角色名称和剧本上下文，生成详细的角色档案。\n\n"
            + "本剧的视觉风格为「" + styleDesc + "」。\n\n"
            + "## 输出要求\n\n"
            + "直接输出 JSON 数组，不要 markdown 代码块标记。\n"
            + "按角色重要性分级处理：\n\n"
            + "### 主角/反派（完整档案）\n"
            + "必须包含以下字段：\n"
            + "- name: 姓名\n"
            + "- role: 角色定位（主角/反派/配角）\n"
            + "- alias: 称谓或外号（如「李医生」「老张」）\n"
            + "- personality: 性格描述（主性格+次性格）\n"
            + "- appearance: 中文外貌描述（年龄、性别、身高、身材、发型、着装）\n"
            + "- appearancePrompt: 英文AI生图提示词（关键！格式：[Style Keywords], [Character Description], [Clothing], [Face], [Lighting]。必须严格匹配「" + styleDesc + "」视觉风格）\n"
            + "- profession: 职业（含隐藏身份）\n"
            + "- background: 生活环境、生理特征、地域标签\n"
            + "- voice: 声音特点\n"
            + "- motivation: 核心动机\n"
            + "- weakness: 恐惧与弱点\n"
            + "- relationships: 核心关系及影响\n"
            + "- habits: 语言风格、行为习惯、兴趣爱好\n\n"
            + "### 配角（精简档案）\n"
            + "必须包含以下字段：\n"
            + "- name, role, alias, personality, appearance, appearancePrompt, profession, voice\n"
            + "- 不需要 motivation/weakness/relationships/habits\n\n"
            + "## appearancePrompt 编写规则\n"
            + "这是最重要的字段，直接用于AI图片生成。要求：\n"
            + "1. 必须是纯英文\n"
            + "2. 以视觉风格关键词开头\n"
            + "3. 按此顺序：[Style], [Gender/Age/Build], [Hair], [Face/Eyes], [Clothing], [Accessories], [Lighting]\n"
            + "4. 避免抽象描述，用具体视觉元素（如「long silver hair」而非「elegant hair」）\n"
            + "5. 不要出现中文名字\n\n"
            + "示例（3D风格主角）：\n"
            + "\"Semi-realistic 3D CG character, young woman age 22, slender build, long silver hair with blue highlights, "
            + "sharp ice-blue eyes, delicate features, wearing dark blue military coat with silver buttons, "
            + "black leather gloves, silver earring on left ear, soft ethereal lighting, cinematic rim light\"\n";
    }

    /**
     * 构建角色提取用户提示词
     */
    private String buildCharacterExtractionUserPrompt(String storyPrompt, String outline) {
        return "请从以下故事中提取所有角色信息：\n\n"
            + "【故事创意】\n" + storyPrompt + "\n\n"
            + "【故事大纲】\n" + outline + "\n\n"
            + "要求：\n"
            + "1. 只返回纯JSON数组，不要有任何其他文字说明\n"
            + "2. 不要使用markdown代码块标记\n"
            + "3. 所有字段都必须有值，不能为null\n"
            + "4. role只能是：主角、反派、配角\n"
            + "5. appearancePrompt 必须是英文，是AI图片生成的核心依据\n"
            + "6. 主角和反派必须填写完整档案（含motivation/weakness/relationships/habits）\n"
            + "7. 配角只需要精简档案\n\n"
            + "请直接返回JSON数组：";
    }

    private String getScriptOutlineText(Project project) {
        Map<String, Object> scriptMap = getScriptMap(project);
        if (scriptMap == null) return null;
        Object outline = scriptMap.get(ProjectInfoKeys.SCRIPT_OUTLINE);
        return outline != null ? outline.toString() : null;
    }
}