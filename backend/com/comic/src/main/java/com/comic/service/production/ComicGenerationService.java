package com.comic.service.production;

import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.response.ComicStatusResponse;
import com.comic.entity.Panel;
import com.comic.repository.PanelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicGenerationService {

    private final PanelRepository panelRepository;
    private final ImageGenerationService imageGenerationService;
    private final ApplicationContext applicationContext;

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
            String sceneDesc = getStr(info, "sceneDescription");
            String prompt = buildComicPrompt(sceneDesc);
            String comicUrl = imageGenerationService.generateWithReference(prompt, bgUrl, 1280, 720);
            info.put("comicUrl", comicUrl);
            info.put("comicStatus", "pending_review");
            info.put("errorMessage", null);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);
            log.info("四宫格漫画生成完成: panelId={}", panelId);
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
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("分镜不存在");
        Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
        info.put("comicStatus", "needs_revision");
        info.put("revisionFeedback", feedback);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
        self().doGenerateComic(panelId);
    }

    private String buildComicPrompt(String sceneDesc) {
        StringBuilder prompt = new StringBuilder();
        if (sceneDesc != null && !sceneDesc.isEmpty()) prompt.append(sceneDesc);
        prompt.append(". Create a 2x2 comic grid layout with 4 sequential scenes, ");
        prompt.append("with sequence numbers (1,2,3,4) marked on each cell. ");
        prompt.append("Anime style, high quality.");
        return prompt.toString();
    }

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }
}