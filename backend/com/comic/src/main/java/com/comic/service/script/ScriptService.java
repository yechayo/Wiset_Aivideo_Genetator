package com.comic.service.script;

import com.comic.ai.ComicCommentaryScriptPromptBuilder;
import com.comic.ai.ScriptPromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.exception.BusinessException;
import com.comic.constant.EpisodeInfoKeys;
import com.comic.constant.ProjectInfoKeys;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.redis.ProgressService;
import com.comic.service.world.WorldRuleService;
import com.comic.util.ProjectProductionMode;
import com.comic.statemachine.enums.ProjectMilestoneEventType;
import com.comic.statemachine.service.ProjectMilestoneStateMachineService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剧本服务
 * 实现两级剧本生成：大纲生成 + 分章节剧集生成
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final TextGenerationService textGenerationService;
    private final ScriptPromptBuilder scriptPromptBuilder;
    private final ComicCommentaryScriptPromptBuilder comicCommentaryScriptPromptBuilder;
    private final WorldRuleService worldRuleService;
    private final ObjectMapper objectMapper;

    @Lazy
    @Autowired
    private ProgressService progressService;

    @Lazy
    @Autowired
    private ProjectMilestoneStateMachineService milestoneStateMachineService;

    @Lazy
    @Autowired
    private StateChangeEventPublisher eventPublisher;

    private static final int DEFAULT_EPISODE_COUNT = 4;

    // ==================== Map 辅助方法 ====================

    private String getProjectInfoStr(Project project, String key) {
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Integer getProjectInfoInt(Project project, String key) {
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? ((Number) v).intValue() : null;
    }

    private String getEpisodeInfoStr(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private Integer getEpisodeInfoInt(Episode episode, String key) {
        Map<String, Object> info = episode.getEpisodeInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? ((Number) v).intValue() : null;
    }

    private Map<String, Object> ensureProjectInfo(Project project) {
        Map<String, Object> info = project.getProjectInfo();
        if (info == null) {
            info = new HashMap<>();
            project.setProjectInfo(info);
        }
        return info;
    }

    private Map<String, Object> ensureEpisodeInfo(Episode episode) {
        Map<String, Object> info = episode.getEpisodeInfo();
        if (info == null) {
            info = new HashMap<>();
            episode.setEpisodeInfo(info);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getScriptMap(Project project) {
        Map<String, Object> info = project.getProjectInfo();
        if (info == null) return null;
        Object script = info.get(ProjectInfoKeys.SCRIPT);
        return script instanceof Map ? (Map<String, Object>) script : null;
    }

    // ================= 第一步：生成剧本大纲 =================

    /**
     * 生成剧本大纲
     * 由 PipelineService 在异步线程中调用，使用独立事务确保数据落库
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void generateScriptOutline(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // Redis 锁防止重复提交
        if (!progressService.tryLock(projectId, "outline")) {
            throw new BusinessException("正在生成大纲，请勿重复提交");
        }

        try {
            Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
            String genre = getProjectInfoStr(project, ProjectInfoKeys.GENRE);
            String targetAudience = getProjectInfoStr(project, ProjectInfoKeys.TARGET_AUDIENCE);
            String storyPrompt = getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT);
            Integer episodeDuration = getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION);
            String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);

            // 获取世界观配置
            WorldConfigModel worldConfig = worldRuleService.getWorldConfig(projectId);

            boolean comicMode = ProjectProductionMode.isComicCommentary(project);
            String scriptStyle = (String) project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
            int resolvedTotalEpisodes = totalEpisodes != null ? totalEpisodes : 4;

            ScriptPromptBuilder.ScriptParams params = comicMode
                    ? comicCommentaryScriptPromptBuilder.calculateScriptParameters(resolvedTotalEpisodes)
                    : scriptPromptBuilder.calculateScriptParameters(resolvedTotalEpisodes);

            String systemPrompt;
            String userPrompt;
            int resolvedEpisodeDuration = episodeDuration != null ? episodeDuration : 60;
            if (comicMode) {
                systemPrompt = comicCommentaryScriptPromptBuilder.buildScriptOutlineSystemPrompt(
                        resolvedTotalEpisodes,
                        genre,
                        targetAudience,
                        params.chapterCount,
                        params.episodesPerChapter,
                        resolvedEpisodeDuration
                );
                userPrompt = comicCommentaryScriptPromptBuilder.buildScriptOutlineUserPrompt(
                        storyPrompt,
                        genre,
                        worldConfig.getRulesText(),
                        resolvedTotalEpisodes,
                        resolvedEpisodeDuration,
                        visualStyle != null ? visualStyle : "REAL"
                );
            } else {
                systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
                        resolvedTotalEpisodes,
                        genre,
                        targetAudience,
                        params.chapterCount,
                        params.episodesPerChapter,
                        resolvedEpisodeDuration,
                        scriptStyle
                );
                userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
                        storyPrompt,
                        genre,
                        worldConfig.getRulesText(),
                        resolvedTotalEpisodes,
                        resolvedEpisodeDuration,
                        visualStyle != null ? visualStyle : "REAL",
                        scriptStyle
                );
            }

            log.info("systemPrompt: {}", systemPrompt);
            log.info("userPrompt: {}", userPrompt);

            // 调用文本生成服务生成大纲
            String rawOutlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析 AI 返回：JSON 格式提取 outline 字段，Markdown 格式直接使用
            String outlineContent = extractOutlineContent(rawOutlineContent);

            // 保存大纲到 projectInfo
            Map<String, Object> info = ensureProjectInfo(project);
            Map<String, Object> scriptMap = getScriptMap(project);
            if (scriptMap == null) {
                scriptMap = new HashMap<>();
                info.put(ProjectInfoKeys.SCRIPT, scriptMap);
            }
            scriptMap.put(ProjectInfoKeys.SCRIPT_OUTLINE, outlineContent);

            int fallbackEpisodeCount = params.isSingleEpisode ? 1 : Math.max(1, params.episodesPerChapter);
            info.put(ProjectInfoKeys.EPISODES_PER_CHAPTER, fallbackEpisodeCount);

            // 持久化大纲内容到数据库（方法无事务，必须显式 save）
            projectRepository.updateById(project);

            // 成功：释放锁 + 清除错误 + SSE 推送完成
            progressService.unlock(projectId);
            progressService.clearError(projectId);
            eventPublisher.publishTaskComplete(projectId, "outline", null);

            log.info("剧本大纲生成完成: projectId={}", projectId);

        } catch (Exception e) {
            // 失败：释放锁 + 设置错误 + SSE 推送失败
            progressService.unlock(projectId);
            progressService.setError(projectId, e.getMessage());
            eventPublisher.publishFailure(projectId, e.getMessage());
            log.error("剧本大纲生成失败: projectId={}", projectId, e);
            throw new BusinessException("剧本大纲生成失败: " + e.getMessage());
        }
    }

    // ================= 第二步：生成指定章节的剧集 =================

    /**
     * 生成指定章节的剧集
     */
    @Transactional
    public void generateScriptEpisodes(String projectId, String chapter, Integer episodeCount, String modificationSuggestion) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // Redis 锁防止重复提交
        if (!progressService.tryLock(projectId, "episode")) {
            throw new BusinessException("正在生成分集，请勿重复提交");
        }

        // 验证 milestone
        String status = project.getStatus();
        if (!"outline_confirmed".equals(status) && !"episode_confirmed".equals(status)) {
            progressService.unlock(projectId);
            throw new BusinessException("当前状态不能生成分集，请先确认大纲");
        }

        // 验证章节顺序（必须顺序生成，单集只有一个章节，验证天然通过）
        validateChapterOrder(project, chapter);

        int resolvedEpisodeCount = resolveEpisodeCount(project, chapter, episodeCount, false);

        // 保存选中的章节到 projectInfo
        Map<String, Object> info = ensureProjectInfo(project);
        info.put(ProjectInfoKeys.SELECTED_CHAPTER, chapter);
        projectRepository.updateById(project);

        try {
            String outline = getScriptOutlineText(project);

            // 从大纲提取全局信息
            String globalCharacters = extractCharactersFromOutline(outline);
            String globalItems = extractItemsFromOutline(outline);

            // 获取前序剧集摘要（保持连贯性）
            String previousSummary = buildPreviousEpisodesSummary(projectId);

            Integer episodeDuration = getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION);

            boolean comicMode = ProjectProductionMode.isComicCommentary(project);
            String scriptStyle = (String) project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
            String systemPrompt = comicMode
                    ? comicCommentaryScriptPromptBuilder.buildScriptEpisodeSystemPrompt()
                    : scriptPromptBuilder.buildScriptEpisodeSystemPrompt(scriptStyle);
            String userPrompt = comicMode
                    ? comicCommentaryScriptPromptBuilder.buildScriptEpisodeUserPrompt(
                            outline,
                            chapter,
                            globalCharacters,
                            globalItems,
                            previousSummary,
                            resolvedEpisodeCount,
                            episodeDuration != null ? episodeDuration : 60,
                            modificationSuggestion
                    )
                    : scriptPromptBuilder.buildScriptEpisodeUserPrompt(
                            outline,
                            chapter,
                            globalCharacters,
                            globalItems,
                            previousSummary,
                            resolvedEpisodeCount,
                            episodeDuration != null ? episodeDuration : 60,
                            modificationSuggestion,
                            scriptStyle
                    );

            // 调用文本生成服务生成分集
            String episodesJson = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析并保存剧集
            List<Episode> episodes = parseAndSaveEpisodes(project, episodesJson, chapter);

            // 成功：释放锁 + SSE 推送完成
            progressService.unlock(projectId);
            eventPublisher.publishTaskComplete(projectId, "episode", null);

            log.info("分集生成完成: projectId={}, chapter={}, episodes={}",
                    projectId, chapter, episodes.size());

        } catch (Exception e) {
            // 失败：释放锁 + 设置错误 + SSE 推送失败
            progressService.unlock(projectId);
            progressService.setError(projectId, e.getMessage());
            eventPublisher.publishFailure(projectId, e.getMessage());
            log.error("分集生成失败: projectId={}, chapter={}", projectId, chapter, e);
            throw new BusinessException("分集生成失败: " + e.getMessage());
        }
    }

    /**
     * 批量生成所有剩余章节的剧集
     * 注意：不使用 @Transactional，确保每章生成后数据立即可见（SSE 增量刷新）
     */
    public void generateAllEpisodes(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 验证 milestone
        String status = project.getStatus();
        if (!"outline_confirmed".equals(status) && !"episode_confirmed".equals(status)) {
            throw new BusinessException("当前状态不能生成分集，请先确认大纲");
        }

        Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);

        // 判断是否为单集模式
        boolean isSingleEpisode = totalEpisodes != null && totalEpisodes == 1;

        // 获取所有章节
        List<String> chapters;
        if (isSingleEpisode) {
            chapters = Collections.singletonList("单集剧本");
        } else {
            chapters = extractChaptersFromOutline(getScriptOutlineText(project));
        }

        // 获取已生成的章节
        List<Episode> existingEpisodes = episodeRepository.findByProjectId(projectId);
        Set<String> generatedChapters = new HashSet<>();
        for (Episode ep : existingEpisodes) {
            String chapterTitle = getEpisodeInfoStr(ep, EpisodeInfoKeys.CHAPTER_TITLE);
            if (chapterTitle != null) {
                generatedChapters.add(chapterTitle);
            }
        }

        // 找出所有未生成的章节
        List<String> pendingChapters = new ArrayList<>();
        for (String chapter : chapters) {
            if (!generatedChapters.contains(chapter)) {
                pendingChapters.add(chapter);
            }
        }

        if (pendingChapters.isEmpty()) {
            throw new BusinessException("所有章节已生成，无需重复生成");
        }

        // 按顺序生成每一章
        int totalChapters = pendingChapters.size();
        int completedChapters = 0;
        for (String chapter : pendingChapters) {
            try {
                Integer episodeCount = resolveEpisodeCount(project, chapter, null, true);
                generateScriptEpisodes(projectId, chapter, episodeCount, null);
                completedChapters++;
                // 每章完成后推送 SSE 进度，前端可立即看到已生成的剧集
                eventPublisher.publishEpisodeScriptDone(
                        projectId, completedChapters, chapter, totalChapters, completedChapters, "batch_episode");
                log.info("批量生成进度: {}/{}, chapter={}", completedChapters, totalChapters, chapter);
            } catch (Exception e) {
                log.error("批量生成失败，停止在章节: {}", chapter, e);
                throw new BusinessException("批量生成在章节「" + chapter + "」处失败: " + e.getMessage());
            }
        }

        log.info("批量生成完成: projectId={}, 共生成 {} 章", projectId, totalChapters);
    }

    // ================= 确认与修改 =================

    /**
     * 确认剧本
     */
    @Transactional
    public void confirmScript(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        String status = project.getStatus();

        // 已经是确认状态，直接返回（幂等操作）
        if ("episode_confirmed".equals(status)) {
            log.info("剧本已确认，跳过: projectId={}", projectId);
            return;
        }

        // 根据 milestone 判断确认操作类型
        if ("outline_confirmed".equals(status)) {
            if (isAllChaptersGenerated(project)) {
                // 大纲已确认 + 分集全部生成，确认分集
                milestoneStateMachineService.sendEvent(projectId, ProjectMilestoneEventType.CONFIRM_EPISODE);
                log.info("分集剧本确认完成: projectId={}", projectId);
            } else {
                throw new BusinessException("请先生成所有章节的剧集");
            }
        } else if ("draft".equals(status)) {
            // draft 下大纲已存在但未确认 → 确认大纲
            if (getScriptOutlineText(project) != null) {
                milestoneStateMachineService.sendEvent(projectId, ProjectMilestoneEventType.CONFIRM_OUTLINE);
                log.info("大纲确认完成: projectId={}", projectId);
            } else {
                throw new BusinessException("请先生成大纲");
            }
        } else {
            throw new BusinessException("当前状态不能确认剧本");
        }
    }

    /**
     * 直接保存用户编辑的大纲
     */
    @Transactional
    public void updateScriptOutline(String projectId, String outline) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 验证 milestone：draft（大纲已存在）或 outline_confirmed
        String status = project.getStatus();
        if (!"draft".equals(status) && !"outline_confirmed".equals(status) && !"episode_confirmed".equals(status)) {
            throw new BusinessException("当前状态不能修改大纲");
        }

        Map<String, Object> scriptMap = getScriptMap(project);
        if (scriptMap == null) {
            Map<String, Object> info = ensureProjectInfo(project);
            scriptMap = new HashMap<>();
            info.put(ProjectInfoKeys.SCRIPT, scriptMap);
        }
        scriptMap.put(ProjectInfoKeys.SCRIPT_OUTLINE, outline);
        projectRepository.updateById(project);

        // 大纲变更，删除已生成的所有剧集
        episodeRepository.deleteByProjectId(projectId);

        log.info("大纲直接保存完成（已清除剧集）: projectId={}", projectId);
    }

    /**
     * 修改大纲
     */
    @Transactional
    public void reviseOutline(String projectId, String revisionNote, String currentOutline) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        String status = project.getStatus();

        // 验证 milestone：draft（数据存在时即 outline_review）或 outline_confirmed
        if (!"draft".equals(status) && !"outline_confirmed".equals(status)) {
            throw new BusinessException("当前状态不能修改大纲");
        }

        Map<String, Object> info = ensureProjectInfo(project);
        info.put(ProjectInfoKeys.SCRIPT_REVISION_NOTE, revisionNote);
        projectRepository.updateById(project);

        // 重新生成大纲
        regenerateOutline(project, revisionNote, currentOutline);
    }

    /**
     * 修改指定章节的分集
     */
    @Transactional
    public void reviseEpisodes(String projectId, String chapter, Integer episodeCount, String revisionNote) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 验证 milestone：outline_confirmed（分集已生成可修改）或 episode_confirmed
        String status = project.getStatus();
        if (!"outline_confirmed".equals(status) && !"episode_confirmed".equals(status)) {
            throw new BusinessException("当前状态不能修改分集");
        }

        // 删除该章节的旧剧集
        deleteEpisodesByChapter(projectId, chapter);

        // 重新生成
        generateScriptEpisodes(projectId, chapter, episodeCount, revisionNote);
    }

    // ================= 获取内容 =================

    /**
     * 获取剧本内容供预览
     */
    public Map<String, Object> getScriptContent(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        List<Episode> episodes = episodeRepository.findByProjectId(projectId);

        Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
        boolean isSingleEpisode = totalEpisodes != null && totalEpisodes == 1;
        String outline = getScriptOutlineText(project);

        Map<String, Object> result = new HashMap<>();
        result.put("project", project);
        result.put("outline", outline);
        result.put("isSingleEpisode", isSingleEpisode);
        result.put("episodes", episodes);

        if (isSingleEpisode) {
            String singleChapter = "单集剧本";
            boolean hasEpisodes = !episodes.isEmpty();
            result.put("chapters", Collections.singletonList(singleChapter));
            result.put("generatedChapters", hasEpisodes ? Collections.singletonList(singleChapter) : Collections.emptyList());
            result.put("pendingChapters", hasEpisodes ? Collections.emptyList() : Collections.singletonList(singleChapter));
            result.put("nextChapter", hasEpisodes ? null : singleChapter);
            result.put("needGenerateScript", !hasEpisodes);
        } else {
            List<String> chapters = extractChaptersFromOutline(outline);

            Set<String> generatedChapters = new HashSet<>();
            for (Episode ep : episodes) {
                String chapterTitle = getEpisodeInfoStr(ep, EpisodeInfoKeys.CHAPTER_TITLE);
                if (chapterTitle != null) {
                    generatedChapters.add(chapterTitle);
                }
            }

            List<String> pendingChapters = new ArrayList<>();
            for (String chapter : chapters) {
                if (!generatedChapters.contains(chapter)) {
                    pendingChapters.add(chapter);
                }
            }

            String nextChapter = pendingChapters.isEmpty() ? null : pendingChapters.get(0);

            result.put("chapters", chapters);
            result.put("generatedChapters", new ArrayList<>(generatedChapters));
            result.put("pendingChapters", pendingChapters);
            result.put("nextChapter", nextChapter);
        }

        return result;
    }

    // ================= 私有方法：解析与提取 =================

    /**
     * 从 AI 返回内容中提取大纲 Markdown 文本。
     * 多集模式下 AI 返回 JSON（含 outline 字段），需要解析提取；
     * 单集模式下 AI 直接返回 Markdown，无需处理。
     */
    private String extractOutlineContent(String rawContent) {
        if (rawContent == null || rawContent.trim().isEmpty()) {
            return rawContent;
        }
        String trimmed = rawContent.trim();
        // 先剥掉 markdown 代码块标记
        String clean = trimmed;
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        clean = clean.trim();

        // 检测是否为 JSON
        if (clean.startsWith("{")) {
            // 优先尝试标准 JSON 解析（智能引号替换仅用于 Jackson 解析）
            String cleanForJson = clean.replace('\u201C', '"').replace('\u201D', '"');
            try {
                JsonNode root = objectMapper.readTree(cleanForJson);
                if (root.has("outline") && !root.get("outline").isNull()) {
                    String outline = root.get("outline").asText();
                    if (outline != null && !outline.trim().isEmpty()) {
                        log.info("从 JSON 中提取 outline 字段，长度: {}", outline.length());
                        return outline;
                    }
                }
                log.warn("JSON 中未找到 outline 字段，尝试正则提取");
            } catch (Exception e) {
                log.warn("JSON 解析失败（可能含未转义换行）: {}，尝试正则提取", e.getMessage());
            }
            // 回退：从原始文本中提取 outline 字段（不替换智能引号，避免内容中的引号干扰截断）
            String regexExtracted = extractOutlineValue(clean);
            if (regexExtracted != null && !regexExtracted.trim().isEmpty()) {
                log.info("正则提取 outline 字段成功，长度: {}", regexExtracted.length());
                return regexExtracted;
            }
        }
        return rawContent;
    }

    /**
     * 从可能含未转义换行的 JSON 中提取 outline 字段值。
     * AI 有时在字符串值中直接输出实际换行符（非法 JSON），导致 Jackson 解析失败。
     * 使用字符串定位而非正则，避免内容中的引号导致截断。
     */
    private String extractOutlineValue(String jsonText) {
        try {
            // 找到 "outline" 键（支持智能引号和普通引号）
            String[] keyPatterns = {"\"outline\"", "\u201Coutline\u201D"};
            int keyPos = -1;
            for (String key : keyPatterns) {
                keyPos = jsonText.indexOf(key);
                if (keyPos >= 0) break;
            }
            if (keyPos < 0) return null;

            // 找到冒号后的第一个引号（值开始）
            int colonPos = jsonText.indexOf(':', keyPos);
            if (colonPos < 0) return null;
            int valueStart = -1;
            for (int i = colonPos + 1; i < jsonText.length(); i++) {
                char c = jsonText.charAt(i);
                if (c == '"' || c == '\u201C') {
                    valueStart = i + 1;
                    break;
                }
                if (!Character.isWhitespace(c)) break; // 非空白非引号，不是字符串值
            }
            if (valueStart < 0) return null;

            // 从末尾找最后一个引号（值结束）—— JSON 只有一个 outline 字段，最后出现的 " 就是闭合引号
            int valueEnd = -1;
            for (int i = jsonText.length() - 1; i >= valueStart; i--) {
                char c = jsonText.charAt(i);
                if (c == '"' || c == '\u201D') {
                    valueEnd = i;
                    break;
                }
            }
            if (valueEnd <= valueStart) return null;

            String value = jsonText.substring(valueStart, valueEnd);
            // 反转义 JSON 转义序列（用占位符避免 \\ 和 \" 互相干扰）
            value = value.replace("\\\\", "\u0000")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\u0000", "\\");
            return value;
        } catch (Exception e) {
            log.warn("字符串定位提取 outline 失败: {}", e.getMessage());
        }
        return null;
    }

    private String getScriptOutlineText(Project project) {
        Map<String, Object> scriptMap = getScriptMap(project);
        if (scriptMap != null) {
            Object outline = scriptMap.get(ProjectInfoKeys.SCRIPT_OUTLINE);
            if (outline != null) {
                String text = outline.toString();
                // 如果数据库存的是原始 JSON（extractOutlineContent 解析失败时的 fallback），
                // 重新尝试提取 outline 字段
                String extracted = extractOutlineContent(text);
                if (extracted != null && !extracted.equals(text)) {
                    log.info("从数据库中的原始 JSON 重新提取 outline，长度: {}", extracted.length());
                    return extracted;
                }
                // 修复字面 \n（AI 输出未正确转义的情况）
                return text.replace("\\n", "\n").replace("\\r\\n", "\n");
            }
        }
        return null;
    }

    /**
     * 从大纲中提取章节列表
     */
    public List<String> extractChaptersFromOutline(String outline) {
        List<String> chapters = new ArrayList<>();
        if (outline == null || outline.isEmpty()) {
            log.warn("大纲内容为空，无法提取章节");
            return chapters;
        }

        // 防御：AI 有时输出字面 \n 而非实际换行，导致正则 [^\n]* 无法正确断行
        String normalized = outline.replace("\\n", "\n").replace("\\r\\n", "\n");

        String[] patterns = {
            "#{3,4}\\s+第([一二三四五六七八九十百千万0-9]+)章[：:]?\\s*([^\\n]*)",
            "#{3,4}\\s+([一二三四五六七八九十百千万0-9]+)[、.\\s]+[^\\n]*章[：:]?\\s*([^\\n]*)"
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(normalized);

            while (matcher.find()) {
                String chapterTitle = matcher.group().trim();
                if (!chapters.contains(chapterTitle)) {
                    chapters.add(chapterTitle);
                }
            }
        }

        if (chapters.isEmpty()) {
            log.warn("标准章节格式未匹配到，尝试宽松匹配");
            Pattern loosePattern = Pattern.compile("^(#{3,4})\\s*(第.+章[^\\n]*)", Pattern.MULTILINE);
            Matcher looseMatcher = loosePattern.matcher(normalized);

            while (looseMatcher.find()) {
                String chapterTitle = looseMatcher.group(2).trim();
                if (!chapters.contains(chapterTitle)) {
                    chapters.add(chapterTitle);
                }
            }
        }

        log.info("从大纲中提取到 {} 个章节: {}", chapters.size(), chapters);
        return chapters;
    }

    /**
     * 从大纲中提取角色信息
     */
    public String extractCharactersFromOutline(String outline) {
        if (outline == null || outline.isEmpty()) {
            return "未找到明确的角色定义";
        }

        Pattern pattern = Pattern.compile("## 主要人物小传[^#]*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(outline);

        if (matcher.find()) {
            return matcher.group().trim();
        }

        return "未找到明确的角色定义";
    }

    /**
     * 从大纲中提取物品信息
     */
    public String extractItemsFromOutline(String outline) {
        if (outline == null || outline.isEmpty()) {
            return "未找到明确的物品定义";
        }

        Pattern pattern = Pattern.compile("## 关键物品设定[^#]*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(outline);

        if (matcher.find()) {
            return matcher.group().trim();
        }

        return "未找到明确的物品定义";
    }

    /**
     * 构建前序剧集摘要
     */
    private String buildPreviousEpisodesSummary(String projectId) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);

        if (episodes.isEmpty()) {
            return "无前序剧集";
        }

        StringBuilder sb = new StringBuilder();
        for (Episode ep : episodes) {
            Integer epNum = getEpisodeInfoInt(ep, EpisodeInfoKeys.EPISODE_NUM);
            String title = getEpisodeInfoStr(ep, EpisodeInfoKeys.TITLE);
            String characters = getEpisodeInfoStr(ep, EpisodeInfoKeys.CHARACTERS);
            String keyItems = getEpisodeInfoStr(ep, EpisodeInfoKeys.KEY_ITEMS);
            String content = getEpisodeInfoStr(ep, EpisodeInfoKeys.CONTENT);

            sb.append("第").append(epNum).append("集：").append(title != null ? title : "").append("\n");
            sb.append("- 涉及角色：").append(characters != null ? characters : "无").append("\n");
            sb.append("- 关键物品：").append(keyItems != null ? keyItems : "无").append("\n");
            if (content != null && content.length() > 200) {
                sb.append("- 剧情摘要：").append(content.substring(0, 200)).append("...\n");
            } else if (content != null) {
                sb.append("- 剧情摘要：").append(content).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 验证章节顺序（必须顺序生成）
     */
    private void validateChapterOrder(Project project, String chapter) {
        String outline = getScriptOutlineText(project);
        List<String> chapters = extractChaptersFromOutline(outline);
        List<Episode> existingEpisodes = episodeRepository.findByProjectId(project.getProjectId());

        Set<String> generatedChapters = new HashSet<>();
        for (Episode ep : existingEpisodes) {
            String chapterTitle = getEpisodeInfoStr(ep, EpisodeInfoKeys.CHAPTER_TITLE);
            if (chapterTitle != null && project.getProjectId().equals(ep.getProjectId())) {
                generatedChapters.add(chapterTitle);
            }
        }

        String expectedNext = null;
        for (String ch : chapters) {
            if (!generatedChapters.contains(ch)) {
                expectedNext = ch;
                break;
            }
        }

        if (expectedNext != null && !expectedNext.equals(chapter)) {
            throw new BusinessException("必须顺序生成章节。下一个待生成的章节是：" + expectedNext);
        }
    }

    /**
     * 检查是否已生成所有章节
     */
    private boolean isAllChaptersGenerated(Project project) {
        Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
        List<Episode> episodes = episodeRepository.findByProjectId(project.getProjectId());
        return episodes.size() >= (totalEpisodes != null ? totalEpisodes : 0);
    }

    /**
     * 删除指定章节的剧集
     */
    private void deleteEpisodesByChapter(String projectId, String chapter) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            if (chapter.equals(getEpisodeInfoStr(ep, EpisodeInfoKeys.CHAPTER_TITLE))) {
                episodeRepository.deleteById(ep.getId());
            }
        }
    }

    int resolveEpisodeCount(Project project, String chapter, Integer requestedEpisodeCount, boolean isBatch) {
        Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
        boolean isSingleEpisode = project != null
                && totalEpisodes != null
                && totalEpisodes == 1;
        if (isSingleEpisode) {
            return 1;
        }

        if (requestedEpisodeCount != null && requestedEpisodeCount > 0) {
            return requestedEpisodeCount;
        }

        Integer chapterDerivedCount = extractEpisodeCountFromChapter(chapter);
        if (chapterDerivedCount != null && chapterDerivedCount > 0) {
            return chapterDerivedCount;
        }

        Integer episodesPerChapter = getProjectInfoInt(project, ProjectInfoKeys.EPISODES_PER_CHAPTER);
        if (project != null && episodesPerChapter != null && episodesPerChapter > 0) {
            return episodesPerChapter;
        }

        return DEFAULT_EPISODE_COUNT;
    }

    private Integer extractEpisodeCountFromChapter(String chapterTitle) {
        if (chapterTitle == null || chapterTitle.trim().isEmpty()) {
            return null;
        }

        Pattern rangePattern = Pattern.compile("(?:第\\s*)?(\\d+)\\s*[-~～—–]\\s*(\\d+)\\s*集");
        Matcher rangeMatcher = rangePattern.matcher(chapterTitle);
        if (rangeMatcher.find()) {
            int start = Integer.parseInt(rangeMatcher.group(1));
            int end = Integer.parseInt(rangeMatcher.group(2));
            if (end >= start) {
                return end - start + 1;
            }
        }

        Pattern singlePattern = Pattern.compile("(?:第\\s*)?(\\d+)\\s*集");
        Matcher singleMatcher = singlePattern.matcher(chapterTitle);
        if (singleMatcher.find()) {
            return 1;
        }

        return null;
    }

    /**
     * 解析并保存剧集
     */
    private List<Episode> parseAndSaveEpisodes(Project project, String episodesJson, String chapterTitle) {
        log.info("========== 分集生成结果 ==========");
        log.info("ProjectId: {}", project.getProjectId());
        log.info("Chapter: {}", chapterTitle);
        log.info("Raw Content:\n{}", episodesJson);
        log.info("========== 内容结束 ==========");

        List<Episode> episodes = new ArrayList<>();

        try {
            // 清理可能的 markdown 代码块标记
            String cleanJson = episodesJson;
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            JsonNode rootNode = objectMapper.readTree(cleanJson);

            List<JsonNode> episodeNodes = new ArrayList<>();
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    episodeNodes.add(node);
                }
            } else if (rootNode.isObject()) {
                episodeNodes.add(rootNode);
            } else {
                throw new BusinessException("剧集 JSON 格式不正确，期望数组或对象");
            }

            int episodeNum = getNextEpisodeNum(project.getProjectId());
            for (JsonNode episodeNode : episodeNodes) {
                Episode episode = new Episode();
                episode.setProjectId(project.getProjectId());
                episode.setStatus("DRAFT");

                Map<String, Object> epInfo = new HashMap<>();
                epInfo.put(EpisodeInfoKeys.EPISODE_NUM, episodeNum++);
                epInfo.put(EpisodeInfoKeys.TITLE, getJsonText(episodeNode, "title", "第" + (episodeNum - 1) + "集"));
                epInfo.put(EpisodeInfoKeys.CONTENT, getJsonText(episodeNode, "content", ""));
                epInfo.put(EpisodeInfoKeys.CHARACTERS, getJsonText(episodeNode, "characters", ""));
                epInfo.put(EpisodeInfoKeys.KEY_ITEMS, getJsonText(episodeNode, "keyItems", ""));
                epInfo.put(EpisodeInfoKeys.CONTINUITY_NOTE, getJsonText(episodeNode, "continuityNote", ""));
                epInfo.put(EpisodeInfoKeys.VISUAL_STYLE_NOTE, getJsonText(episodeNode, "visualStyleNote", ""));
                epInfo.put(EpisodeInfoKeys.CHAPTER_TITLE, chapterTitle);
                epInfo.put(EpisodeInfoKeys.RETRY_COUNT, 0);
                episode.setEpisodeInfo(epInfo);

                episodeRepository.insert(episode);
                episodes.add(episode);
            }

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析剧集JSON失败", e);
            throw new BusinessException("解析剧集内容失败: " + e.getMessage());
        }

        return episodes;
    }

    /**
     * 获取下一个剧集编号
     */
    private int getNextEpisodeNum(String projectId) {
        List<Episode> existing = episodeRepository.findByProjectId(projectId);
        int max = 0;
        for (Episode ep : existing) {
            Integer epNum = getEpisodeInfoInt(ep, EpisodeInfoKeys.EPISODE_NUM);
            if (epNum != null && epNum > max) {
                max = epNum;
            }
        }
        return max + 1;
    }

    private String getJsonText(JsonNode node, String field, String defaultValue) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText(defaultValue);
        }
        return defaultValue;
    }

    /**
     * 重新生成大纲（Redis 锁 + SSE 推送模式）
     */
    private void regenerateOutline(Project project, String revisionNote, String currentOutline) {
        String projectId = project.getProjectId();

        // Redis 锁防止重复提交
        if (!progressService.tryLock(projectId, "outline")) {
            throw new BusinessException("正在修改大纲，请勿重复提交");
        }

        try {
            Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
            String genre = getProjectInfoStr(project, ProjectInfoKeys.GENRE);
            String targetAudience = getProjectInfoStr(project, ProjectInfoKeys.TARGET_AUDIENCE);
            String storyPrompt = getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT);
            Integer episodeDuration = getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION);
            String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);

            boolean comicMode = ProjectProductionMode.isComicCommentary(project);
            String scriptStyle = (String) project.getProjectInfo().getOrDefault(ProjectInfoKeys.SCRIPT_STYLE, "standard");
            int resolvedTotalEpisodes = totalEpisodes != null ? totalEpisodes : 4;

            ScriptPromptBuilder.ScriptParams params = comicMode
                    ? comicCommentaryScriptPromptBuilder.calculateScriptParameters(resolvedTotalEpisodes)
                    : scriptPromptBuilder.calculateScriptParameters(resolvedTotalEpisodes);

            String systemPrompt;
            String userPrompt;
            int resolvedEpisodeDuration = episodeDuration != null ? episodeDuration : 60;
            if (comicMode) {
                systemPrompt = comicCommentaryScriptPromptBuilder.buildScriptOutlineSystemPrompt(
                        resolvedTotalEpisodes,
                        genre,
                        targetAudience,
                        params.chapterCount,
                        params.episodesPerChapter,
                        resolvedEpisodeDuration
                );
                userPrompt = comicCommentaryScriptPromptBuilder.buildScriptOutlineUserPrompt(
                        storyPrompt,
                        genre,
                        currentOutline,
                        resolvedTotalEpisodes,
                        resolvedEpisodeDuration,
                        visualStyle != null ? visualStyle : "REAL"
                );
            } else {
                systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
                        resolvedTotalEpisodes,
                        genre,
                        targetAudience,
                        params.chapterCount,
                        params.episodesPerChapter,
                        resolvedEpisodeDuration,
                        scriptStyle
                );
                userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
                        storyPrompt,
                        genre,
                        currentOutline,
                        resolvedTotalEpisodes,
                        resolvedEpisodeDuration,
                        visualStyle != null ? visualStyle : "REAL",
                        scriptStyle
                );
            }

            // 添加修改意见
            userPrompt += "\n\n**修改要求**：" + revisionNote;

            String rawOutlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析 AI 返回：JSON 格式提取 outline 字段，Markdown 格式直接使用
            String outlineContent = extractOutlineContent(rawOutlineContent);

            // 重新加载 project 以获取最新状态
            Project currentProject = projectRepository.findByProjectId(projectId);
            Map<String, Object> info = ensureProjectInfo(currentProject);
            Map<String, Object> scriptMap = getScriptMap(currentProject);
            if (scriptMap == null) {
                scriptMap = new HashMap<>();
                info.put(ProjectInfoKeys.SCRIPT, scriptMap);
            }
            scriptMap.put(ProjectInfoKeys.SCRIPT_OUTLINE, outlineContent);
            projectRepository.updateById(currentProject);

            // 成功：释放锁 + 清除错误 + SSE 推送完成
            progressService.unlock(projectId);
            progressService.clearError(projectId);
            eventPublisher.publishTaskComplete(projectId, "outline", null);

            // 删除之前生成的所有剧集
            episodeRepository.deleteByProjectId(projectId);

            log.info("大纲重新生成完成: projectId={}", projectId);

        } catch (Exception e) {
            // 失败：释放锁 + 设置错误 + SSE 推送失败
            progressService.unlock(projectId);
            progressService.setError(projectId, e.getMessage());
            eventPublisher.publishFailure(projectId, e.getMessage());
            log.error("大纲重新生成失败", e);
            throw new BusinessException("大纲重新生成失败: " + e.getMessage());
        }
    }
}
