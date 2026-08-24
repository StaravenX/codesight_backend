package com.codesight.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.article.model.entity.Article;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章数据持久层接口
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
