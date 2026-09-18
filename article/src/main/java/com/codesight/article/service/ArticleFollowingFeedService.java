package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.config.FeedProperties;
import com.codesight.article.constant.FeedRedisKeys;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.service.RelationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 社交关注流业务服务（推拉结合模型、大 V 状态机、收发件箱管理与回填清理）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleFollowingFeedService {

    private final ArticleMapper articleMapper;
    private final UserFollowerMapper userFollowerMapper;
    private final RelationCacheService relationCacheService;
    private final CounterService counterService;
    private final StringRedisTemplate stringRedisTemplate;
    private final FeedProperties feedProperties;
    private final ArticleFeedHydrator articleFeedHydrator;

    /**
     * 获取社交关注流（推拉结合）
     *
     * @param request       分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 关注流分页响应体
     */
    public ArticleFeedPageResponse getFollowingFeed(ArticleFeedRequest request, Long currentUserId) {
        if (currentUserId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录后查看关注动态");
        }
        String cursorStr = request.cursor();
        Integer pageSize = request.size();

        // 1. 获取当前用户关注的所有博主集合
        Set<Long> followingIds = relationCacheService.getFollowingUserIds(currentUserId);
        if (followingIds == null || followingIds.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        // 2. 批量探测关注博主的粉丝数，识别出大 V
        List<String> authorKeys = followingIds.stream().map(String::valueOf).toList();
        Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = counterService.batchGetCounts(CounterSchema.EntityType.USER, authorKeys);

        List<Long> bigVAuthorIds = new ArrayList<>();
        for (Long authorId : followingIds) {
            Map<CounterSchema.MetricItem, Long> authorCounts = countsMap.get(String.valueOf(authorId));
            long followers = authorCounts != null
                    ? authorCounts.getOrDefault(CounterSchema.UserMetric.FOLLOWERS, 0L)
                    : 0L;
            if (isBigV(authorId, followers)) {
                bigVAuthorIds.add(authorId);
            }
        }

        // 3. 计算查询最大分数窗口
        double maxScore = Double.POSITIVE_INFINITY;
        if (cursorStr != null && !cursorStr.isBlank()) {
            try {
                long cursorTimeMs = Long.parseLong(cursorStr.trim());
                maxScore = cursorTimeMs - 1;
            } catch (NumberFormatException ignored) {
                // 忽略非数字格式，走最新窗口
            }
        }

        final double queryMaxScore = maxScore;
        final int fetchLimit = pageSize + 1;

        // 4. 利用 Pipeline 一次性批量拉取个人收件箱与所有大 V 发件箱候选集
        List<Object> pipelineResults = stringRedisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(@NonNull RedisOperations operations) throws DataAccessException {
                // 个人收件箱（推）
                operations.opsForZSet().reverseRangeByScoreWithScores(
                        FeedRedisKeys.getInboxKey(currentUserId), 0, queryMaxScore, 0, fetchLimit);

                // 大 V 发件箱（拉）
                for (Long bigVId : bigVAuthorIds) {
                    operations.opsForZSet().reverseRangeByScoreWithScores(
                            FeedRedisKeys.getOutboxKey(bigVId), 0, queryMaxScore, 0, fetchLimit);
                }
                return null;
            }
        });

        // 5. 聚合所有流的候选元素
        record FeedCandidate(long articleId, long publishTimeMs) {}
        List<FeedCandidate> candidates = new ArrayList<>();

        for (Object result : pipelineResults) {
            if (result instanceof Set<?> tupleSet) {
                for (Object item : tupleSet) {
                    if (item instanceof TypedTuple<?> tuple && tuple.getValue() != null && tuple.getScore() != null) {
                        try {
                            long aId = Long.parseLong((String) tuple.getValue());
                            long pTime = tuple.getScore().longValue();
                            candidates.add(new FeedCandidate(aId, pTime));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        // 6. 去重与时间倒序归并
        Set<Long> seenArticleIds = new HashSet<>();
        List<FeedCandidate> sortedCandidates = candidates.stream()
                .filter(c -> seenArticleIds.add(c.articleId()))
                .sorted((a, b) -> {
                    int cmp = Long.compare(b.publishTimeMs(), a.publishTimeMs());
                    return cmp != 0 ? cmp : Long.compare(b.articleId(), a.articleId());
                })
                .toList();

        boolean hasMore = sortedCandidates.size() > pageSize;
        List<FeedCandidate> paged = hasMore ? sortedCandidates.subList(0, pageSize) : sortedCandidates;

        String nextCursor = null;
        if (hasMore && !paged.isEmpty()) {
            nextCursor = String.valueOf(paged.getLast().publishTimeMs());
        }

        List<Long> targetArticleIds = paged.stream().map(FeedCandidate::articleId).toList();

        // 7. 批量拉取有效文章并保持排序
        List<Article> rawArticles = articleMapper.selectByIds(targetArticleIds);
        if (rawArticles == null || rawArticles.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), nextCursor, hasMore);
        }

        Map<Long, Article> articleMap = rawArticles.stream()
                .filter(a -> a.getStatus() == ArticleStatus.PUBLISHED && a.getVisible() == ArticleVisible.PUBLIC)
                .collect(Collectors.toMap(Article::getId, a -> a));

        List<Article> orderedArticles = new ArrayList<>();
        for (Long id : targetArticleIds) {
            Article a = articleMap.get(id);
            if (a != null) {
                orderedArticles.add(a);
            }
        }

        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(orderedArticles, currentUserId);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 文章公开发布：写入发件箱，若为普通博主则写扩散推入粉丝收件箱
     */
    public void onArticlePublished(Article article) {
        Long authorId = article.getAuthorId();
        Long articleId = article.getId();
        long publishTimeMs = article.getPublishTime().toEpochMilli();

        // 1. 写入发件箱并维护容量上限
        String outboxKey = FeedRedisKeys.getOutboxKey(authorId);
        stringRedisTemplate.opsForZSet().add(outboxKey, String.valueOf(articleId), publishTimeMs);
        stringRedisTemplate.opsForZSet().removeRange(outboxKey, 0, -(feedProperties.getOutboxMaxCapacity() + 1));

        // 2. 判定博主粉丝数
        Map<CounterSchema.MetricItem, Long> userCounts = counterService.getCounts(
                CounterSchema.EntityType.USER,
                String.valueOf(authorId)
        );
        long followerCount = userCounts != null
                ? userCounts.getOrDefault(CounterSchema.UserMetric.FOLLOWERS, 0L)
                : 0L;

        // 3. 若为大V，不写扩散，直接返回
        if (isBigV(authorId, followerCount)) {
            return;
        }

        // 4. 普通博主：查询有效粉丝列表并执行写扩散
        List<UserFollower> followerList = userFollowerMapper.selectList(
                new LambdaQueryWrapper<UserFollower>()
                        .select(UserFollower::getFromUserId)
                        .eq(UserFollower::getToUserId, authorId)
        );

        if (followerList == null || followerList.isEmpty()) {
            return;
        }

        List<Long> fanIds = followerList.stream()
                .map(UserFollower::getFromUserId)
                .filter(Objects::nonNull)
                .toList();

        // 利用 Redis Pipeline 批量推入粉丝收件箱并维护容量上限
        stringRedisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(@NonNull RedisOperations operations) throws DataAccessException {
                for (Long fanId : fanIds) {
                    String inboxKey = FeedRedisKeys.getInboxKey(fanId);
                    operations.opsForZSet().add(inboxKey, String.valueOf(articleId), publishTimeMs);
                    operations.opsForZSet().removeRange(inboxKey, 0, -(feedProperties.getInboxMaxCapacity() + 1));
                }
                return null;
            }
        });
    }

    /**
     * 文章删除/下架：从作者发件箱移除
     */
    public void onArticleRemoved(Article article) {
        String outboxKey = FeedRedisKeys.getOutboxKey(article.getAuthorId());
        stringRedisTemplate.opsForZSet().remove(outboxKey, String.valueOf(article.getId()));
    }

    /**
     * 判定创作者是否为明星大 V
     */
    public boolean isBigV(Long authorId, long followerCount) {
        if (followerCount >= feedProperties.getBigVPromotionThreshold()) {
            stringRedisTemplate.opsForSet().add(FeedRedisKeys.BIG_V_SET_KEY, String.valueOf(authorId));
            return true;
        }
        if (followerCount < feedProperties.getBigVDemotionThreshold()) {
            Long removed = stringRedisTemplate.opsForSet().remove(FeedRedisKeys.BIG_V_SET_KEY, String.valueOf(authorId));
            if (removed != null && removed > 0) {
                // 异步触发发件箱历史文章回填粉丝收件箱
                triggerDemotionBackfill(authorId);
            }
            return false;
        }
        // 落在 [4500, 5500) 缓冲区，维持作者既有角色
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(
                FeedRedisKeys.BIG_V_SET_KEY, String.valueOf(authorId));
        return Boolean.TRUE.equals(isMember);
    }

    /**
     * 触发大 V 降级异步补偿
     */
    public void triggerDemotionBackfill(Long authorId) {
        if (authorId == null) {
            return;
        }
        Thread.ofVirtual().name("demotion-backfill-" + authorId).start(() -> backfillOnDemotion(authorId));
    }

    /**
     * 大 V 降级补偿核心逻辑
     */
    public void backfillOnDemotion(Long authorId) {
        if (authorId == null) {
            return;
        }

        // 1. 从发件箱取出该作者近期的有效文章
        Set<TypedTuple<String>> outboxTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(FeedRedisKeys.getOutboxKey(authorId), 0, 19);
        if (outboxTuples == null || outboxTuples.isEmpty()) {
            return;
        }

        // 2. 查出该作者现存的所有有效粉丝
        List<UserFollower> followers = userFollowerMapper.selectList(
                new LambdaQueryWrapper<UserFollower>()
                        .select(UserFollower::getFromUserId)
                        .eq(UserFollower::getToUserId, authorId)
        );
        if (followers == null || followers.isEmpty()) {
            return;
        }

        List<Long> fanIds = followers.stream()
                .map(UserFollower::getFromUserId)
                .filter(Objects::nonNull)
                .toList();
        if (fanIds.isEmpty()) {
            return;
        }

        // 3. 利用 Pipeline 批量补偿推入粉丝的收件箱并维护容量上限截断
        stringRedisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(@NonNull RedisOperations operations) throws DataAccessException {
                for (Long fanId : fanIds) {
                    String inboxKey = FeedRedisKeys.getInboxKey(fanId);
                    for (TypedTuple<String> tuple : outboxTuples) {
                        if (tuple.getValue() != null && tuple.getScore() != null) {
                            operations.opsForZSet().add(inboxKey, tuple.getValue(), tuple.getScore());
                        }
                    }
                    operations.opsForZSet().removeRange(inboxKey, 0, -(feedProperties.getInboxMaxCapacity() + 1));
                }
                return null;
            }
        });
    }

    /**
     * 关注回填
     */
    public void backfillOnFollow(Long followerId, Long authorId) {
        Map<CounterSchema.MetricItem, Long> userCounts = counterService.getCounts(
                CounterSchema.EntityType.USER,
                String.valueOf(authorId)
        );
        long followerCount = userCounts != null
                ? userCounts.getOrDefault(CounterSchema.UserMetric.FOLLOWERS, 0L)
                : 0L;
        if (isBigV(authorId, followerCount)) {
            return;
        }

        // 取出最近 20 篇历史文章
        String outboxKey = FeedRedisKeys.getOutboxKey(authorId);
        Set<TypedTuple<String>> outboxTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(outboxKey, 0, 19);

        // 若 Redis 发件箱为空，从数据库加载
        List<Article> fallbackArticles = Collections.emptyList();
        if (outboxTuples == null || outboxTuples.isEmpty()) {
            fallbackArticles = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>()
                            .eq(Article::getAuthorId, authorId)
                            .eq(Article::getStatus, ArticleStatus.PUBLISHED)
                            .eq(Article::getVisible, ArticleVisible.PUBLIC)
                            .orderByDesc(Article::getPublishTime)
                            .last("LIMIT 20")
            );
            if (fallbackArticles == null || fallbackArticles.isEmpty()) {
                return;
            }
        }

        final Set<TypedTuple<String>> tuplesToBackfill = outboxTuples;
        final List<Article> articlesToBackfill = fallbackArticles;

        stringRedisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(@NonNull RedisOperations operations) throws DataAccessException {
                String inboxKey = FeedRedisKeys.getInboxKey(followerId);
                if (tuplesToBackfill != null && !tuplesToBackfill.isEmpty()) {
                    for (TypedTuple<String> tuple : tuplesToBackfill) {
                        if (tuple.getValue() != null && tuple.getScore() != null) {
                            operations.opsForZSet().add(inboxKey, tuple.getValue(), tuple.getScore());
                        }
                    }
                } else {
                    for (Article a : articlesToBackfill) {
                        if (a.getId() != null && a.getPublishTime() != null) {
                            operations.opsForZSet().add(inboxKey, String.valueOf(a.getId()), a.getPublishTime().toEpochMilli());
                        }
                    }
                }
                operations.opsForZSet().removeRange(inboxKey, 0, -(feedProperties.getInboxMaxCapacity() + 1));
                return null;
            }
        });
    }

    /**
     * 取关清理
     */
    public void cleanupOnUnfollow(Long followerId, Long authorId) {
        // 1. 获取该博主的所有有效文章 ID（优先从发件箱获取，发件箱为空查库兜底）
        String outboxKey = FeedRedisKeys.getOutboxKey(authorId);
        Set<String> articleIds = stringRedisTemplate.opsForZSet().range(outboxKey, 0, -1);

        if (articleIds == null || articleIds.isEmpty()) {
            List<Article> articles = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>()
                            .select(Article::getId)
                            .eq(Article::getAuthorId, authorId)
            );
            if (articles != null && !articles.isEmpty()) {
                articleIds = articles.stream()
                        .map(a -> String.valueOf(a.getId()))
                        .collect(Collectors.toSet());
            }
        }

        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }

        // 2. 从粉丝收件箱中批量移除该博主的所有文章
        String inboxKey = FeedRedisKeys.getInboxKey(followerId);
        stringRedisTemplate.opsForZSet().remove(inboxKey, articleIds.toArray(new Object[0]));
    }
}
