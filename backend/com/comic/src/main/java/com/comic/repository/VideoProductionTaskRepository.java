package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.VideoProductionTask;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频生产任务Repository
 */
@Mapper
public interface VideoProductionTaskRepository extends BaseMapper<VideoProductionTask> {

    /**
     * 根据剧集ID查找所有任务
     */
    default List<VideoProductionTask> findByEpisodeId(Long episodeId) {
        return selectList(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getEpisodeId, episodeId)
                .orderByAsc(VideoProductionTask::getPanelIndex));
    }

    /**
     * 根据任务组查找任务列表
     */
    default List<VideoProductionTask> findByTaskGroup(String taskGroup) {
        return selectList(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getTaskGroup, taskGroup)
                .orderByAsc(VideoProductionTask::getPanelIndex));
    }

    /**
     * 查找待处理任务
     */
    default List<VideoProductionTask> findPendingTasks() {
        return selectList(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getStatus, "PENDING")
                .lt(VideoProductionTask::getRetryCount, 3)
                .orderByAsc(VideoProductionTask::getCreatedAt));
    }

        /**
         * 查找可恢复任务：
         * - PENDING 任务
         * - 长时间卡住的 PROCESSING 任务
         */
        default List<VideoProductionTask> findRecoverableTasks(LocalDateTime processingStaleBefore) {
        return selectList(new LambdaQueryWrapper<VideoProductionTask>()
            .lt(VideoProductionTask::getRetryCount, 3)
            .and(w -> w
                .eq(VideoProductionTask::getStatus, "PENDING")
                .or(pw -> pw
                    .eq(VideoProductionTask::getStatus, "PROCESSING")
                    .lt(VideoProductionTask::getUpdatedAt, processingStaleBefore)))
            .orderByAsc(VideoProductionTask::getCreatedAt));
        }

    /**
     * 删除剧集下的所有生产任务
     */
    default void deleteByEpisodeId(Long episodeId) {
        delete(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getEpisodeId, episodeId));
    }

    /**
     * 根据任务ID查找
     */
    default VideoProductionTask findByTaskId(String taskId) {
        return selectOne(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getTaskId, taskId));
    }

    /**
     * 统计剧集已完成任务数
     */
    default Integer countCompletedByEpisodeId(Long episodeId) {
        return Math.toIntExact(selectCount(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getEpisodeId, episodeId)
                .eq(VideoProductionTask::getStatus, "COMPLETED")));
    }

    /**
     * 统计任务组已完成任务数
     */
    default Integer countCompletedByTaskGroup(String taskGroup) {
        return Math.toIntExact(selectCount(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getTaskGroup, taskGroup)
                .eq(VideoProductionTask::getStatus, "COMPLETED")));
    }

    /**
     * 根据剧集ID和格子索引查找任务列表
     */
    default List<VideoProductionTask> findByEpisodeIdAndPanelIndex(Long episodeId, Integer panelIndex) {
        return selectList(new LambdaQueryWrapper<VideoProductionTask>()
                .eq(VideoProductionTask::getEpisodeId, episodeId)
                .eq(VideoProductionTask::getPanelIndex, panelIndex));
    }
}
