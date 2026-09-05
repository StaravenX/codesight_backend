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
import com.codesight.user.User;
import com.codesight.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final UserService userService;
    private final CounterService counterService;
    private final RecommendRankService recommendRankService;

    /**
     * 获取文章信息流
     *
     * @param request       分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 分页信息流响应体
     */
    public ArticleFeedPageResponse getFeed(ArticleFeedRequest request, Long currentUserId) {
        // 多查一条数据，高效判断是否有下一页
        int limitSize = request.size() + 1;
        boolean isRecommended = (request.sortBy() == FeedSortType.RECOMMENDED);
        boolean isDefaultRecommended = isRecommended && request.authorId() == null && request.tagId() == null && request.categoryId() == null;

        // 1. 推荐流全站主流
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

        // 2. MySQL 查询
        List<Article> rawList;
        if (isRecommended) {
            FeedCursor cursor = parseCursor(request.cursor(), CURSOR_PREFIX_RECOMMENDED);
            Long cursorRankScore = cursor != null ? cursor.value() : null;
            Long cursorId = cursor != null ? cursor.articleId() : null;
            Instant earliestPublishTime = Instant.now().minus(30, ChronoUnit.DAYS);

            rawList = articleMapper.selectFeedRecommended(
                    request.categoryId(),
                    request.tagId(),
                    request.authorId(),
                    earliestPublishTime,
                    cursorRankScore,
                    cursorId,
                    limitSize
            );
        } else {
            FeedCursor cursor = parseCursor(request.cursor(), CURSOR_PREFIX_NEWEST);
            Instant cursorTime = cursor != null ? Instant.ofEpochMilli(cursor.value()) : null;
            Long cursorId = cursor != null ? cursor.articleId() : null;

            rawList = articleMapper.selectFeedNewest(
                    request.categoryId(),
                    request.tagId(),
                    request.authorId(),
                    cursorTime,
                    cursorId,
                    limitSize
            );
        }

        if (rawList == null || rawList.isEmpty()) {
            return new ArticleFeedPageResponse(Collections.emptyList(), null, false);
        }

        // 3. 回填 Redis 候选池
        if (isDefaultRecommended) {
            recommendRankService.batchAddScores(rawList);
        }

        boolean hasMore = rawList.size() > request.size();
        List<Article> articles = hasMore ? rawList.subList(0, request.size()) : rawList;

        String nextCursor = null;
        if (hasMore && !articles.isEmpty()) {
            Article last = articles.getLast();
            if (isRecommended) {
                long rankScore = last.getRankScore().longValue();
                nextCursor = buildCursor(CURSOR_PREFIX_RECOMMENDED, rankScore, last.getId());
            } else {
                long millis = last.getPublishTime() != null ? last.getPublishTime().toEpochMilli() : 0L;
                nextCursor = buildCursor(CURSOR_PREFIX_NEWEST, millis, last.getId());
            }
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

    public List<ArticleFeedItemResponse> hydrateFeedItems(List<Article> articles, Long currentUserId) {
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 批量作者装配
        Set<Long> authorIds = articles.stream()
                .map(Article::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, User> authorMap = Collections.emptyMap();
        if (!authorIds.isEmpty()) {
            List<User> users = userService.listByIds(authorIds);
            if (users != null) {
                authorMap = users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
            }
        }

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
            User author = authorMap.get(a.getAuthorId());
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
                    .authorName(author != null ? author.getNickname() : "知识作者")
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

    private record FeedCursor(long value, long articleId) {}

}
