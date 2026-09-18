package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 信息流卡片物化装配器（作者画像、标签多对多、SDS 计数、点赞状态）
 */
@Component
@RequiredArgsConstructor
public class ArticleFeedHydrator {

    private final UserCacheService userCacheService;
    private final CounterService counterService;
    private final ArticleTagRelMapper articleTagRelMapper;
    private final TagMapper tagMapper;

    /**
     * 批量装配文章 Feed 项响应体
     *
     * @param articles      文章实体列表
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 卡片响应体列表
     */
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
}
