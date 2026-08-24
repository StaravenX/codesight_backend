package com.codesight.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.article.model.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 一级技术分类数据持久层接口
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
