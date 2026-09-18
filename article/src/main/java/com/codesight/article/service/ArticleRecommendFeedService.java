package com.codesight.article.service;

import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.config.FeedProperties;
import com.codesight.article.constant.FeedRedisKeys;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐信息流业务服务（全站推荐候选池、受控补拉、已读曝光过滤与 MySQL 兜底）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleRecommendFeedService {

    private final ArticleMapper articleMapper;
    private final RecommendRankService recommendRankService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ArticleRecommendVectorService articleRecommendVectorService;
    private final FeedProperties feedProperties;
    private final ArticleFeedHydrator articleFeedHydrator;

    /**
     * 获取综合推荐信息流（全站推荐候选池 + MySQL 频道/标签多维推荐）
     *
     * @param request       分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 分页信息流响应体
     */
    public ArticleFeedPageResponse getRecommendedFeed(ArticleFeedRequest request, Long currentUserId) {
        int limitSize = request.size() + 1;
        boolean isDefaultRecommended = request.authorId() == null && request.tagId() == null && request.categoryId() == null;

        // 1. 全站推荐主流（从Redis推荐池中获取）
        if (isDefaultRecommended) {
            FeedCursorUtils.FeedCursor cursor = FeedCursorUtils.parseCursor(request.cursor(), FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED);
            Double curRankScore = cursor != null ? (double) cursor.value() : null;
            Long curArticleId = cursor != null ? cursor.articleId() : null;

            List<Long> candidateIds = new ArrayList<>();
            Map<Long, Double> scoreMap = new HashMap<>();
            int maxRounds = (currentUserId != null) ? 3 : 1;
            boolean poolExhausted = false;

            while (maxRounds-- > 0 && candidateIds.size() < request.size()) {
                int need = limitSize - candidateIds.size();
                int fetchLimit = currentUserId != null ? Math.max(need * 2, 25) : need;
                List<TypedTuple<String>> tuples = recommendRankService.getRankedArticleIds(curRankScore, curArticleId, fetchLimit);
                if (tuples.isEmpty()) {
                    poolExhausted = true;
                    break;
                }

                List<Long> batchIds = new ArrayList<>(tuples.size());
                for (TypedTuple<String> t : tuples) {
                    if (t.getValue() != null) {
                        Long id = Long.parseLong(t.getValue());
                        batchIds.add(id);
                        scoreMap.put(id, t.getScore() != null ? t.getScore() : 0.0);
                    }
                }

                // 推进游标至当前批次末尾元素
                TypedTuple<String> lastTuple = tuples.getLast();
                curRankScore = lastTuple.getScore();
                curArticleId = lastTuple.getValue() != null ? Long.parseLong(lastTuple.getValue()) : null;

                // 过滤已读曝光文章
                List<Long> unexposed = filterUnexposedIds(currentUserId, batchIds);
                candidateIds.addAll(unexposed);

                if (tuples.size() < fetchLimit) {
                    poolExhausted = true;
                    break;
                }
            }

            if (!candidateIds.isEmpty()) {
                List<Article> dbArticles = articleMapper.selectByIds(candidateIds);
                if (dbArticles != null && !dbArticles.isEmpty()) {
                    Map<Long, Article> articleMap = dbArticles.stream()
                            .filter(a -> a != null && a.getStatus() == ArticleStatus.PUBLISHED && a.getVisible() == ArticleVisible.PUBLIC)
                            .collect(Collectors.toMap(Article::getId, Function.identity(), (o1, o2) -> o1));

                    List<Article> sorted = new ArrayList<>(candidateIds.size());
                    for (Long id : candidateIds) {
                        Article a = articleMap.get(id);
                        if (a != null) {
                            a.setRankScore(scoreMap.getOrDefault(id, 0.0));
                            sorted.add(a);
                        }
                    }

                    // 双向向量感知推荐（负向语义剪枝 + 正向加权精排）
                    List<Article> reranked = articleRecommendVectorService.recommendAndRerank(currentUserId, sorted);
                    if (!reranked.isEmpty()) {
                        boolean hasMore = reranked.size() > request.size() || !poolExhausted;
                        List<Article> paged = reranked.size() > request.size() ? reranked.subList(0, request.size()) : reranked;

                        String nextCursor = null;
                        if (hasMore && !paged.isEmpty()) {
                            Article last = paged.getLast();
                            long scoreVal = last.getRankScore() != null ? last.getRankScore().longValue() : 0L;
                            nextCursor = FeedCursorUtils.buildCursor(FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED, scoreVal, last.getId());
                        }

                        recordExposed(currentUserId, paged.stream().map(Article::getId).toList());
                        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(paged, currentUserId);
                        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
                    }
                }
            }
        }

        // 2. MySQL 游标查询推荐流（适用频道/标签/作者过滤、全站推荐池见底后的推荐流）
        FeedCursorUtils.FeedCursor cursor = FeedCursorUtils.parseCursor(request.cursor(), FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED);
        Long cursorRankScore = cursor != null ? cursor.value() : null;
        Long cursorId = cursor != null ? cursor.articleId() : null;
        Instant earliestPublishTime = Instant.now().minus(30, ChronoUnit.DAYS);

        List<Article> rawList = articleMapper.selectFeedRecommended(
                request.categoryId(),
                request.tagId(),
                request.authorId(),
                earliestPublishTime,
                cursorRankScore,
                cursorId,
                limitSize
        );

        if (rawList == null || rawList.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        // 双向向量感知推荐（负向语义剪枝 + 正向加权精排）
        List<Article> reranked = articleRecommendVectorService.recommendAndRerank(currentUserId, rawList);

        if (reranked.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        boolean hasMore = reranked.size() > request.size();
        List<Article> articles = hasMore ? reranked.subList(0, request.size()) : reranked;

        String nextCursor = null;
        if (hasMore && !articles.isEmpty()) {
            Article last = articles.getLast();
            long rankScore = (last.getRankScore() != null) ? last.getRankScore().longValue() : 0L;
            nextCursor = FeedCursorUtils.buildCursor(FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED, rankScore, last.getId());
        }

        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(articles, currentUserId);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 文章发布：同步添加至推荐候选池
     */
    public void onArticlePublished(Long articleId) {
        recommendRankService.addOrIncrScore(articleId, 0.0);
    }

    /**
     * 文章删除或下架：从推荐候选池移除
     */
    public void onArticleRemoved(Long articleId) {
        recommendRankService.removeArticle(articleId);
    }

    /**
     * 过滤当前用户已曝光的文章 ID 列表
     */
    public List<Long> filterUnexposedIds(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return articleIds != null ? articleIds : Collections.emptyList();
        }
        try {
            String exposedKey = FeedRedisKeys.getExposedKey(userId);
            List<Object> results = stringRedisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(@NonNull RedisOperations operations) {
                    for (Long id : articleIds) {
                        operations.opsForZSet().score(exposedKey, String.valueOf(id));
                    }
                    return null;
                }
            });

            if (results.isEmpty()) {
                return articleIds;
            }

            List<Long> unexposed = new ArrayList<>(articleIds.size());
            for (int i = 0; i < articleIds.size(); i++) {
                if (i >= results.size() || results.get(i) == null) {
                    unexposed.add(articleIds.get(i));
                }
            }
            return unexposed;
        } catch (Exception e) {
            log.warn("查询用户推荐曝光缓存失败, userId={}, error={}", userId, e.getMessage());
            return articleIds;
        }
    }

    /**
     * 批量记录用户已曝光文章并维护容量上限
     */
    public void recordExposed(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return;
        }
        try {
            String exposedKey = FeedRedisKeys.getExposedKey(userId);
            long now = System.currentTimeMillis();
            stringRedisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(@NonNull RedisOperations operations) {
                    for (Long id : articleIds) {
                        operations.opsForZSet().add(exposedKey, String.valueOf(id), (double) now);
                    }
                    operations.expire(exposedKey, feedProperties.getExposedTtl());
                    operations.opsForZSet().removeRange(exposedKey, 0, -(feedProperties.getExposedMaxCapacity() + 1));
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("记录用户推荐曝光失败, userId={}, error={}", userId, e.getMessage());
        }
    }
}
