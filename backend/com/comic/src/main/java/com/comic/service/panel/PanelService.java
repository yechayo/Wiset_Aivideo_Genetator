package com.comic.service.panel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comic.common.BusinessException;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.PanelListItemResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PanelService {

    private final PanelRepository panelRepository;
    private final EpisodeRepository episodeRepository;
    private final ObjectMapper objectMapper;

    private Episode validateEpisodeOwnership(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        return episode;
    }

    public List<PanelListItemResponse> getPanels(String projectId, Long episodeId) {
        validateEpisodeOwnership(projectId, episodeId);
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
}