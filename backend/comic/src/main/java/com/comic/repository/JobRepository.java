package com.comic.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.Job;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobRepository extends BaseMapper<Job> {
}
