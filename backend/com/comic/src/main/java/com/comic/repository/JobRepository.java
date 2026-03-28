package com.comic.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Job;
import org.apache.ibatis.annotations.Mapper;

import java.util.Arrays;
import java.util.List;

@Mapper
public interface JobRepository extends BaseMapper<Job> {

    default List<Job> findPendingRunningJobs(String jobType) {
        return selectList(new LambdaQueryWrapper<Job>()
                .eq(Job::getJobType, jobType)
                .in(Job::getStatus, Arrays.asList("PENDING", "RUNNING")));
    }
}
