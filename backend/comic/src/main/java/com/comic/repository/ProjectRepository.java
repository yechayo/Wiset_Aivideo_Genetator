package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectRepository extends BaseMapper<Project> {

    default Project findByProjectId(String projectId) {
        return selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getProjectId, projectId));
    }

    default Project findByUserIdAndStatus(String userId, String status) {
        return selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getStatus, status)
            .orderByDesc(Project::getCreatedAt)
            .last("LIMIT 1"));
    }

    /**
     * 获取用户的所有项目列表
     */
    default java.util.List<Project> findAllByUserId(String userId) {
        return selectList(new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .orderByDesc(Project::getCreatedAt));
    }
}
