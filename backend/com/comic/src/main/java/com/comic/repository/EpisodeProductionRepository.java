package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.EpisodeProduction;
import org.apache.ibatis.annotations.Mapper;

/**
 * 单集生产状态Repository
 */
@Mapper
public interface EpisodeProductionRepository extends BaseMapper<EpisodeProduction> {

    /**
     * 根据剧集ID查找生产记录
     */
    default EpisodeProduction findByEpisodeId(Long episodeId) {
        return selectOne(new LambdaQueryWrapper<EpisodeProduction>()
                .eq(EpisodeProduction::getEpisodeId, episodeId));
    }

    /**
     * 根据生产ID查找记录
     */
    default EpisodeProduction findByProductionId(String productionId) {
        return selectOne(new LambdaQueryWrapper<EpisodeProduction>()
                .eq(EpisodeProduction::getProductionId, productionId));
    }
}
