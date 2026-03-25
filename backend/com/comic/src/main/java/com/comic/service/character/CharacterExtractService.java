package com.comic.service.character;

import com.comic.ai.text.TextGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.model.CharacterDraftModel;
import com.comic.entity.Character;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional
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

        project.setStatus(ProjectStatus.CHARACTER_EXTRACTING.getCode());
        projectRepository.updateById(project);

        try {
            String outline = getScriptOutlineText(project);
            if (outline == null || outline.trim().isEmpty()) {
                throw new BusinessException("项目大纲为空，无法提取角色");
            }

            String storyPrompt = getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT);

            String systemPrompt = "你是一个专业的角色设计师，擅长从故事大纲中提取和分析角色信息。\n\n"
                    + "请仔细阅读以下故事大纲，提取出所有重要角色，并为每个角色生成详细的角色档案。\n"
                    + "角色定位只能是以下三种之一：主角、反派、配角";
            String userPrompt = "请从以下故事大纲中提取角色信息：\n\n"
                    + "【故事创意】\n" + storyPrompt + "\n\n"
                    + "【故事大纲】\n" + outline + "\n\n"
                    + "要求：\n"
                    + "1. 只返回纯JSON数组，不要有任何其他文字说明\n"
                    + "2. 不要使用markdown代码块标记\n"
                    + "3. 每个角色必须包含：name(姓名), role(角色定位), personality(性格), appearance(外貌), background(背景)\n"
                    + "4. 所有字段都必须有值，不能为null\n"
                    + "5. role只能是：主角、反派、配角\n"
                    + "6. 返回格式示例：[{\"name\":\"张三\",\"role\":\"主角\",\"personality\":\"勇敢\",\"appearance\":\"英俊\",\"background\":\"孤儿\"}]\n\n"
                    + "请直接返回JSON数组：";

            String result = textGenerationService.generate(systemPrompt, userPrompt);

            List<CharacterDraftModel> characters = parseCharacters(result, projectId);

            saveCharacters(projectId, characters);

            project.setStatus(ProjectStatus.CHARACTER_REVIEW.getCode());
            projectRepository.updateById(project);

            log.info("角色提取完成: projectId={}, 角色数={}", projectId, characters.size());
            return characters;

        } catch (Exception e) {
            log.error("角色提取失败: projectId={}", projectId, e);
            project.setStatus(ProjectStatus.CHARACTER_EXTRACTING_FAILED.getCode());
            projectRepository.updateById(project);
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
        project.setStatus(ProjectStatus.CHARACTER_CONFIRMED.getCode());
        projectRepository.updateById(project);
        log.info("角色已确认: projectId={}", projectId);
    }

    @Transactional
    public void updateCharacter(String charId, CharacterDraftModel dto) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在");
        }
        Map<String, Object> info = character.getCharacterInfo();
        if (info == null) info = new HashMap<>();
        info.put(CharacterInfoKeys.NAME, dto.getName());
        info.put(CharacterInfoKeys.ROLE, dto.getRole());
        info.put("personality", dto.getPersonality());
        info.put(CharacterInfoKeys.APPEARANCE, dto.getAppearance());
        info.put(CharacterInfoKeys.BACKGROUND, dto.getBackground());
        info.put(CharacterInfoKeys.CONFIRMED, dto.getConfirmed());
        character.setCharacterInfo(info);
        characterRepository.updateById(character);
        log.info("角色已更新: charId={}", charId);
    }

    public List<CharacterDraftModel> getProjectCharacters(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        List<CharacterDraftModel> result = new ArrayList<>();
        for (Character character : characters) {
            Map<String, Object> info = character.getCharacterInfo();
            if (info == null) continue;
            CharacterDraftModel dto = new CharacterDraftModel();
            dto.setCharId(getInfoStr(info, CharacterInfoKeys.CHAR_ID));
            dto.setName(getInfoStr(info, CharacterInfoKeys.NAME));
            dto.setRole(getInfoStr(info, CharacterInfoKeys.ROLE));
            dto.setPersonality(getInfoStr(info, "personality"));
            dto.setAppearance(getInfoStr(info, CharacterInfoKeys.APPEARANCE));
            dto.setBackground(getInfoStr(info, CharacterInfoKeys.BACKGROUND));
            dto.setConfirmed(getInfoBool(info, CharacterInfoKeys.CONFIRMED));
            result.add(dto);
        }
        return result;
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
                dto.setPersonality(getStringValue(data, "personality"));
                dto.setAppearance(getStringValue(data, "appearance"));
                dto.setBackground(getStringValue(data, "background"));
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
            info.put("personality", dto.getPersonality());
            info.put(CharacterInfoKeys.APPEARANCE, dto.getAppearance());
            info.put(CharacterInfoKeys.BACKGROUND, dto.getBackground());
            info.put(CharacterInfoKeys.CONFIRMED, false);
            info.put(CharacterInfoKeys.VISUAL_STYLE, visualStyle);
            character.setCharacterInfo(info);

            characterRepository.insert(character);
        }
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

    private String getScriptOutlineText(Project project) {
        Map<String, Object> scriptMap = getScriptMap(project);
        if (scriptMap == null) return null;
        Object outline = scriptMap.get(ProjectInfoKeys.SCRIPT_OUTLINE);
        return outline != null ? outline.toString() : null;
    }
}