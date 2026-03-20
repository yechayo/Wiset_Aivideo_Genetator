package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.EpisodeProduction;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

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

    /**
     * 从等待融合状态原子切换到提示词构建，防止重复恢复流程
     */
    default boolean tryMarkFusionResumed(Long episodeId) {
        int affected = update(new LambdaUpdateWrapper<EpisodeProduction>()
                .eq(EpisodeProduction::getEpisodeId, episodeId)
                .eq(EpisodeProduction::getStatus, "GRID_FUSION_PENDING")
                .set(EpisodeProduction::getStatus, "BUILDING_PROMPTS")
                .set(EpisodeProduction::getCurrentStage, "PROMPT_BUILDING")
                .set(EpisodeProduction::getProgressPercent, 20)
                .set(EpisodeProduction::getProgressMessage, "正在构建视频提示词...")
                .set(EpisodeProduction::getUpdatedAt, LocalDateTime.now()));
        return affected > 0;
    }
}
