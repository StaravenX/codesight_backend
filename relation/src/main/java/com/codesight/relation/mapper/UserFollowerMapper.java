package com.codesight.relation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.relation.model.UserFollower;
import org.apache.ibatis.annotations.Mapper;

/**
 * 粉丝关系持久层 Mapper
 */
@Mapper
public interface UserFollowerMapper extends BaseMapper<UserFollower> {
}
