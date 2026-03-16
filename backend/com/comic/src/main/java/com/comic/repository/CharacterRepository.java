package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Character;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CharacterRepository extends BaseMapper<Character> {

    // 新增方法（用于角色提取和项目管理）
    default List<Character> findByProjectId(String projectId) {
        return selectList(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId));
    }

    default Character findByProjectIdAndCharId(String projectId, String charId) {
        return selectOne(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId)
            .eq(Character::getCharId, charId));
    }

    default Character findByCharId(String charId) {
        return selectOne(new LambdaQueryWrapper<Character>()
            .eq(Character::getCharId, charId));
    }

    default List<Character> findConfirmedByProjectId(String projectId) {
        return selectList(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId)
            .eq(Character::getConfirmed, true));
    }
}
