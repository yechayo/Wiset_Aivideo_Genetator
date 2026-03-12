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

    @Select("SELECT * FROM episode WHERE series_id = #{seriesId} " +
            "AND episode_num BETWEEN #{startEp} AND #{endEp} ORDER BY episode_num")
    List<Episode> findRecentEpisodes(
        @Param("seriesId") String seriesId,
        @Param("startEp") int startEp,
        @Param("endEp") int endEp
    );

    default List<Episode> findBySeriesId(String seriesId) {
        return selectList(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getSeriesId, seriesId)
            .orderByAsc(Episode::getEpisodeNum));
    }

    default int countBufferedEpisodes(String seriesId) {
        return Math.toIntExact(selectCount(new LambdaQueryWrapper<Episode>()
            .eq(Episode::getSeriesId, seriesId)
            .eq(Episode::getStatus, "DONE")));
    }
}
