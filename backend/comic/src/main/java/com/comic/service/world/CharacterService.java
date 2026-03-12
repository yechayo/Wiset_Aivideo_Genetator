package com.comic.service.world;

import com.comic.dto.CharacterStateDTO;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色状态服务（Java 8 版）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final ObjectMapper objectMapper;

    @Cacheable(value = "characterStates", key = "#seriesId")
    public List<CharacterStateDTO> getCurrentStates(String seriesId) {
        List<Character> characters = characterRepository.findBySeriesId(seriesId);
        List<CharacterStateDTO> dtos = new ArrayList<>();

        for (Character c : characters) {
            try {
                CharacterStateDTO dto = objectMapper.readValue(c.getCurrentStateJson(), CharacterStateDTO.class);
                dto.setCharId(c.getCharId());
                dto.setName(c.getName());
                dtos.add(dto);
            } catch (Exception e) {
                log.warn("解析角色状态失败: charId={}", c.getCharId(), e);
            }
        }
        return dtos;
    }

    @CacheEvict(value = "characterStates", key = "#seriesId")
    public void updateStatesFromStoryboard(String seriesId, JsonNode storyboardJson) {
        JsonNode panels = storyboardJson.get("panels");
        if (panels == null || !panels.isArray() || panels.size() == 0) return;

        JsonNode lastPanel = panels.get(panels.size() - 1);
        JsonNode characters = lastPanel.get("characters");
        if (characters == null || !characters.isArray()) return;

        for (JsonNode charNode : characters) {
            String charId = charNode.path("char_id").asText();
            if (charId == null || charId.isEmpty()) continue;

            Character character = characterRepository.findBySeriesIdAndCharId(seriesId, charId);
            if (character == null) continue;

            try {
                CharacterStateDTO newState = objectMapper.readValue(
                        character.getCurrentStateJson(), CharacterStateDTO.class);

                String expression = charNode.path("expression").asText();
                if (expression != null && !expression.isEmpty()) {
                    newState.setEmotion(expression);
                }

                String costumeState = charNode.path("costume_state").asText();
                if (costumeState != null && !costumeState.isEmpty()) {
                    newState.setCostumeState(costumeState);
                }

                character.setCurrentStateJson(objectMapper.writeValueAsString(newState));
                characterRepository.updateById(character);

            } catch (Exception e) {
                log.warn("更新角色状态失败: charId={}", charId, e);
            }
        }
    }
}
