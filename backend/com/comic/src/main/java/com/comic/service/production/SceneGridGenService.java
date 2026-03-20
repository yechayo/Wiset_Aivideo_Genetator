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

        // 尝试从分镜 JSON 提取逐格描述
        String detailedPanelDesc = generateDetailedGridFromStoryboard(
                episodeId, sceneGroup,
                sceneGroup.getStartPanelIndex() != null ? sceneGroup.getStartPanelIndex() : 0
        );

        // 构建九宫格提示词
        String prompt = buildGridPrompt(project, sceneGroup, characters, detailedPanelDesc);

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
    private String buildGridPrompt(Project project, SceneGroupModel sceneGroup, List<Character> characters, String detailedPanelDesc) {
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
        if (sceneGroup.getTimeOfDay() != null) {
            prompt.append("时间：").append(sceneGroup.getTimeOfDay()).append("。\n");
        }
        if (sceneGroup.getLighting() != null) {
            prompt.append("光线：").append(sceneGroup.getLighting()).append("。\n");
        }
        if (sceneGroup.getMood() != null) {
            prompt.append("氛围：").append(sceneGroup.getMood()).append("。\n");
        }
        prompt.append("所有格子的场景环境、光线、色调必须完全一致。\n");
        prompt.append("就像同一个场景用不同机位拍摄。\n\n");

        // 第四部分：负面约束
        prompt.append("【负面约束】\n");
        prompt.append("绝对禁止出现任何文字、数字、字母、字幕、水印、对话框气泡、面板编号。\n");
        prompt.append("唯一例外：场景中实物上的文字（如路牌、书封面、横幅）。\n");
        prompt.append("每个格子必须是不同的画面内容，严禁重复相同的构图。\n\n");

        // 第五部分：逐格画面描述
        prompt.append("【逐格画面描述】\n");
        if (detailedPanelDesc != null && !detailedPanelDesc.isEmpty()) {
            prompt.append(detailedPanelDesc);
        } else {
            prompt.append(buildFallbackPanelDescriptions(sceneGroup));
        }

        // 第六部分：风格描述
        prompt.append("【风格描述】\n");
        prompt.append(project.getVisualStyle() != null ? project.getVisualStyle() : "3D动画风格");
        prompt.append("，电影级光影，高画质。\n");

        return prompt.toString();
    }

    /**
     * 构建差异化的 fallback 逐格描述（无分镜 JSON 时使用）
     * 每个格子使用不同的景别、角度和构图
     */
    private String buildFallbackPanelDescriptions(SceneGroupModel sceneGroup) {
        String[] shotTypes = {"远景", "全景", "中景", "中近景", "近景", "特写", "过肩镜头", "仰拍全景", "俯拍全景"};
        String[] angles = {"视平角度", "低角度仰拍", "高角度俯拍", "侧面角度", "正面角度", "斜45度角", "鸟瞰角度", " worms-eye仰拍", "荷兰角（倾斜）"};
        String[] actions = {
                "建立镜头，展示整体环境",
                "角色走入画面",
                "角色交谈互动",
                "角色反应/表情变化",
                "关键道具/物品特写",
                "角色做出重要动作",
                "两人对峙或对视",
                "角色独白或沉思",
                "场景高潮或转折瞬间"
        };

        StringBuilder sb = new StringBuilder();
        String location = sceneGroup.getLocation() != null ? sceneGroup.getLocation() : "";
        List<String> chars = sceneGroup.getCharacters() != null ? sceneGroup.getCharacters() : Collections.emptyList();
        String charStr = chars.isEmpty() ? "" : String.join("和", chars);

        for (int i = 0; i < GRID_ROWS * GRID_COLUMNS; i++) {
            sb.append("第").append(i + 1).append("格：\n");
            sb.append("景别：").append(shotTypes[i]).append("。\n");
            sb.append("角度：").append(angles[i]).append("。\n");
            sb.append("运镜：固定镜头。\n");
            sb.append("环境：").append(location).append("。\n");
            sb.append("内容：");
            sb.append(actions[i]);
            if (!charStr.isEmpty()) {
                sb.append("，").append(charStr).append("在场");
            }
            sb.append("。\n\n");
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
