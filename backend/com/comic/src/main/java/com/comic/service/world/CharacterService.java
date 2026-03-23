package com.comic.service.world;

import com.comic.dto.model.CharacterStateModel;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final ObjectMapper objectMapper;

    @Cacheable(value = "characterStates", key = "#projectId")
    public List<CharacterStateModel> getCurrentStates(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        List<CharacterStateModel> dtos = new ArrayList<>();

        for (Character character : characters) {
            try {
                CharacterStateModel dto = parseOrDefault(character);
                dto.setCharId(character.getCharId());
                dto.setName(character.getName());
                dtos.add(dto);
            } catch (Exception e) {
                log.warn("Failed to parse character state: charId={}", character.getCharId(), e);
            }
        }
        return dtos;
    }

    @CacheEvict(value = "characterStates", key = "#projectId")
    public void updateStatesFromStoryboard(String projectId, JsonNode storyboardJson) {
        JsonNode panels = storyboardJson.get("panels");
        if (panels == null || !panels.isArray() || panels.size() == 0) {
            return;
        }

        JsonNode lastPanel = panels.get(panels.size() - 1);
        JsonNode characters = lastPanel.get("characters");
        if (characters == null || !characters.isArray()) {
            return;
        }

        for (JsonNode charNode : characters) {
            String charId = charNode.path("char_id").asText();
            if (charId == null || charId.isEmpty()) {
                continue;
            }

            Character character = characterRepository.findByProjectIdAndCharId(projectId, charId);
            if (character == null) {
                continue;
            }

            try {
                CharacterStateModel newState = parseOrDefault(character);

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
                log.warn("Failed to update character state: charId={}", charId, e);
            }
        }
    }

    private CharacterStateModel parseOrDefault(Character character) throws Exception {
        String stateJson = character.getCurrentStateJson();
        if (stateJson == null || stateJson.trim().isEmpty()) {
            CharacterStateModel state = new CharacterStateModel();
            state.setCharId(character.getCharId());
            state.setName(character.getName());
            state.setLocation("unknown");
            state.setEmotion("neutral");
            state.setCostumeState("normal");
            return state;
        }
        return objectMapper.readValue(stateJson, CharacterStateModel.class);
    }
}
