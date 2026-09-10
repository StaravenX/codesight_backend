package com.codesight.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.search.config.EsProperties;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 搜索索引数据同步服务：
 * 启动时全量冷启动回灌
 * 单篇增量 upsert 写入与更新
 * 文章下架/删除同步
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexService {

    private final ElasticsearchClient es;
    private final EsProperties props;
    private final ArticleMapper articleMapper;
    private final ArticleTagRelMapper articleTagRelMapper;
    private final TagMapper tagMapper;
    private final UserCacheService userCacheService;
    private final CounterService counterService;

    /**
     * 服务冷启动时，若 ES 索引为空则从数据库批量回灌已发布文章
     */
    @PostConstruct
    public void ensureBackfill() {
        String indexName = props.getIndex();
        try {
            long docCount = es.count(c -> c.index(indexName)).count();
            if (docCount > 0) {
                return;
            }

            long lastId = 0L;
            int batchSize = 100;

            while (true) {
                List<Article> articles = articleMapper.selectList(
                        new LambdaQueryWrapper<Article>()
                                .eq(Article::getStatus, ArticleStatus.PUBLISHED)
                                .eq(Article::getVisible, ArticleVisible.PUBLIC)
                                .gt(Article::getId, lastId)
                                .orderByAsc(Article::getId)
                                .last("LIMIT " + batchSize)
                );

                if (articles == null || articles.isEmpty()) {
                    break;
                }

                bulkIndexArticles(articles);
                lastId = articles.getLast().getId();
            }
        } catch (Exception e) {
            log.error("冷启动回灌 ES 索引数据失败: index={}", indexName, e);
        }
    }

    /**
     * 批量组装并写入 ES
     */
    private void bulkIndexArticles(List<Article> articles) {
        if (articles.isEmpty()) {
            return;
        }

        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        List<String> articleIdStrs = articleIds.stream().map(String::valueOf).toList();

        // 1. 批量作者装配
        Set<Long> authorIds = articles.stream().map(Article::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, UserBaseInfo> authorMap = userCacheService.batchGetUserBaseInfo(authorIds);

        // 2. 批量标签装配
        Map<Long, List<String>> tagMap = loadBatchTagNames(articleIds);

        // 3. 批量计数装配
        Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = counterService.batchGetCounts(
                CounterSchema.EntityType.ARTICLE,
                articleIdStrs
        );

        BulkRequest.Builder br = new BulkRequest.Builder();
        for (Article a : articles) {
            String strId = String.valueOf(a.getId());
            UserBaseInfo author = authorMap.get(a.getAuthorId());
            List<String> tags = tagMap.getOrDefault(a.getId(), Collections.emptyList());
            Map<CounterSchema.MetricItem, Long> counts = countsMap.getOrDefault(strId, Collections.emptyMap());

            ArticleSearchDoc doc = buildDoc(a, author, tags, counts);

            br.operations(op -> op.index(idx -> idx
                    .index(props.getIndex())
                    .id(strId)
                    .document(doc)
            ));
        }

        try {
            es.bulk(br.build());
        } catch (Exception e) {
            log.error("ES 批量回灌执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 单篇文章同步写入或更新
     *
     * @param articleId 文章 ID
     */
    public void upsertArticle(Long articleId) {
        if (articleId == null) {
            return;
        }

        try {
            Article article = articleMapper.selectById(articleId);
            if (article == null || article.getStatus() != ArticleStatus.PUBLISHED || article.getVisible() != ArticleVisible.PUBLIC) {
                deleteArticle(articleId);
                return;
            }

            List<String> tags = loadBatchTagNames(List.of(articleId)).getOrDefault(articleId, Collections.emptyList());
            UserBaseInfo author = userCacheService.getUserBaseInfo(article.getAuthorId());
            Map<CounterSchema.MetricItem, Long> counts = counterService.getCounts(
                    CounterSchema.EntityType.ARTICLE,
                    String.valueOf(articleId)
            );

            ArticleSearchDoc doc = buildDoc(article, author, tags, counts);

            es.index(i -> i
                    .index(props.getIndex())
                    .id(String.valueOf(articleId))
                    .document(doc)
                    .refresh(Refresh.WaitFor)
            );

        } catch (Exception e) {
            log.error("ES 同步文章失败: articleId={}, error={}", articleId, e.getMessage(), e);
        }
    }

    /**
     * 从 ES 索引中物理删除文章
     *
     * @param articleId 文章 ID
     */
    public void deleteArticle(Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            es.delete(d -> d.index(props.getIndex()).id(String.valueOf(articleId)));
        } catch (Exception e) {
            log.warn("ES 删除文章跳过: articleId={}, error={}", articleId, e.getMessage());
        }
    }

    /**
     * 统一构建 ES 搜索文档模型
     */
    private ArticleSearchDoc buildDoc(Article article,
                                      UserBaseInfo author,
                                      List<String> tags,
                                      Map<CounterSchema.MetricItem, Long> counts) {
        long likeCount = counts != null ? counts.getOrDefault(CounterSchema.ArticleMetric.LIKE, 0L) : 0L;
        long favoriteCount = counts != null ? counts.getOrDefault(CounterSchema.ArticleMetric.FAVORITE, 0L) : 0L;
        long fallbackView = article.getViewCount() != null ? article.getViewCount() : 0L;
        long viewCount = counts != null ? counts.getOrDefault(CounterSchema.ArticleMetric.VIEWS, fallbackView) : fallbackView;

        return ArticleSearchDoc.builder()
                .articleId(article.getId())
                .title(article.getTitle())
                .body(article.getContentMd())
                .summary(article.getSummary())
                .tags(tags != null ? tags : Collections.emptyList())
                .authorId(article.getAuthorId())
                .authorAvatar(author != null ? author.avatar() : null)
                .authorNickname(author != null ? author.nickname() : "知识作者")
                .coverUrl(article.getCoverUrl())
                .publishTime(article.getPublishTime() != null ? article.getPublishTime().toEpochMilli() : System.currentTimeMillis())
                .likeCount(likeCount)
                .favoriteCount(favoriteCount)
                .viewCount(viewCount)
                .status("published")
                .build();
    }

    /**
     * 批量加载关联标签名列表
     */
    private Map<Long, List<String>> loadBatchTagNames(List<Long> articleIds) {
        List<ArticleTagRel> rels = articleTagRelMapper.selectList(
                new LambdaQueryWrapper<ArticleTagRel>().in(ArticleTagRel::getArticleId, articleIds)
        );
        if (rels == null || rels.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> tagIds = rels.stream().map(ArticleTagRel::getTagId).collect(Collectors.toSet());
        List<Tag> tags = tagMapper.selectByIds(tagIds);
        Map<Long, String> tagIdNameMap = tags != null
                ? tags.stream().collect(Collectors.toMap(Tag::getId, Tag::getName))
                : Collections.emptyMap();

        Map<Long, List<String>> result = new HashMap<>();
        for (ArticleTagRel rel : rels) {
            String tagName = tagIdNameMap.get(rel.getTagId());
            if (tagName != null) {
                result.computeIfAbsent(rel.getArticleId(), k -> new ArrayList<>()).add(tagName);
            }
        }
        return result;
    }
}
