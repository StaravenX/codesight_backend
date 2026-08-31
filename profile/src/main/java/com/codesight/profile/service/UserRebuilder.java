package com.codesight.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.counter.schema.CounterRebuilder;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户维度重建
 */
@Service
@RequiredArgsConstructor
public class UserRebuilder implements CounterRebuilder {

    private final CounterService counterService;
    private final ArticleMapper articleMapper;

    @Override
    public CounterSchema.EntityType entityType() {
        return CounterSchema.EntityType.USER;
    }

    @Override
    public Map<CounterSchema.MetricItem, Long> rebuild(String authorId) {
        Map<CounterSchema.MetricItem, Long> resultMap = new HashMap<>();
        Long authorIdVal = Long.parseLong(authorId);

        // 1. 粉丝数真值：从 4KB 分片位图统计 TODO: 用户关系模块完成后补充 MySQL兜底
        long followersCount = counterService.bitCountShards(this.entityType(), authorId, CounterSchema.UserMetric.FOLLOWERS);
        if (followersCount < 0) {
            followersCount = 0L;
        }

        // 2. 关注数真值：从 4KB 分片位图统计 TODO: 用户关系模块完成后补充 MySQL兜底
        long followingsCount = counterService.bitCountShards(this.entityType(), authorId, CounterSchema.UserMetric.FOLLOWINGS);
        if (followingsCount < 0) {
            followingsCount = 0L;
        }

        // 3. 总阅读量 + 获得总点赞量：从 articles 表汇总
        List<Article> articles = articleMapper.selectList(
                new LambdaQueryWrapper<Article>()
                        .select(Article::getViewCount, Article::getLikeCount)
                        .eq(Article::getAuthorId, authorIdVal)
                        .ne(Article::getStatus, ArticleStatus.DELETED)
        );

        long totalViews = 0L;
        long totalLikes = 0L;
        if (articles != null) {
            for (Article article : articles) {
                totalViews += (article.getViewCount() != null ? article.getViewCount() : 0L);
                totalLikes += (article.getLikeCount() != null ? article.getLikeCount() : 0L);
            }
        }

        resultMap.put(CounterSchema.UserMetric.VIEWS_RECEIVED, totalViews);
        resultMap.put(CounterSchema.UserMetric.LIKES_RECEIVED, totalLikes);
        resultMap.put(CounterSchema.UserMetric.FOLLOWERS, followersCount);
        resultMap.put(CounterSchema.UserMetric.FOLLOWINGS, followingsCount);

        return resultMap;
    }
}
