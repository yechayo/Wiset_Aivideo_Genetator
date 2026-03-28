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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final ObjectMapper objectMapper;

    private String getCharacterInfoStr(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    @Cacheable(value = "characterStates", key = "#projectId")
    public List<CharacterStateModel> getCurrentStates(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        List<CharacterStateModel> dtos = new ArrayList<>();

        for (Character character : characters) {
            try {
                CharacterStateModel dto = parseOrDefault(character);
                dto.setCharId(getCharacterInfoStr(character, "charId"));
                dto.setName(getCharacterInfoStr(character, "name"));
                dtos.add(dto);
            } catch (Exception e) {
                log.warn("Failed to parse character state: charId={}", getCharacterInfoStr(character, "charId"), e);
            }
        }
        return dtos;
    }

    @CacheEvict(value = "characterStates", key = "#projectId")
    public void updateStatesFromPanelJson(String projectId, JsonNode panelJson) {
        JsonNode panels = panelJson.get("panels");
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

            Character character = characterRepository.findByCharId(charId);
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

                character.getCharacterInfo().put("currentStateJson", objectMapper.writeValueAsString(newState));
                characterRepository.updateById(character);
            } catch (Exception e) {
                log.warn("Failed to update character state: charId={}", charId, e);
            }
        }
    }

    private CharacterStateModel parseOrDefault(Character character) throws Exception {
        String stateJson = getCharacterInfoStr(character, "currentStateJson");
        if (stateJson == null || stateJson.trim().isEmpty()) {
            CharacterStateModel state = new CharacterStateModel();
            state.setCharId(getCharacterInfoStr(character, "charId"));
            state.setName(getCharacterInfoStr(character, "name"));
            state.setLocation("unknown");
            state.setEmotion("neutral");
            state.setCostumeState("normal");
            return state;
        }
        return objectMapper.readValue(stateJson, CharacterStateModel.class);
    }
}