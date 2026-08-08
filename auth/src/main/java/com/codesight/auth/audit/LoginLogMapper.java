package com.codesight.auth.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.auth.audit.model.LoginLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}
