package com.comic.service.production;

import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.dto.response.ComicStatusResponse;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.service.panel.PanelService;
import com.comic.service.pipeline.ProjectStatusBroadcaster;
import com.comic.service.oss.OssService;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicGenerationService {

    private final PanelRepository panelRepository;
    private final PanelPromptBuilder panelPromptBuilder;
    private final ImageGenerationService imageGenerationService;
    private final ApplicationContext applicationContext;
    private final EpisodeRepository episodeRepository;
    private final CharacterRepository characterRepository;
    private final ProjectStatusBroadcaster broadcaster;
    private final OssService ossService;
    private final VideoCompositionService videoCompositionService;
    private final PanelService panelService;

    private ComicGenerationService self() {
        return applicationContext.getBean(ComicGenerationService.class);
    }

    public ComicStatusResponse getComicStatus(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        ComicStatusResponse response = new ComicStatusResponse();
        response.setPanelId(panelId);
        Map<String, Object> info = panel.getPanelInfo();
        String comicUrl = info != null ? getStr(info, "comicUrl") : null;
        String comicStatus = info != null ? getStr(info, "comicStatus") : null;
        String bgUrl = info != null ? getStr(info, "backgroundUrl") : null;
        String errorMsg = info != null ? getStr(info, "errorMessage") : null;
        response.setBackgroundUrl(bgUrl);
        response.setComicUrl(comicUrl);
        response.setStatus(comicStatus != null ? comicStatus : (comicUrl != null ? "approved" : "pending"));
        response.setErrorMessage(errorMsg);
        return response;
    }

    public void generateComic(Long panelId) {
        checkNotGenerating(panelId, "comicStatus", "四宫格漫画");
        self().doGenerateComic(panelId);
    }

    @Async
    public void doGenerateComic(Long panelId) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) throw new BusinessException("分镜不存在");
            Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
            String bgUrl = getStr(info, "backgroundUrl");
            if (bgUrl == null || bgUrl.isEmpty()) throw new BusinessException("背景图不存在，请先生成背景图");
            info.put("comicStatus", "generating");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);

            // 提前获取 episode（供 resolveCharacterDescriptions 和剧情简介共用）
            String projectId = null;
            Episode episode = null;
            try {
                episode = episodeRepository.selectById(panel.getEpisodeId());
                if (episode != null) {
                    projectId = episode.getProjectId();
                }
            } catch (Exception e) {
                log.warn("获取 episode 失败: panelId={}", panelId, e);
            }

            Map<String, Map<String, String>> charDescriptions = resolveCharacterDescriptions(info, projectId);

            // 获取剧情简介
            String episodeSynopsis = null;
            try {
                if (episode != null) {
                    Map<String, Object> epInfo = episode.getEpisodeInfo();
                    if (epInfo != null) {
                        Object syn = epInfo.get("synopsis");
                        if (syn == null) syn = epInfo.get("summary");
                        if (syn == null) syn = epInfo.get("title");
                        if (syn != null) episodeSynopsis = syn.toString();
                    }
                }
            } catch (Exception e) {
                log.warn("获取剧情简介失败: panelId={}", panelId, e);
            }

            // 提取上一面板视频的尾帧作为视觉衔接参考
            String prevTailFrameUrl = null;
            try {
                Panel prevPanel = panelService.findPreviousPanel(panel.getEpisodeId(), panel.getId());
                if (prevPanel != null) {
                    Map<String, Object> prevInfo = prevPanel.getPanelInfo();
                    // 检查上一面板是否有已完成的视频
                    if (prevInfo != null) {
                        String prevVideoUrl = getStr(prevInfo, "videoUrl");
                        String prevVideoStatus = getStr(prevInfo, "videoStatus");
                        if ("completed".equals(prevVideoStatus) && prevVideoUrl != null && !prevVideoUrl.isEmpty()) {
                            // 优先使用缓存的尾帧URL
                            prevTailFrameUrl = getStr(prevInfo, "tailFrameUrl");
                            if (prevTailFrameUrl == null || prevTailFrameUrl.isEmpty()) {
                                // 提取视频最后一帧
                                prevTailFrameUrl = videoCompositionService.extractFrame(prevVideoUrl, -1);
                                // 缓存到上一面板的panelInfo中
                                prevInfo.put("tailFrameUrl", prevTailFrameUrl);
                                prevPanel.setPanelInfo(prevInfo);
                                panelRepository.updateById(prevPanel);
                                log.info("提取并缓存上一面板尾帧: prevPanelId={}, tailFrameUrl={}", prevPanel.getId(), prevTailFrameUrl);
                            } else {
                                log.info("使用缓存的上一面板尾帧: prevPanelId={}", prevPanel.getId());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("提取上一面板尾帧失败，跳过视觉衔接参考: panelId={}", panelId, e);
                prevTailFrameUrl = null; // 确保失败时不影响后续流程
            }

            // 构建参考图列表：[尾帧(如有), 背景图, 角色A合成图(如有), 角色B合成图(如有), ...]
            List<String> referenceImages = new ArrayList<>();
            // 如果有尾帧参考，放在最前面作为图1，背景图变为图2
            if (prevTailFrameUrl != null && !prevTailFrameUrl.isEmpty()) {
                referenceImages.add(prevTailFrameUrl);
                log.info("漫画图生成加入上一面板尾帧参考: 尾帧作为图1");
            }
            referenceImages.add(bgUrl);
            // 记录每个角色对应的参考图索引（从1开始；有尾帧时背景为图2，否则背景为图1）
            Map<String, String> characterImageIndices = new HashMap<>();
            int bgIndex = (prevTailFrameUrl != null && !prevTailFrameUrl.isEmpty()) ? 2 : 1;
            int imageIndex = bgIndex + 1; // 角色从背景图之后开始
            for (Map.Entry<String, Map<String, String>> entry : charDescriptions.entrySet()) {
                String charId = entry.getKey();
                Map<String, String> desc = entry.getValue();
                String threeViewUrl = desc.get("threeViewGridUrl");
                String expressionUrl = desc.get("expressionGridUrl");

                // 收集可用图片：优先三视图，其次表情图
                java.util.List<String> charImages = new ArrayList<>();
                if (threeViewUrl != null && !threeViewUrl.isEmpty()) charImages.add(threeViewUrl);
                if (expressionUrl != null && !expressionUrl.isEmpty()) charImages.add(expressionUrl);

                if (!charImages.isEmpty()) {
                    // 如果有两张图，水平拼合成一张以节省 API 槽位
                    String combinedUrl;
                    if (charImages.size() == 2) {
                        combinedUrl = ossService.combineImagesHorizontal(charImages.get(0), charImages.get(1));
                    } else {
                        combinedUrl = charImages.get(0);
                    }
                    referenceImages.add(combinedUrl);
                    characterImageIndices.put(charId, String.valueOf(imageIndex));
                    imageIndex++;
                }
            }
            log.info("漫画生成参考图列表: 共{}张(含尾帧={}, 背景图, 角色图)", referenceImages.size(), prevTailFrameUrl != null ? "图1" : "无");

            // 将 characterImageIndices 注入 charDescriptions
            for (Map.Entry<String, Map<String, String>> entry : charDescriptions.entrySet()) {
                String idx = characterImageIndices.get(entry.getKey());
                if (idx != null) {
                    entry.getValue().put("imageIndex", idx);
                }
            }

            String prompt = panelPromptBuilder.buildComicPrompt(panel.getPanelInfo(), charDescriptions, episodeSynopsis);
            // 如果有尾帧参考，在prompt中标注，并修正图片编号（背景图从图1变为图2）
            if (prevTailFrameUrl != null && !prevTailFrameUrl.isEmpty()) {
                prompt = "图1是上一个画面的结尾状态，请保持角色位置和动作的连贯性，画面应从上一场景自然过渡。\n\n" + prompt;
                // Fix image numbering: background was 图1, now needs to be 图2
                prompt = prompt.replace("图1：场景背景参考图", "图2：场景背景参考图");
                prompt = prompt.replace("图1：背景参考图", "图2：背景参考图");
            }
            String comicUrl;
            if (referenceImages.size() == 1) {
                // 只有背景图，走旧的单图参考逻辑
                comicUrl = imageGenerationService.generateWithReference(prompt, bgUrl, 2848, 1600);
            } else {
                comicUrl = imageGenerationService.generateWithMultipleReferences(prompt, referenceImages, 2848, 1600);
            }
            info.put("comicUrl", comicUrl);
            info.put("comicStatus", "pending_review");
            info.put("errorMessage", null);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            log.info("四宫格漫画生成完成: panelId={}", panelId);
            // 通知前端漫画待审核
            try {
                if (episode != null) {
                    broadcaster.broadcast(episode.getProjectId(), "PRODUCING", "PRODUCING");
                }
            } catch (Exception ex) {
                log.warn("漫画 pending_review 推送失败: panelId={}", panelId, ex);
            }
        } catch (Exception e) {
            log.error("四宫格漫画生成失败: panelId={}", panelId, e);
            Panel panel = panelRepository.selectById(panelId);
            if (panel != null) {
                Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
                info.put("comicStatus", "failed");
                info.put("errorMessage", e.getMessage());
                panel.setPanelInfo(info);
                panelRepository.updateById(panel);
            }
            throw new BusinessException("四宫格漫画生成失败: " + e.getMessage());
        }
    }

    public void approveComic(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();
        String comicUrl = info != null ? getStr(info, "comicUrl") : null;
        if (comicUrl == null) throw new BusinessException("四宫格漫画不存在，请先生成");
        info.put("comicStatus", "approved");
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
    }

    public void reviseComic(Long panelId, String feedback) {
        checkNotGenerating(panelId, "comicStatus", "四宫格漫画");
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
        info.put("comicStatus", "needs_revision");
        info.put("revisionFeedback", feedback);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
        self().doGenerateComic(panelId);
    }

    private void checkNotGenerating(Long panelId, String statusKey, String label) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo();
        String status = info != null ? getStr(info, statusKey) : null;
        if ("generating".equals(status)) {
            throw new BusinessException(label + "正在生成中，请稍后");
        }
    }

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * 从 panelInfo.characters 中提取 charId，查库获取角色的名称、外貌、角色图 URL 等。
     * 额外返回 expressionGridUrl、threeViewGridUrl、role 以供多图参考和提示词映射。
     * 兜底：当 char_id 存的是角色名称而非真实 ID 时，按名称查找。
     */
    private Map<String, Map<String, String>> resolveCharacterDescriptions(Map<String, Object> panelInfo, String projectId) {
        Map<String, Map<String, String>> result = new HashMap<>();
        if (panelInfo == null) return result;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) panelInfo.get("characters");
        if (characters == null || characters.isEmpty()) return result;

        for (Map<String, Object> ch : characters) {
            Object charIdObj = ch.get("char_id");
            if (charIdObj == null) continue;
            String charId = charIdObj.toString();

            try {
                Character character = characterRepository.findByCharId(charId);

                // 兜底：char_id 可能存的是角色名称而非真实 ID（如 "T-1" 而非 "CHAR-xxx"）
                if (character == null && projectId != null) {
                    character = characterRepository.findByNameAndProjectId(projectId, charId);
                    if (character != null) {
                        log.info("角色ID兜底命中: char_id='{}' -> 实际角色 '{}'", charId,
                                character.getCharacterInfo() != null ? character.getCharacterInfo().get(CharacterInfoKeys.NAME) : "?");
                    }
                }

                if (character == null) {
                    log.warn("漫画生成时角色未找到: charId={}", charId);
                    continue;
                }
                Map<String, Object> charInfo = character.getCharacterInfo();
                if (charInfo == null) continue;

                Map<String, String> desc = new HashMap<>();
                Object name = charInfo.get(CharacterInfoKeys.NAME);
                Object appearance = charInfo.get(CharacterInfoKeys.APPEARANCE);
                Object role = charInfo.get(CharacterInfoKeys.ROLE);
                Object expressionGridUrl = charInfo.get(CharacterInfoKeys.EXPRESSION_GRID_URL);
                Object threeViewGridUrl = charInfo.get(CharacterInfoKeys.THREE_VIEW_GRID_URL);
                desc.put("name", name != null ? name.toString() : null);
                desc.put("appearance", appearance != null ? appearance.toString() : null);
                desc.put("role", role != null ? role.toString() : null);
                desc.put("expressionGridUrl", expressionGridUrl != null ? expressionGridUrl.toString() : null);
                desc.put("threeViewGridUrl", threeViewGridUrl != null ? threeViewGridUrl.toString() : null);

                // 用真实的 charId 作为 key（兜底时 charId 参数是名称，需要替换为真实 ID）
                String realCharId = charInfo.get(CharacterInfoKeys.CHAR_ID) != null
                        ? charInfo.get(CharacterInfoKeys.CHAR_ID).toString() : charId;
                result.put(realCharId, desc);
            } catch (Exception e) {
                log.warn("查询角色信息失败: charId={}", charId, e);
            }
        }
        return result;
    }
}