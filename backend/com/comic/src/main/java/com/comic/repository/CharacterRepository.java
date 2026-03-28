package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.entity.Character;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CharacterRepository extends BaseMapper<Character> {

    default List<Character> findByProjectId(String projectId) {
        return selectList(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId));
    }

    default Character findByCharId(String charId) {
        return selectOne(new LambdaQueryWrapper<Character>()
            .apply("JSON_EXTRACT(character_info, '$.charId') = {0}", charId));
    }

    default void deleteByProjectId(String projectId) {
        delete(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId));
    }

    default IPage<Character> findPageByProjectId(String projectId, String role, String name, IPage<Character> page) {
        LambdaQueryWrapper<Character> wrapper = new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId);
        if (role != null && !role.isEmpty()) {
            wrapper.apply("JSON_EXTRACT(character_info, '$.role') = {0}", role);
        }
        if (name != null && !name.isEmpty()) {
            wrapper.apply("JSON_EXTRACT(character_info, '$.name') LIKE {0}", "%" + name + "%");
        }
        return selectPage(page, wrapper);
    }
}