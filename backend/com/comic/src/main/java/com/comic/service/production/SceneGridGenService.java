package com.comic.service.production;

import com.comic.ai.image.ImageGenerationService;
import com.comic.dto.model.SceneGroupModel;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 场景九宫格生成服务
 * 为每个场景组生成3x3九宫格参考图
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SceneGridGenService {

    private final ImageGenerationService imageGenerationService;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final CharacterRepository characterRepository;
    private final ObjectMapper objectMapper;

    // 九宫格尺寸配置 (3x3, 每格16:9)
    private static final int GRID_COLUMNS = 3;
    private static final int GRID_ROWS = 3;
    private static final int PANEL_WIDTH = 1024;   // 每格宽度
    private static final int PANEL_HEIGHT = 576;   // 每格高度 (16:9)
    private static final int GRID_WIDTH = PANEL_WIDTH * GRID_COLUMNS + (GRID_COLUMNS - 1) * 4; // 加分隔线
    private static final int GRID_HEIGHT = PANEL_HEIGHT * GRID_ROWS + (GRID_ROWS - 1) * 4;

    /**
     * 为场景组生成九宫格图
     *
     * @param episodeId 剧集ID
     * @param sceneGroup 场景分组
     * @return 九宫格图URL
     */
    public String generateSceneGrid(Long episodeId, SceneGroupModel sceneGroup) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("剧集不存在: " + episodeId);
        }

        Project project = projectRepository.findByProjectId(episode.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + episode.getProjectId());
        }

        // 获取角色参考图
        List<Character> characters = getCharactersForScene(episode.getProjectId(), sceneGroup.getCharacters());

        // 构建九宫格提示词
        String prompt = buildGridPrompt(project, sceneGroup, characters);

        // 计算九宫格总尺寸
        int totalWidth = PANEL_WIDTH * GRID_COLUMNS + (GRID_COLUMNS - 1) * 4;
        int totalHeight = PANEL_HEIGHT * GRID_ROWS + (GRID_ROWS - 1) * 4;

        // 调用图片生成服务
        log.info("开始生成场景九宫格: episodeId={}, sceneId={}, size={}x{}",
                episodeId, sceneGroup.getSceneId(), totalWidth, totalHeight);

        String imageUrl = imageGenerationService.generate(
                prompt,
                totalWidth,
                totalHeight,
                project.getVisualStyle()
        );

        log.info("场景九宫格生成完成: sceneId={}, url={}", sceneGroup.getSceneId(), imageUrl);
        return imageUrl;
    }

    /**
     * 构建九宫格提示词
     * 参考文档六部分结构
     */
    private String buildGridPrompt(Project project, SceneGroupModel sceneGroup, List<Character> characters) {
        StringBuilder prompt = new StringBuilder();

        // 第一部分：布局规范
        prompt.append("请生成一个3行3列的九宫格图片。\n");
        prompt.append("每个格子的比例是16:9（横屏）。\n");
        prompt.append("格子之间用4像素黑色细线分隔。\n");
        prompt.append("整体要求高清画质。\n\n");

        // 第二部分：角色一致性约束
        if (!characters.isEmpty()) {
            prompt.append("【角色一致性约束】\n");
            for (int i = 0; i < characters.size(); i++) {
                Character ch = characters.get(i);
                prompt.append("角色").append(i + 1).append("（代号：角色").append(i + 1).append("）：")
                      .append(ch.getName()).append("。");
                if (ch.getAppearance() != null) {
                    prompt.append("外貌：").append(ch.getAppearance()).append("。");
                }
                prompt.append("\n");
            }
            prompt.append("所有格子中的角色必须与上述描述完全一致，包括脸型、发型、衣服、肤色。\n");
            prompt.append("这是不可商量的硬性要求。\n\n");
        }

        // 第三部分：场景一致性约束
        prompt.append("【场景一致性约束】\n");
        prompt.append("本场景位置：").append(sceneGroup.getLocation()).append("。\n");
        prompt.append("所有格子的场景环境、光线、色调必须完全一致。\n");
        prompt.append("就像同一个场景用不同机位拍摄。\n\n");

        // 第四部分：负面约束
        prompt.append("【负面约束】\n");
        prompt.append("绝对禁止出现任何文字、数字、字母、字幕、水印、对话框气泡、面板编号。\n");
        prompt.append("唯一例外：场景中实物上的文字（如路牌、书封面、横幅）。\n\n");

        // 第五部分：逐格画面描述
        prompt.append("【逐格画面描述】\n");
        prompt.append(buildPanelDescriptions(sceneGroup));

        // 第六部分：风格描述
        prompt.append("【风格描述】\n");
        prompt.append(project.getVisualStyle() != null ? project.getVisualStyle() : "3D动画风格");
        prompt.append("，电影级光影，高画质。\n");

        return prompt.toString();
    }

    /**
     * 构建逐格画面描述
     * 根据场景组中的分镜生成九宫格描述
     */
    private String buildPanelDescriptions(SceneGroupModel sceneGroup) {
        StringBuilder sb = new StringBuilder();

        // 这里简化处理，生成9个格子的描述
        // 实际应该从storyboardJson中提取具体分镜信息
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLUMNS; col++) {
                int panelNum = row * GRID_COLUMNS + col + 1;
                sb.append("第").append(panelNum).append("格：\n");

                // 根据场景信息生成描述
                sb.append("景别：中景。\n");
                sb.append("角度：视平角度。\n");
                sb.append("运镜：固定镜头。\n");
                sb.append("环境：").append(sceneGroup.getLocation()).append("。\n");

                // 如果有角色，描述角色动作
                if (sceneGroup.getCharacters() != null && !sceneGroup.getCharacters().isEmpty()) {
                    sb.append("内容：");
                    for (int i = 0; i < sceneGroup.getCharacters().size(); i++) {
                        if (i > 0) sb.append("和");
                        sb.append("角色").append(i + 1);
                    }
                    sb.append("在").append(sceneGroup.getLocation()).append("中。\n");
                } else {
                    sb.append("内容：").append(sceneGroup.getLocation()).append("的全景。\n");
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取场景中的角色列表
     */
    private List<Character> getCharactersForScene(String projectId, List<String> characterNames) {
        if (characterNames == null || characterNames.isEmpty()) {
            return Collections.emptyList();
        }

        // 这里简化处理，获取项目的所有已确认角色
        List<Character> allCharacters = characterRepository.findByProjectId(projectId);

        // 过滤出场景中出现的角色
        return allCharacters.stream()
                .filter(ch -> characterNames.contains(ch.getName()) || Boolean.TRUE.equals(ch.getConfirmed()))
                .limit(9) // 九宫格最多9个格子，限制角色数量
                .collect(Collectors.toList());
    }

    /**
     * 从storyboard JSON中提取完整的分镜列表用于生成详细的九宫格
     */
    public String generateDetailedGridFromStoryboard(Long episodeId, SceneGroupModel sceneGroup, int startPanelIndex) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null || episode.getStoryboardJson() == null) {
            return null;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(episode.getStoryboardJson());
            JsonNode panelsNode = rootNode.get("panels");

            if (panelsNode == null || !panelsNode.isArray()) {
                return null;
            }

            StringBuilder gridDesc = new StringBuilder();
            int panelCount = Math.min(GRID_ROWS * GRID_COLUMNS, sceneGroup.getPanelCount());

            for (int i = 0; i < panelCount; i++) {
                int panelIndex = startPanelIndex + i;
                if (panelIndex >= panelsNode.size()) {
                    break;
                }

                JsonNode panelNode = panelsNode.get(panelIndex);
                gridDesc.append("第").append(i + 1).append("格：\n");

                // 提取景别
                JsonNode shotSizeNode = panelNode.get("shot_size");
                if (shotSizeNode != null) {
                    gridDesc.append("景别：").append(shotSizeNode.asText()).append("。\n");
                } else {
                    gridDesc.append("景别：中景。\n");
                }

                // 提取角度
                JsonNode angleNode = panelNode.get("camera_angle");
                if (angleNode != null) {
                    gridDesc.append("角度：").append(angleNode.asText()).append("。\n");
                } else {
                    gridDesc.append("角度：视平角度。\n");
                }

                // 提取运镜
                JsonNode movementNode = panelNode.get("camera_movement");
                if (movementNode != null) {
                    gridDesc.append("运镜：").append(movementNode.asText()).append("。\n");
                } else {
                    gridDesc.append("运镜：固定镜头。\n");
                }

                // 场景环境
                gridDesc.append("环境：").append(sceneGroup.getLocation()).append("。\n");

                // 提取画面描述
                JsonNode descNode = panelNode.get("description");
                if (descNode != null) {
                    gridDesc.append("内容：").append(descNode.asText()).append("。\n");
                }

                gridDesc.append("\n");
            }

            return gridDesc.toString();

        } catch (Exception e) {
            log.error("解析分镜JSON失败", e);
            return null;
        }
    }
}
