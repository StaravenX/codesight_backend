package com.codesight.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.counter.schema.CounterRebuilder;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户维度重建
 */
@Service
public class UserRebuilder implements CounterRebuilder {

    private final CounterService counterService;
    private final ArticleMapper articleMapper;
    private final UserFollowerMapper userFollowerMapper;
    private final UserFollowingMapper userFollowingMapper;

    public UserRebuilder(@Lazy CounterService counterService,
                         ArticleMapper articleMapper,
                         UserFollowerMapper userFollowerMapper,
                         UserFollowingMapper userFollowingMapper) {
        this.counterService = counterService;
        this.articleMapper = articleMapper;
        this.userFollowerMapper = userFollowerMapper;
        this.userFollowingMapper = userFollowingMapper;
    }

    @Override
    public CounterSchema.EntityType entityType() {
        return CounterSchema.EntityType.USER;
    }

    @Override
    public Map<CounterSchema.MetricItem, Long> rebuild(String authorId) {
        Map<CounterSchema.MetricItem, Long> resultMap = new HashMap<>();
        Long authorIdVal = Long.parseLong(authorId);

        // 1. 粉丝数真值：优先从 4KB 分片位图统计，位图缺失时走 MySQL user_follower 兜底
        long followersCount = counterService.bitCountShards(this.entityType(), authorId, CounterSchema.UserMetric.FOLLOWERS);
        if (followersCount < 0) {
            Long count = userFollowerMapper.selectCount(
                    new LambdaQueryWrapper<UserFollower>()
                            .eq(UserFollower::getToUserId, authorIdVal)
            );
            followersCount = (count != null ? count : 0L);
        }

        // 2. 关注数真值：优先从 4KB 分片位图统计，位图缺失时走 MySQL user_following 兜底
        long followingsCount = counterService.bitCountShards(this.entityType(), authorId, CounterSchema.UserMetric.FOLLOWINGS);
        if (followingsCount < 0) {
            Long count = userFollowingMapper.selectCount(
                    new LambdaQueryWrapper<UserFollowing>()
                            .eq(UserFollowing::getFromUserId, authorIdVal)
            );
            followingsCount = (count != null ? count : 0L);
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
