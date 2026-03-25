package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Episode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface EpisodeRepository extends BaseMapper<Episode> {

    default List<Episode> findByProjectId(String projectId) {
        return selectList(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId)
            .orderByAsc(Episode::getId));
    }

    default int countByProjectIdAndStatus(String projectId, String status) {
        return Math.toIntExact(selectCount(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId)
            .eq(Episode::getStatus, status)));
    }

    default void deleteByProjectId(String projectId) {
        delete(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId));
    }
}