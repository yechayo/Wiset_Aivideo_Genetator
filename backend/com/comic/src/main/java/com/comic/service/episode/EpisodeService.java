package com.comic.service.episode;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.common.BusinessException;
import com.comic.dto.request.EpisodeCreateRequest;
import com.comic.dto.request.EpisodeUpdateRequest;
import com.comic.dto.response.EpisodeListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EpisodeService {

    private final EpisodeRepository episodeRepository;

    public PaginatedResponse<EpisodeListItemResponse> getEpisodesPage(String projectId, String name, int page, int size) {
        IPage<Episode> episodePage = episodeRepository.findPageByProjectId(
                projectId, name, new Page<>(page, size));
        List<EpisodeListItemResponse> items = episodePage.getRecords().stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());
        return PaginatedResponse.of(items, episodePage.getTotal(), (int) episodePage.getCurrent(), (int) episodePage.getSize());
    }

    public EpisodeListItemResponse getEpisode(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        return toListItemResponse(episode);
    }

    @Transactional
    public EpisodeListItemResponse createEpisode(String projectId, EpisodeCreateRequest request) {
        Episode episode = new Episode();
        episode.setProjectId(projectId);
        episode.setEpisodeInfo(request.getEpisodeInfo());
        episodeRepository.insert(episode);
        return toListItemResponse(episode);
    }

    @Transactional
    public void updateEpisode(String projectId, Long episodeId, EpisodeUpdateRequest request) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        if (request.getEpisodeInfo() != null) {
            Map<String, Object> existingInfo = episode.getEpisodeInfo();
            if (existingInfo == null) {
                episode.setEpisodeInfo(request.getEpisodeInfo());
            } else {
                existingInfo.putAll(request.getEpisodeInfo());
                episode.setEpisodeInfo(existingInfo);
            }
        }
        episodeRepository.updateById(episode);
    }

    @Transactional
    public void deleteEpisode(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        episodeRepository.deleteById(episodeId);
    }

    private EpisodeListItemResponse toListItemResponse(Episode episode) {
        EpisodeListItemResponse response = new EpisodeListItemResponse();
        response.setId(episode.getId());
        response.setProjectId(episode.getProjectId());
        response.setEpisodeInfo(episode.getEpisodeInfo());
        response.setCreatedAt(episode.getCreatedAt());
        response.setUpdatedAt(episode.getUpdatedAt());
        return response;
    }
}