package com.comic.service.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

    /** 是否正在生成（包含批量 batch 锁） */
    public boolean isGenerating(String projectId) {
        return getGeneratingTask(projectId) != null || isBatchLocked(projectId);
    }

    // ===== Batch 锁（批量生成期间保持整体 isGenerating=true，覆盖章节间的锁空窗） =====

    private String batchLockKey(String projectId) { return "project:" + projectId + ":batch_lock"; }

    /** 尝试获取批量锁（SETNX），防止并发重复触发批量生成 */
    public boolean tryBatchLock(String projectId) {
        Boolean ok = redis.opsForValue().setIfAbsent(
            batchLockKey(projectId), "batch", 60, TimeUnit.MINUTES);
        boolean success = Boolean.TRUE.equals(ok);
        if (success) {
            log.info("获取批量生成锁成功: projectId={}", projectId);
        } else {
            log.warn("获取批量生成锁失败（批量任务已在运行）: projectId={}", projectId);
        }
        return success;
    }

    /** 释放批量锁 */
    public void clearBatchLock(String projectId) {
        redis.delete(batchLockKey(projectId));
    }

    /** 是否持有批量锁 */
    public boolean isBatchLocked(String projectId) {
        return Boolean.TRUE.equals(redis.hasKey(batchLockKey(projectId)));
    }

    // ===== One-shot 标记（原子 SETNX，防并发重复触发） =====

    /** 原子标记，仅第一次调用返回 true，用于防重复发事件 */
    public boolean trySetOnce(String projectId, String marker) {
        Boolean ok = redis.opsForValue().setIfAbsent(
            "project:" + projectId + ":" + marker, "1", 5, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(ok);
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

    // ===== 启动清锁 =====

    /**
     * 后端启动完成后自动清理所有遗留的生成锁。
     * 防止后端异常重启后锁未释放导致任务无法提交。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void clearStaleLocks() {
        String[] patterns = {
            "project:*:generating",
            "project:*:batch_lock"
        };
        int total = 0;
        for (String pattern : patterns) {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                total += keys.size();
                log.info("启动清锁: 删除 {} 个遗留 key（pattern={}）: {}", keys.size(), pattern, keys);
            }
        }
        if (total == 0) {
            log.info("启动清锁: 无遗留锁，跳过");
        } else {
            log.warn("启动清锁: 共清理 {} 个遗留锁", total);
        }
    }

    // ===== 清理 =====

    /** 清理该项目所有 Redis 状态 */
    public void clearAll(String projectId) {
        redis.delete(generatingKey(projectId));
        redis.delete(errorKey(projectId));
        redis.delete(batchKey(projectId));
        redis.delete(batchLockKey(projectId));
    }
}
