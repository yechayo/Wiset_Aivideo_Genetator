package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.VideoProductionTask;
import org.apache.ibatis.annotations.Mapper;

import java.util.Arrays;
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
     * 查找待处理或处理中的任务
     */
    default List<VideoProductionTask> findPendingTasks() {
        return selectList(new LambdaQueryWrapper<VideoProductionTask>()
                .in(VideoProductionTask::getStatus, Arrays.asList("PENDING", "PROCESSING"))
                .lt(VideoProductionTask::getRetryCount, 3)
                .orderByAsc(VideoProductionTask::getCreatedAt));
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
}
