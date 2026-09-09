package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.FeedSortType;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.codesight.article.constant.FeedRedisKeys;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.service.RelationCacheService;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import com.codesight.article.model.enums.ArticleVisible;
import java.util.stream.Collectors;

/**
 * 首页与频道信息流（Feed 流）及相关推荐核心业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleFeedService {

    private static final String CURSOR_PREFIX_RECOMMENDED = "rec";
    private static final String CURSOR_PREFIX_NEWEST = "new";

    private final ArticleMapper articleMapper;
    private final ArticleTagRelMapper articleTagRelMapper;
    private final TagMapper tagMapper;
    private final UserCacheService userCacheService;
    private final CounterService counterService;
    private final RecommendRankService recommendRankService;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserFollowerMapper userFollowerMapper;
    private final RelationCacheService relationCacheService;

    /**
     * 获取文章信息流（推荐、最新、关注三大分支）
     *
     * @param request       分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 分页信息流响应体
     */
    public ArticleFeedPageResponse getFeed(ArticleFeedRequest request, Long currentUserId) {
        FeedSortType sortType = (request.sortBy() != null) ? request.sortBy() : FeedSortType.RECOMMENDED;
        return switch (sortType) {
            case FOLLOWING -> getFollowingFeed(request, currentUserId);
            case RECOMMENDED -> getRecommendedFeed(request, currentUserId);
            case NEWEST -> getNewestFeed(request, currentUserId);
        };
    }

    /**
     * 综合推荐信息流（全站推荐候选池 + MySQL 频道/标签多维推荐）
     */
    private ArticleFeedPageResponse getRecommendedFeed(ArticleFeedRequest request, Long currentUserId) {
        // 多查一条数据，高效判断是否有下一页
        int limitSize = request.size() + 1;
        boolean isDefaultRecommended = request.authorId() == null && request.tagId() == null && request.categoryId() == null;

        // 1. 全站推荐主流（从Redis推荐池中获取）
        if (isDefaultRecommended) {
            FeedCursor cursor = parseCursor(request.cursor(), CURSOR_PREFIX_RECOMMENDED);
            Double cursorRankScore = cursor != null ? (double) cursor.value() : null;

            List<TypedTuple<String>> tuples = recommendRankService.getRankedArticleIds(cursorRankScore, limitSize);
            if (!tuples.isEmpty()) {
                List<Long> articleIds = tuples.stream()
                        .map(t -> Long.parseLong(Objects.requireNonNull(t.getValue())))
                        .toList();

                List<Article> dbArticles = articleMapper.selectByIds(articleIds);
                if (dbArticles != null && !dbArticles.isEmpty()) {
                    List<Article> sorted = new ArrayList<>(articleIds.size());
                    for (Article a : dbArticles) {
                        if (a != null && a.getStatus() == ArticleStatus.PUBLISHED && a.getVisible() == ArticleVisible.PUBLIC) {
                            sorted.add(a);
                        }
                    }

                    if (!sorted.isEmpty()) {
                        boolean hasMore = sorted.size() > request.size();
                        List<Article> paged = hasMore ? sorted.subList(0, request.size()) : sorted;

                        String nextCursor = null;
                        if (hasMore && !paged.isEmpty()) {
                            Article last = paged.getLast();
                            Double lastScore = recommendRankService.getScore(last.getId());
                            long scoreVal = lastScore != null ? lastScore.longValue() : 0L;
                            nextCursor = buildCursor(CURSOR_PREFIX_RECOMMENDED, scoreVal, last.getId());
                        }

                        List<ArticleFeedItemResponse> items = hydrateFeedItems(paged, currentUserId);
                        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
                    }
                }
            }
        }

        // 2. MySQL 游标查询推荐流（适用频道/标签/作者过滤、全站推荐池见底后的推荐流）
        FeedCursor cursor = parseCursor(request.cursor(), CURSOR_PREFIX_RECOMMENDED);
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

        boolean hasMore = rawList.size() > request.size();
        List<Article> articles = hasMore ? rawList.subList(0, request.size()) : rawList;

        String nextCursor = null;
        if (hasMore && !articles.isEmpty()) {
            Article last = articles.getLast();
            long rankScore = (last.getRankScore() != null) ? last.getRankScore().longValue() : 0L;
            nextCursor = buildCursor(CURSOR_PREFIX_RECOMMENDED, rankScore, last.getId());
        }

        List<ArticleFeedItemResponse> items = hydrateFeedItems(articles, currentUserId);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 最新发布信息流（Keyset 游标寻址，按发布时间倒序）
     */
    private ArticleFeedPageResponse getNewestFeed(ArticleFeedRequest request, Long currentUserId) {
        int limitSize = request.size() + 1;

        FeedCursor cursor = parseCursor(request.cursor(), CURSOR_PREFIX_NEWEST);
        Instant cursorTime = cursor != null ? Instant.ofEpochMilli(cursor.value()) : null;
        Long cursorId = cursor != null ? cursor.articleId() : null;

        List<Article> rawList = articleMapper.selectFeedNewest(
                request.categoryId(),
                request.tagId(),
                request.authorId(),
                cursorTime,
                cursorId,
                limitSize
        );

        if (rawList == null || rawList.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        boolean hasMore = rawList.size() > request.size();
        List<Article> articles = hasMore ? rawList.subList(0, request.size()) : rawList;

        String nextCursor = null;
        if (hasMore && !articles.isEmpty()) {
            Article last = articles.getLast();
            long millis = (last.getPublishTime() != null) ? last.getPublishTime().toEpochMilli() : 0L;
            nextCursor = buildCursor(CURSOR_PREFIX_NEWEST, millis, last.getId());
        }

        List<ArticleFeedItemResponse> items = hydrateFeedItems(articles, currentUserId);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 获取文章详情页底部相关推荐（Top-5）
     *
     * @param articleId     当前文章 ID
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 相关文章列表
     */
    public List<ArticleFeedItemResponse> listRelatedArticles(Long articleId, Long currentUserId) {
        if (articleId == null) {
            return Collections.emptyList();
        }

        Article currentArticle = articleMapper.selectById(articleId);
        if (currentArticle == null || currentArticle.getStatus() == ArticleStatus.DELETED) {
            return Collections.emptyList();
        }

        List<ArticleTagRel> rels = articleTagRelMapper.selectList(
                new LambdaQueryWrapper<ArticleTagRel>().eq(ArticleTagRel::getArticleId, articleId)
        );
        List<Long> tagIds = (rels != null && !rels.isEmpty())
                ? rels.stream().map(ArticleTagRel::getTagId).toList()
                : Collections.emptyList();

        List<Article> relatedArticles = articleMapper.selectRelatedArticles(
                articleId,
                currentArticle.getCategoryId(),
                tagIds,
                5
        );

        if (relatedArticles == null || relatedArticles.isEmpty()) {
            return Collections.emptyList();
        }

        return hydrateFeedItems(relatedArticles, currentUserId);
    }

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

        List<ArticleFeedItemResponse> items = hydrateFeedItems(orderedArticles, currentUserId);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 文章公开发布事件触发：同步维护推荐候选池与推拉结合分流
     *
     * @param article 发布成功的文章实体
     */
    public void onArticlePublished(Article article) {
        Long authorId = article.getAuthorId();
        Long articleId = article.getId();
        long publishTimeMs = article.getPublishTime().toEpochMilli();

        // 1. 推荐候选池更新
        recommendRankService.addOrIncrScore(articleId, 0.0);

        // 2. 写入发件箱
        String outboxKey = FeedRedisKeys.getOutboxKey(authorId);
        stringRedisTemplate.opsForZSet().add(outboxKey, String.valueOf(articleId), publishTimeMs);
        stringRedisTemplate.opsForZSet().removeRange(outboxKey, 0, -(FeedRedisKeys.OUTBOX_MAX_CAPACITY + 1));

        // 3. 判定博主粉丝数
        Map<CounterSchema.MetricItem, Long> userCounts = counterService.getCounts(
                CounterSchema.EntityType.USER,
                String.valueOf(authorId)
        );
        long followerCount = userCounts != null
                ? userCounts.getOrDefault(CounterSchema.UserMetric.FOLLOWERS, 0L)
                : 0L;

        // 4.1 若为大V，不需要写扩散，直接返回
        if (isBigV(authorId, followerCount)) {
            return;
        }

        // 4.2 普通博主：查询有效粉丝列表并执行写扩散
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
                    operations.opsForZSet().removeRange(inboxKey, 0, -(FeedRedisKeys.INBOX_MAX_CAPACITY + 1));
                }
                return null;
            }
        });
    }

    /**
     * 文章删除/下架事件触发：从全站推荐池与发件箱移除
     *
     * @param article 文章实体
     */
    public void onArticleRemoved(Article article) {
        recommendRankService.removeArticle(article.getId());
        String outboxKey = FeedRedisKeys.getOutboxKey(article.getAuthorId());
        stringRedisTemplate.opsForZSet().remove(outboxKey, String.valueOf(article.getId()));
    }

    public List<ArticleFeedItemResponse> hydrateFeedItems(List<Article> articles, Long currentUserId) {
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 批量作者装配
        Set<Long> authorIds = articles.stream()
                .map(Article::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, UserBaseInfo> authorMap = !authorIds.isEmpty()
                ? userCacheService.batchGetUserBaseInfo(authorIds)
                : Collections.emptyMap();

        // 2. 批量标签多对多装配
        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        Map<Long, List<TagResponse>> articleTagsMap = loadArticleTagsBatch(articleIds);

        // 3. 批量 16B SDS 实时计数装配
        List<String> articleIdStrs = articleIds.stream().map(String::valueOf).toList();
        Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = counterService.batchGetCounts(
                CounterSchema.EntityType.ARTICLE,
                articleIdStrs
        );

        // 4. 批量当前用户点赞状态判定
        Map<String, Boolean> isLikedMap = Collections.emptyMap();
        if (currentUserId != null && currentUserId > 0) {
            isLikedMap = counterService.batchIsSet(
                    CounterSchema.EntityType.ARTICLE,
                    articleIdStrs,
                    CounterSchema.ArticleMetric.LIKE,
                    currentUserId
            );
        }

        // 5. 聚合
        List<ArticleFeedItemResponse> items = new ArrayList<>(articles.size());
        for (Article a : articles) {
            String strId = String.valueOf(a.getId());
            UserBaseInfo author = authorMap.get(a.getAuthorId());
            Map<CounterSchema.MetricItem, Long> counts = countsMap.getOrDefault(strId, Collections.emptyMap());

            long viewCount = counts.getOrDefault(CounterSchema.ArticleMetric.VIEWS, a.getViewCount() != null ? a.getViewCount() : 0L);
            long likeCount = counts.getOrDefault(CounterSchema.ArticleMetric.LIKE, a.getLikeCount() != null ? a.getLikeCount() : 0L);
            long commentCount = counts.getOrDefault(CounterSchema.ArticleMetric.COMMENT, a.getCommentCount() != null ? a.getCommentCount() : 0L);
            long collectCount = counts.getOrDefault(CounterSchema.ArticleMetric.FAVORITE, a.getFavoriteCount() != null ? a.getFavoriteCount() : 0L);
            boolean isLiked = Boolean.TRUE.equals(isLikedMap.get(strId));

            ArticleFeedItemResponse item = ArticleFeedItemResponse.builder()
                    .id(a.getId())
                    .title(a.getTitle())
                    .summary(a.getSummary())
                    .coverUrl(a.getCoverUrl())
                    .authorId(a.getAuthorId())
                    .authorName(author != null ? author.nickname() : "知识作者")
                    .categoryId(a.getCategoryId())
                    .tags(articleTagsMap.getOrDefault(a.getId(), Collections.emptyList()))
                    .publishTime(a.getPublishTime())
                    .viewCount(viewCount)
                    .likeCount(likeCount)
                    .commentCount(commentCount)
                    .collectCount(collectCount)
                    .isLiked(isLiked)
                    .isTop(Boolean.TRUE.equals(a.getIsTop()))
                    .build();

            items.add(item);
        }

        return items;
    }

    /**
     * 批量加载文章关联的标签
     */
    private Map<Long, List<TagResponse>> loadArticleTagsBatch(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ArticleTagRel> rels = articleTagRelMapper.selectList(
                new LambdaQueryWrapper<ArticleTagRel>().in(ArticleTagRel::getArticleId, articleIds)
        );
        if (rels == null || rels.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> tagIds = rels.stream().map(ArticleTagRel::getTagId).collect(Collectors.toSet());
        List<Tag> tags = tagMapper.selectByIds(tagIds);
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, String> tagNameMap = tags.stream().collect(Collectors.toMap(Tag::getId, Tag::getName, (a, b) -> a));

        Map<Long, List<TagResponse>> result = new HashMap<>();
        for (ArticleTagRel r : rels) {
            String name = tagNameMap.get(r.getTagId());
            if (name != null) {
                result.computeIfAbsent(r.getArticleId(), k -> new ArrayList<>())
                        .add(new TagResponse(r.getTagId(), name));
            }
        }
        return result;
    }

    /**
     * 解析游标（格式：{prefix}:{value}:{articleId} 的 Base64 URL 字符串）
     */
    private FeedCursor parseCursor(String cursorStr, String prefix) {
        if (cursorStr == null || cursorStr.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursorStr), StandardCharsets.UTF_8);
            if (decoded.startsWith(prefix + ":")) {
                decoded = decoded.substring(prefix.length() + 1);
                String[] parts = decoded.split(":");
                if (parts.length >= 2) {
                    return new FeedCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                }
            } else {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "游标前缀格式错误");
            }
        } catch (Exception e) {
            log.warn("解析游标失败，cursorStr: {}, prefix: {}", cursorStr, prefix, e);
        }
        return null;
    }

    /**
     * 构建游标
     */
    private String buildCursor(String prefix, long value, long articleId) {
        String raw = prefix + ":" + value + ":" + articleId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判定创作者是否为明星大 V
     * <p>
     * 1. 晋升大 V 门限：粉丝数 >= 5,500，记录大 V 集合并返回 true；
     * 2. 跌落普通人门限：粉丝数 < 4,500，从大 V 集合移除并返回 false；
     * 3. 缓冲迟滞带 [4,500, 5,500)：维持作者既有状态不变。
     *
     * @param authorId 创作者 ID
     * @param followerCount 当前粉丝数
     * @return true 表示为大 V（拉模式），false 表示为普通博主（推模式）
     */
    public boolean isBigV(Long authorId, long followerCount) {
        if (followerCount >= FeedRedisKeys.BIG_V_PROMOTION_THRESHOLD) {
            stringRedisTemplate.opsForSet().add(FeedRedisKeys.BIG_V_SET_KEY, String.valueOf(authorId));
            return true;
        }
        if (followerCount < FeedRedisKeys.BIG_V_DEMOTION_THRESHOLD) {
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
     *
     * @param authorId 降级作者 ID
     */
    public void triggerDemotionBackfill(Long authorId) {
        if (authorId == null) {
            return;
        }
        Thread.ofVirtual().name("demotion-backfill-" + authorId).start(() -> backfillOnDemotion(authorId));
    }

    /**
     * 大 V 降级补偿核心逻辑
     *
     * @param authorId 降级作者 ID
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
                    operations.opsForZSet().removeRange(inboxKey, 0, -(FeedRedisKeys.INBOX_MAX_CAPACITY + 1));
                }
                return null;
            }
        });

    }

    private record FeedCursor(long value, long articleId) {}

}
