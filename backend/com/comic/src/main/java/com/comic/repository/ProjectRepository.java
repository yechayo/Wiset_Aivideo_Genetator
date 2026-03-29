package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectRepository extends BaseMapper<Project> {

    /**
     * 获取原始的 project_info JSON 字符串（用于调试）
     */
    @Select("SELECT project_info FROM project WHERE project_id = #{projectId} AND deleted = 0")
    String getRawProjectInfo(String projectId);

    default Project findByProjectId(String projectId) {
        return selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getProjectId, projectId)
            .eq(Project::getDeleted, false));
    }

    default Project findByUserIdAndStatus(String userId, String status) {
        return selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getStatus, status)
            .eq(Project::getDeleted, false)
            .orderByDesc(Project::getCreatedAt)
            .last("LIMIT 1"));
    }

    /**
     * 获取用户的所有项目列表
     */
    default List<Project> findAllByUserId(String userId) {
        return selectList(new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getDeleted, false)
            .orderByDesc(Project::getCreatedAt));
    }

    /**
     * 分页查询项目列表
     */
    default IPage<Project> findPage(String userId, String status, String sortBy, String sortOrder,
                                      int page, int size) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
            .eq(Project::getUserId, userId)
            .eq(Project::getDeleted, false);

        if (status != null && !status.isEmpty()) {
            wrapper.eq(Project::getStatus, status);
        }

        if ("updatedAt".equals(sortBy)) {
            wrapper.orderBy(true, "asc".equals(sortOrder), Project::getUpdatedAt);
        } else {
            wrapper.orderBy(true, "asc".equals(sortOrder), Project::getCreatedAt);
        }

        return selectPage(new Page<>(page, size), wrapper);
    }
}