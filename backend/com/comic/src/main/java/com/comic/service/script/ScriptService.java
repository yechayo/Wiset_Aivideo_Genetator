package com.comic.service.script;

import com.comic.ai.PromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.service.ProjectStateMachineService;
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
@Slf4j
public class ScriptService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final TextGenerationService textGenerationService;
    private final PromptBuilder promptBuilder;
    private final WorldRuleService worldRuleService;
    private final ObjectMapper objectMapper;
    private final ProjectStateMachineService stateMachineService;

    public ScriptService(
            ProjectRepository projectRepository,
            EpisodeRepository episodeRepository,
            TextGenerationService textGenerationService,
            PromptBuilder promptBuilder,
            WorldRuleService worldRuleService,
            ObjectMapper objectMapper,
            @Lazy ProjectStateMachineService stateMachineService) {
        this.projectRepository = projectRepository;
        this.episodeRepository = episodeRepository;
        this.textGenerationService = textGenerationService;
        this.promptBuilder = promptBuilder;
        this.worldRuleService = worldRuleService;
        this.objectMapper = objectMapper;
        this.stateMachineService = stateMachineService;
    }

    // 状态常量（统一使用 ProjectStatus 枚举）
    private static final String STATUS_DRAFT = ProjectStatus.DRAFT.getCode();
    private static final String STATUS_OUTLINE_GENERATING = ProjectStatus.OUTLINE_GENERATING.getCode();
    private static final String STATUS_OUTLINE_REVIEW = ProjectStatus.OUTLINE_REVIEW.getCode();
    private static final String STATUS_EPISODE_GENERATING = ProjectStatus.EPISODE_GENERATING.getCode();
    private static final String STATUS_SCRIPT_REVIEW = ProjectStatus.SCRIPT_REVIEW.getCode();
    private static final String STATUS_SCRIPT_CONFIRMED = ProjectStatus.SCRIPT_CONFIRMED.getCode();
    private static final String STATUS_OUTLINE_FAILED = ProjectStatus.OUTLINE_GENERATING_FAILED.getCode();
    private static final String STATUS_EPISODE_FAILED = ProjectStatus.EPISODE_GENERATING_FAILED.getCode();
    private static final int DEFAULT_EPISODE_COUNT = 4;

    // ================= 第一步：生成剧本大纲 =================

    /**
     * 生成剧本大纲
     * POST /api/projects/{projectId}/generate-script
     * 复用原有接口，改为生成大纲
     */
    @Transactional
    public void generateScriptOutline(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 更新项目状态
        project.setStatus(STATUS_OUTLINE_GENERATING);
        projectRepository.updateById(project);

        try {
            // 获取世界观配置
            WorldConfigModel worldConfig = worldRuleService.getWorldConfig(projectId);

            // 构建生成大纲的prompt
            String systemPrompt = promptBuilder.buildScriptOutlineSystemPrompt(
                project.getTotalEpisodes(),
                project.getGenre(),
                project.getTargetAudience()
            );

            String userPrompt = promptBuilder.buildScriptOutlineUserPrompt(
                project.getStoryPrompt(),
                project.getGenre(),
                worldConfig.getRulesText(),
                project.getTotalEpisodes(),
                project.getEpisodeDuration() != null ? project.getEpisodeDuration() / 60 : 1,
                project.getVisualStyle() != null ? project.getVisualStyle() : "REAL"
            );

            log.info("systemPrompt: {}", systemPrompt);
            log.info("userPrompt: {}", userPrompt);

            // 调用文本生成服务生成大纲
            String outlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            // 保存大纲到项目
            project.setScriptOutline(outlineContent);
            PromptBuilder.ScriptParams params = promptBuilder.calculateScriptParameters(project.getTotalEpisodes());
            int fallbackEpisodeCount = params.isSingleEpisode ? 1 : Math.max(1, params.episodesPerChapter);
            project.setEpisodesPerChapter(fallbackEpisodeCount);
            project.setStatus(STATUS_OUTLINE_REVIEW);
            projectRepository.updateById(project);

            log.info("剧本大纲生成完成: projectId={}", projectId);

        } catch (Exception e) {
            log.error("剧本大纲生成失败: projectId={}", projectId, e);
            project.setStatus(STATUS_OUTLINE_FAILED);
            projectRepository.updateById(project);
            throw new BusinessException("剧本大纲生成失败: " + e.getMessage());
        }
    }

    // ================= 第二步：生成指定章节的剧集 =================

    /**
     * 生成指定章节的剧集
     * POST /api/projects/{projectId}/generate-episodes
     * 支持单集模式和多集模式
     */
    @Transactional
    public void generateScriptEpisodes(String projectId, String chapter, Integer episodeCount, String modificationSuggestion) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 验证状态
        if (!STATUS_OUTLINE_REVIEW.equals(project.getStatus()) &&
            !STATUS_SCRIPT_REVIEW.equals(project.getStatus())) {
            throw new BusinessException("当前状态不能生成分集，请先生成并确认大纲");
        }

        // 验证章节顺序（必须顺序生成，单集只有一个章节，验证天然通过）
        validateChapterOrder(project, chapter);

        int resolvedEpisodeCount = resolveEpisodeCount(project, chapter, episodeCount, false);

        // 更新状态
        project.setStatus(STATUS_EPISODE_GENERATING);
        project.setSelectedChapter(chapter);
        projectRepository.updateById(project);

        try {
            String outline = project.getScriptOutline();

            // 从大纲提取全局信息
            String globalCharacters = extractCharactersFromOutline(outline);
            String globalItems = extractItemsFromOutline(outline);

            // 获取前序剧集摘要（保持连贯性）
            String previousSummary = buildPreviousEpisodesSummary(projectId);

            // 构建分集prompt（单集/多集统一使用章节 prompt）
            String systemPrompt = promptBuilder.buildScriptEpisodeSystemPrompt();
            String userPrompt = promptBuilder.buildScriptEpisodeUserPrompt(
                outline,
                chapter,
                globalCharacters,
                globalItems,
                previousSummary,
                resolvedEpisodeCount,
                project.getEpisodeDuration() != null ? project.getEpisodeDuration() / 60 : 1,
                modificationSuggestion
            );

            // 调用文本生成服务生成分集
            String episodesJson = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析并保存剧集
            List<Episode> episodes = parseAndSaveEpisodes(project, episodesJson, chapter);

            // 更新状态
            project.setStatus(STATUS_SCRIPT_REVIEW);
            projectRepository.updateById(project);

            log.info("分集生成完成: projectId={}, chapter={}, episodes={}",
                    projectId, chapter, episodes.size());

        } catch (Exception e) {
            log.error("分集生成失败: projectId={}, chapter={}", projectId, chapter, e);
            project.setStatus(STATUS_EPISODE_FAILED);
            projectRepository.updateById(project);
            throw new BusinessException("分集生成失败: " + e.getMessage());
        }
    }

    /**
     * 批量生成所有剩余章节的剧集
     * 按顺序生成，如果某章失败则停止
     */
    @Transactional
    public void generateAllEpisodes(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        if (!STATUS_OUTLINE_REVIEW.equals(project.getStatus()) &&
            !STATUS_SCRIPT_REVIEW.equals(project.getStatus())) {
            throw new BusinessException("当前状态不能生成分集，请先生成并确认大纲");
        }

        // 判断是否为单集模式
        boolean isSingleEpisode = project.getTotalEpisodes() != null && project.getTotalEpisodes() == 1;

        // 获取所有章节
        List<String> chapters;
        if (isSingleEpisode) {
            chapters = Collections.singletonList("单集剧本");
        } else {
            chapters = extractChaptersFromOutline(project.getScriptOutline());
        }

        // 获取已生成的章节
        List<Episode> existingEpisodes = episodeRepository.findByProjectId(projectId);
        Set<String> generatedChapters = new HashSet<>();
        for (Episode ep : existingEpisodes) {
            if (ep.getChapterTitle() != null) {
                generatedChapters.add(ep.getChapterTitle());
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
     * - OUTLINE_REVIEW 状态：确认大纲（单集模式可直接确认，多集模式需先生成所有章节）
     * - SCRIPT_REVIEW 状态：确认所有分集，进入下一阶段
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
            // 检查是否已生成所有章节的剧集
            if (isAllChaptersGenerated(project)) {
                project.setStatus(STATUS_SCRIPT_CONFIRMED);
                log.info("剧本全部确认完成: projectId={}", projectId);
            } else {
                throw new BusinessException("请先生成所有章节的剧集");
            }
        } else if (STATUS_SCRIPT_REVIEW.equals(status)) {
            // 确认分集
            if (isAllChaptersGenerated(project)) {
                project.setStatus(STATUS_SCRIPT_CONFIRMED);
                log.info("剧本全部确认完成: projectId={}", projectId);
            } else {
                throw new BusinessException("请先生成所有章节的剧集");
            }
        } else {
            throw new BusinessException("当前状态不能确认剧本");
        }

        projectRepository.updateById(project);

        log.info("剧本已确认: projectId={}", projectId);
        // 状态转换现在由状态机处理，不再手动触发
    }

    /**
     * 直接保存用户编辑的大纲（不触发 AI 重新生成）
     * 保存后会删除所有已生成剧集（大纲变更导致剧集失效）
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

        project.setScriptOutline(outline);
        projectRepository.updateById(project);

        // 大纲变更，删除已生成的所有剧集
        episodeRepository.deleteByProjectId(projectId);

        log.info("大纲直接保存完成（已清除剧集）: projectId={}", projectId);
    }

    /**
     * 修改大纲
     * 支持 OUTLINE_REVIEW 和 SCRIPT_CONFIRMED 状态修改
     */
    @Transactional
    public void reviseOutline(String projectId, String revisionNote, String currentOutline) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        String status = project.getStatus();

        // 只允许在 OUTLINE_REVIEW 状态修改大纲
        if (!STATUS_OUTLINE_REVIEW.equals(status)) {
            throw new BusinessException("当前状态不能修改大纲");
        }

        project.setScriptRevisionNote(revisionNote);
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
     * 包含：大纲、章节列表、已生成的剧集
     * 支持单集模式和多集模式
     */
    public Map<String, Object> getScriptContent(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        List<Episode> episodes = episodeRepository.findByProjectId(projectId);

        // 判断是否为单集模式
        boolean isSingleEpisode = project.getTotalEpisodes() != null && project.getTotalEpisodes() == 1;

        Map<String, Object> result = new HashMap<>();
        result.put("project", project);
        result.put("outline", project.getScriptOutline());
        result.put("isSingleEpisode", isSingleEpisode);
        result.put("episodes", episodes);

        if (isSingleEpisode) {
            // 单集模式：用"单集剧本"作为唯一章节，与多集模式统一数据结构
            String singleChapter = "单集剧本";
            boolean hasEpisodes = !episodes.isEmpty();
            result.put("chapters", Collections.singletonList(singleChapter));
            result.put("generatedChapters", hasEpisodes ? Collections.singletonList(singleChapter) : Collections.emptyList());
            result.put("pendingChapters", hasEpisodes ? Collections.emptyList() : Collections.singletonList(singleChapter));
            result.put("nextChapter", hasEpisodes ? null : singleChapter);
            // needGenerateScript 保留向后兼容，但前端不再依赖它
            result.put("needGenerateScript", !hasEpisodes);
        } else {
            // 多集模式：提取章节列表
            List<String> chapters = extractChaptersFromOutline(project.getScriptOutline());

            // 计算已生成和未生成的章节
            Set<String> generatedChapters = new HashSet<>();
            for (Episode ep : episodes) {
                if (ep.getChapterTitle() != null) {
                    generatedChapters.add(ep.getChapterTitle());
                }
            }

            List<String> pendingChapters = new ArrayList<>();
            for (String chapter : chapters) {
                if (!generatedChapters.contains(chapter)) {
                    pendingChapters.add(chapter);
                }
            }

            // 找出下一个待生成的章节
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
     * 从大纲中提取章节列表
     * 正则匹配 "#### 第X章：..." 格式
     * 支持多种格式变体，提高兼容性
     */
    public List<String> extractChaptersFromOutline(String outline) {
        List<String> chapters = new ArrayList<>();
        if (outline == null || outline.isEmpty()) {
            log.warn("大纲内容为空，无法提取章节");
            return chapters;
        }

        // 匹配多种可能的章节标题格式：
        // 1. #### 第X章：章节名称（第A-B集）
        // 2. ### 第X章：章节名称
        // 3. #### 第X章 章节名称
        // 4. #### 章节X：章节名称
        // 支持中文数字和阿拉伯数字
        String[] patterns = {
            "#{3,4}\\s+第([一二三四五六七八九十百千万0-9]+)章[：:]?\\s*([^\\n]*)",  // #### 第X章：...
            "#{3,4}\\s+([一二三四五六七八九十百千万0-9]+)[、.\\s]+[^\\n]*章[：:]?\\s*([^\\n]*)"  // #### X. 章节标题...
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(outline);

            while (matcher.find()) {
                String chapterTitle = matcher.group().trim();
                // 确保不重复添加
                if (!chapters.contains(chapterTitle)) {
                    chapters.add(chapterTitle);
                }
            }
        }

        // 如果以上格式都没匹配到，尝试更宽松的匹配：查找所有 ### 或 #### 开头的行
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

        // 匹配 "## 主要人物小传" 部分
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

        // 匹配 "## 关键物品设定" 部分
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
            sb.append("第").append(ep.getEpisodeNum()).append("集：").append(ep.getTitle()).append("\n");
            sb.append("- 涉及角色：").append(ep.getCharacters() != null ? ep.getCharacters() : "无").append("\n");
            sb.append("- 关键物品：").append(ep.getKeyItems() != null ? ep.getKeyItems() : "无").append("\n");
            if (ep.getContent() != null && ep.getContent().length() > 200) {
                sb.append("- 剧情摘要：").append(ep.getContent().substring(0, 200)).append("...\n");
            } else if (ep.getContent() != null) {
                sb.append("- 剧情摘要：").append(ep.getContent()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 验证章节顺序（必须顺序生成）
     */
    private void validateChapterOrder(Project project, String chapter) {
        List<String> chapters = extractChaptersFromOutline(project.getScriptOutline());
        List<Episode> existingEpisodes = episodeRepository.findByProjectId(project.getProjectId());

        // 获取已生成的章节
        Set<String> generatedChapters = new HashSet<>();
        for (Episode ep : existingEpisodes) {
            if (ep.getChapterTitle() != null && project.getProjectId().equals(ep.getProjectId())) {
                generatedChapters.add(ep.getChapterTitle());
            }
        }

        // 找到第一个未生成的章节
        String expectedNext = null;
        for (String ch : chapters) {
            if (!generatedChapters.contains(ch)) {
                expectedNext = ch;
                break;
            }
        }

        // 验证用户选择的是否是下一个待生成的章节
        if (expectedNext != null && !expectedNext.equals(chapter)) {
            throw new BusinessException("必须顺序生成章节。下一个待生成的章节是：" + expectedNext);
        }
    }

    /**
     * 检查是否已生成所有章节
     */
    private boolean isAllChaptersGenerated(Project project) {
        List<String> chapters = extractChaptersFromOutline(project.getScriptOutline());
        List<Episode> episodes = episodeRepository.findByProjectId(project.getProjectId());

        // 简化判断：已生成的集数 >= 总集数
        return episodes.size() >= project.getTotalEpisodes();
    }

    /**
     * 删除指定章节的剧集
     */
    private void deleteEpisodesByChapter(String projectId, String chapter) {
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        for (Episode ep : episodes) {
            if (chapter.equals(ep.getChapterTitle())) {
                episodeRepository.deleteById(ep.getId());
            }
        }
    }

    int resolveEpisodeCount(Project project, String chapter, Integer requestedEpisodeCount, boolean isBatch) {
        boolean isSingleEpisode = project != null
                && project.getTotalEpisodes() != null
                && project.getTotalEpisodes() == 1;
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

        if (project != null && project.getEpisodesPerChapter() != null && project.getEpisodesPerChapter() > 0) {
            return project.getEpisodesPerChapter();
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

            // 解析 JSON（支持数组和单对象两种格式）
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            // 如果 AI 返回的是单个对象（单集模式常见），包装成数组统一处理
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
                episode.setEpisodeNum(episodeNum++);
                episode.setTitle(getJsonText(episodeNode, "title", "第" + (episodeNum - 1) + "集"));
                episode.setContent(getJsonText(episodeNode, "content", ""));
                episode.setCharacters(getJsonText(episodeNode, "characters", ""));
                episode.setKeyItems(getJsonText(episodeNode, "keyItems", ""));
                episode.setContinuityNote(getJsonText(episodeNode, "continuityNote", ""));
                episode.setVisualStyleNote(getJsonText(episodeNode, "visualStyleNote", ""));
                episode.setChapterTitle(chapterTitle);
                episode.setStatus("DRAFT");
                episode.setRetryCount(0);

                episodeRepository.insert(episode);
                episodes.add(episode);
            }

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
            if (ep.getEpisodeNum() != null && ep.getEpisodeNum() > max) {
                max = ep.getEpisodeNum();
            }
        }
        return max + 1;
    }

    /**
     * 安全获取JSON文本字段
     */
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
        project.setStatus(STATUS_OUTLINE_GENERATING);
        projectRepository.updateById(project);

        try {
            // 构建带修改意见的prompt
            String systemPrompt = promptBuilder.buildScriptOutlineSystemPrompt(
                project.getTotalEpisodes(),
                project.getGenre(),
                project.getTargetAudience()
            );

            String userPrompt = promptBuilder.buildScriptOutlineUserPrompt(
                project.getStoryPrompt(),
                project.getGenre(),
                currentOutline,
                project.getTotalEpisodes(),
                project.getEpisodeDuration() != null ? project.getEpisodeDuration() / 60 : 1,
                project.getVisualStyle() != null ? project.getVisualStyle() : "REAL"
            );

            // 添加修改意见
            userPrompt += "\n\n**修改要求**：" + revisionNote;

            String outlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            project.setScriptOutline(outlineContent);
            project.setStatus(STATUS_OUTLINE_REVIEW);
            projectRepository.updateById(project);

            // 删除之前生成的所有剧集（因为大纲变了）
            episodeRepository.deleteByProjectId(project.getProjectId());

            log.info("大纲重新生成完成: projectId={}", project.getProjectId());

        } catch (Exception e) {
            log.error("大纲重新生成失败", e);
            project.setStatus(STATUS_OUTLINE_FAILED);
            projectRepository.updateById(project);
            throw new BusinessException("大纲重新生成失败: " + e.getMessage());
        }
    }
}
