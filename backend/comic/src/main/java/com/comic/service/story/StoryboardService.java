package com.comic.service.story;

import com.comic.ai.ClaudeService;
import com.comic.ai.PromptBuilder;
import com.comic.common.AiCallException;
import com.comic.dto.CharacterStateDTO;
import com.comic.dto.WorldConfigDTO;
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
 * 流程：加载世界观 + 角色状态 → 构建Prompt → 调用Claude（带重试）
 *       → 解析验证JSON → 更新角色状态 → 保存结果
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardService {

    private final ClaudeService claudeService;
    private final PromptBuilder promptBuilder;
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
        WorldConfigDTO world = worldRuleService.getWorldConfig(episode.getSeriesId());
        List<CharacterStateDTO> charStates = characterService.getCurrentStates(episode.getSeriesId());

        String systemPrompt = promptBuilder.buildStoryboardSystemPrompt(world, charStates);
        String userPrompt = promptBuilder.buildEpisodeUserPrompt(
                episode.getEpisodeNum(),
                episode.getOutlineNode(),
                getRecentMemory(episode.getSeriesId(), episode.getEpisodeNum())
        );

        Exception lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log.info("第{}集，第{}次尝试...", episode.getEpisodeNum(), attempt);

                String currentSystemPrompt = attempt > 1
                        ? promptBuilder.addStricterConstraints(systemPrompt, attempt)
                        : systemPrompt;

                String rawResult = claudeService.call(currentSystemPrompt, userPrompt);
                String cleanResult = cleanJsonOutput(rawResult);

                JsonNode jsonNode = objectMapper.readTree(cleanResult);
                validateStoryboardJson(jsonNode);

                characterService.updateStatesFromStoryboard(episode.getSeriesId(), jsonNode);

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

    private String getRecentMemory(String seriesId, int currentEp) {
        int startEp = Math.max(1, currentEp - 5);
        List<Episode> recentEps = episodeRepository.findRecentEpisodes(seriesId, startEp, currentEp - 1);

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
}
