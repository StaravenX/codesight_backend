package com.codesight.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.article.model.entity.Tag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二级技术标签数据持久层接口
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {
}
