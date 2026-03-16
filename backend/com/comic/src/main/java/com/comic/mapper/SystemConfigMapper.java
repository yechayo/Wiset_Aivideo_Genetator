package com.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {

    /**
     * 根据配置键查询配置
     *
     * @param configKey 配置键
     * @return 配置对象
     */
    @Select("SELECT * FROM t_system_config WHERE config_key = #{configKey} LIMIT 1")
    SystemConfig selectByKey(@Param("configKey") String configKey);
}
