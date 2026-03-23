package com.comic.service.production;

import com.comic.ai.image.ImageGenerationService;
import com.comic.dto.model.SceneGroupModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scene grid generation service.
 * Supports both 3x3 (9 cells) and 2x3 (6 cells) layouts with per-scene-group pagination.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SceneGridGenService {

    private final ImageGenerationService imageGenerationService;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    private static final int PANEL_WIDTH = 1024;
    private static final int PANEL_HEIGHT = 576;
    private static final int GRID_GAP_PX = 4;
    private static final String DEFAULT_SHOT_TYPE = "MEDIUM_SHOT";
    private static final String DEFAULT_CAMERA_ANGLE = "eye_level";
    private static final String DEFAULT_CAMERA_MOVEMENT = "static";
    private static final String PLACEHOLDER_DESCRIPTION =
            "Solid gray placeholder cell. Ignore this cell for visual content.";

    /**
     * Backward-compatible single page generation. Returns the first generated page URL.
     */
    public String generateSceneGrid(Long episodeId, SceneGroupModel sceneGroup) {
        List<String> pageUrls = generateSceneGridPages(episodeId, sceneGroup);
        if (pageUrls.isEmpty()) {
            throw new IllegalStateException("No scene grid page was generated.");
        }
        return pageUrls.get(0);
    }

    /**
     * Generate one or more grid pages for a scene group.
     * Layout selection: panelCount <= 6 uses 2x3, otherwise 3x3.
     */
    public List<String> generateSceneGridPages(Long episodeId, SceneGroupModel sceneGroup) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }

        Project project = projectRepository.findByProjectId(episode.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + episode.getProjectId());
        }

        int panelCount = Math.max(sceneGroup != null ? sceneGroup.getPanelCount() : 0, 0);
        GridLayout layout = resolveLayoutForSceneGroup(sceneGroup);
        int cellsPerPage = layout.getCellCount();
        int totalPages = Math.max(1, (panelCount + cellsPerPage - 1) / cellsPerPage);

        int startPanelIndex = sceneGroup != null && sceneGroup.getStartPanelIndex() != null
                ? sceneGroup.getStartPanelIndex() : 0;
        List<String> pageUrls = new ArrayList<>(totalPages);

        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            int pageStartPanelIndex = startPanelIndex + pageIndex * cellsPerPage;
            int remainingPanels = Math.max(panelCount - pageIndex * cellsPerPage, 0);
            int validCount = Math.min(cellsPerPage, remainingPanels);

            String detailedPanelDesc = generateDetailedGridFromStoryboard(
                    episodeId,
                    sceneGroup,
                    pageStartPanelIndex,
                    layout.getRows(),
                    layout.getCols(),
                    validCount
            );

            String prompt = buildGridPrompt(project, sceneGroup, layout, pageIndex, detailedPanelDesc);
            int totalWidth = PANEL_WIDTH * layout.getCols() + (layout.getCols() - 1) * GRID_GAP_PX;
            int totalHeight = PANEL_HEIGHT * layout.getRows() + (layout.getRows() - 1) * GRID_GAP_PX;

            log.info(
                    "Generating scene grid page: episodeId={}, sceneId={}, page={}/{}, layout={}x{}, validCells={}",
                    episodeId,
                    sceneGroup != null ? sceneGroup.getSceneId() : "unknown",
                    pageIndex + 1,
                    totalPages,
                    layout.getRows(),
                    layout.getCols(),
                    validCount
            );

            String imageUrl = imageGenerationService.generate(
                    prompt,
                    totalWidth,
                    totalHeight,
                    project.getVisualStyle()
            );
            pageUrls.add(imageUrl);
        }

        return pageUrls;
    }

    /**
     * Resolve layout by scene-group panel count.
     * <= 6 -> 2x3; > 6 -> 3x3.
     */
    public GridLayout resolveLayoutForSceneGroup(SceneGroupModel sceneGroup) {
        int panelCount = Math.max(sceneGroup != null ? sceneGroup.getPanelCount() : 0, 0);
        if (panelCount <= 6) {
            return GridLayout.twoByThree();
        }
        return GridLayout.threeByThree();
    }

    /**
     * Resolve expected cells per page for a scene group.
     */
    public int resolveCellsPerPage(SceneGroupModel sceneGroup) {
        return resolveLayoutForSceneGroup(sceneGroup).getCellCount();
    }

    /**
     * Legacy signature kept for compatibility.
     */
    public String generateDetailedGridFromStoryboard(Long episodeId, SceneGroupModel sceneGroup, int startPanelIndex) {
        GridLayout layout = resolveLayoutForSceneGroup(sceneGroup);
        int cellsPerPage = layout.getCellCount();
        return generateDetailedGridFromStoryboard(
                episodeId, sceneGroup, startPanelIndex, layout.getRows(), layout.getCols(), cellsPerPage
        );
    }

    /**
     * Build a fixed-size grid metadata JSON array.
     * Grid cells are aligned by index with storyboard panels in row-major order.
     */
    public String generateDetailedGridFromStoryboard(
            Long episodeId,
            SceneGroupModel sceneGroup,
            int startPanelIndex,
            int rows,
            int cols,
            int validCellCount
    ) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null || episode.getStoryboardJson() == null) {
            return null;
        }

        int totalCells = Math.max(rows, 0) * Math.max(cols, 0);
        int safeValidCount = Math.max(0, Math.min(validCellCount, totalCells));

        try {
            JsonNode rootNode = objectMapper.readTree(episode.getStoryboardJson());
            JsonNode panelsNode = rootNode.get("panels");
            if (panelsNode == null || !panelsNode.isArray()) {
                return null;
            }

            ArrayNode gridArray = objectMapper.createArrayNode();

            for (int i = 0; i < totalCells; i++) {
                ObjectNode cellNode = objectMapper.createObjectNode();
                cellNode.put("index", i);

                if (i < safeValidCount) {
                    int panelIndex = startPanelIndex + i;
                    if (panelIndex >= 0 && panelIndex < panelsNode.size()) {
                        JsonNode panelNode = panelsNode.get(panelIndex);
                        fillValidCell(cellNode, panelNode);
                    } else {
                        fillPlaceholderCell(cellNode);
                    }
                } else {
                    fillPlaceholderCell(cellNode);
                }
                gridArray.add(cellNode);
            }

            return objectMapper.writeValueAsString(gridArray);
        } catch (Exception e) {
            log.error("Failed to build grid metadata from storyboard JSON.", e);
            return null;
        }
    }

    private void fillValidCell(ObjectNode cellNode, JsonNode panelNode) {
        cellNode.put("type", "valid");

        String shotType = getText(panelNode, "shot_type");
        if (shotType == null || shotType.isEmpty()) {
            shotType = getText(panelNode, "shot_size"); // fallback for legacy data
        }
        cellNode.put("shotType", shotType != null && !shotType.isEmpty() ? shotType : DEFAULT_SHOT_TYPE);

        String cameraAngle = getText(panelNode, "camera_angle");
        cellNode.put("cameraAngle", cameraAngle != null && !cameraAngle.isEmpty() ? cameraAngle : DEFAULT_CAMERA_ANGLE);

        String cameraMovement = getText(panelNode, "camera_movement");
        cellNode.put("cameraMovement",
                cameraMovement != null && !cameraMovement.isEmpty() ? cameraMovement : DEFAULT_CAMERA_MOVEMENT);

        String composition = getText(panelNode, "composition");
        if (composition == null || composition.isEmpty()) {
            composition = getText(panelNode, "description"); // fallback for legacy data
        }
        cellNode.put("description", composition != null ? composition : "");
    }

    private void fillPlaceholderCell(ObjectNode cellNode) {
        cellNode.put("type", "placeholder");
        cellNode.put("shotType", "");
        cellNode.put("cameraAngle", "");
        cellNode.put("cameraMovement", "");
        cellNode.put("description", PLACEHOLDER_DESCRIPTION);
    }

    private String buildGridPrompt(
            Project project,
            SceneGroupModel sceneGroup,
            GridLayout layout,
            int pageIndex,
            String detailedPanelDesc
    ) {
        String location = sceneGroup != null && sceneGroup.getLocation() != null ? sceneGroup.getLocation() : "";
        String timeOfDay = sceneGroup != null ? safeText(sceneGroup.getTimeOfDay()) : "";
        String mood = sceneGroup != null ? safeText(sceneGroup.getMood()) : "";
        String style = project != null && project.getVisualStyle() != null ? project.getVisualStyle() : "cinematic";

        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a storyboard grid image with ")
                .append(layout.getRows()).append(" rows and ")
                .append(layout.getCols()).append(" columns.\n");
        prompt.append("Each cell must be 16:9 landscape, with thin black separators between cells.\n");
        prompt.append("Page index: ").append(pageIndex + 1).append(".\n\n");

        prompt.append("[Scene Constraints]\n");
        prompt.append("Location: ").append(location).append('\n');
        if (!timeOfDay.isEmpty()) {
            prompt.append("Time of day: ").append(timeOfDay).append('\n');
        }
        if (!mood.isEmpty()) {
            prompt.append("Mood: ").append(mood).append('\n');
        }
        prompt.append("Keep visual style consistent across all cells on this page.\n\n");

        prompt.append("[Cell Instructions]\n");
        if (detailedPanelDesc != null && !detailedPanelDesc.isEmpty()) {
            prompt.append(convertGridJsonToPrompt(detailedPanelDesc, location));
        } else {
            prompt.append(buildFallbackPanelDescriptions(layout, location));
        }

        prompt.append("\n[Negative Constraints]\n");
        prompt.append("No UI overlays, no subtitles, no watermark, no panel number text.\n");
        prompt.append("Placeholder cells must remain simple solid gray.\n\n");

        prompt.append("[Style]\n").append(style);
        return prompt.toString();
    }

    private String buildFallbackPanelDescriptions(GridLayout layout, String location) {
        int cells = layout.getCellCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells; i++) {
            sb.append("Cell ").append(i + 1).append(": ");
            sb.append("shot=").append(DEFAULT_SHOT_TYPE).append(", ");
            sb.append("angle=").append(DEFAULT_CAMERA_ANGLE).append(", ");
            sb.append("movement=").append(DEFAULT_CAMERA_MOVEMENT).append(", ");
            sb.append("location=").append(location).append(".\n");
        }
        return sb.toString();
    }

    /**
     * Convert grid metadata JSON to natural-language prompt lines.
     */
    private String convertGridJsonToPrompt(String gridJson, String location) {
        try {
            JsonNode gridArray = objectMapper.readTree(gridJson);
            if (!gridArray.isArray()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode cell : gridArray) {
                int index = cell.path("index").asInt();
                String type = cell.path("type").asText();

                sb.append("Cell ").append(index + 1).append(": ");
                if ("placeholder".equals(type)) {
                    sb.append("solid gray placeholder.\n");
                    continue;
                }

                String shotType = safeText(cell.path("shotType").asText(DEFAULT_SHOT_TYPE));
                String cameraAngle = safeText(cell.path("cameraAngle").asText(DEFAULT_CAMERA_ANGLE));
                String movement = safeText(cell.path("cameraMovement").asText(DEFAULT_CAMERA_MOVEMENT));
                String description = safeText(cell.path("description").asText(""));

                sb.append("shot=").append(shotType)
                        .append(", angle=").append(cameraAngle)
                        .append(", movement=").append(movement)
                        .append(", location=").append(safeText(location));
                if (!description.isEmpty()) {
                    sb.append(", detail=").append(description);
                }
                sb.append(".\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to convert grid metadata JSON to prompt text.", e);
            return "";
        }
    }

    private String getText(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        if (fieldNode == null || !fieldNode.isValueNode()) {
            return null;
        }
        String text = fieldNode.asText();
        return text == null ? null : text.trim();
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    @Getter
    public static final class GridLayout {
        private final int rows;
        private final int cols;

        private GridLayout(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
        }

        public static GridLayout threeByThree() {
            return new GridLayout(3, 3);
        }

        public static GridLayout twoByThree() {
            return new GridLayout(2, 3);
        }

        public int getCellCount() {
            return rows * cols;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%dx%d", rows, cols);
        }
    }
}
