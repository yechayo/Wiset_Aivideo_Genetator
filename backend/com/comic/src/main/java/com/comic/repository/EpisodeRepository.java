package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Episode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EpisodeRepository extends BaseMapper<Episode> {

    @Select("SELECT * FROM episode WHERE project_id = #{projectId} " +
            "AND episode_num BETWEEN #{startEp} AND #{endEp} ORDER BY episode_num")
    List<Episode> findRecentEpisodes(
        @Param("projectId") String projectId,
        @Param("startEp") int startEp,
        @Param("endEp") int endEp
    );

    default List<Episode> findByProjectId(String projectId) {
        return selectList(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId)
            .orderByAsc(Episode::getEpisodeNum));
    }

    default int countBufferedEpisodes(String projectId) {
        return Math.toIntExact(selectCount(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId)
            .eq(Episode::getStatus, "DONE")));
    }

    default void deleteByProjectId(String projectId) {
        delete(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getProjectId, projectId));
    }
}
