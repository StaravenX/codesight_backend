package com.codesight.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.article.model.entity.CategoryTagRel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类与标签多对多关联数据持久层接口
 */
@Mapper
public interface CategoryTagRelMapper extends BaseMapper<CategoryTagRel> {
}
