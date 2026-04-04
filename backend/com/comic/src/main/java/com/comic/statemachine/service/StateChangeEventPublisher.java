package com.comic.statemachine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StateChangeEventPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 发布项目状态变更到 Redis pub/sub（SSE 控制器监听此 channel）
     */
    private void publishToRedis(String projectId, String eventType, Map<String, Object> payload) {
        try {
            payload.put("eventType", eventType);
            payload.put("projectId", projectId);
            payload.put("timestamp", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(payload);
            redis.convertAndSend("project:status:" + projectId, json);
            log.debug("Published to Redis: projectId={}, eventType={}", projectId, eventType);
        } catch (Exception e) {
            log.error("Failed to publish to Redis: projectId={}, eventType={}", projectId, eventType, e);
        }
    }

    // ===== 里程碑变更 =====

    /**
     * 发布里程碑变更事件（新状态机使用）
     */
    public void publishMilestoneChange(String projectId, String milestoneCode) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", "");
        payload.put("to", milestoneCode);
        publishToRedis(projectId, "milestone-change", payload);
        log.info("Milestone change published: projectId={}, milestone={}", projectId, milestoneCode);
    }

    // ===== 任务进度 =====

    public void publishProgress(String projectId, int progress, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("progress", progress);
        payload.put("message", message);
        publishToRedis(projectId, "progress", payload);
    }

    public void publishFailure(String projectId, String error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("error", error);
        publishToRedis(projectId, "failure", payload);
    }

    public void publishTaskStart(String projectId, String taskType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskType", taskType);
        publishToRedis(projectId, "task-start", payload);
    }

    public void publishTaskComplete(String projectId, String taskType, Object result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskType", taskType);
        if (result != null) {
            payload.put("result", result);
        }
        publishToRedis(projectId, "task-complete", payload);
    }

    // ===== 细粒度事件（Step5 SSE 使用） =====

    public void publishEpisodeScriptDone(String projectId, int episodeNum, String title, int totalEpisodes, int completedEpisodes) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeNum", episodeNum);
        payload.put("title", title);
        payload.put("totalEpisodes", totalEpisodes);
        payload.put("completedEpisodes", completedEpisodes);
        publishToRedis(projectId, "episode:script_done", payload);
    }

    public void publishEpisodePanelDone(String projectId, Long episodeId, int episodeNum, int shotsCount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeId", episodeId);
        payload.put("episodeNum", episodeNum);
        payload.put("shotsCount", shotsCount);
        publishToRedis(projectId, "episode:panel_done", payload);
    }

    public void publishEpisodeGridStatus(String projectId, Long episodeId, int episodeNum, String gridStatus) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeId", episodeId);
        payload.put("episodeNum", episodeNum);
        payload.put("gridStatus", gridStatus);
        publishToRedis(projectId, "episode:grid_status", payload);
    }

    // ===== Panel 级别事件（视频生成） =====

    public void publishPanelVideoDone(String projectId, Long episodeId, Long panelId, String videoUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeId", episodeId);
        payload.put("panelId", panelId);
        payload.put("videoUrl", videoUrl);
        publishToRedis(projectId, "panel:video_done", payload);
    }

    public void publishPanelVideoFailed(String projectId, Long episodeId, Long panelId, String error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeId", episodeId);
        payload.put("panelId", panelId);
        payload.put("error", error);
        publishToRedis(projectId, "panel:video_failed", payload);
    }

    // ===== Panel 级别事件（TTS 旁白） =====

    public void publishPanelTtsDone(String projectId, Long episodeId, Long panelId, String ttsAudioUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeId", episodeId);
        payload.put("panelId", panelId);
        payload.put("ttsAudioUrl", ttsAudioUrl);
        payload.put("ttsStatus", "completed");
        publishToRedis(projectId, "panel:tts_done", payload);
    }

    public void publishPanelTtsFailed(String projectId, Long episodeId, Long panelId, String error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("episodeId", episodeId);
        payload.put("panelId", panelId);
        payload.put("ttsStatus", "failed");
        payload.put("error", error);
        publishToRedis(projectId, "panel:tts_failed", payload);
    }
}