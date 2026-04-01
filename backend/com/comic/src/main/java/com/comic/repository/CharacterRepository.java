package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comic.entity.Character;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    default Character findByNameAndProjectId(String projectId, String name) {
        return selectOne(new LambdaQueryWrapper<Character>()
            .eq(Character::getProjectId, projectId)
            .apply("JSON_EXTRACT(character_info, '$.name') = {0}", name));
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

    /**
     * 手动更新 characterInfo JSON 字段（绕过 MyBatis-Plus updateById 不应用 JacksonTypeHandler 的问题）
     */
    @Update("UPDATE `character` SET character_info = #{characterInfoJson} WHERE id = #{id} AND deleted = 0")
    void updateCharacterInfoById(@Param("id") Long id, @Param("characterInfoJson") String characterInfoJson);

    /**
     * 序列化 characterInfo 并通过 @Update 注解持久化到数据库
     */
    default void updateCharacterInfo(Character character) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(character.getCharacterInfo());
            updateCharacterInfoById(character.getId(), json);
        } catch (Exception e) {
            throw new RuntimeException("更新角色信息失败", e);
        }
    }
}
