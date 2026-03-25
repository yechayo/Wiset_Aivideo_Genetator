package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Panel;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PanelRepository extends BaseMapper<Panel> {

    default List<Panel> findByEpisodeId(Long episodeId) {
        return selectList(new LambdaQueryWrapper<Panel>()
            .eq(Panel::getEpisodeId, episodeId)
            .orderByAsc(Panel::getId));
    }

    default Panel findByEpisodeIdAndId(Long episodeId, Long panelId) {
        return selectOne(new LambdaQueryWrapper<Panel>()
            .eq(Panel::getEpisodeId, episodeId)
            .eq(Panel::getId, panelId));
    }
}