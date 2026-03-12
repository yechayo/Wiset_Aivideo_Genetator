package com.comic.service.script;

import com.comic.ai.ClaudeService;
import com.comic.ai.PromptBuilder;
import com.comic.common.BusinessException;
import com.comic.dto.WorldConfigDTO;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 剧本服务
 * 包含剧本生成、确认状态机
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final ClaudeService claudeService;
    private final PromptBuilder promptBuilder;
    private final WorldRuleService worldRuleService;
    private final ObjectMapper objectMapper;

    /**
     * 生成剧本
     * 基于项目参数和故事提示词，生成所有集数的剧本
     */
    @Transactional
    public String generateScript(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 更新项目状态
        project.setStatus("SCRIPT_GENERATING");
        projectRepository.updateById(project);

        try {
            // 获取世界观配置
            WorldConfigDTO worldConfig = worldRuleService.getWorldConfig(projectId);

            // 构建生成剧本的prompt
            String systemPrompt = promptBuilder.buildScriptSystemPrompt(
                project.getGenre(),
                worldConfig.getRulesText(),
                project.getTargetAudience()
            );

            String userPrompt = promptBuilder.buildScriptUserPrompt(
                project.getStoryPrompt(),
                project.getTotalEpisodes(),
                project.getEpisodeDuration()
            );

            // 调用Claude生成剧本
            String scriptContent = claudeService.call(systemPrompt, userPrompt);

            // 解析剧本内容，生成Episode记录
            List<Episode> episodes = parseAndSaveEpisodes(project, scriptContent);

            // 生成seriesId
            String seriesId = generateSeriesId();
            project.setSeriesId(seriesId);
            project.setStatus("SCRIPT_REVIEW");
            projectRepository.updateById(project);

            log.info("剧本生成完成: projectId={}, 集数={}", projectId, episodes.size());
            return seriesId;

        } catch (Exception e) {
            log.error("剧本生成失败: projectId={}", projectId, e);
            project.setStatus("SCRIPT_GENERATING_FAILED");
            projectRepository.updateById(project);
            throw new BusinessException("剧本生成失败: " + e.getMessage());
        }
    }

    /**
     * 确认剧本
     * 用户确认剧本后，锁定数据，触发角色提取流程
     */
    @Transactional
    public void confirmScript(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!"SCRIPT_REVIEW".equals(project.getStatus())) {
            throw new BusinessException("当前状态不能确认剧本");
        }

        project.setStatus("SCRIPT_CONFIRMED");
        projectRepository.updateById(project);

        log.info("剧本已确认: projectId={}", projectId);
    }

    /**
     * 要求修改剧本
     * 用户提出修改意见，重新生成
     */
    @Transactional
    public void reviseScript(String projectId, String revisionNote) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!"SCRIPT_REVIEW".equals(project.getStatus())) {
            throw new BusinessException("当前状态不能修改剧本");
        }

        project.setScriptRevisionNote(revisionNote);
        project.setStatus("SCRIPT_REVISION_REQUESTED");
        projectRepository.updateById(project);

        // 重新生成剧本
        regenerateScript(project);
    }

    /**
     * 获取剧本内容供预览
     */
    public Map<String, Object> getScriptContent(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        List<Episode> episodes = episodeRepository.findBySeriesId(project.getSeriesId());

        Map<String, Object> result = new HashMap<>();
        result.put("project", project);
        result.put("episodes", episodes);
        return result;
    }

    // ================= 私有方法 =================

    private List<Episode> parseAndSaveEpisodes(Project project, String scriptContent) {
        // 这里简化处理，实际应该解析LLM返回的结构化数据
        List<Episode> episodes = new ArrayList<>();

        // 假设LLM返回的是JSON格式的剧本
        // 实际实现需要根据LLM返回的格式来解析
        try {
            // 简化版本：直接按集数创建空episode
            for (int i = 1; i <= project.getTotalEpisodes(); i++) {
                Episode episode = new Episode();
                episode.setSeriesId(project.getSeriesId());
                episode.setEpisodeNum(i);
                episode.setTitle("第" + i + "集");
                episode.setOutlineNode("待生成");
                episode.setStatus("DRAFT");
                episode.setRetryCount(0);
                episodeRepository.insert(episode);
                episodes.add(episode);
            }
        } catch (Exception e) {
            log.error("解析剧本内容失败", e);
            throw new BusinessException("解析剧本内容失败");
        }

        return episodes;
    }

    private void regenerateScript(Project project) {
        // 使用修改意见重新生成剧本
        String revisionPrompt = "用户修改意见：" + project.getScriptRevisionNote() +
                               "\n请根据修改意见重新生成剧本。";

        // 调用生成逻辑（类似generateScript，但携带修改意见）
        // 这里简化处理，实际应该更智能地处理修改意见
        project.setStatus("SCRIPT_GENERATING");
        projectRepository.updateById(project);

        // 重新生成...
        log.info("开始重新生成剧本: projectId={}", project.getProjectId());
    }

    private String generateSeriesId() {
        return "SERIES-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
