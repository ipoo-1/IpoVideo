package com.ipovideo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ipovideo.entity.User;

/**
 * 继承 BaseMapper 后，insert / selectById / selectOne 等常用 SQL 方法自动可用。
 */
public interface UserMapper extends BaseMapper<User> {
}
