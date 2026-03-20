package com.comic.service.production;

import com.comic.ai.image.ImageGenerationService;
import com.comic.dto.model.SceneGroupModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

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
    private final ObjectMapper objectMapper;

    // 九宫格尺寸配置 (3x3, 每格16:9)
    private static final int GRID_COLUMNS = 3;
    private static final int GRID_ROWS = 3;
    private static final int PANEL_WIDTH = 1024;   // 每格宽度
    private static final int PANEL_HEIGHT = 576;   // 每格高度 (16:9)

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

        // 尝试从分镜 JSON 提取逐格描述
        String detailedPanelDesc = generateDetailedGridFromStoryboard(
                episodeId, sceneGroup,
                sceneGroup.getStartPanelIndex() != null ? sceneGroup.getStartPanelIndex() : 0
        );

        // 构建九宫格提示词（纯场景，不含角色）
        String prompt = buildGridPrompt(project, sceneGroup, detailedPanelDesc);

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
    private String buildGridPrompt(Project project, SceneGroupModel sceneGroup, String detailedPanelDesc) {
        StringBuilder prompt = new StringBuilder();

        // 第一部分：布局规范
        prompt.append("请生成一个3行3列的九宫格图片。\n");
        prompt.append("每个格子的比例是16:9（横屏）。\n");
        prompt.append("格子之间用4像素黑色细线分隔。\n");
        prompt.append("整体要求高清画质。\n\n");

        // 第二部分：纯场景约束
        prompt.append("【纯场景约束】\n");
        prompt.append("本九宫格只生成纯环境场景，不包含任何人物角色、动物、拟人化生物。\n");
        prompt.append("所有格子中只能出现建筑、自然景观、道具、光影等环境元素。\n\n");

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
            prompt.append(convertGridJsonToPrompt(detailedPanelDesc, sceneGroup.getLocation()));
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
                "建立镜头，展示整体环境全貌",
                "中景展示场景主要区域",
                "近景展示场景细节与纹理",
                "展示光影变化与氛围",
                "关键道具/物品特写",
                "展示场景纵深感与层次",
                "展示场景另一侧或角落",
                "低角度或俯拍展示特殊视角",
                "场景全景或氛围高潮瞬间"
        };

        StringBuilder sb = new StringBuilder();
        String location = sceneGroup.getLocation() != null ? sceneGroup.getLocation() : "";

        for (int i = 0; i < GRID_ROWS * GRID_COLUMNS; i++) {
            sb.append("第").append(i + 1).append("格：\n");
            sb.append("景别：").append(shotTypes[i]).append("。\n");
            sb.append("角度：").append(angles[i]).append("。\n");
            sb.append("运镜：固定镜头。\n");
            sb.append("环境：").append(location).append("。\n");
            sb.append("内容：").append(actions[i]).append("。\n\n");
        }

        return sb.toString();
    }

    /**
     * 从storyboard JSON中提取完整的分镜列表，生成固定3x3九宫格的结构化JSON。
     * 有效内容从第1格开始排列，不足9格的部分用纯灰色占位格填充在尾部。
     *
     * @return JSON字符串，包含固定9个格子，每个格子有 type/description/shotSize/cameraAngle/cameraMovement 字段；
     *         解析失败时返回 null
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

            int totalCells = GRID_ROWS * GRID_COLUMNS; // 固定9格
            int validCount = Math.min(totalCells, sceneGroup.getPanelCount());
            ArrayNode gridArray = objectMapper.createArrayNode();

            for (int i = 0; i < totalCells; i++) {
                ObjectNode cellNode = objectMapper.createObjectNode();
                cellNode.put("index", i + 1);

                if (i < validCount) {
                    // 有效格子：从分镜JSON提取
                    int panelIndex = startPanelIndex + i;
                    cellNode.put("type", "valid");

                    if (panelIndex < panelsNode.size()) {
                        JsonNode panelNode = panelsNode.get(panelIndex);

                        JsonNode shotSizeNode = panelNode.get("shot_size");
                        cellNode.put("shotSize", shotSizeNode != null ? shotSizeNode.asText() : "中景");

                        JsonNode angleNode = panelNode.get("camera_angle");
                        cellNode.put("cameraAngle", angleNode != null ? angleNode.asText() : "视平角度");

                        JsonNode movementNode = panelNode.get("camera_movement");
                        cellNode.put("cameraMovement", movementNode != null ? movementNode.asText() : "固定镜头");

                        JsonNode descNode = panelNode.get("description");
                        cellNode.put("description", descNode != null ? descNode.asText() : "");
                    } else {
                        // panelIndex 超出分镜数组范围，也视为占位
                        fillPlaceholderCell(cellNode, sceneGroup.getLocation());
                    }
                } else {
                    // 占位格子：纯灰色背景
                    fillPlaceholderCell(cellNode, sceneGroup.getLocation());
                }

                gridArray.add(cellNode);
            }

            return objectMapper.writeValueAsString(gridArray);

        } catch (Exception e) {
            log.error("解析分镜JSON失败", e);
            return null;
        }
    }

    /**
     * 填充占位格子（纯灰色背景）
     */
    private void fillPlaceholderCell(ObjectNode cellNode, String location) {
        cellNode.put("type", "placeholder");
        cellNode.put("shotSize", "");
        cellNode.put("cameraAngle", "");
        cellNode.put("cameraMovement", "");
        cellNode.put("description", "纯灰色背景占位格，无需关注此格内容");
    }

    /**
     * 将九宫格JSON转换为文本描述，拼入图片生成prompt。
     * 有效格子输出详细描述，占位格子输出纯灰色占位提示。
     */
    private String convertGridJsonToPrompt(String gridJson, String location) {
        try {
            JsonNode gridArray = objectMapper.readTree(gridJson);
            if (!gridArray.isArray()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode cell : gridArray) {
                int index = cell.get("index").asInt();
                String type = cell.get("type").asText();

                sb.append("第").append(index).append("格：\n");

                if ("placeholder".equals(type)) {
                    sb.append("内容：纯灰色背景，不绘制任何场景或物体。\n\n");
                } else {
                    String shotSize = cell.has("shotSize") ? cell.get("shotSize").asText() : "中景";
                    String cameraAngle = cell.has("cameraAngle") ? cell.get("cameraAngle").asText() : "视平角度";
                    String movement = cell.has("cameraMovement") ? cell.get("cameraMovement").asText() : "固定镜头";
                    String desc = cell.has("description") ? cell.get("description").asText() : "";

                    sb.append("景别：").append(shotSize).append("。\n");
                    sb.append("角度：").append(cameraAngle).append("。\n");
                    sb.append("运镜：").append(movement).append("。\n");
                    sb.append("环境：").append(location != null ? location : "").append("。\n");
                    if (!desc.isEmpty()) {
                        sb.append("内容：").append(desc).append("。\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("转换九宫格JSON为prompt失败", e);
            return "";
        }
    }
}
