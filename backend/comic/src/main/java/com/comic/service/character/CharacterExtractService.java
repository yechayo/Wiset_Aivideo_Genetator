package com.comic.service.character;

import com.comic.ai.text.TextGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.CharacterDraftDTO;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 角色提取服务
 * 从已确认的剧本中自动提取角色
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterExtractService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final CharacterRepository characterRepository;
    private final TextGenerationService textGenerationService;
    private final ObjectMapper objectMapper;

    /**
     * 从剧本中提取角色
     */
    @Transactional
    public List<CharacterDraftDTO> extractCharacters(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!"SCRIPT_CONFIRMED".equals(project.getStatus()) &&
            !"CHARACTER_REVIEW".equals(project.getStatus())) {
            throw new BusinessException("请先确认剧本后再提取角色");
        }

        // 更新项目状态
        project.setStatus("CHARACTER_EXTRACTING");
        projectRepository.updateById(project);

        try {
            // 获取所有剧本内容
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            String scriptContent = buildScriptContent(episodes);

            // 构建prompt
            String systemPrompt = "你是一个专业的角色设计师，擅长从剧本中提取和分析角色信息。\n\n"
                    + "请仔细阅读以下剧本内容，提取出所有重要角色，并为每个角色生成详细的角色档案。";
            String userPrompt = "请从以下剧本中提取角色信息：\n\n" + scriptContent + "\n\n"
                    + "请以JSON数组格式返回，每个角色包含：name, role, personality, appearance, background";

            // 调用AI提取角色
            String result = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析结果
            List<CharacterDraftDTO> characters = parseCharacters(result, projectId);

            // 保存到数据库
            saveCharacters(projectId, characters);

            // 更新项目状态
            project.setStatus("CHARACTER_REVIEW");
            projectRepository.updateById(project);

            log.info("角色提取完成: projectId={}, 角色数={}", projectId, characters.size());
            return characters;

        } catch (Exception e) {
            log.error("角色提取失败: projectId={}", projectId, e);
            project.setStatus("CHARACTER_EXTRACTING_FAILED");
            projectRepository.updateById(project);
            throw new BusinessException("角色提取失败: " + e.getMessage());
        }
    }

    /**
     * 确认所有角色
     * 用户确认角色特征后，锁定数据
     */
    @Transactional
    public void confirmCharacters(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!"CHARACTER_REVIEW".equals(project.getStatus())) {
            throw new BusinessException("当前状态不能确认角色");
        }

        // 将所有角色标记为已确认
        List<Character> characters = characterRepository.findByProjectId(projectId);
        for (Character character : characters) {
            character.setConfirmed(true);
            characterRepository.updateById(character);
        }

        project.setStatus("CHARACTER_CONFIRMED");
        projectRepository.updateById(project);

        log.info("角色已确认: projectId={}", projectId);
    }

    /**
     * 编辑角色特征
     */
    @Transactional
    public void updateCharacter(String charId, CharacterDraftDTO dto) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            throw new BusinessException("角色不存在");
        }

        character.setName(dto.getName());
        character.setRole(dto.getRole());
        character.setPersonality(dto.getPersonality());
        character.setAppearance(dto.getAppearance());
        character.setBackground(dto.getBackground());
        character.setConfirmed(dto.getConfirmed());

        characterRepository.updateById(character);

        log.info("角色已更新: charId={}", charId);
    }

    /**
     * 获取项目角色列表
     */
    public List<CharacterDraftDTO> getProjectCharacters(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        List<CharacterDraftDTO> result = new ArrayList<>();

        for (Character character : characters) {
            CharacterDraftDTO dto = new CharacterDraftDTO();
            dto.setCharId(character.getCharId());
            dto.setName(character.getName());
            dto.setRole(character.getRole());
            dto.setPersonality(character.getPersonality());
            dto.setAppearance(character.getAppearance());
            dto.setBackground(character.getBackground());
            dto.setConfirmed(character.getConfirmed());
            result.add(dto);
        }

        return result;
    }

    // ================= 私有方法 =================

    private String buildScriptContent(List<Episode> episodes) {
        StringBuilder sb = new StringBuilder();
        for (Episode episode : episodes) {
            sb.append("## 第").append(episode.getEpisodeNum()).append("集\n");
            sb.append(episode.getOutlineNode()).append("\n\n");
        }
        return sb.toString();
    }

    private List<CharacterDraftDTO> parseCharacters(String jsonResult, String projectId) {
        try {
            // 解析JSON
            List<Map<String, Object>> dataList = objectMapper.readValue(
                jsonResult,
                new TypeReference<List<Map<String, Object>>>() {}
            );

            List<CharacterDraftDTO> result = new ArrayList<>();
            for (Map<String, Object> data : dataList) {
                CharacterDraftDTO dto = new CharacterDraftDTO();
                dto.setCharId(generateCharId(projectId));
                dto.setName((String) data.get("name"));
                dto.setRole((String) data.get("role"));
                dto.setPersonality((String) data.get("personality"));
                dto.setAppearance((String) data.get("appearance"));
                dto.setBackground((String) data.getOrDefault("background", ""));
                dto.setConfirmed(false);
                result.add(dto);
            }

            return result;
        } catch (Exception e) {
            log.error("解析角色数据失败", e);
            throw new BusinessException("解析角色数据失败");
        }
    }

    private void saveCharacters(String projectId, List<CharacterDraftDTO> characters) {
        // 先删除旧的角色数据
        List<Character> oldCharacters = characterRepository.findByProjectId(projectId);
        for (Character old : oldCharacters) {
            characterRepository.deleteById(old.getId());
        }

        // 保存新的角色数据
        for (CharacterDraftDTO dto : characters) {
            Character character = new Character();
            character.setProjectId(projectId);
            character.setCharId(dto.getCharId());
            character.setName(dto.getName());
            character.setRole(dto.getRole());
            character.setPersonality(dto.getPersonality());
            character.setAppearance(dto.getAppearance());
            character.setBackground(dto.getBackground());
            character.setConfirmed(false);
            characterRepository.insert(character);
        }
    }

    private String generateCharId(String projectId) {
        return "CHAR-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
