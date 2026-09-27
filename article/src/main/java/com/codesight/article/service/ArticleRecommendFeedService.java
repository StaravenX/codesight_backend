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
import com.codesight.article.util.FeedCursorUtils;

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
        if (currentUserId != null && FeedCursorUtils.parseCursor(request.cursor(), FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED) != null) {
            return getRecommendedFeedFromMysql(request, currentUserId);
        }

        boolean isDefaultRecommended = request.authorId() == null && request.tagId() == null && request.categoryId() == null;

        // 1. 全站综合推荐流
        if (isDefaultRecommended && currentUserId != null) {
            String bufferKey = FeedRedisKeys.BUFFER_PREFIX + currentUserId;
            int pageSize = request.size();
            boolean isRefresh = request.cursor() == null || request.cursor().isBlank();
            int offset = isRefresh ? 0 : FeedCursorUtils.parseBufferCursor(request.cursor());
            if (isRefresh) {
                stringRedisTemplate.delete(bufferKey);
            }

            // 从用户专属 Buffer 中切片读取
            List<String> cachedIdStrings = stringRedisTemplate.opsForList().range(bufferKey, offset, offset + pageSize - 1);

            // Buffer 为空
            if (cachedIdStrings == null || cachedIdStrings.isEmpty()) {
                boolean refilled = refillUserBuffer(currentUserId);
                if (refilled) {
                    offset = 0;
                    cachedIdStrings = stringRedisTemplate.opsForList().range(bufferKey, offset, offset + pageSize - 1);
                }
            }

            // 若成功从 Buffer 获取到文章 ID
            if (cachedIdStrings != null && !cachedIdStrings.isEmpty()) {
                List<Long> articleIds = cachedIdStrings.stream().map(Long::parseLong).toList();
                List<Article> dbArticles = articleMapper.selectByIds(articleIds);

                if (dbArticles != null && !dbArticles.isEmpty()) {
                    Map<Long, Article> articleMap = dbArticles.stream()
                            .filter(a -> a != null && a.getStatus() == ArticleStatus.PUBLISHED && a.getVisible() == ArticleVisible.PUBLIC)
                            .collect(Collectors.toMap(Article::getId, Function.identity(), (o1, o2) -> o1));

                    List<Article> orderedArticles = new ArrayList<>();
                    for (Long id : articleIds) {
                        Article article = articleMap.get(id);
                        if (article != null) {
                            orderedArticles.add(article);
                        }
                    }

                    if (!orderedArticles.isEmpty()) {
                        int nextOffset = offset + cachedIdStrings.size();
                        boolean hasMore = true;
                        String nextCursor = FeedCursorUtils.buildBufferCursor(nextOffset);

                        recordExposed(currentUserId, orderedArticles.stream().map(Article::getId).toList());
                        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(orderedArticles, currentUserId);
                        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
                    }
                }
            }

            // 获取失败，启用 MySQL 兜底
            return getRecommendedFeedFromMysql(request, currentUserId);
        }

        // 2. 未登录用户：直接基于 Redis 推荐池热度排序返回
        if (isDefaultRecommended) {
            return getGuestRecommendedFeed(request);
        }

        // 3. 分类/标签频道或兜底：MySQL 游标查询推荐流
        return getRecommendedFeedFromMysql(request, currentUserId);
    }

    /**
     * 为用户推荐流 Buffer 批量填充新文章
     *
     * @param currentUserId 目标用户 ID
     * @return 是否成功写入文章
     */
    private boolean refillUserBuffer(Long currentUserId) {
        if (currentUserId == null) {
            return false;
        }
        int targetBatchSize = feedProperties.getBufferBatchSize();
        int maxRounds = feedProperties.getBufferMaxRounds();
        int fetchLimit = feedProperties.getBufferFetchLimit();

        Double curRankScore = null;
        Long curArticleId = null;

        List<Long> candidateIds = new ArrayList<>();
        Map<Long, Double> scoreMap = new HashMap<>();

        while (maxRounds-- > 0 && candidateIds.size() < targetBatchSize) {
            List<TypedTuple<String>> tuples = recommendRankService.getRankedArticleIds(curRankScore, curArticleId, fetchLimit);
            if (tuples.isEmpty()) {
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
                break;
            }
        }

        if (candidateIds.isEmpty()) {
            return false;
        }

        List<Article> dbArticles = articleMapper.selectByIds(candidateIds);
        if (dbArticles == null || dbArticles.isEmpty()) {
            return false;
        }

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
        if (reranked.isEmpty()) {
            return false;
        }

        String bufferKey = FeedRedisKeys.BUFFER_PREFIX + currentUserId;
        List<String> idStrings = reranked.stream().map(a -> String.valueOf(a.getId())).toList();
        stringRedisTemplate.delete(bufferKey);
        stringRedisTemplate.opsForList().rightPushAll(bufferKey, idStrings);
        stringRedisTemplate.expire(bufferKey, feedProperties.getBufferTtl());
        return true;
    }

    /**
     * 游客全站推荐流
     */
    private ArticleFeedPageResponse getGuestRecommendedFeed(ArticleFeedRequest request) {
        int limitSize = request.size() + 1;
        FeedCursorUtils.FeedCursor cursor = FeedCursorUtils.parseCursor(request.cursor(), FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED);
        Double curRankScore = cursor != null ? (double) cursor.value() : null;
        Long curArticleId = cursor != null ? cursor.articleId() : null;

        List<TypedTuple<String>> tuples = recommendRankService.getRankedArticleIds(curRankScore, curArticleId, limitSize);
        if (tuples.isEmpty()) {
            return getRecommendedFeedFromMysql(request, null);
        }

        List<Long> articleIds = tuples.stream().map(t -> Long.parseLong(Objects.requireNonNull(t.getValue()))).toList();
        List<Article> dbArticles = articleMapper.selectByIds(articleIds);
        if (dbArticles == null || dbArticles.isEmpty()) {
            return getRecommendedFeedFromMysql(request, null);
        }

        Map<Long, Article> map = dbArticles.stream()
                .filter(a -> a != null && a.getStatus() == ArticleStatus.PUBLISHED && a.getVisible() == ArticleVisible.PUBLIC)
                .collect(Collectors.toMap(Article::getId, Function.identity(), (o1, o2) -> o1));

        List<Article> sorted = new ArrayList<>();
        for (TypedTuple<String> t : tuples) {
            Article a = map.get(Long.parseLong(Objects.requireNonNull(t.getValue())));
            if (a != null) {
                a.setRankScore(t.getScore() != null ? t.getScore() : 0.0);
                sorted.add(a);
            }
        }

        boolean hasMore = sorted.size() > request.size();
        List<Article> paged = hasMore ? sorted.subList(0, request.size()) : sorted;

        String nextCursor = null;
        if (hasMore && !paged.isEmpty()) {
            Article last = paged.getLast();
            long scoreVal = last.getRankScore() != null ? last.getRankScore().longValue() : 0L;
            nextCursor = FeedCursorUtils.buildCursor(FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED, scoreVal, last.getId());
        }

        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(paged, null);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * MySQL 游标查询推荐流
     */
    private ArticleFeedPageResponse getRecommendedFeedFromMysql(ArticleFeedRequest request, Long currentUserId) {
        int pageSize = request.size();
        FeedCursorUtils.FeedCursor cursor = FeedCursorUtils.parseCursor(request.cursor(), FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED);
        Long cursorRankScore = cursor != null ? cursor.value() : null;
        Long cursorId = cursor != null ? cursor.articleId() : null;
        Instant earliestPublishTime = Instant.now().minus(30, ChronoUnit.DAYS);

        List<Article> articles = articleMapper.selectFeedRecommended(
                request.categoryId(),
                request.tagId(),
                request.authorId(),
                earliestPublishTime,
                cursorRankScore,
                cursorId,
                pageSize + 1
        );

        if (articles == null || articles.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        boolean hasMore = articles.size() > pageSize;
        List<Article> paged = hasMore ? articles.subList(0, pageSize) : articles;

        List<Article> reranked = articleRecommendVectorService.recommendAndRerank(currentUserId, paged);
        List<Article> result = reranked.isEmpty() ? paged : reranked;

        String nextCursor = null;
        if (hasMore && !paged.isEmpty()) {
            Article last = paged.getLast();
            long rankScore = (last.getRankScore() != null) ? last.getRankScore().longValue() : 0L;
            nextCursor = FeedCursorUtils.buildCursor(FeedCursorUtils.CURSOR_PREFIX_RECOMMENDED, rankScore, last.getId());
        }

        recordExposed(currentUserId, result.stream().map(Article::getId).toList());
        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(result, currentUserId);
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
