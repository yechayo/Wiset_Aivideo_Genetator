package com.comic.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comic.entity.User;
import org.apache.ibatis.annotations.*;

/**
 * 用户数据访问层
 * 继承 BaseMapper 自动获得：insert / deleteById / updateById / selectById 等通用方法
 * 业务专用查询在下面单独定义
 */
@Mapper
public interface UserRepository extends BaseMapper<User> {

    @Select("SELECT * FROM user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    User findByUsername(String username);

    @Select("SELECT COUNT(*) FROM user WHERE username = #{username} AND deleted = 0")
    int countByUsername(String username);
}
