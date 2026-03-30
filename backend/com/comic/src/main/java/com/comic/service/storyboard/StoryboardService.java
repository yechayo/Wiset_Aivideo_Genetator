package com.comic.service.storyboard;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.panel.GridImageService;
import com.comic.service.pipeline.PipelineService;
import com.comic.service.pipeline.ProjectStatusBroadcaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StoryboardService {

    private static final int MAX_PANEL_DURATION = 16;

    @Resource
    private DeepSeekTextService deepSeekTextService;
    @Resource
    private EpisodeRepository episodeRepository;
    @Resource
    private PanelRepository panelRepository;
    @Resource
    private ProjectRepository projectRepository;
    @Resource
    private PipelineService pipelineService;
    @Resource
    private ProjectStatusBroadcaster broadcaster;
    @Resource
    private GridImageService gridImageService;

    /**
     * 主入口：生成结构化分集剧本 → 分镜脚本 → 贪心分组 → 创建 Panel
     * 被PipelineService异步调用
     */
    @Transactional
    public void generateEpisodeScriptAndStoryboard(String projectId) {
        try {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null) {
                throw new BusinessException("项目不存在: " + projectId);
            }
            Map<String, Object> projectInfo = project.getProjectInfo();
            String visualStyle = (String) projectInfo.getOrDefault("visualStyle", "ANIME");
            int targetDuration = getIntFromMap(projectInfo, "episodeDuration", 60);
            String outline = (String) projectInfo.getOrDefault("scriptOutline", "");
            String charactersDesc = getCharacterDescriptions(projectId);

            // 1. 生成结构化分集剧本
            List<Map<String, Object>> scripts = deepSeekTextService.generateEpisodeScript(
                outline, charactersDesc, targetDuration, visualStyle);

            // 2. 逐集生成分镜并创建Panel
            for (Map<String, Object> script : scripts) {
                String content = (String) script.get("content");
                String characters = (String) script.getOrDefault("characters", "");

                List<Map<String, Object>> shots = deepSeekTextService.generateStoryboard(
                    content, characters, targetDuration, visualStyle);

                Long episodeId = findOrCreateEpisode(projectId, script, shots, visualStyle);
                deleteExistingPanels(episodeId);

                // 设置 episodeInfo.gridStatus = "generating"，异步生成整集九宫格
                gridImageService.updateEpisodeGridStatus(episodeId, "generating");
                gridImageService.generateGridsForEpisode(episodeId, shots, visualStyle);
            }

            // 3. 推进状态：两步推进
            // EPISODE_SCRIPT_GENERATING → "episode_script_generated" → STORYBOARD_GENERATING
            pipelineService.advancePipeline(projectId, "episode_script_generated");
            // STORYBOARD_GENERATING → "storyboard_generated" → STORYBOARD_REVIEW
            pipelineService.advancePipeline(projectId, "storyboard_generated");

        } catch (Exception e) {
            log.error("分镜生成异常: projectId={}", projectId, e);
            try {
                // 根据当前状态选择正确的失败事件
                Project current = projectRepository.findByProjectId(projectId);
                if (current != null) {
                    String status = current.getStatus();
                    if (ProjectStatus.EPISODE_SCRIPT_GENERATING.getCode().equals(status)) {
                        pipelineService.advancePipeline(projectId, "episode_script_failed");
                    } else if (ProjectStatus.STORYBOARD_GENERATING.getCode().equals(status)) {
                        pipelineService.advancePipeline(projectId, "storyboard_failed");
                    }
                }
            } catch (Exception ex) {
                log.error("Failed to set failed status: projectId={}", projectId, ex);
            }
        }
    }

    /**
     * 贪心分组算法（纯函数，可独立测试）
     */
    public static List<List<Map<String, Object>>> greedyGroup(
            List<Map<String, Object>> shots, int maxDuration) {
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<Map<String, Object>> currentGroup = new ArrayList<>();
        int currentDuration = 0;

        for (Map<String, Object> shot : shots) {
            int duration = ((Number) shot.get("duration")).intValue();
            if (duration > maxDuration) {
                duration = maxDuration;
                shot.put("duration", duration);
            }

            if (currentDuration + duration > maxDuration && !currentGroup.isEmpty()) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentDuration = 0;
            }
            currentGroup.add(shot);
            currentDuration += duration;
        }
        if (!currentGroup.isEmpty()) groups.add(currentGroup);
        return groups;
    }

    // --- 私有方法 ---

    private String getCharacterDescriptions(String projectId) {
        // TODO: 实现 CharacterService.getCharacterDescriptions(projectId)
        return "";
    }

    private Long findOrCreateEpisode(String projectId, Map<String, Object> script,
                                       List<Map<String, Object>> shots, String visualStyle) {
        String title = (String) script.get("title");
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            Map<String, Object> info = ep.getEpisodeInfo();
            if (info != null && title.equals(info.get("title"))) {
                info.putAll(script);
                // 新流程：shots 存入 episodeInfo
                info.put("shots", shots);
                info.put("visualStyle", visualStyle);
                info.put("gridStatus", "pending");
                ep.setEpisodeInfo(info);
                episodeRepository.updateById(ep);
                return ep.getId();
            }
        }
        Episode episode = new Episode();
        episode.setProjectId(projectId);
        episode.setStatus("pending");
        episode.setDeleted(false);
        Map<String, Object> episodeInfo = new HashMap<>(script);
        episodeInfo.put("shots", shots);
        episodeInfo.put("visualStyle", visualStyle);
        episodeInfo.put("gridStatus", "pending");
        episode.setEpisodeInfo(episodeInfo);
        episodeRepository.insert(episode);
        return episode.getId();
    }

    private void deleteExistingPanels(Long episodeId) {
        List<Panel> existing = panelRepository.findByEpisodeId(episodeId);
        for (Panel p : existing) {
            p.setDeleted(true);
            panelRepository.updateById(p);
        }
    }

    private void createPanels(Long episodeId, List<List<Map<String, Object>>> groups, String visualStyle) {
        for (List<Map<String, Object>> group : groups) {
            Panel panel = new Panel();
            panel.setEpisodeId(episodeId);
            panel.setStatus("pending");
            panel.setDeleted(false);
            Map<String, Object> panelInfo = new HashMap<>();
            panelInfo.put("shots", group);
            panelInfo.put("totalShots", group.size());
            panelInfo.put("totalDuration",
                group.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum());
            panelInfo.put("gridStatus", "pending");
            panelInfo.put("gridPageCount", (int) Math.ceil(group.size() / 9.0));
            panelInfo.put("gridImages", new ArrayList<String>());
            panelInfo.put("videoStatus", "pending");
            panelInfo.put("visualStyle", visualStyle);
            panel.setPanelInfo(panelInfo);
            panelRepository.insert(panel);
        }
    }

    private int getIntFromMap(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }
}
