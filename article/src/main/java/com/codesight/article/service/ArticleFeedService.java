package com.codesight.article.service;

import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.FeedSortType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 首页与频道信息流（Feed 流）门面服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleFeedService {

    private final ArticleMapper articleMapper;
    private final ArticleRecommendFeedService articleRecommendFeedService;
    private final ArticleFollowingFeedService articleFollowingFeedService;
    private final ArticleFeedHydrator articleFeedHydrator;

    /**
     * 获取文章信息流（推荐、最新、关注三大分支路由）
     *
     * @param request       分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 分页信息流响应体
     */
    public ArticleFeedPageResponse getFeed(ArticleFeedRequest request, Long currentUserId) {
        FeedSortType sortType = (request.sortBy() != null) ? request.sortBy() : FeedSortType.RECOMMENDED;
        return switch (sortType) {
            case FOLLOWING -> articleFollowingFeedService.getFollowingFeed(request, currentUserId);
            case RECOMMENDED -> articleRecommendFeedService.getRecommendedFeed(request, currentUserId);
            case NEWEST -> getNewestFeed(request, currentUserId);
        };
    }

    /**
     * 获取社交关注流（推拉结合）
     */
    public ArticleFeedPageResponse getFollowingFeed(ArticleFeedRequest request, Long currentUserId) {
        return articleFollowingFeedService.getFollowingFeed(request, currentUserId);
    }

    /**
     * 文章公开发布事件触发：同步维护推荐候选池与推拉结合分流
     */
    public void onArticlePublished(Article article) {
        articleRecommendFeedService.onArticlePublished(article.getId());
        articleFollowingFeedService.onArticlePublished(article);
    }

    /**
     * 文章删除/下架事件触发：从全站推荐池与发件箱移除
     */
    public void onArticleRemoved(Article article) {
        articleRecommendFeedService.onArticleRemoved(article.getId());
        articleFollowingFeedService.onArticleRemoved(article);
    }

    /**
     * 判定创作者是否为明星大 V
     */
    public boolean isBigV(Long authorId, long followerCount) {
        return articleFollowingFeedService.isBigV(authorId, followerCount);
    }

    /**
     * 大 V 降级补偿核心逻辑
     */
    public void backfillOnDemotion(Long authorId) {
        articleFollowingFeedService.backfillOnDemotion(authorId);
    }

    /**
     * 关注回填
     */
    public void backfillOnFollow(Long followerId, Long authorId) {
        articleFollowingFeedService.backfillOnFollow(followerId, authorId);
    }

    /**
     * 取关清理
     */
    public void cleanupOnUnfollow(Long followerId, Long authorId) {
        articleFollowingFeedService.cleanupOnUnfollow(followerId, authorId);
    }

    /**
     * 最新发布信息流（Keyset 游标寻址，按发布时间倒序）
     */
    private ArticleFeedPageResponse getNewestFeed(ArticleFeedRequest request, Long currentUserId) {
        int limitSize = request.size() + 1;

        FeedCursorUtils.FeedCursor cursor = FeedCursorUtils.parseCursor(request.cursor(), FeedCursorUtils.CURSOR_PREFIX_NEWEST);
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
            nextCursor = FeedCursorUtils.buildCursor(FeedCursorUtils.CURSOR_PREFIX_NEWEST, millis, last.getId());
        }

        List<ArticleFeedItemResponse> items = articleFeedHydrator.hydrateFeedItems(articles, currentUserId);
        return new ArticleFeedPageResponse(items, nextCursor, hasMore);
    }
}
