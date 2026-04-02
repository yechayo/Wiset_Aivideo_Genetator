package com.comic.service.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 项目生成进度管理（Redis）
 *
 * 只存三类信息，不参与状态推导：
 * - generating 锁：防重复提交
 * - error：失败原因（展示给用户）
 * - batch：批量进度（真实计数）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final long TTL_MINUTES = 30;

    // ===== Key 生成 =====

    private String generatingKey(String projectId) { return "project:" + projectId + ":generating"; }
    private String errorKey(String projectId) { return "project:" + projectId + ":error"; }
    private String batchKey(String projectId) { return "project:" + projectId + ":batch"; }

    // ===== Generating 锁（防重复提交） =====

    /**
     * 尝试获取生成锁（SETNX + TTL）
     *
     * @param projectId 项目ID
     * @param taskType  任务类型（如 "outline", "episode", "asset_extract", "asset_image", "panel", "assembling"）
     * @return true = 获取成功，可以开始生成
     */
    public boolean tryLock(String projectId, String taskType) {
        Boolean ok = redis.opsForValue().setIfAbsent(
            generatingKey(projectId), taskType, TTL_MINUTES, TimeUnit.MINUTES);
        boolean success = Boolean.TRUE.equals(ok);
        if (success) {
            log.info("获取生成锁成功: projectId={}, task={}", projectId, taskType);
        } else {
            log.warn("获取生成锁失败（已有任务运行中）: projectId={}, task={}", projectId, taskType);
        }
        return success;
    }

    /** 释放生成锁 */
    public void unlock(String projectId) {
        redis.delete(generatingKey(projectId));
    }

    /** 获取当前生成任务类型（null = 无任务运行中） */
    public String getGeneratingTask(String projectId) {
        return redis.opsForValue().get(generatingKey(projectId));
    }

    /** 是否正在生成 */
    public boolean isGenerating(String projectId) {
        return getGeneratingTask(projectId) != null;
    }

    // ===== Error =====

    public void setError(String projectId, String error) {
        redis.opsForValue().set(errorKey(projectId), error, TTL_MINUTES, TimeUnit.MINUTES);
    }

    public String getError(String projectId) {
        return redis.opsForValue().get(errorKey(projectId));
    }

    public void clearError(String projectId) {
        redis.delete(errorKey(projectId));
    }

    // ===== Batch Progress =====

    public void setBatchProgress(String projectId, int completed, int total, String phase) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("completed", completed);
            data.put("total", total);
            data.put("phase", phase);
            redis.opsForValue().set(batchKey(projectId), objectMapper.writeValueAsString(data),
                TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("设置批量进度失败: projectId={}", projectId, e);
        }
    }

    public Map<String, Object> getBatchProgress(String projectId) {
        try {
            String json = redis.opsForValue().get(batchKey(projectId));
            if (json == null) return null;
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("读取批量进度失败: projectId={}", projectId, e);
            return null;
        }
    }

    // ===== 清理 =====

    /** 清理该项目所有 Redis 状态 */
    public void clearAll(String projectId) {
        redis.delete(generatingKey(projectId));
        redis.delete(errorKey(projectId));
        redis.delete(batchKey(projectId));
    }
}
