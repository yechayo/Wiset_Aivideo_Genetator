package com.comic.service.story;

import com.comic.ai.text.TextGenerationService;
import com.comic.common.AiCallException;
import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.world.CharacterService;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分镜生成服务（核心业务，Java 8 版）
 *
 * 流程：加载世界观 + 角色状态 → 构建Prompt → 调用AI（带重试）
 *       → 解析验证JSON → 更新角色状态 → 保存结果
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardService {

    private final TextGenerationService textGenerationService;
    private final CharacterService characterService;
    private final WorldRuleService worldRuleService;
    private final EpisodeRepository episodeRepository;
    private final ObjectMapper objectMapper;

    public String generateStoryboard(Long episodeId) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("找不到集数记录: " + episodeId);
        }

        episode.setStatus("GENERATING");
        episode.setRetryCount(0);
        episodeRepository.updateById(episode);

        try {
            String result = generateWithRetry(episode);

            episode.setStoryboardJson(result);
            episode.setStatus("DONE");
            episode.setErrorMsg(null);
            episodeRepository.updateById(episode);

            log.info("第{}集分镜生成成功", episode.getEpisodeNum());
            return result;

        } catch (Exception e) {
            episode.setStatus("FAILED");
            episode.setErrorMsg(e.getMessage());
            episodeRepository.updateById(episode);
            throw e;
        }
    }

    private String generateWithRetry(Episode episode) {
        WorldConfigModel world = worldRuleService.getWorldConfig(episode.getProjectId());
        List<CharacterStateModel> charStates = characterService.getCurrentStates(episode.getProjectId());

        // 构建基础系统提示词
        String systemPrompt = buildSystemPrompt(world, charStates);
        String userPrompt = buildUserPrompt(episode);

        Exception lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log.info("第{}集，第{}次尝试...", episode.getEpisodeNum(), attempt);

                String currentSystemPrompt = attempt > 1
                        ? addStricterConstraints(systemPrompt, attempt)
                        : systemPrompt;

                String rawResult = textGenerationService.generate(currentSystemPrompt, userPrompt);
                String cleanResult = cleanJsonOutput(rawResult);

                JsonNode jsonNode = objectMapper.readTree(cleanResult);
                validateStoryboardJson(jsonNode);

                characterService.updateStatesFromStoryboard(episode.getProjectId(), jsonNode);

                episode.setRetryCount(attempt - 1);
                return cleanResult;

            } catch (AiCallException e) {
                throw e; // 网络/超时错误直接抛出，不重试
            } catch (Exception e) {
                lastError = e;
                log.warn("第{}集第{}次生成失败: {}", episode.getEpisodeNum(), attempt, e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("生成失败，已重试3次。最后错误: " +
                (lastError != null ? lastError.getMessage() : "未知"));
    }

    private String cleanJsonOutput(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private void validateStoryboardJson(JsonNode json) {
        if (!json.has("panels")) {
            throw new IllegalStateException("JSON 缺少 panels 字段");
        }
        if (!json.get("panels").isArray() || json.get("panels").size() == 0) {
            throw new IllegalStateException("panels 不能为空数组");
        }
    }

    private String getRecentMemory(String projectId, int currentEp) {
        int startEp = Math.max(1, currentEp - 5);
        List<Episode> recentEps = episodeRepository.findRecentEpisodes(projectId, startEp, currentEp - 1);

        if (recentEps.isEmpty()) {
            return "这是第一集，没有历史剧情。";
        }

        StringBuilder sb = new StringBuilder();
        for (Episode ep : recentEps) {
            sb.append("[第").append(ep.getEpisodeNum()).append("集] ")
              .append(ep.getTitle() != null ? ep.getTitle() : "")
              .append("：").append(ep.getOutlineNode() != null ? ep.getOutlineNode() : "")
              .append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的分镜设计师，擅长将剧本转化为详细的分镜脚本。\n\n");
        sb.append("## 世界观设定\n");
        if (world != null) {
            sb.append("系列名称：").append(world.getSeriesName() != null ? world.getSeriesName() : "").append("\n");
            sb.append("类型：").append(world.getGenre() != null ? world.getGenre() : "").append("\n");
            sb.append("目标受众：").append(world.getTargetAudience() != null ? world.getTargetAudience() : "").append("\n");
            sb.append("世界规则：\n").append(world.getRulesText()).append("\n");
        }
        sb.append("\n## 角色状态\n");
        if (charStates != null && !charStates.isEmpty()) {
            for (CharacterStateModel charState : charStates) {
                sb.append(charState.toPromptText()).append("\n");
            }
        }
        sb.append("\n请生成符合以上设定的分镜脚本，以JSON格式返回。");
        return sb.toString();
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(Episode episode) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为第").append(episode.getEpisodeNum()).append("集生成分镜脚本。\n\n");
        sb.append("## 剧集大纲\n");
        sb.append(episode.getOutlineNode() != null ? episode.getOutlineNode() : "").append("\n\n");
        sb.append("## 历史剧情\n");
        sb.append(getRecentMemory(episode.getProjectId(), episode.getEpisodeNum())).append("\n\n");
        sb.append("请生成分镜JSON，包含：\n");
        sb.append("- panels: 分镜数组\n");
        sb.append("- 每个panel包含：scene, characters, shot_size, camera_angle, dialogue, effects");
        return sb.toString();
    }

    /**
     * 添加更严格的约束（重试时使用）
     */
    private String addStricterConstraints(String basePrompt, int attempt) {
        String additional = "\n\n【重要】这是第" + attempt + "次尝试，请严格遵守：\n"
                + "1. 必须返回有效的JSON格式\n"
                + "2. 不能有语法错误\n"
                + "3. 必须包含panels数组\n"
                + "4. 每个panel必须包含所有必需字段";
        return basePrompt + additional;
    }
}
