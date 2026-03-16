package com.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.FileInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileMapper extends BaseMapper<FileInfo> {
}
