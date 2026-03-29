package com.comic.service.panel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.PanelListItemResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.service.ProjectStateMachineService;
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
    private final ProjectRepository projectRepository;
    private final ProjectStateMachineService stateMachineService;
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

        // 删除后检查并同步项目状态
        syncProjectStatusAfterPanelChange(projectId);
    }

    /**
     * 分镜变更后同步项目状态
     * 检查所有分镜是否都已完成，如果是则将项目状态更新为 PANEL_REVIEW
     */
    private void syncProjectStatusAfterPanelChange(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) return;

        ProjectStatus currentStatus = ProjectStatus.fromCode(project.getStatus());

        // 只在分镜相关阶段才需要同步
        if (currentStatus != ProjectStatus.PANEL_GENERATING
            && currentStatus != ProjectStatus.PANEL_REVIEW
            && currentStatus != ProjectStatus.PANEL_GENERATING_FAILED) {
            return;
        }

        // 检查所有分镜是否都已完成
        List<Episode> episodes = episodeRepository.findByProjectId(projectId);
        boolean allPanelsCompleted = true;
        int totalPanels = 0;

        for (Episode episode : episodes) {
            List<Panel> panels = panelRepository.findByEpisodeId(episode.getId());
            totalPanels += panels.size();
            for (Panel p : panels) {
                if (!isPanelCompleted(p)) {
                    allPanelsCompleted = false;
                    break;
                }
            }
            if (!allPanelsCompleted) break;
        }

        // 如果没有任何分镜，不更新状态
        if (totalPanels == 0) {
            log.info("没有任何分镜，不更新项目状态: projectId={}", projectId);
            return;
        }

        // 如果所有分镜都完成且当前不是 PANEL_REVIEW，则更新状态
        if (allPanelsCompleted && currentStatus != ProjectStatus.PANEL_REVIEW) {
            log.info("所有分镜已完成，更新项目状态为 PANEL_REVIEW: projectId={}", projectId);
            stateMachineService.persistState(projectId,
                com.comic.statemachine.enums.ProjectState.PANEL_REVIEW);
        }
    }

    /**
     * 检查分镜是否完成（视频生成完成）
     */
    private boolean isPanelCompleted(Panel panel) {
        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) return false;

        String videoStatus = info.get("videoStatus") != null ? info.get("videoStatus").toString() : null;
        return "completed".equals(videoStatus);
    }

    @Transactional
    public void clearOldPanels(Long episodeId) {
        List<Panel> oldPanels = panelRepository.findByEpisodeId(episodeId);
        for (Panel old : oldPanels) {
            panelRepository.deleteById(old.getId());
        }
        log.info("Cleared {} old panels for episodeId={}", oldPanels.size(), episodeId);
    }

    @Transactional
    public void saveSinglePanel(Long episodeId, Map<String, Object> panelData, int index) {
        Panel panel = new Panel();
        panel.setEpisodeId(episodeId);
        panel.setStatus("CREATED");
        panel.setPanelInfo(panelData);
        panelRepository.insert(panel);
        log.debug("Saved panel {}/{} for episodeId={}", index + 1, index, episodeId);
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