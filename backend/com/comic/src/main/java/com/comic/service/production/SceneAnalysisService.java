package com.comic.service.production;

import com.comic.dto.model.SceneAnalysisResultModel;
import com.comic.dto.model.SceneGroupModel;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景分析服务
 * 从分镜JSON中分析场景信息，对分镜进行场景分组
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SceneAnalysisService {

    private final EpisodeRepository episodeRepository;
    private final ObjectMapper objectMapper;

    /**
     * 分析剧集的分镜场景
     *
     * @param episodeId 剧集ID
     * @return 场景分析结果
     */
    public SceneAnalysisResultModel analyzeScenes(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("剧集不存在: " + episodeId);
        }

        String storyboardJson = getEpisodeInfoStr(episode, "storyboardJson");
        if (storyboardJson == null || storyboardJson.isEmpty()) {
            throw new IllegalArgumentException("剧集分镜数据为空，请先生成分镜");
        }

        try {
            JsonNode rootNode = objectMapper.readTree(storyboardJson);
            JsonNode panelsNode = rootNode.get("panels");

            if (panelsNode == null || !panelsNode.isArray()) {
                throw new IllegalArgumentException("分镜数据格式错误，缺少panels数组");
            }

            List<PanelData> panels = parsePanels(panelsNode);
            List<SceneGroupModel> sceneGroups = groupPanelsByScene(panels);

            SceneAnalysisResultModel result = new SceneAnalysisResultModel();
            result.setSceneGroups(sceneGroups);
            result.setTotalPanelCount(panels.size());
            result.setSceneCount(sceneGroups.size());

            log.info("剧集{}场景分析完成: {}个分镜, {}个场景", episodeId, panels.size(), sceneGroups.size());
            return result;

        } catch (Exception e) {
            log.error("场景分析失败: episodeId={}", episodeId, e);
            throw new RuntimeException("场景分析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析分镜数据
     */
    private List<PanelData> parsePanels(JsonNode panelsNode) {
        List<PanelData> panels = new ArrayList<>();
        int index = 0;

        for (JsonNode panelNode : panelsNode) {
            PanelData panel = new PanelData();
            panel.setIndex(index++);

            JsonNode backgroundNode = panelNode.get("background");
            if (backgroundNode == null || !backgroundNode.isObject()) {
                throw new IllegalArgumentException("分镜格式错误：background 必须是对象");
            }

            JsonNode sceneDescNode = backgroundNode.get("scene_desc");
            if (sceneDescNode == null || !sceneDescNode.isTextual()
                    || sceneDescNode.asText().trim().isEmpty()) {
                throw new IllegalArgumentException("分镜格式错误：background.scene_desc 不能为空");
            }
            panel.setScene(sceneDescNode.asText().trim());

            JsonNode timeOfDayNode = backgroundNode.get("time_of_day");
            panel.setTimeOfDay(timeOfDayNode != null && timeOfDayNode.isTextual()
                    ? timeOfDayNode.asText().trim() : "");

            JsonNode atmosphereNode = backgroundNode.get("atmosphere");
            panel.setAtmosphere(atmosphereNode != null && atmosphereNode.isTextual()
                    ? atmosphereNode.asText().trim() : "");

            JsonNode charactersNode = panelNode.get("characters");
            if (charactersNode != null && charactersNode.isArray()) {
                List<String> characters = new ArrayList<>();
                for (JsonNode charNode : charactersNode) {
                    if (charNode != null && charNode.isObject()) {
                        JsonNode charIdNode = charNode.get("char_id");
                        if (charIdNode != null && charIdNode.isTextual()
                                && !charIdNode.asText().trim().isEmpty()) {
                            characters.add(charIdNode.asText().trim());
                        }
                    }
                }
                panel.setCharacters(characters);
            }

            JsonNode descriptionNode = panelNode.get("composition");
            panel.setDescription(descriptionNode != null ? descriptionNode.asText() : "");

            panels.add(panel);
        }

        return panels;
    }

    /**
     * 按场景对分镜进行分组
     * 使用简单的场景相似度算法：场景文本相似则认为是同一场景
     */
    private List<SceneGroupModel> groupPanelsByScene(List<PanelData> panels) {
        List<SceneGroupModel> sceneGroups = new ArrayList<>();

        if (panels.isEmpty()) {
            return sceneGroups;
        }

        // 当前场景组
        SceneGroupModel currentGroup = null;
        String currentScene = null;

        for (PanelData panel : panels) {
            // 判断是否需要创建新场景
            if (currentScene == null || !isSameScene(currentScene, panel.getScene())) {
                // 保存上一个场景组
                if (currentGroup != null) {
                    sceneGroups.add(currentGroup);
                }

                // 创建新场景组
                currentGroup = new SceneGroupModel();
                currentGroup.setSceneId("SCENE-" + sceneGroups.size());
                currentGroup.setStartPanelIndex(panel.getIndex());
                currentGroup.setLocation(panel.getScene());
                currentGroup.setCharacters(panel.getCharacters());
                currentGroup.setDescription(panel.getDescription());
                currentGroup.setTimeOfDay(panel.getTimeOfDay());
                currentGroup.setMood(panel.getAtmosphere());
                currentScene = panel.getScene();
            }

            // 更新当前场景组的结束索引
            if (currentGroup != null) {
                currentGroup.setEndPanelIndex(panel.getIndex());
            }
        }

        // 添加最后一个场景组
        if (currentGroup != null) {
            sceneGroups.add(currentGroup);
        }

        // 后处理：为每个场景组添加更多信息
        enhanceSceneGroupModels(sceneGroups, panels);

        return sceneGroups;
    }

    /**
     * 判断是否为同一场景
     * 简单实现：完全相同或高度相似
     */
    private boolean isSameScene(String scene1, String scene2) {
        if (scene1 == null || scene2 == null) {
            return false;
        }

        // 完全相同
        if (scene1.equals(scene2)) {
            return true;
        }

        // 去除空格和标点后比较
        String normalized1 = scene1.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
        String normalized2 = scene2.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();

        return normalized1.equals(normalized2);
    }

    /**
     * 增强场景组信息（添加时间、氛围等）
     */
    private void enhanceSceneGroupModels(List<SceneGroupModel> sceneGroups, List<PanelData> panels) {
        for (SceneGroupModel group : sceneGroups) {
            // 收集该场景中的所有角色
            Set<String> uniqueCharacters = new LinkedHashSet<>();
            for (int i = group.getStartPanelIndex(); i <= group.getEndPanelIndex(); i++) {
                if (i < panels.size()) {
                    List<String> chars = panels.get(i).getCharacters();
                    if (chars != null) {
                        uniqueCharacters.addAll(chars);
                    }
                }
            }
            group.setCharacters(new ArrayList<>(uniqueCharacters));

            if (group.getDescription() == null || group.getDescription().trim().isEmpty()) {
                group.setDescription(group.getLocation());
            }

            if (group.getTimeOfDay() == null || group.getTimeOfDay().trim().isEmpty()) {
                group.setTimeOfDay("白天");
            }
        }
    }

    private String getEpisodeInfoStr(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    /**
     * 分镜数据内部类
     */
    private static class PanelData {
        private Integer index;
        private String scene;
        private List<String> characters;
        private String description;
        private String timeOfDay;
        private String atmosphere;

        public Integer getIndex() { return index; }
        public void setIndex(Integer index) { this.index = index; }

        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }

        public List<String> getCharacters() { return characters; }
        public void setCharacters(List<String> characters) { this.characters = characters; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTimeOfDay() { return timeOfDay; }
        public void setTimeOfDay(String timeOfDay) { this.timeOfDay = timeOfDay; }

        public String getAtmosphere() { return atmosphere; }
        public void setAtmosphere(String atmosphere) { this.atmosphere = atmosphere; }
    }
}
