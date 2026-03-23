package com.comic.controller;

import com.comic.common.Result;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
import com.comic.service.story.StoryboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryControllerTest {

    @Mock
    private JobQueueService jobQueueService;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private StoryboardService storyboardService;

    @InjectMocks
    private StoryController storyController;

    @Test
    @DisplayName("generateStoryboard - 已有分镜时应拒绝提交生成任务")
    void generateStoryboard_shouldRejectWhenStoryboardAlreadyExists() {
        Map<String, Long> body = new HashMap<>();
        body.put("episodeId", 1L);

        Episode episode = new Episode();
        episode.setId(1L);
        episode.setStoryboardJson("{\"panels\":[{\"panel_id\":\"ep1_p1\"}]}");
        when(episodeRepository.selectById(1L)).thenReturn(episode);

        Result<Map<String, String>> result = storyController.generateStoryboard(body);

        assertEquals(400, result.getCode());
        assertEquals("当前剧集已有分镜，请使用修改分镜接口", result.getMessage());
        assertNull(result.getData());
        verify(jobQueueService, never()).submitStoryboardJob(1L);
    }

    @Test
    @DisplayName("generateStoryboard - 分镜为空时应提交生成任务")
    void generateStoryboard_shouldSubmitWhenStoryboardIsEmpty() {
        Map<String, Long> body = new HashMap<>();
        body.put("episodeId", 2L);

        Episode episode = new Episode();
        episode.setId(2L);
        episode.setStoryboardJson("   ");
        when(episodeRepository.selectById(2L)).thenReturn(episode);
        when(jobQueueService.submitStoryboardJob(2L)).thenReturn("job-2");

        Result<Map<String, String>> result = storyController.generateStoryboard(body);

        assertEquals(200, result.getCode());
        assertEquals("success", result.getMessage());
        assertNotNull(result.getData());
        assertEquals("job-2", result.getData().get("jobId"));
        verify(jobQueueService).submitStoryboardJob(2L);
    }

    @Test
    @DisplayName("retryStoryboard - 已有分镜时应拒绝重试生成")
    void retryStoryboard_shouldRejectWhenStoryboardAlreadyExists() {
        Map<String, Long> body = new HashMap<>();
        body.put("episodeId", 3L);

        Episode episode = new Episode();
        episode.setId(3L);
        episode.setStoryboardJson("{\"panels\":[{\"panel_id\":\"ep3_p1\"}]}");
        when(episodeRepository.selectById(3L)).thenReturn(episode);

        Result<String> result = storyController.retryStoryboard(body);

        assertEquals(400, result.getCode());
        assertEquals("当前剧集已有分镜，请使用修改分镜接口", result.getMessage());
        verify(storyboardService, never()).retryFailedStoryboard(3L);
    }
}
