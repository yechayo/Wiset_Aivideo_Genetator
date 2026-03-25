package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Character;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CharacterRepository extends BaseMapper<Character> {

    default List<Character> findByProjectId(String projectId) {
        return selectList(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId));
    }

    default List<Character> findByProjectIdAndStatus(String projectId, String status) {
        return selectList(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId)
            .eq(Character::getStatus, status));
    }

    default Character findByCharId(String charId) {
        return selectOne(new LambdaQueryWrapper<Character>()
            .apply("JSON_EXTRACT(character_info, '$.charId') = {0}", charId));
    }

    default void deleteByProjectId(String projectId) {
        delete(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId));
    }
}