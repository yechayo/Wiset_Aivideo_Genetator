package com.comic.service.script;

import com.comic.ai.PromptBuilder;
import com.comic.ai.text.TextGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.WorldConfigDTO;
import com.comic.entity.Episode;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.service.world.WorldRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final PromptBuilder promptBuilder;
    private final WorldRuleService worldRuleService;
    private final ObjectMapper objectMapper;

    // 状态常量（统一使用 ProjectStatus 枚举）
    private static final String STATUS_DRAFT = ProjectStatus.DRAFT.getCode();
    private static final String STATUS_OUTLINE_GENERATING = ProjectStatus.OUTLINE_GENERATING.getCode();
    private static final String STATUS_OUTLINE_REVIEW = ProjectStatus.OUTLINE_REVIEW.getCode();
    private static final String STATUS_EPISODE_GENERATING = ProjectStatus.EPISODE_GENERATING.getCode();
    private static final String STATUS_SCRIPT_REVIEW = ProjectStatus.SCRIPT_REVIEW.getCode();
    private static final String STATUS_SCRIPT_CONFIRMED = ProjectStatus.SCRIPT_CONFIRMED.getCode();
    private static final String STATUS_OUTLINE_FAILED = ProjectStatus.OUTLINE_GENERATING_FAILED.getCode();
    private static final String STATUS_EPISODE_FAILED = ProjectStatus.EPISODE_GENERATING_FAILED.getCode();

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
            WorldConfigDTO worldConfig = worldRuleService.getWorldConfig(projectId);

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
                project.getEpisodeDuration() != null ? project.getEpisodeDuration() : 1,
                "REAL" // 默认视觉风格
            );

            log.info("systemPrompt: {}", systemPrompt);
            log.info("userPrompt: {}", userPrompt);

            // 调用文本生成服务生成大纲
            String outlineContent = textGenerationService.generate(systemPrompt, userPrompt);

            // 保存大纲到项目
            project.setScriptOutline(outlineContent);
            project.setEpisodesPerChapter(4); // 默认每章4集
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
    public void generateScriptEpisodes(String projectId, String chapter, int episodeCount, String modificationSuggestion) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        // 验证状态
        if (!STATUS_OUTLINE_REVIEW.equals(project.getStatus()) &&
            !STATUS_SCRIPT_REVIEW.equals(project.getStatus())) {
            throw new BusinessException("当前状态不能生成分集，请先生成并确认大纲");
        }

        // 判断是否为单集模式
        boolean isSingleEpisode = project.getTotalEpisodes() != null && project.getTotalEpisodes() == 1;

        // 多集模式：验证章节顺序（必须顺序生成）
        if (!isSingleEpisode) {
            validateChapterOrder(project, chapter);
        }

        // 更新状态
        project.setStatus(STATUS_EPISODE_GENERATING);
        project.setSelectedChapter(isSingleEpisode ? "单集剧本" : chapter);
        projectRepository.updateById(project);

        try {
            String outline = project.getScriptOutline();

            // 从大纲提取全局信息
            String globalCharacters = extractCharactersFromOutline(outline);
            String globalItems = extractItemsFromOutline(outline);

            // 获取前序剧集摘要（保持连贯性）
            String previousSummary = buildPreviousEpisodesSummary(projectId);

            // 构建分集prompt
            String systemPrompt;
            String userPrompt;

            if (isSingleEpisode) {
                // 单集模式：使用专门的单集 prompt
                systemPrompt = promptBuilder.buildSingleEpisodeScriptSystemPrompt();
                userPrompt = promptBuilder.buildSingleEpisodeScriptUserPrompt(
                    outline,
                    globalCharacters,
                    globalItems,
                    project.getEpisodeDuration() != null ? project.getEpisodeDuration() : 1,
                    modificationSuggestion
                );
            } else {
                // 多集模式：使用章节 prompt
                systemPrompt = promptBuilder.buildScriptEpisodeSystemPrompt();
                userPrompt = promptBuilder.buildScriptEpisodeUserPrompt(
                    outline,
                    chapter,
                    globalCharacters,
                    globalItems,
                    previousSummary,
                    episodeCount,
                    project.getEpisodeDuration() != null ? project.getEpisodeDuration() : 1,
                    modificationSuggestion
                );
            }

            // 调用文本生成服务生成分集
            String episodesJson = textGenerationService.generate(systemPrompt, userPrompt);

            // 解析并保存剧集
            String chapterTitle = isSingleEpisode ? "单集剧本" : chapter;
            List<Episode> episodes = parseAndSaveEpisodes(project, episodesJson, chapterTitle);

            // 更新状态
            project.setStatus(STATUS_SCRIPT_REVIEW);
            projectRepository.updateById(project);

            log.info("分集生成完成: projectId={}, chapter={}, episodes={}, isSingleEpisode={}",
                    projectId, chapterTitle, episodes.size(), isSingleEpisode);

        } catch (Exception e) {
            log.error("分集生成失败: projectId={}, chapter={}", projectId, chapter, e);
            project.setStatus(STATUS_EPISODE_FAILED);
            projectRepository.updateById(project);
            throw new BusinessException("分集生成失败: " + e.getMessage());
        }
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
        boolean isSingleEpisode = project.getTotalEpisodes() != null && project.getTotalEpisodes() == 1;

        // 已经是确认状态，直接返回（幂等操作）
        if (STATUS_SCRIPT_CONFIRMED.equals(status)) {
            log.info("剧本已确认，跳过: projectId={}", projectId);
            return;
        }

        if (STATUS_OUTLINE_REVIEW.equals(status)) {
            // 单集模式：大纲生成后可直接确认
            if (isSingleEpisode) {
                project.setStatus(STATUS_SCRIPT_CONFIRMED);
                log.info("单集剧本确认完成: projectId={}", projectId);
            } else {
                // 多集模式：检查是否已生成所有章节
                if (isAllChaptersGenerated(project)) {
                    project.setStatus(STATUS_SCRIPT_CONFIRMED);
                    log.info("剧本全部确认完成: projectId={}", projectId);
                } else {
                    throw new BusinessException("请先生成所有章节的剧集");
                }
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
    }

    /**
     * 修改大纲
     * 支持 OUTLINE_REVIEW 和 SCRIPT_CONFIRMED 状态修改
     */
    @Transactional
    public void reviseOutline(String projectId, String revisionNote) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        String status = project.getStatus();
        boolean isSingleEpisode = project.getTotalEpisodes() != null && project.getTotalEpisodes() == 1;

        // 单集模式：允许在 OUTLINE_REVIEW 或 SCRIPT_CONFIRMED 状态修改
        // 多集模式：只允许在 OUTLINE_REVIEW 状态修改
        boolean canRevise = STATUS_OUTLINE_REVIEW.equals(status) ||
                           (isSingleEpisode && STATUS_SCRIPT_CONFIRMED.equals(status));

        if (!canRevise) {
            throw new BusinessException("当前状态不能修改大纲");
        }

        project.setScriptRevisionNote(revisionNote);
        projectRepository.updateById(project);

        // 重新生成大纲
        regenerateOutline(project, revisionNote);
    }

    /**
     * 修改指定章节的分集
     */
    @Transactional
    public void reviseEpisodes(String projectId, String chapter, int episodeCount, String revisionNote) {
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
            // 单集模式：不需要章节列表
            result.put("chapters", Collections.emptyList());
            result.put("generatedChapters", Collections.emptyList());
            result.put("pendingChapters", Collections.emptyList());
            result.put("nextChapter", "单集剧本");
            result.put("needGenerateScript", episodes.isEmpty());
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
     */
    public List<String> extractChaptersFromOutline(String outline) {
        List<String> chapters = new ArrayList<>();
        if (outline == null || outline.isEmpty()) {
            return chapters;
        }

        // 匹配 "#### 第X章：章节名称（第A-B集）" 格式
        Pattern pattern = Pattern.compile("####\\s+第([一二三四五六七八九十百千万0-9]+)章[：:]\\s*([^\\n]+)");
        Matcher matcher = pattern.matcher(outline);

        while (matcher.find()) {
            chapters.add(matcher.group().trim());
        }

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

            // 解析 JSON
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            if (rootNode.isArray()) {
                int episodeNum = getNextEpisodeNum(project.getProjectId());

                for (JsonNode episodeNode : rootNode) {
                    Episode episode = new Episode();
                    episode.setProjectId(project.getProjectId());
                    episode.setEpisodeNum(episodeNum++);
                    episode.setTitle(getJsonText(episodeNode, "title", "第" + (episodeNum - 1) + "集"));
                    episode.setContent(getJsonText(episodeNode, "content", ""));
                    episode.setCharacters(getJsonText(episodeNode, "characters", ""));
                    episode.setKeyItems(getJsonText(episodeNode, "keyItems", ""));
                    episode.setContinuityNote(getJsonText(episodeNode, "continuityNote", ""));
                    episode.setChapterTitle(chapterTitle);
                    episode.setStatus("DRAFT");
                    episode.setRetryCount(0);

                    episodeRepository.insert(episode);
                    episodes.add(episode);
                }
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
    private void regenerateOutline(Project project, String revisionNote) {
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
                null,
                project.getTotalEpisodes(),
                project.getEpisodeDuration() != null ? project.getEpisodeDuration() : 1,
                "REAL"
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
