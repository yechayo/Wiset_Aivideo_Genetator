package com.comic.service.production;

import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.dto.response.ComicStatusResponse;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.statemachine.service.StateChangeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
    private final StateChangeEventPublisher eventPublisher;

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
            Map<String, Map<String, String>> charDescriptions = resolveCharacterDescriptions(info);
            String prompt = panelPromptBuilder.buildComicPrompt(panel.getPanelInfo(), charDescriptions);
            String comicUrl = imageGenerationService.generateWithReference(prompt, bgUrl, 2848, 1600);
            info.put("comicUrl", comicUrl);
            info.put("comicStatus", "pending_review");
            info.put("errorMessage", null);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            log.info("四宫格漫画生成完成: panelId={}", panelId);
            // 通知前端漫画待审核
            try {
                Episode episode = episodeRepository.selectById(panel.getEpisodeId());
                if (episode != null) {
                    eventPublisher.publishProgress(episode.getProjectId(), 0, "四宫格漫画生成完成，待审核");
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
     * 从 panelInfo.characters 中提取 charId，查库获取角色的名称、外貌描述和英文提示词。
     */
    private Map<String, Map<String, String>> resolveCharacterDescriptions(Map<String, Object> panelInfo) {
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
                if (character == null) {
                    log.warn("漫画生成时角色未找到: charId={}", charId);
                    continue;
                }
                Map<String, Object> charInfo = character.getCharacterInfo();
                if (charInfo == null) continue;

                Map<String, String> desc = new HashMap<>();
                Object name = charInfo.get(CharacterInfoKeys.NAME);
                Object appearance = charInfo.get(CharacterInfoKeys.APPEARANCE);
                // 优先使用英文提示词
                Object appearancePrompt = charInfo.get(CharacterInfoKeys.APPEARANCE_PROMPT);

                desc.put("name", name != null ? name.toString() : null);
                desc.put("appearance", appearance != null ? appearance.toString() : null);
                desc.put("appearancePrompt", appearancePrompt != null ? appearancePrompt.toString() : null);
                result.put(charId, desc);
            } catch (Exception e) {
                log.warn("查询角色信息失败: charId={}", charId, e);
            }
        }
        return result;
    }
}