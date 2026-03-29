package com.comic.service.script;

import com.comic.ai.ScriptPromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.EpisodeInfoKeys;
import com.comic.common.ProjectInfoKeys;
import com.comic.common.ProjectStatus;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.world.WorldRuleService;
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
    private final WorldRuleService worldRuleService;
    private final ObjectMapper objectMapper;

    // 状态常量（统一使用 ProjectStatus 枚举）
    private static final String STATUS_OUTLINE_REVIEW = ProjectStatus.OUTLINE_REVIEW.getCode();
    private static final String STATUS_SCRIPT_REVIEW = ProjectStatus.SCRIPT_REVIEW.getCode();
    private static final String STATUS_SCRIPT_CONFIRMED = ProjectStatus.SCRIPT_CONFIRMED.getCode();
    private static final String STATUS_OUTLINE_FAILED = ProjectStatus.OUTLINE_GENERATING_FAILED.getCode();
    private static final String STATUS_EPISODE_FAILED = ProjectStatus.EPISODE_GENERATING_FAILED.getCode();
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
     * 由状态机 Action 在异步线程中调用，使用独立事务确保数据落库
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void generateScriptOutline(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
        String genre = getProjectInfoStr(project, ProjectInfoKeys.GENRE);
        String targetAudience = getProjectInfoStr(project, ProjectInfoKeys.TARGET_AUDIENCE);
        String storyPrompt = getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT);
        Integer episodeDuration = getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION);
        String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);

        // 状态已由 triggerNextStage 设置为 OUTLINE_GENERATING，无需重复设置

        try {
            // 获取世界观配置
            WorldConfigModel worldConfig = worldRuleService.getWorldConfig(projectId);

            // 构建生成大纲的prompt
            String systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
                totalEpisodes != null ? totalEpisodes : 4,
                genre,
                targetAudience
            );

            String userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
                storyPrompt,
                genre,
                worldConfig.getRulesText(),
                totalEpisodes != null ? totalEpisodes : 4,
                episodeDuration != null ? episodeDuration / 60 : 1,
                visualStyle != null ? visualStyle : "REAL"
            );

            log.info("systemPrompt: {}", systemPrompt);
            log.info("userPrompt: {}", userPrompt);

            // 调用文本生成服务生成大纲
            String outlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            // 保存大纲到 projectInfo
            Map<String, Object> info = ensureProjectInfo(project);
            Map<String, Object> scriptMap = getScriptMap(project);
            if (scriptMap == null) {
                scriptMap = new HashMap<>();
                info.put(ProjectInfoKeys.SCRIPT, scriptMap);
            }
            scriptMap.put(ProjectInfoKeys.SCRIPT_OUTLINE, outlineContent);

            ScriptPromptBuilder.ScriptParams params = scriptPromptBuilder.calculateScriptParameters(
                totalEpisodes != null ? totalEpisodes : 4);
            int fallbackEpisodeCount = params.isSingleEpisode ? 1 : Math.max(1, params.episodesPerChapter);
            info.put(ProjectInfoKeys.EPISODES_PER_CHAPTER, fallbackEpisodeCount);

            // 持久化大纲内容到数据库（方法无事务，必须显式 save）
            projectRepository.updateById(project);

            log.info("剧本大纲生成完成: projectId={}", projectId);

        } catch (Exception e) {
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

        String status = project.getStatus();
        // 验证状态 - 允许从 OUTLINE_REVIEW、SCRIPT_REVIEW、EPISODE_GENERATING 或 EPISODE_GENERATING_FAILED 生成
        if (!STATUS_OUTLINE_REVIEW.equals(status) &&
            !STATUS_SCRIPT_REVIEW.equals(status) &&
            !ProjectStatus.EPISODE_GENERATING.getCode().equals(status) &&
            !STATUS_EPISODE_FAILED.equals(status)) {
            throw new BusinessException("当前状态不能生成分集，请先生成并确认大纲。当前状态: " + status);
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

            // 构建分集prompt
            String systemPrompt = scriptPromptBuilder.buildScriptEpisodeSystemPrompt();
            String userPrompt = scriptPromptBuilder.buildScriptEpisodeUserPrompt(
                outline,
                chapter,
                globalCharacters,
                globalItems,
                previousSummary,
                resolvedEpisodeCount,
                episodeDuration != null ? episodeDuration / 60 : 1,
                modificationSuggestion
            );

            // 调用文本生成服务生成分集
            String episodesJson = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析并保存剧集
            List<Episode> episodes = parseAndSaveEpisodes(project, episodesJson, chapter);

            log.info("分集生成完成: projectId={}, chapter={}, episodes={}",
                    projectId, chapter, episodes.size());

        } catch (Exception e) {
            log.error("分集生成失败: projectId={}, chapter={}", projectId, chapter, e);
            throw new BusinessException("分集生成失败: " + e.getMessage());
        }
    }

    /**
     * 批量生成所有剩余章节的剧集
     */
    @Transactional
    public void generateAllEpisodes(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        String status = project.getStatus();
        log.info("generateAllEpisodes: projectId={}, status={}, expected={}",
                 projectId, status, "OUTLINE_REVIEW/SCRIPT_REVIEW/EPISODE_GENERATING/EPISODE_GENERATING_FAILED");

        // 允许从 OUTLINE_REVIEW、SCRIPT_REVIEW、EPISODE_GENERATING 或 EPISODE_GENERATING_FAILED 生成
        if (!STATUS_OUTLINE_REVIEW.equals(status) &&
            !STATUS_SCRIPT_REVIEW.equals(status) &&
            !ProjectStatus.EPISODE_GENERATING.getCode().equals(status) &&
            !STATUS_EPISODE_FAILED.equals(status)) {
            throw new BusinessException("当前状态不能生成分集，请先生成并确认大纲。当前状态: " + status);
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
        for (String chapter : pendingChapters) {
            try {
                Integer episodeCount = resolveEpisodeCount(project, chapter, null, true);
                generateScriptEpisodes(projectId, chapter, episodeCount, null);
            } catch (Exception e) {
                log.error("批量生成失败，停止在章节: {}", chapter, e);
                throw new BusinessException("批量生成在章节「" + chapter + "」处失败: " + e.getMessage());
            }
        }

        log.info("批量生成完成: projectId={}, 共生成 {} 章", projectId, pendingChapters.size());
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
        if (STATUS_SCRIPT_CONFIRMED.equals(status)) {
            log.info("剧本已确认，跳过: projectId={}", projectId);
            return;
        }

        if (STATUS_OUTLINE_REVIEW.equals(status)) {
            if (isAllChaptersGenerated(project)) {
                log.info("剧本全部确认完成: projectId={}", projectId);
            } else {
                throw new BusinessException("请先生成所有章节的剧集");
            }
        } else if (STATUS_SCRIPT_REVIEW.equals(status)) {
            if (isAllChaptersGenerated(project)) {
                log.info("剧本全部确认完成: projectId={}", projectId);
            } else {
                throw new BusinessException("请先生成所有章节的剧集");
            }
        } else {
            throw new BusinessException("当前状态不能确认剧本");
        }

        // 状态转换由状态机 Action 处理
        log.info("剧本确认完成: projectId={}", projectId);
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

        if (!STATUS_OUTLINE_REVIEW.equals(project.getStatus())) {
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

        if (!STATUS_OUTLINE_REVIEW.equals(status)) {
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

        if (!STATUS_SCRIPT_REVIEW.equals(project.getStatus())) {
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

        log.info("getScriptContent: projectId={}, status={}", projectId, project.getStatus());

        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        log.info("getScriptContent: episodesCount={}", episodes.size());

        // 检查 episodeInfo 是否正确反序列化
        for (Episode ep : episodes) {
            log.info("Episode id={}, episodeInfo={}, episodeInfoSize={}",
                     ep.getId(),
                     ep.getEpisodeInfo() != null ? "not null" : "NULL",
                     ep.getEpisodeInfo() != null ? ep.getEpisodeInfo().size() : 0);
        }

        // 如果 episodeInfo 为 null，读取原始数据进行调试
        if (!episodes.isEmpty() && episodes.get(0).getEpisodeInfo() == null) {
            log.warn("episodeInfo is null, reading raw data from DB");
            List<java.util.Map<String, Object>> rawEpisodes = episodeRepository.getRawEpisodeInfo(projectId);
            log.info("Raw episode data: {}", rawEpisodes);
        }

        Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
        boolean isSingleEpisode = totalEpisodes != null && totalEpisodes == 1;
        String outline = getScriptOutlineText(project);

        log.info("getScriptContent result: outlineLength={}, episodesCount={}",
                 outline != null ? outline.length() : 0, episodes.size());

        Map<String, Object> result = new HashMap<>();
        // 不直接返回 project 对象，避免序列化问题
        result.put("projectId", project.getProjectId());
        result.put("status", project.getStatus());
        result.put("projectInfo", project.getProjectInfo());  // 直接返回 projectInfo
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

    private String getScriptOutlineText(Project project) {
        Map<String, Object> info = project.getProjectInfo();
        log.info("getScriptOutlineText: projectId={}, projectInfo={}", project.getProjectId(), info);

        // 如果 projectInfo 为 null，尝试直接读取数据库原始 JSON
        if (info == null) {
            log.warn("projectInfo is null for projectId={}, reading raw JSON from DB", project.getProjectId());
            String rawJson = projectRepository.getRawProjectInfo(project.getProjectId());
            log.info("Raw project_info from DB: {}", rawJson);
            return null;
        }

        Object script = info.get(ProjectInfoKeys.SCRIPT);
        log.info("script object: {}", script);

        if (script instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> scriptMap = (Map<String, Object>) script;
            Object outline = scriptMap.get(ProjectInfoKeys.SCRIPT_OUTLINE);
            log.info("outline object: {}, type={}", outline,
                     outline != null ? outline.getClass().getName() : "null");
            return outline != null ? outline.toString() : null;
        }

        log.warn("script is not a Map, it's: {}",
                 script != null ? script.getClass().getName() : "null");
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

        String[] patterns = {
            "#{3,4}\\s+第([一二三四五六七八九十百千万0-9]+)章[：:]?\\s*([^\\n]*)",
            "#{3,4}\\s+([一二三四五六七八九十百千万0-9]+)[、.\\s]+[^\\n]*章[：:]?\\s*([^\\n]*)"
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(outline);

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
            Matcher looseMatcher = loosePattern.matcher(outline);

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
     * 重新生成大纲
     */
    private void regenerateOutline(Project project, String revisionNote, String currentOutline) {
        try {
            Integer totalEpisodes = getProjectInfoInt(project, ProjectInfoKeys.TOTAL_EPISODES);
            String genre = getProjectInfoStr(project, ProjectInfoKeys.GENRE);
            String targetAudience = getProjectInfoStr(project, ProjectInfoKeys.TARGET_AUDIENCE);
            String storyPrompt = getProjectInfoStr(project, ProjectInfoKeys.STORY_PROMPT);
            Integer episodeDuration = getProjectInfoInt(project, ProjectInfoKeys.EPISODE_DURATION);
            String visualStyle = getProjectInfoStr(project, ProjectInfoKeys.VISUAL_STYLE);

            String systemPrompt = scriptPromptBuilder.buildScriptOutlineSystemPrompt(
                totalEpisodes != null ? totalEpisodes : 4,
                genre,
                targetAudience
            );

            String userPrompt = scriptPromptBuilder.buildScriptOutlineUserPrompt(
                storyPrompt,
                genre,
                currentOutline,
                totalEpisodes != null ? totalEpisodes : 4,
                episodeDuration != null ? episodeDuration / 60 : 1,
                visualStyle != null ? visualStyle : "REAL"
            );

            // 添加修改意见
            userPrompt += "\n\n**修改要求**：" + revisionNote;

            String outlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            // 重新加载 project 以获取最新状态（advancePipeline("revise_outline") 已改为 OUTLINE_GENERATING）
            Project currentProject = projectRepository.findByProjectId(project.getProjectId());
            Map<String, Object> info = ensureProjectInfo(currentProject);
            Map<String, Object> scriptMap = getScriptMap(currentProject);
            if (scriptMap == null) {
                scriptMap = new HashMap<>();
                info.put(ProjectInfoKeys.SCRIPT, scriptMap);
            }
            scriptMap.put(ProjectInfoKeys.SCRIPT_OUTLINE, outlineContent);
            projectRepository.updateById(currentProject);

            // 删除之前生成的所有剧集
            episodeRepository.deleteByProjectId(project.getProjectId());

            log.info("大纲重新生成完成: projectId={}", project.getProjectId());

        } catch (Exception e) {
            log.error("大纲重新生成失败", e);
            throw new BusinessException("大纲重新生成失败: " + e.getMessage());
        }
    }
}