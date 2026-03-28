package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    default Episode findByProjectIdAndId(String projectId, Long episodeId) {
        return selectOne(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId)
            .eq(Episode::getId, episodeId));
    }

    default IPage<Episode> findPageByProjectId(String projectId, String name, IPage<Episode> page) {
        LambdaQueryWrapper<Episode> wrapper = new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId);
        if (name != null && !name.isEmpty()) {
            wrapper.apply("JSON_EXTRACT(episode_info, '$.title') LIKE {0}", "%" + name + "%");
        }
        wrapper.orderByAsc(Episode::getId);
        return selectPage(page, wrapper);
    }
}