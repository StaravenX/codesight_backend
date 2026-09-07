package com.codesight.relation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.relation.model.UserFollowing;
import org.apache.ibatis.annotations.Mapper;

/**
 * 关注关系持久层 Mapper
 */
@Mapper
public interface UserFollowingMapper extends BaseMapper<UserFollowing> {
}
