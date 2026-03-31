package com.comic.service.storyboard;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
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
    @Resource
    private CharacterRepository characterRepository;

    /**
     * 主入口：生成结构化分集剧本 → 分镜脚本 → 异步生成整集九宫格
     * 被PipelineService异步调用
     */
    @Transactional
    public void generateEpisodeScriptAndStoryboard(String projectId) {
        log.info("[Pipeline] 开始分集剧本+分镜生成: projectId={}", projectId);
        try {
            Project project = projectRepository.findByProjectId(projectId);
            if (project == null) {
                throw new BusinessException("项目不存在: " + projectId);
            }
            Map<String, Object> projectInfo = project.getProjectInfo();
            String visualStyle = (String) projectInfo.getOrDefault("visualStyle", "ANIME");
            int targetDuration = getIntFromMap(projectInfo, "episodeDuration", 60);
            // 正确读取 projectInfo.script.outline
            @SuppressWarnings("unchecked")
            Map<String, Object> scriptMap = (Map<String, Object>) projectInfo.get("script");
            String outline = scriptMap != null ? (String) scriptMap.getOrDefault("outline", "") : "";
            String charactersDesc = getCharacterDescriptions(projectId);

            // 1. 生成结构化分集剧本
            int totalEpisodes = getIntFromMap(projectInfo, "totalEpisodes", 1);
            log.info("[Pipeline] Step1: 调用DeepSeek生成分集剧本: projectId={}, totalEpisodes={}", projectId, totalEpisodes);
            List<Map<String, Object>> scripts = deepSeekTextService.generateEpisodeScript(
                outline, charactersDesc, targetDuration, visualStyle, totalEpisodes);
            log.info("[Pipeline] Step1完成: 生成 {} 集剧本, projectId={}", scripts.size(), projectId);

            // 2. 逐集生成分镜并创建Panel
            for (Map<String, Object> script : scripts) {
                String title = (String) script.get("title");
                String content = (String) script.get("content");
                String characters = (String) script.getOrDefault("characters", "");

                log.info("[Pipeline] Step2: 调用DeepSeek生成分镜: projectId={}, episode={}", projectId, title);
                List<Map<String, Object>> shots = deepSeekTextService.generateStoryboard(
                    content, characters, targetDuration, visualStyle);
                log.info("[Pipeline] Step2完成: 生成 {} 个分镜, projectId={}, episode={}", shots.size(), projectId, title);

                // 注入角色ID
                Map<String, String> nameToId = buildCharacterIdMap(projectId);
                injectCharacterIds(shots, nameToId);

                Long episodeId = findOrCreateEpisode(projectId, script, shots, visualStyle);
                log.info("[Pipeline] Episode创建/更新: episodeId={}, projectId={}", episodeId, projectId);
                deleteExistingPanels(episodeId);

                // 设置 episodeInfo.gridStatus = "generating"，异步生成整集九宫格
                gridImageService.updateEpisodeGridStatus(episodeId, "generating");
                log.info("[Pipeline] Step3: 启动异步九宫格生成: episodeId={}, shotCount={}", episodeId, shots.size());
                gridImageService.generateGridsForEpisode(episodeId, shots, visualStyle);
            }

            // 3. 推进状态：两步推进
            // EPISODE_SCRIPT_GENERATING → "episode_script_generated" → STORYBOARD_GENERATING
            log.info("[Pipeline] Step4: 推进状态 episode_script_generated: projectId={}", projectId);
            pipelineService.advancePipeline(projectId, "episode_script_generated");
            // STORYBOARD_GENERATING → "storyboard_generated" → STORYBOARD_REVIEW
            log.info("[Pipeline] Step5: 推进状态 storyboard_generated → STORYBOARD_REVIEW: projectId={}", projectId);
            pipelineService.advancePipeline(projectId, "storyboard_generated");
            log.info("[Pipeline] 全部完成: projectId={}, 状态已推进到STORYBOARD_REVIEW", projectId);

        } catch (Exception e) {
            log.error("[Pipeline] 分镜生成异常: projectId={}, error={}", projectId, e.getMessage(), e);
            try {
                // 根据当前状态选择正确的失败事件
                Project current = projectRepository.findByProjectId(projectId);
                if (current != null) {
                    String status = current.getStatus();
                    log.error("[Pipeline] 当前状态: {}, 尝试推进失败事件: projectId={}", status, projectId);
                    if (ProjectStatus.EPISODE_SCRIPT_GENERATING.getCode().equals(status)) {
                        pipelineService.advancePipeline(projectId, "episode_script_failed");
                    } else if (ProjectStatus.STORYBOARD_GENERATING.getCode().equals(status)) {
                        pipelineService.advancePipeline(projectId, "storyboard_failed");
                    } else {
                        log.error("[Pipeline] 无法匹配失败事件: status={}, projectId={}", status, projectId);
                    }
                }
            } catch (Exception ex) {
                log.error("[Pipeline] 设置失败状态也失败: projectId={}", projectId, ex);
            }
        }
    }

    /**
     * 贪心分组算法（纯函数，可独立测试）
     *
     * 重要：必须深拷贝每个 shot，避免多个 Panel 引用同一个 Map 对象
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
            }

            if (currentDuration + duration > maxDuration && !currentGroup.isEmpty()) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentDuration = 0;
            }

            // 深拷贝 shot，避免多个 Panel 共享同一个 Map 对象
            @SuppressWarnings("unchecked")
            Map<String, Object> shotCopy = new HashMap<>(shot);
            shotCopy.put("duration", duration);
            currentGroup.add(shotCopy);
            currentDuration += duration;
        }
        if (!currentGroup.isEmpty()) groups.add(currentGroup);
        return groups;
    }

    // --- 私有方法 ---

    private String getCharacterDescriptions(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        if (characters == null || characters.isEmpty()) {
            log.warn("项目没有配置角色: projectId={}", projectId);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Character c : characters) {
            Map<String, Object> info = c.getCharacterInfo();
            if (info == null) continue;

            String name = (String) info.get("name");
            String appearance = (String) info.get("appearance");
            String personality = (String) info.get("personality");
            String role = (String) info.get("role");

            if (name == null || name.isEmpty()) continue;

            sb.append("【").append(name).append("】");
            if (role != null && !role.isEmpty()) {
                sb.append(" 角色：").append(role);
            }
            if (appearance != null && !appearance.isEmpty()) {
                sb.append(" 外貌：").append(appearance);
            }
            if (personality != null && !personality.isEmpty()) {
                sb.append(" 性格：").append(personality);
            }
            sb.append("\n");
        }

        String result = sb.toString();
        log.info("获取角色描述: projectId={}, characters={}, descLength={}",
            projectId, characters.size(), result.length());
        return result;
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

    /**
     * 构建角色名到 charId 的映射
     */
    private Map<String, String> buildCharacterIdMap(String projectId) {
        List<Character> characters = characterRepository.findByProjectId(projectId);
        Map<String, String> nameToId = new HashMap<>();
        for (Character c : characters) {
            Map<String, Object> info = c.getCharacterInfo();
            if (info != null) {
                String name = (String) info.get("name");
                String charId = (String) info.get("charId");
                if (name != null && charId != null) {
                    nameToId.put(name.trim(), charId);
                }
            }
        }
        log.info("构建角色ID映射: projectId={}, mapping={}", projectId, nameToId);
        return nameToId;
    }

    /**
     * 为分镜中的角色注入 charId
     */
    private void injectCharacterIds(List<Map<String, Object>> shots, Map<String, String> nameToId) {
        for (Map<String, Object> shot : shots) {
            @SuppressWarnings("unchecked")
            List<String> charNames = (List<String>) shot.get("characters");
            if (charNames == null || charNames.isEmpty()) continue;

            List<Map<String, String>> charRefs = new ArrayList<>();
            for (String charName : charNames) {
                String charId = nameToId.get(charName.trim());
                Map<String, String> ref = new HashMap<>();
                ref.put("name", charName);
                if (charId != null) {
                    ref.put("charId", charId);
                    log.debug("角色注入成功: name={}, charId={}", charName, charId);
                } else {
                    log.warn("角色未找到匹配: name={}", charName);
                }
                charRefs.add(ref);
            }
            shot.put("characterRefs", charRefs);
        }
    }

    private int getIntFromMap(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }
}
