package com.comic.service.panel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comic.ai.text.ViduTtsService;
import com.comic.ai.video.VideoAudioMergeService;
import com.comic.constant.ProjectInfoKeys;
import com.comic.exception.BusinessException;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.PanelListItemResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.service.StateChangeEventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
@Slf4j
public class PanelService {

    private final PanelRepository panelRepository;
    private final EpisodeRepository episodeRepository;
    private final ProjectRepository projectRepository;
    private final StateChangeEventPublisher stateChangeEventPublisher;
    private final ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    private Episode validateEpisodeOwnership(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        return episode;
    }

    public List<PanelListItemResponse> getPanels(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            // episode 可能正在 @Transactional pipeline 中创建但尚未提交，返回空列表而非抛异常
            return Collections.emptyList();
        }
        return panelRepository.findByEpisodeId(episodeId).stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());
    }

    public PanelListItemResponse getPanel(String projectId, Long episodeId, Long panelId) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = panelRepository.findByEpisodeIdAndId(episodeId, panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }
        return toListItemResponse(panel);
    }

    @Transactional
    public PanelListItemResponse createPanel(String projectId, Long episodeId, PanelCreateRequest request) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = new Panel();
        panel.setEpisodeId(episodeId);
        panel.setStatus(request.getStatus() != null ? request.getStatus() : "DRAFT");
        panel.setPanelInfo(request.getPanelInfo());
        panelRepository.insert(panel);
        return toListItemResponse(panel);
    }

    @Transactional
    public void updatePanel(String projectId, Long episodeId, Long panelId, PanelUpdateRequest request) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = panelRepository.findByEpisodeIdAndId(episodeId, panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }
        if (request.getStatus() != null) {
            panel.setStatus(request.getStatus());
        }
        if (request.getPanelInfo() != null) {
            Map<String, Object> existingInfo = panel.getPanelInfo();
            if (existingInfo == null) {
                panel.setPanelInfo(request.getPanelInfo());
            } else {
                existingInfo.putAll(request.getPanelInfo());
                panel.setPanelInfo(existingInfo);
            }
        }
        panelRepository.updateById(panel);
    }

    @Transactional
    public void deletePanel(String projectId, Long episodeId, Long panelId) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = panelRepository.findByEpisodeIdAndId(episodeId, panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }
        panelRepository.deleteById(panelId);
    }

    // ===== TTS 旁白生成 =====

    /**
     * 为单个 panel 生成 TTS 旁白
     */
    public Map<String, Object> generateTts(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在");

        // 获取项目信息以确定音色
        Episode episode = episodeRepository.selectById(panel.getEpisodeId());
        Project project = projectRepository.findByProjectId(episode.getProjectId());
        Map<String, Object> projectInfo = project.getProjectInfo();

        String narrationPerspective = (String) projectInfo.get(ProjectInfoKeys.NARRATION_PERSPECTIVE);
        String voiceId;
        if ("first_person".equals(narrationPerspective)) {
            voiceId = (String) projectInfo.get(ProjectInfoKeys.PROTAGONIST_VOICE_ID);
        } else {
            voiceId = (String) projectInfo.get(ProjectInfoKeys.NARRATION_VOICE_ID);
        }
        if (voiceId == null || voiceId.isEmpty()) {
            throw new BusinessException("未配置旁白音色，请先在项目设置中选择");
        }

        // 更新状态为 generating
        updatePanelTtsStatus(panel, "generating", null, null);

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) panel.getPanelInfo().get("shots");
            ViduTtsService ttsService = applicationContext.getBean(ViduTtsService.class);
            String ttsText = ttsService.buildTtsText(shots);

            if (ttsText == null || ttsText.isEmpty()) {
                updatePanelTtsStatus(panel, "completed", null, 0);
                Map<String, Object> result = new HashMap<>();
                result.put("ttsAudioUrl", null);
                result.put("ttsStatus", "completed");
                result.put("ttsCredits", 0);
                result.put("skipped", true);
                return result;
            }

            String ossUrl = ttsService.generate(ttsText, voiceId, resolvePanelEmotion(shots));
            updatePanelTtsStatus(panel, "completed", ossUrl, null);

            stateChangeEventPublisher.publishPanelTtsDone(
                episode.getProjectId(), panel.getEpisodeId(), panelId, ossUrl);

            Map<String, Object> result = new HashMap<>();
            result.put("ttsAudioUrl", ossUrl);
            result.put("ttsStatus", "completed");
            result.put("ttsCredits", null);
            return result;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS 生成失败: panelId={}", panelId, e);
            updatePanelTtsStatus(panel, "failed", null, null);
            stateChangeEventPublisher.publishPanelTtsFailed(
                episode.getProjectId(), panel.getEpisodeId(), panelId, e.getMessage());
            throw new RuntimeException("TTS 生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量为某集所有 panel 生成 TTS
     */
    public Map<String, Object> batchGenerateTts(Long episodeId) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        int generated = 0, skipped = 0;
        for (Panel panel : panels) {
            try {
                Map<String, Object> result = generateTts(panel.getId());
                if (Boolean.TRUE.equals(result.get("skipped"))) {
                    skipped++;
                } else {
                    generated++;
                }
            } catch (Exception e) {
                log.warn("Panel {} TTS 跳过或失败: {}", panel.getId(), e.getMessage());
                skipped++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("generated", generated);
        result.put("skipped", skipped);
        return result;
    }

    private void updatePanelTtsStatus(Panel panel, String status, String audioUrl, Integer credits) {
        Map<String, Object> panelInfo = panel.getPanelInfo();
        if (panelInfo == null) {
            panelInfo = new HashMap<>();
        }
        panelInfo.put("ttsStatus", status);
        if (audioUrl != null) panelInfo.put("ttsAudioUrl", audioUrl);
        if (credits != null) panelInfo.put("ttsCredits", credits);
        panel.setPanelInfo(panelInfo);
        panelRepository.updateById(panel);
    }

    /**
     * 根据 Panel 内所有 shot 的 narrationTone 计算 TTS 使用的 emotion。
     * 取占比最高的情绪；平局时按优先级选强情绪。
     * 优先级：angry > fearful > surprised > disgusted > sad > happy > fluent > calm
     */
    private String resolvePanelEmotion(List<Map<String, Object>> shots) {
        // 强情绪优先级（数值越小优先级越高）
        java.util.LinkedHashMap<String, Integer> priority = new java.util.LinkedHashMap<>();
        priority.put("angry", 0);
        priority.put("fearful", 1);
        priority.put("surprised", 2);
        priority.put("disgusted", 3);
        priority.put("sad", 4);
        priority.put("happy", 5);
        priority.put("fluent", 6);
        priority.put("calm", 7);

        java.util.Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> shot : shots) {
            Object toneObj = shot.get("narrationTone");
            if (toneObj == null) continue;
            String tone = toneObj.toString().trim().toLowerCase();
            if (!tone.isEmpty() && !"无".equals(tone) && priority.containsKey(tone)) {
                counts.merge(tone, 1, Integer::sum);
            }
        }

        if (counts.isEmpty()) return null;

        // 找到优先级最高（数值最小）的情绪
        String best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            int p = priority.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            if (p < bestPriority || (p == bestPriority && counts.getOrDefault(entry.getKey(), 0) > counts.getOrDefault(best, 0))) {
                bestPriority = p;
                best = entry.getKey();
            }
        }
        return best;
    }

    // ===== 音视频合并 =====

    /**
     * 合并单个 panel 的视频和 TTS 旁白
     */
    public Map<String, Object> mergeAudio(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在");
        Map<String, Object> panelInfo = panel.getPanelInfo();

        String videoUrl = (String) panelInfo.get("videoUrl");
        String ttsAudioUrl = (String) panelInfo.get("ttsAudioUrl");
        String ttsStatus = (String) panelInfo.getOrDefault("ttsStatus", "pending");

        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new BusinessException("面板视频尚未生成");
        }
        if (!"completed".equals(ttsStatus) || ttsAudioUrl == null || ttsAudioUrl.isEmpty()) {
            throw new BusinessException("面板旁白尚未生成");
        }

        // 更新状态
        updatePanelInfo(panel, "mergeStatus", "generating");

        try {
            VideoAudioMergeService mergeService = applicationContext.getBean(VideoAudioMergeService.class);
            String mergedUrl = mergeService.merge(videoUrl, ttsAudioUrl);
            updatePanelInfo(panel, "videoWithNarrationUrl", mergedUrl);
            updatePanelInfo(panel, "mergeStatus", "completed");

            Map<String, Object> result = new HashMap<>();
            result.put("videoWithNarrationUrl", mergedUrl);
            result.put("mergeStatus", "completed");

            // 发布 SSE 事件通知前端
            Episode episode = episodeRepository.selectById(panel.getEpisodeId());
            String projectId = episode != null ? episode.getProjectId() : null;
            Long episodeId = panel.getEpisodeId();
            stateChangeEventPublisher.publishPanelMergeDone(projectId, episodeId, panelId, mergedUrl);

            return result;
        } catch (Exception e) {
            log.error("音频合并失败: panelId={}", panelId, e);
            updatePanelInfo(panel, "mergeStatus", "failed");
            Episode episode = episodeRepository.selectById(panel.getEpisodeId());
            String projectId = episode != null ? episode.getProjectId() : null;
            stateChangeEventPublisher.publishPanelMergeFailed(projectId, panel.getEpisodeId(), panelId, e.getMessage());
            throw new RuntimeException("音频合并失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量合并某集所有 panel 的视频和 TTS
     */
    public Map<String, Object> batchMergeAudio(Long episodeId) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        int merged = 0, skipped = 0;
        for (Panel panel : panels) {
            try {
                mergeAudio(panel.getId());
                merged++;
            } catch (Exception e) {
                log.warn("Panel {} 合并跳过: {}", panel.getId(), e.getMessage());
                skipped++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("merged", merged);
        result.put("skipped", skipped);
        return result;
    }

    // ===== 一键合成（剧集视频拼接）=====

    /**
     * 一键合成：将某集所有 panel 视频拼接为一集完整视频
     * 漫剧模式使用 videoWithNarrationUrl（已合并旁白的视频），其他模式使用 videoUrl（原始视频）
     */
    public Map<String, Object> composeEpisode(Long episodeId, String productionMode) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);

        boolean useNarrationVideo = "comic_commentary".equals(productionMode);

        // 筛选出可拼接的 panel，按 id 排序
        List<Panel> sourcePanels = panels.stream()
                .filter(p -> {
                    Map<String, Object> info = p.getPanelInfo();
                    if (info == null) return false;
                    if (useNarrationVideo) {
                        return "completed".equals(info.get("mergeStatus"))
                                && info.get("videoWithNarrationUrl") != null;
                    } else {
                        return "completed".equals(info.get("videoStatus"))
                                && info.get("videoUrl") != null;
                    }
                })
                .sorted(Comparator.comparing(Panel::getId))
                .collect(Collectors.toList());

        if (sourcePanels.isEmpty()) {
            throw new BusinessException(useNarrationVideo
                    ? "没有已合并的 panel，请先合成音视频"
                    : "没有已完成的视频可合成");
        }

        com.comic.service.oss.OssService ossService = applicationContext.getBean(com.comic.service.oss.OssService.class);

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("episode-compose-");
            List<String> inputFiles = new ArrayList<>();

            // 下载所有视频
            String urlKey = useNarrationVideo ? "videoWithNarrationUrl" : "videoUrl";
            for (int i = 0; i < sourcePanels.size(); i++) {
                Panel panel = sourcePanels.get(i);
                String url = (String) panel.getPanelInfo().get(urlKey);
                File localFile = tempDir.resolve(String.format("panel_%03d.mp4", i)).toFile();
                ossService.downloadToFile(url, localFile.getAbsolutePath());
                inputFiles.add(localFile.getAbsolutePath());
            }

            // FFmpeg concat
            String outputFile = tempDir.resolve("episode_full.mp4").toAbsolutePath().toString();

            // 创建 concat 文件列表
            String concatFile = tempDir.resolve("concat.txt").toAbsolutePath().toString();
            StringBuilder sb = new StringBuilder();
            for (String f : inputFiles) {
                sb.append("file '").append(f.replace("\\", "/")).append("'\n");
            }
            Files.write(Paths.get(concatFile), sb.toString().getBytes());

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatFile,
                    "-c", "copy",
                    outputFile
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = process.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg 拼接失败: " + baos.toString());
            }

            // 上传到 OSS
            String ossUrl = ossService.uploadFromFile(outputFile, "episodes");

            // 更新 episodeInfo
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode != null) {
                Map<String, Object> epInfo = episode.getEpisodeInfo();
                if (epInfo == null) epInfo = new HashMap<>();
                epInfo.put("composedVideoUrl", ossUrl);
                epInfo.put("composedVideoStatus", "completed");
                episode.setEpisodeInfo(epInfo);
                episodeRepository.updateById(episode);
            }

            log.info("一键合成完成: episodeId={}, panels={}, url={}", episodeId, sourcePanels.size(), ossUrl);

            Map<String, Object> result = new HashMap<>();
            result.put("composedVideoUrl", ossUrl);
            result.put("panelCount", sourcePanels.size());
            return result;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("一键合成失败: episodeId={}", episodeId, e);
            throw new RuntimeException("一键合成失败: " + e.getMessage(), e);
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private void updatePanelInfo(Panel panel, String key, Object value) {
        Map<String, Object> panelInfo = panel.getPanelInfo();
        if (panelInfo == null) {
            panelInfo = new HashMap<>();
        }
        panelInfo.put(key, value);
        panel.setPanelInfo(panelInfo);
        panelRepository.updateById(panel);
    }

    @Transactional
    public void savePanelsFromGeneration(Long episodeId, String panelJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(panelJson, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> panels = (List<Map<String, Object>>) root.get("panels");
            if (panels == null || panels.isEmpty()) {
                log.warn("Panel JSON has no panels: episodeId={}", episodeId);
                return;
            }

            // 删除该 episode 下旧的 Panel（逻辑删除）
            List<Panel> oldPanels = panelRepository.findByEpisodeId(episodeId);
            for (Panel old : oldPanels) {
                panelRepository.deleteById(old.getId());
            }

            // 批量创建新 Panel
            for (Map<String, Object> panelData : panels) {
                Panel panel = new Panel();
                panel.setEpisodeId(episodeId);
                panel.setStatus("CREATED");
                panel.setPanelInfo(panelData);
                panelRepository.insert(panel);
            }

            log.info("Saved {} panels from generation: episodeId={}", panels.size(), episodeId);
        } catch (Exception e) {
            log.error("Failed to save panels from generation: episodeId={}", episodeId, e);
            throw new BusinessException("保存分镜数据失败: " + e.getMessage());
        }
    }

    /**
     * Find the panel immediately before the given panel within the same episode.
     * Panels are ordered by ID ascending.
     *
     * @param episodeId      the episode ID
     * @param currentPanelId the current panel ID
     * @return the previous panel, or null if this is the first panel
     */
    public Panel findPreviousPanel(Long episodeId, Long currentPanelId) {
        List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).getId().equals(currentPanelId)) {
                return i > 0 ? panels.get(i - 1) : null;
            }
        }
        return null;
    }

    private PanelListItemResponse toListItemResponse(Panel panel) {
        PanelListItemResponse response = new PanelListItemResponse();
        response.setId(panel.getId());
        response.setEpisodeId(panel.getEpisodeId());
        response.setStatus(panel.getStatus());
        response.setPanelInfo(panel.getPanelInfo());
        response.setCreatedAt(panel.getCreatedAt());
        response.setUpdatedAt(panel.getUpdatedAt());
        return response;
    }

    /**
     * 贪心分组算法（纯函数，可独立测试）
     *
     * 将分镜 shots 按最大时长分组，每组不超过 maxDuration 秒。
     * 最后一组只有 1 个 shot 且前面有组时，合并到前一组，保证每组至少 2 个 shot。
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

            // 浅拷贝 shot（顶层字段独立，嵌套 List/Map 仍共享引用）
            @SuppressWarnings("unchecked")
            Map<String, Object> shotCopy = new HashMap<>(shot);
            shotCopy.put("duration", duration);
            currentGroup.add(shotCopy);
            currentDuration += duration;
        }
        if (!currentGroup.isEmpty()) {
            // 最后一组只有 1 个 shot 且前面有组时，仅在不超过 maxDuration 时合并，保证每组至少 2 个 shot 且不突破时长上限
            if (currentGroup.size() == 1 && !groups.isEmpty()) {
                List<Map<String, Object>> prev = groups.get(groups.size() - 1);
                int prevSum = prev.stream().mapToInt(s -> ((Number) s.get("duration")).intValue()).sum();
                int lone = ((Number) currentGroup.get(0).get("duration")).intValue();
                if (prevSum + lone <= maxDuration) {
                    prev.addAll(currentGroup);
                } else {
                    groups.add(currentGroup);
                }
            } else {
                groups.add(currentGroup);
            }
        }
        return groups;
    }
}