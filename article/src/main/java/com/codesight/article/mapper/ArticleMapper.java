package com.codesight.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codesight.article.model.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 文章数据持久层接口
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 最新信息流
     */
    List<Article> selectFeedNewest(
            @Param("categoryId") Long categoryId,
            @Param("tagId") Long tagId,
            @Param("authorId") Long authorId,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") Long cursorId,
            @Param("limitSize") int limitSize
    );

    /**
     * 推荐信息流
     */
    List<Article> selectFeedRecommended(
            @Param("categoryId") Long categoryId,
            @Param("tagId") Long tagId,
            @Param("authorId") Long authorId,
            @Param("earliestPublishTime") Instant earliestPublishTime,
            @Param("cursorRankScore") Long cursorRankScore,
            @Param("cursorId") Long cursorId,
            @Param("limitSize") int limitSize
    );

    /**
     * 详情页底部相关推荐
     */
    List<Article> selectRelatedArticles(
            @Param("excludeArticleId") Long excludeArticleId,
            @Param("categoryId") Long categoryId,
            @Param("tagIds") List<Long> tagIds,
            @Param("limitSize") int limitSize
    );
}


